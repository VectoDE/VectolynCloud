// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.panel.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.vectolyn.cloud.api.http.ApiJson;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import org.jspecify.annotations.Nullable;

/**
 * The panel's accounts and roles, in {@code secrets/panel-users.json} (owner-only, written
 * atomically). Built-in roles ({@code owner}, {@code operator}, {@code viewer}) are not stored and
 * cannot be changed; custom roles are. A user's rights are the union of the rights of their roles.
 *
 * <p>The store always keeps at least one enabled user with the {@code owner} role once one exists:
 * changes that would remove the last owner are refused.
 */
public final class PanelUsers {

    /** File name below {@code secrets/}. */
    public static final String FILE_NAME = "panel-users.json";
    /** Schema version of the file. */
    public static final int SCHEMA_VERSION = 1;
    /** Largest file. */
    static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** Most users. */
    static final int MAX_USERS = 500;
    /** Most custom roles. */
    static final int MAX_ROLES = 64;
    /** Most passkeys per user. */
    static final int MAX_PASSKEYS = 16;
    /** Most linked single sign-on accounts per user. */
    static final int MAX_LINKS = 8;

    /** Rule for user names. */
    public static final Pattern USER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}");
    /** Rule for role names. */
    public static final Pattern ROLE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern B64URL = Pattern.compile("[A-Za-z0-9_-]{1,2048}");

    /** The built-in role with every right. */
    public static final String OWNER = "owner";

    /** Built-in roles. */
    public static final Map<String, Set<PanelRight>> BUILT_IN = builtIn();

    private final Path file;
    private final AtomicFileWriter writer;
    private volatile Data data;

    /**
     * A role.
     *
     * @param name   the name
     * @param rights the rights
     */
    public record Role(String name, Set<PanelRight> rights) {
        /** Copies the rights. */
        public Role {
            Objects.requireNonNull(name, "name");
            rights = rights.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(rights));
        }
    }

    /**
     * A registered passkey.
     *
     * @param id        credential id, Base64url
     * @param publicKey COSE key, Base64url
     * @param algorithm COSE algorithm
     * @param signCount the last signature counter
     * @param label     a name the user gave it
     * @param createdAt when it was added
     */
    public record Passkey(String id, String publicKey, int algorithm, long signCount, String label, String createdAt) {
    }

    /**
     * A linked account of the single sign-on provider.
     *
     * @param issuer  the issuer
     * @param subject the {@code sub} claim
     */
    public record OidcLink(String issuer, String subject) {
    }

    /**
     * An account.
     *
     * @param name         the login name
     * @param passwordHash {@link PasswordHash} format
     * @param roles        role names
     * @param totpSecret   Base32 TOTP secret, or null
     * @param totpLastStep last accepted TOTP step, or -1
     * @param passkeys     passkeys
     * @param oidc         linked single sign-on accounts
     * @param disabled     whether sign-in is blocked
     * @param createdAt    when it was created
     */
    public record User(String name, String passwordHash, List<String> roles, @Nullable String totpSecret,
                       long totpLastStep,
                       List<Passkey> passkeys, List<OidcLink> oidc, boolean disabled, String createdAt) {

        /** Copies the lists. */
        public User {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(passwordHash, "passwordHash");
            roles = roles == null ? List.of() : List.copyOf(roles);
            passkeys = passkeys == null ? List.of() : List.copyOf(passkeys);
            oidc = oidc == null ? List.of() : List.copyOf(oidc);
        }

        /**
         * Whether TOTP is switched on.
         *
         * @return true with a secret
         */
        public boolean totp() {
            return totpSecret != null;
        }

        @Override
        public String toString() {
            return "User[" + name + "]";
        }
    }

    /** The file's content. */
    record Data(@JsonProperty(required = true) int schemaVersion, List<Role> roles, List<User> users) {
        Data {
            roles = roles == null ? List.of() : List.copyOf(roles);
            users = users == null ? List.of() : List.copyOf(users);
        }
    }

    /** The file breaks a rule. */
    public static final class FormatException extends Exception {

        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message);
        }

        FormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A change was refused; the message is meant for the user. */
    public static final class RefusedException extends Exception {

        private static final long serialVersionUID = 1L;

        private final String code;

        RefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        RefusedException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        /**
         * A stable code for the frontend.
         *
         * @return the code
         */
        public String code() {
            return code;
        }
    }

    private PanelUsers(Path file, AtomicFileWriter writer, Data data) {
        this.file = file;
        this.writer = writer;
        this.data = data;
    }

    /**
     * Loads the store.
     *
     * @param file the file; missing means no users yet
     * @return the store
     * @throws IOException if the file cannot be read or breaks a rule
     */
    public static PanelUsers load(Path file) throws IOException {
        return load(file, new AtomicFileWriter());
    }

    static PanelUsers load(Path file, AtomicFileWriter writer) throws IOException {
        RegularPaths.requireDirectoryOrMissing(Objects.requireNonNull(file.getParent(), "parent"));
        RegularPaths.requireFileOrMissing(file);
        if (!Files.exists(file)) {
            return new PanelUsers(file, writer, new Data(SCHEMA_VERSION, List.of(), List.of()));
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException(FILE_NAME + " is larger than " + MAX_FILE_BYTES + " bytes");
        }
        try {
            return new PanelUsers(file, writer, parse(Files.readAllBytes(file)));
        } catch (FormatException e) {
            throw new IOException(FILE_NAME + " is not valid: " + e.getMessage(), e);
        }
    }

    /**
     * Parses and checks the file.
     *
     * @param content the bytes
     * @return the content
     * @throws FormatException if it is not valid
     */
    static Data parse(byte[] content) throws FormatException {
        if (content == null || content.length > MAX_FILE_BYTES) {
            throw new FormatException("too large");
        }
        Data parsed;
        try {
            parsed = ApiJson.mapper().readValue(content, Data.class);
        } catch (RuntimeException e) {
            throw new FormatException("unknown field, wrong type or broken JSON", e);
        }
        if (parsed == null) {
            throw new FormatException("empty");
        }
        if (parsed.schemaVersion() != SCHEMA_VERSION) {
            throw new FormatException("unsupported schema-version");
        }
        check(parsed);
        return parsed;
    }

    private static void check(Data data) throws FormatException {
        if (data.roles().size() > MAX_ROLES || data.users().size() > MAX_USERS) {
            throw new FormatException("too many roles or users");
        }
        Set<String> roleNames = new HashSet<>(BUILT_IN.keySet());
        for (Role role : data.roles()) {
            if (role == null || role.name() == null || !ROLE_NAME.matcher(role.name()).matches()) {
                throw new FormatException("a role has an invalid name");
            }
            if (!roleNames.add(role.name())) {
                throw new FormatException("role " + role.name() + " exists twice or is built in");
            }
        }
        Set<String> userNames = new HashSet<>();
        Set<String> credentials = new HashSet<>();
        Set<OidcLink> links = new HashSet<>();
        for (User user : data.users()) {
            if (user == null || !USER_NAME.matcher(user.name()).matches()) {
                throw new FormatException("a user has an invalid name");
            }
            if (!userNames.add(user.name().toLowerCase(java.util.Locale.ROOT))) {
                throw new FormatException("user " + user.name() + " exists twice");
            }
            if (PasswordHash.parse(user.passwordHash()).isEmpty()) {
                throw new FormatException("user " + user.name() + " has an invalid password hash");
            }
            if (user.roles().size() > MAX_ROLES || !roleNames.containsAll(user.roles())) {
                throw new FormatException("user " + user.name() + " has an unknown role");
            }
            if (user.totpSecret() != null && Totp.decodeBase32(user.totpSecret()).map(b -> b.length < 10).orElse(true)) {
                throw new FormatException("user " + user.name() + " has an invalid TOTP secret");
            }
            if (user.passkeys().size() > MAX_PASSKEYS || user.oidc().size() > MAX_LINKS) {
                throw new FormatException("user " + user.name() + " has too many passkeys or links");
            }
            for (Passkey key : user.passkeys()) {
                if (key == null || key.id() == null || !B64URL.matcher(key.id()).matches()
                        || key.publicKey() == null || !B64URL.matcher(key.publicKey()).matches()
                        || key.signCount() < 0 || !label(key.label()) || !instant(key.createdAt())) {
                    throw new FormatException("user " + user.name() + " has an invalid passkey");
                }
                if (!credentials.add(key.id())) {
                    throw new FormatException("a passkey belongs to two users");
                }
            }
            for (OidcLink link : user.oidc()) {
                if (link == null || link.issuer() == null || link.subject() == null || link.issuer().length() > 512
                        || link.subject().isEmpty() || link.subject().length() > 255) {
                    throw new FormatException("user " + user.name() + " has an invalid single sign-on link");
                }
                if (!links.add(link)) {
                    throw new FormatException("a single sign-on account is linked to two users");
                }
            }
            if (!instant(user.createdAt())) {
                throw new FormatException("user " + user.name() + " has an invalid created-at");
            }
        }
    }

    private static boolean label(String label) {
        return label != null && !label.isEmpty() && label.length() <= 64 && label.chars().noneMatch(Character::isISOControl);
    }

    private static boolean instant(String text) {
        if (text == null) {
            return false;
        }
        try {
            Instant.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Map<String, Set<PanelRight>> builtIn() {
        Map<String, Set<PanelRight>> roles = new LinkedHashMap<>();
        roles.put(OWNER, Set.copyOf(EnumSet.allOf(PanelRight.class)));
        roles.put("operator", Set.copyOf(EnumSet.of(PanelRight.SERVICES_READ, PanelRight.SERVICES_WRITE,
                PanelRight.CONFIG_READ, PanelRight.BACKUPS_READ, PanelRight.NODE_READ, PanelRight.TEMPLATES_READ,
                PanelRight.TEMPLATES_WRITE, PanelRight.FILES_READ, PanelRight.FILES_WRITE, PanelRight.PLAYERS_MANAGE)));
        roles.put("viewer", Set.copyOf(EnumSet.of(PanelRight.SERVICES_READ, PanelRight.CONFIG_READ,
                PanelRight.BACKUPS_READ, PanelRight.NODE_READ)));
        return java.util.Collections.unmodifiableMap(roles);
    }

    /**
     * Whether no account exists yet.
     *
     * @return true before the first account
     */
    public boolean empty() {
        return data.users().isEmpty();
    }

    /**
     * Every user.
     *
     * @return the users
     */
    public List<User> users() {
        return data.users();
    }

    /**
     * Every role, built-in first.
     *
     * @return the roles
     */
    public List<Role> roles() {
        List<Role> all = new ArrayList<>();
        BUILT_IN.forEach((name, rights) -> all.add(new Role(name, rights)));
        all.addAll(data.roles());
        return all;
    }

    /**
     * A user by name, ignoring case.
     *
     * @param name the name
     * @return the user
     */
    public Optional<User> find(@Nullable String name) {
        if (name == null) {
            return Optional.empty();
        }
        return data.users().stream().filter(u -> u.name().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * The user a passkey belongs to.
     *
     * @param credentialId the credential id, Base64url
     * @return the user
     */
    public Optional<User> findByPasskey(String credentialId) {
        return data.users().stream()
                .filter(u -> u.passkeys().stream().anyMatch(p -> p.id().equals(credentialId))).findFirst();
    }

    /**
     * The user a single sign-on account is linked to.
     *
     * @param issuer  the issuer
     * @param subject the subject
     * @return the user
     */
    public Optional<User> findByOidc(String issuer, String subject) {
        OidcLink link = new OidcLink(issuer, subject);
        return data.users().stream().filter(u -> u.oidc().contains(link)).findFirst();
    }

    /**
     * The rights of a user.
     *
     * @param user the user
     * @return the union of the rights of its roles
     */
    public Set<PanelRight> rights(User user) {
        Set<PanelRight> rights = EnumSet.noneOf(PanelRight.class);
        for (String role : user.roles()) {
            Set<PanelRight> builtIn = BUILT_IN.get(role);
            if (builtIn != null) {
                rights.addAll(builtIn);
            } else {
                data.roles().stream().filter(r -> r.name().equals(role)).findFirst().ifPresent(r -> rights.addAll(r.rights()));
            }
        }
        return rights;
    }

    /**
     * Adds a user.
     *
     * @param user the new user
     * @throws RefusedException if the name is taken or a value breaks a rule
     * @throws IOException      if the file cannot be written
     */
    public synchronized void add(User user) throws RefusedException, IOException {
        if (find(user.name()).isPresent()) {
            throw new RefusedException("user-exists", "A user with this name exists.");
        }
        if (data.users().size() >= MAX_USERS) {
            throw new RefusedException("too-many-users", "The panel has the most users it allows.");
        }
        List<User> users = new ArrayList<>(data.users());
        users.add(user);
        save(new Data(SCHEMA_VERSION, data.roles(), users));
    }

    /**
     * Changes a user.
     *
     * @param name   the user's name
     * @param change the change
     * @return the changed user
     * @throws RefusedException if the user is missing, the result breaks a rule or no owner would remain
     * @throws IOException      if the file cannot be written
     */
    public synchronized User update(String name, UnaryOperator<User> change) throws RefusedException, IOException {
        User current = find(name).orElseThrow(() -> new RefusedException("no-such-user", "No such user."));
        User changed = change.apply(current);
        List<User> users = new ArrayList<>(data.users());
        users.set(users.indexOf(current), changed);
        save(new Data(SCHEMA_VERSION, data.roles(), users));
        return changed;
    }

    /**
     * Removes a user.
     *
     * @param name the name
     * @return the removed user
     * @throws RefusedException if the user is missing or is the last owner
     * @throws IOException      if the file cannot be written
     */
    public synchronized User remove(String name) throws RefusedException, IOException {
        User current = find(name).orElseThrow(() -> new RefusedException("no-such-user", "No such user."));
        List<User> users = new ArrayList<>(data.users());
        users.remove(current);
        save(new Data(SCHEMA_VERSION, data.roles(), users));
        return current;
    }

    /**
     * Adds or replaces a custom role.
     *
     * @param role the role
     * @throws RefusedException if the name is invalid or built in
     * @throws IOException      if the file cannot be written
     */
    public synchronized void saveRole(Role role) throws RefusedException, IOException {
        if (!ROLE_NAME.matcher(role.name()).matches()) {
            throw new RefusedException("invalid-role", "Role names are 1 to 32 characters: a-z, 0-9 and -, starting with a letter.");
        }
        if (BUILT_IN.containsKey(role.name())) {
            throw new RefusedException("built-in-role", "Built-in roles cannot be changed.");
        }
        List<Role> roles = new ArrayList<>(data.roles());
        roles.removeIf(r -> r.name().equals(role.name()));
        roles.add(role);
        save(new Data(SCHEMA_VERSION, roles, data.users()));
    }

    /**
     * Removes a custom role.
     *
     * @param name the role
     * @throws RefusedException if it is built in, missing or still given to a user
     * @throws IOException      if the file cannot be written
     */
    public synchronized void removeRole(String name) throws RefusedException, IOException {
        if (BUILT_IN.containsKey(name)) {
            throw new RefusedException("built-in-role", "Built-in roles cannot be removed.");
        }
        if (data.roles().stream().noneMatch(r -> r.name().equals(name))) {
            throw new RefusedException("no-such-role", "No such role.");
        }
        if (data.users().stream().anyMatch(u -> u.roles().contains(name))) {
            throw new RefusedException("role-in-use", "Users still have this role.");
        }
        List<Role> roles = new ArrayList<>(data.roles());
        roles.removeIf(r -> r.name().equals(name));
        save(new Data(SCHEMA_VERSION, roles, data.users()));
    }

    private void save(Data next) throws RefusedException, IOException {
        try {
            check(next);
        } catch (FormatException e) {
            throw new RefusedException("invalid", Objects.requireNonNullElse(e.getMessage(), "invalid"), e);
        }
        if (!data.users().isEmpty() && owners(next.users()) == 0) {
            throw new RefusedException("last-owner", "At least one enabled user must keep the owner role.");
        }
        Path dir = Objects.requireNonNull(file.getParent(), "parent");
        RegularPaths.requireDirectoryOrMissing(dir);
        Files.createDirectories(dir);
        OwnerOnlyPermissions.restrictDirectory(dir);
        RegularPaths.requireFileOrMissing(file);
        writer.write(file, ApiJson.write(next), OwnerOnlyPermissions::restrictFile);
        OwnerOnlyPermissions.restrictFile(file);
        data = next;
    }

    private static long owners(Collection<User> users) {
        return users.stream().filter(u -> !u.disabled() && u.roles().contains(OWNER)).count();
    }
}
