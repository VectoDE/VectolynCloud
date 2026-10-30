package net.vectolyn.cloud.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import net.vectolyn.cloud.config.CloudHome;
import net.vectolyn.cloud.config.io.AtomicFileWriter;
import net.vectolyn.cloud.config.validation.ConfigNames;
import net.vectolyn.cloud.config.io.RegularPaths;
import net.vectolyn.cloud.config.model.TlsConfig;
import net.vectolyn.cloud.config.secret.OwnerOnlyPermissions;
import net.vectolyn.cloud.config.secret.Secret;

/**
 * Keystore of the API's HTTPS listener ({@code api.tls} in {@code cloud.yml}).
 *
 * <p>Either the operator brings a PKCS#12 keystore with their own certificate (for example from
 * Let's Encrypt, converted with {@code openssl pkcs12 -export}) and puts its password into
 * {@code secrets/vectolyn.env} as {@value #PASSWORD_KEY}, or the cloud makes a self-signed one on the
 * first start with {@code keytool} of the running Java: EC P-256, valid 825 days, for
 * {@code localhost}, {@code 127.0.0.1} and the bind address. The password of a generated keystore is
 * random and lives in {@code secrets/api-keystore.password} (owner-only). Clients pin the
 * SHA-256 fingerprint the node logs ({@link Material#fingerprint()}).
 */
public final class ApiTls {

    /** Key in {@code secrets/vectolyn.env} for the password of a keystore the operator brings. */
    public static final String PASSWORD_KEY = "VECTOLYN_API_KEYSTORE_PASSWORD";
    /** File in {@code secrets/} with the password of a generated keystore. */
    public static final String PASSWORD_FILE = "api-keystore.password";
    /** Alias of the key in a generated keystore. */
    public static final String ALIAS = "vectolyn-api";

    private static final Pattern DNS = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*");
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final String PASSWORD_ENV = "VECTOLYN_KEYTOOL_STOREPASS";

    private ApiTls() {
    }

    /**
     * Key material of the listener.
     *
     * @param keystore    the PKCS#12 file
     * @param password    its password
     * @param fingerprint SHA-256 of the certificate, upper-case hex with colons
     * @param generated   whether the cloud made it (self-signed)
     */
    public record Material(Path keystore, Secret password, String fingerprint, boolean generated) {

        /** Checks the values. */
        public Material {
            Objects.requireNonNull(keystore, "keystore");
            Objects.requireNonNull(password, "password");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        /**
         * Loads the key managers.
         *
         * @return the factory
         * @throws IOException if the keystore cannot be read or the password is wrong
         */
        public KeyManagerFactory keyManagers() throws IOException {
            try {
                KeyStore store = load(keystore, password);
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, password.reveal().toCharArray());
                return factory;
            } catch (GeneralSecurityException e) {
                throw new IOException("The API keystore cannot be used (wrong password or no key in it).", e);
            }
        }
    }

    /**
     * Resolves {@code api.tls.keystore} inside the home. The validator already limits it to a relative
     * path of plain segments; this check keeps it inside the home even if that ever changes.
     */
    static Path keystorePath(CloudHome home, String relative) {
        if (relative == null || !relative.matches(ConfigNames.RELATIVE_PATH)) {
            throw new IllegalArgumentException("api.tls.keystore must be a relative path inside the cloud home");
        }
        Path root = home.root();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("api.tls.keystore must be a relative path inside the cloud home");
        }
        return resolved;
    }

    /**
     * Prepares the keystore for {@code tls}: uses the existing file or generates a self-signed one.
     *
     * @param home           the cloud home
     * @param tls            {@code api.tls}; must be enabled
     * @param bind           {@code api.bind}, added to the certificate when generating
     * @param configuredPassword {@value #PASSWORD_KEY} from {@code vectolyn.env}, if set
     * @return the material
     * @throws IOException if the keystore cannot be read, has no password, or cannot be generated
     */
    public static Material prepare(CloudHome home, TlsConfig tls, String bind, Optional<Secret> configuredPassword)
            throws IOException {
        if (tls == null || !tls.enabled() || tls.keystore() == null) {
            throw new IllegalArgumentException("TLS is not enabled");
        }
        Path keystore = keystorePath(home, tls.keystore());
        Path passwordFile = home.secretsDir().resolve(PASSWORD_FILE);
        RegularPaths.requireFileOrMissing(keystore);
        RegularPaths.requireFileOrMissing(passwordFile);
        if (Files.exists(keystore)) {
            Secret password = configuredPassword.filter(p -> !p.isEmpty())
                    .or(() -> readPasswordFile(passwordFile))
                    .orElseThrow(() -> new IOException("The API keystore " + tls.keystore() + " needs its password as "
                            + PASSWORD_KEY + " in secrets/vectolyn.env."));
            boolean generated = configuredPassword.isEmpty() && Files.exists(passwordFile);
            return new Material(keystore, password, fingerprint(keystore, password), generated);
        }
        Secret password = randomPassword();
        Files.createDirectories(home.secretsDir());
        OwnerOnlyPermissions.restrictDirectory(home.secretsDir());
        new AtomicFileWriter().write(passwordFile, password.reveal().getBytes(StandardCharsets.US_ASCII),
                OwnerOnlyPermissions::restrictFile);
        OwnerOnlyPermissions.restrictFile(passwordFile);
        generate(keystore, password, subjectNames(bind));
        return new Material(keystore, password, fingerprint(keystore, password), true);
    }

    /**
     * Generates a self-signed keystore with {@code keytool}. The password goes to keytool through
     * an environment variable, never on its command line.
     *
     * @param keystore the file to create; must not exist
     * @param password the store and key password
     * @param names    DNS names and IP addresses for the certificate
     * @throws IOException if keytool is missing or fails
     */
    public static void generate(Path keystore, Secret password, List<String> names) throws IOException {
        Path keytool = keytool();
        if (!Files.isRegularFile(keytool)) {
            throw new IOException("keytool is missing from this Java installation (" + keytool.getFileName()
                    + "). Bring your own keystore: api.tls.keystore in cloud.yml and " + PASSWORD_KEY
                    + " in secrets/vectolyn.env.");
        }
        Path parent = keystore.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> san = new ArrayList<>();
        for (String name : names) {
            san.add((IPV4.matcher(name).matches() || name.contains(":") ? "ip:" : "dns:") + name);
        }
        List<String> command = List.of(keytool.toString(), "-genkeypair", "-alias", ALIAS, "-keyalg", "EC",
                "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "825",
                "-dname", "CN=VectolynCloud API", "-ext", "SAN=" + String.join(",", san),
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass:env", PASSWORD_ENV, "-keypass:env", PASSWORD_ENV, "-noprompt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put(PASSWORD_ENV, password.reveal());
        Process process = builder.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readNBytes(16 * 1024), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("keytool did not finish within 60 seconds");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while keytool ran", e);
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(keystore)) {
            String firstLine = output.lines().findFirst().orElse("").replaceAll("\\p{Cntrl}", "?");
            throw new IOException("keytool failed with exit code " + process.exitValue() + ": "
                    + firstLine.substring(0, Math.min(200, firstLine.length())));
        }
        OwnerOnlyPermissions.restrictFile(keystore);
    }

    /**
     * The names a generated certificate covers: {@code localhost}, {@code 127.0.0.1}, {@code ::1}
     * and the bind address unless it is a wildcard.
     *
     * @param bind {@code api.bind}
     * @return the names
     */
    static List<String> subjectNames(String bind) {
        Set<String> names = new LinkedHashSet<>(List.of("localhost", "127.0.0.1", "::1"));
        if (bind != null) {
            String host = bind.startsWith("[") && bind.endsWith("]") ? bind.substring(1, bind.length() - 1) : bind;
            boolean wildcard = host.equals("0.0.0.0") || host.equals("::");
            if (!wildcard && (DNS.matcher(host).matches() || IPV4.matcher(host).matches()
                    || host.matches("[0-9a-fA-F:]{2,45}"))) {
                names.add(host.toLowerCase(Locale.ROOT));
            }
            if (wildcard) {
                try {
                    String own = InetAddress.getLocalHost().getHostName();
                    if (DNS.matcher(own).matches()) {
                        names.add(own.toLowerCase(Locale.ROOT));
                    }
                } catch (IOException e) {
                    // no host name; localhost and the addresses stay
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    private static Optional<Secret> readPasswordFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String text = Files.readString(file, StandardCharsets.US_ASCII).strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(Secret.of(text));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Secret randomPassword() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder(40);
        for (int i = 0; i < 40; i++) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return Secret.of(out.toString());
    }

    private static Path keytool() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool";
        return Path.of(System.getProperty("java.home"), "bin", name);
    }

    private static KeyStore load(Path keystore, Secret password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password.reveal().toCharArray());
        }
        return store;
    }

    private static String fingerprint(Path keystore, Secret password) throws IOException {
        try {
            KeyStore store = load(keystore, password);
            for (String alias : Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias)) {
                    Certificate cert = store.getCertificate(alias);
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
                    return HexFormat.ofDelimiter(":").withUpperCase().formatHex(hash);
                }
            }
            throw new IOException("The API keystore holds no key.");
        } catch (GeneralSecurityException e) {
            throw new IOException("The API keystore cannot be read (wrong password or not PKCS#12).", e);
        }
    }
}
