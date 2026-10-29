package net.vectora.cloud.api.console;

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

    private final ApiTokens tokens;

    /**
     * Creates the commands.
     *
     * @param tokens the token store, the same instance the API server uses
     */
    public ApiCommands(ApiTokens tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
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
                                        .suggest(previous -> tokens.list().stream().map(ApiToken::name).toList()))
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
        if (tokens.list().stream().anyMatch(t -> t.name().equals(name))) {
            throw new CommandException(Text.of("A token named %1$s exists already.",
                    "Ein Token namens %1$s existiert bereits."), name);
        }
        if (tokens.list().size() >= ApiTokens.MAX_TOKENS) {
            throw new CommandException(Text.of("At most %1$s tokens are possible.", "Höchstens %1$s Tokens sind möglich."),
                    ApiTokens.MAX_TOKENS);
        }
        ApiTokens.Created created = tokens.create(name, scopes);
        out.success(Text.of("Token %1$s (%2$s) created with %3$s.", "Token %1$s (%2$s) erstellt mit %3$s."),
                created.info().name(), created.info().id(), scopeNames(created.info()));
        // The one place the token is shown. Text built here from a fixed alphabet, so println is safe.
        out.println("  " + created.token().reveal());
        out.warning(Text.of("Copy it now: it is shown only this once and is not stored. Lost tokens are revoked "
                        + "and created again.",
                "Jetzt kopieren: Es wird nur dieses eine Mal angezeigt und nicht gespeichert. Ein verlorenes Token "
                        + "wird widerrufen und neu erstellt."));
    }

    private void list(ConsoleOutput out) {
        List<ApiToken> all = tokens.list();
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
        Optional<ApiToken> revoked = tokens.revoke(idOrName);
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
