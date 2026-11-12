// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.console;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.vectolyn.cloud.api.auth.ApiToken;
import net.vectolyn.cloud.api.auth.ApiTokens;
import net.vectolyn.cloud.api.auth.Scope;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.cloud.console.ConsoleOutput;
import net.vectolyn.cloud.console.Text;
import net.vectolyn.cloud.console.command.Argument;
import net.vectolyn.cloud.console.command.Command;
import net.vectolyn.cloud.console.command.CommandException;

/**
 * The {@code api} command of the console:
 *
 * <table>
 *   <caption>Subcommands</caption>
 *   <tr><td>{@code api token create <name> <scopes> [lifetime]}</td><td>creates a token and shows it
 *       once; with a lifetime ({@code 12h}, {@code 90d}) it stops working after that time</td></tr>
 *   <tr><td>{@code api token list}</td><td>id, name, scopes, creation time and expiry of every token</td></tr>
 *   <tr><td>{@code api token revoke <id|name>}</td><td>revokes a token at once</td></tr>
 *   <tr><td>{@code api token setup [admin]}</td><td>makes a new one-time setup token for the first
 *       administrator ({@code admin} by default) and shows it once</td></tr>
 *   <tr><td>{@code api scopes}</td><td>lists the scopes</td></tr>
 * </table>
 *
 * <p>The whole command is {@link Command.Builder#sensitive() sensitive}: no line of it reaches the
 * console history. The token is printed only by {@code create}, once, and is not stored anywhere;
 * the node keeps its SHA-256 only.
 */
public final class ApiCommands {

    /** The setup token; %1$s the administrator, %2$s when it expires (UTC). */
    public static final Text SETUP_TOKEN = Text.of(
            "One-time setup token for administrator %1$s, valid once until %2$s:",
            "Einmaliger Einrichtungs-Token für Administrator %1$s, einmal gültig bis %2$s:");
    /** How to use the setup token; %1$s the endpoint. */
    public static final Text SETUP_TOKEN_USE = Text.of(
            "Copy it now: it is shown only this once, the cloud keeps only its SHA-256. Exchange it for the"
                    + " administrator's API token: switch the API on (api.enabled in cloud.yml), then send POST %1$s"
                    + " with \"Authorization: Bearer <setup token>\". \"api token setup\" makes a new one.",
            "Jetzt kopieren: Er wird nur dieses eine Mal angezeigt, die Cloud speichert nur seinen SHA-256. Tausche ihn"
                    + " gegen das API-Token des Administrators: API einschalten (api.enabled in cloud.yml), dann POST %1$s"
                    + " mit „Authorization: Bearer <Einrichtungs-Token>“ senden. „api token setup“ erstellt einen neuen.");
    /** The setup token could not be made; %1$s the reason. */
    public static final Text SETUP_TOKEN_FAILED = Text.of(
            "The setup token could not be created (%1$s). \"api token setup\" in the console tries again.",
            "Der Einrichtungs-Token konnte nicht erstellt werden (%1$s). „api token setup“ in der Konsole versucht es"
                    + " erneut.");

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
                                .argument(Argument.optional("lifetime", Text.of(
                                        "Optional lifetime, 1h to 3650d, for example 90d; without it the token works"
                                                + " until it is revoked",
                                        "Optionale Laufzeit, 1h bis 3650d, zum Beispiel 90d; ohne sie gilt das Token,"
                                                + " bis es widerrufen wird"))
                                        .suggest(List.of("24h", "30d", "90d", "365d")))
                                .executes(context -> create(context.out(), context.argument("name"),
                                        context.argument("scopes"), context.optional("lifetime").orElse(null))))
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
                                .executes(context -> revoke(context.out(), context.argument("token"))))
                        .subcommand(Command.builder("setup")
                                .description(Text.of("Makes a new one-time setup token for the first administrator.",
                                        "Erstellt einen neuen einmaligen Einrichtungs-Token für den ersten Administrator."))
                                .argument(Argument.optional("admin", Text.of("Name of the administrator, default admin",
                                        "Name des Administrators, Standard admin")))
                                .executes(context -> setup(context.out(), context.optional("admin").orElse("admin")))))
                .subcommand(Command.builder("scopes")
                        .description(Text.of("Lists the scopes a token can have.",
                                "Listet die Scopes, die ein Token haben kann."))
                        .executes(context -> scopes(context.out())))
                .build();
    }

    private void create(ConsoleOutput out, String name, String scopeList, String lifetimeText) throws Exception {
        Duration lifetime = null;
        if (lifetimeText != null) {
            try {
                lifetime = ApiTokens.parseLifetime(lifetimeText);
            } catch (IllegalArgumentException e) {
                throw new CommandException(Text.of("The lifetime must be 1h to 3650d, for example 90d.",
                        "Die Laufzeit muss 1h bis 3650d sein, zum Beispiel 90d."));
            }
        }
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
        ApiTokens.Created created = store.create(name, scopes, lifetime);
        out.success(Text.of("Token %1$s (%2$s) created with %3$s.", "Token %1$s (%2$s) erstellt mit %3$s."),
                created.info().name(), created.info().id(), scopeNames(created.info()));
        if (created.info().expiresAt() != null) {
            out.println(Text.of("  Expires at %1$s.", "  Läuft ab am %1$s."), created.info().expiresAt());
        }
        // The one place the token is shown. Text built here from a fixed alphabet, so println is safe.
        out.println("  " + created.token().reveal());
        out.warning(Text.of("Copy it now: it is shown only this once and is not stored. Lost tokens are revoked "
                        + "and created again.",
                "Jetzt kopieren: Es wird nur dieses eine Mal angezeigt und nicht gespeichert. Ein verlorenes Token "
                        + "wird widerrufen und neu erstellt."));
    }

    private void setup(ConsoleOutput out, String admin) throws Exception {
        if (!ConfigNames.isValidName(admin)) {
            throw new CommandException(Text.of("The name must match %1$s.", "Der Name muss %1$s entsprechen."),
                    ConfigNames.NAME);
        }
        printSetupToken(out, tokens().issueSetupToken(admin, ApiTokens.SETUP_TOKEN_TTL));
    }

    /**
     * Shows a new setup token once, with how to exchange it. Used by {@code api token setup} and by
     * the node at the end of the first setup.
     *
     * @param out   where to write
     * @param token the new setup token
     */
    public static void printSetupToken(ConsoleOutput out, ApiTokens.SetupToken token) {
        out.success(SETUP_TOKEN, token.admin(), token.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        // The one place the setup token is shown. Built from a fixed alphabet, so println is safe.
        out.println("  " + token.token().reveal());
        out.warning(SETUP_TOKEN_USE, net.vectolyn.cloud.api.http.ApiRoutes.SETUP_CLAIM);
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
        Instant now = tokens().clock().instant();
        String expired = Text.of("expired", "abgelaufen").get(out.language());
        String until = Text.of("until", "bis").get(out.language());
        for (ApiToken token : all) {
            String expiry = token.expiresAt() == null ? ""
                    : "  " + (token.isExpired(now) ? expired : until) + " " + token.expiresAt();
            out.println("  " + token.id() + "  " + token.name() + " ".repeat(width - token.name().length())
                    + "  " + token.createdAt() + "  " + scopeNames(token) + expiry);
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
