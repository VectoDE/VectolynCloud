package net.vectora.cloud.api.console;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.vectora.cloud.api.auth.ApiToken;
import net.vectora.cloud.api.auth.ApiTokens;
import net.vectora.cloud.api.auth.Scope;
import net.vectora.cloud.config.validation.ConfigNames;
import net.vectora.cloud.console.ConsoleOutput;
import net.vectora.cloud.console.Text;
import net.vectora.cloud.console.command.Argument;
import net.vectora.cloud.console.command.Command;
import net.vectora.cloud.console.command.CommandException;

/**
 * The {@code api} command of the console:
 *
 * <table>
 *   <caption>Subcommands</caption>
 *   <tr><td>{@code api token create <name> <scopes>}</td><td>creates a token and shows it once</td></tr>
 *   <tr><td>{@code api token list}</td><td>id, name, scopes and creation time of every token</td></tr>
 *   <tr><td>{@code api token revoke <id|name>}</td><td>revokes a token at once</td></tr>
 *   <tr><td>{@code api scopes}</td><td>lists the scopes</td></tr>
 * </table>
 *
 * <p>The whole command is {@link Command.Builder#sensitive() sensitive}: no line of it reaches the
 * console history. The token is printed only by {@code create}, once, and is not stored anywhere;
 * the node keeps its SHA-256 only.
 */
public final class ApiCommands {

    private final TokenFile file;
    private volatile ApiTokens tokens;

    /** Opens the token store; fails while {@code secrets/api-tokens.json} is broken. */
    @FunctionalInterface
    public interface TokenFile {
        /**
         * Opens the store.
         *
         * @return the store
         * @throws IOException if the file cannot be read or is malformed (the message names no value)
         */
        ApiTokens open() throws IOException;
    }

    /**
     * Creates the commands.
     *
     * @param tokens the token store, the same instance the API server uses
     */
    public ApiCommands(ApiTokens tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.file = () -> tokens;
    }

    /**
     * Creates the commands for a token file that could not be opened when the node started. Each
     * {@code api token} command tries to open it again: while it fails, the command reports why;
     * once it works (the file was repaired or deleted), that store is used from then on.
     *
     * @param file opens the token file
     */
    public ApiCommands(TokenFile file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    private ApiTokens tokens() throws CommandException {
        ApiTokens current = tokens;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (tokens == null) {
                try {
                    tokens = Objects.requireNonNull(file.open(), "token store");
                } catch (IOException e) {
                    throw new CommandException(Text.of(
                            "secrets/%1$s cannot be read (%2$s). Repair it, or delete it to start without tokens."
                                    + " The API stays off until the node starts with a readable file.",
                            "secrets/%1$s kann nicht gelesen werden (%2$s). Die Datei reparieren, oder löschen, um"
                                    + " ohne Tokens neu anzufangen. Die API bleibt aus, bis der Node mit einer lesbaren"
                                    + " Datei startet."),
                            ApiTokens.FILE_NAME, e.getMessage());
                }
            }
            return tokens;
        }
    }

    private List<String> tokenNames() {
        ApiTokens current = tokens;
        return current == null ? List.of() : current.list().stream().map(ApiToken::name).toList();
    }

    /**
     * Builds the {@code api} command.
     *
     * @return the command
     */
    public Command command() {
        List<String> scopeIds = Stream.of(Scope.values()).map(Scope::id).toList();
        return Command.builder("api")
                .description(Text.of("Manages the tokens of the REST API.", "Verwaltet die Tokens der REST-API."))
                .sensitive()
                .subcommand(Command.builder("token")
                        .description(Text.of("Creates, lists and revokes API tokens.",
                                "Erstellt, listet und widerruft API-Tokens."))
                        .subcommand(Command.builder("create")
                                .description(Text.of("Creates a token and shows it once.",
                                        "Erstellt ein Token und zeigt es einmal an."))
                                .argument(Argument.required("name", Text.of("Name, for example dashboard",
                                        "Name, zum Beispiel dashboard")))
                                .argument(Argument.required("scopes", Text.of(
                                        "Scopes, comma separated, for example services:read,services:write",
                                        "Scopes, durch Komma getrennt, zum Beispiel services:read,services:write"))
                                        .suggest(scopeIds))
                                .executes(context -> create(context.out(), context.argument("name"),
                                        context.argument("scopes"))))
                        .subcommand(Command.builder("list")
                                .description(Text.of("Shows every token without its value.",
                                        "Zeigt alle Tokens ohne ihren Wert."))
                                .executes(context -> list(context.out())))
                        .subcommand(Command.builder("revoke")
                                .aliases("delete", "remove")
                                .description(Text.of("Revokes a token; it stops working at once.",
                                        "Widerruft ein Token; es wirkt sofort nicht mehr."))
                                .argument(Argument.required("token", Text.of("Id or name of the token",
                                        "ID oder Name des Tokens"))
                                        .suggest(previous -> tokenNames()))
                                .executes(context -> revoke(context.out(), context.argument("token")))))
                .subcommand(Command.builder("scopes")
                        .description(Text.of("Lists the scopes a token can have.",
                                "Listet die Scopes, die ein Token haben kann."))
                        .executes(context -> scopes(context.out())))
                .build();
    }

    private void create(ConsoleOutput out, String name, String scopeList) throws Exception {
        if (!ConfigNames.isValidName(name)) {
            throw new CommandException(Text.of("The name must match %1$s.", "Der Name muss %1$s entsprechen."),
                    ConfigNames.NAME);
        }
        Set<Scope> scopes = EnumSet.noneOf(Scope.class);
        for (String item : scopeList.split(",", -1)) {
            Optional<Scope> scope = Scope.of(item);
            if (scope.isEmpty()) {
                throw new CommandException(Text.of("Unknown scope. \"api scopes\" lists them.",
                        "Unbekannter Scope. „api scopes“ listet sie auf."));
            }
            scopes.add(scope.get());
        }
        ApiTokens store = tokens();
        if (store.list().stream().anyMatch(t -> t.name().equals(name))) {
            throw new CommandException(Text.of("A token named %1$s exists already.",
                    "Ein Token namens %1$s existiert bereits."), name);
        }
        if (store.list().size() >= ApiTokens.MAX_TOKENS) {
            throw new CommandException(Text.of("At most %1$s tokens are possible.", "Höchstens %1$s Tokens sind möglich."),
                    ApiTokens.MAX_TOKENS);
        }
        ApiTokens.Created created = store.create(name, scopes);
        out.success(Text.of("Token %1$s (%2$s) created with %3$s.", "Token %1$s (%2$s) erstellt mit %3$s."),
                created.info().name(), created.info().id(), scopeNames(created.info()));
        // The one place the token is shown. Text built here from a fixed alphabet, so println is safe.
        out.println("  " + created.token().reveal());
        out.warning(Text.of("Copy it now: it is shown only this once and is not stored. Lost tokens are revoked "
                        + "and created again.",
                "Jetzt kopieren: Es wird nur dieses eine Mal angezeigt und nicht gespeichert. Ein verlorenes Token "
                        + "wird widerrufen und neu erstellt."));
    }

    private void list(ConsoleOutput out) throws CommandException {
        List<ApiToken> all = tokens().list();
        if (all.isEmpty()) {
            out.println(Text.of("No API tokens. \"api token create <name> <scopes>\" creates one.",
                    "Keine API-Tokens. „api token create <name> <scopes>“ erstellt eins."));
            return;
        }
        out.heading(Text.of("API tokens", "API-Tokens").get(out.language()));
        int width = all.stream().mapToInt(t -> t.name().length()).max().orElse(0);
        for (ApiToken token : all) {
            out.println("  " + token.id() + "  " + token.name() + " ".repeat(width - token.name().length())
                    + "  " + token.createdAt() + "  " + scopeNames(token));
        }
    }

    private void revoke(ConsoleOutput out, String idOrName) throws Exception {
        if (!idOrName.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
            throw new CommandException(Text.of("No such token. \"api token list\" shows them.",
                    "Kein solches Token. „api token list“ zeigt sie."));
        }
        Optional<ApiToken> revoked = tokens().revoke(idOrName);
        if (revoked.isEmpty()) {
            throw new CommandException(Text.of("No such token. \"api token list\" shows them.",
                    "Kein solches Token. „api token list“ zeigt sie."));
        }
        out.success(Text.of("Token %1$s (%2$s) revoked.", "Token %1$s (%2$s) widerrufen."),
                revoked.get().name(), revoked.get().id());
    }

    private static void scopes(ConsoleOutput out) {
        out.heading("Scopes");
        for (Scope scope : Scope.values()) {
            out.println("  " + scope.id());
        }
    }

    private static String scopeNames(ApiToken token) {
        return String.join(",", token.scopes().stream().map(Scope::id).sorted().toList());
    }
}
