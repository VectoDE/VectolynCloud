package net.vectora.cloud.launcher;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Main class of {@code VectoraCloud.jar}. Its only job is step 1 of the start order: make sure the
 * running Java is new enough, then hand over to {@code Launcher}.
 *
 * <p>This class is compiled with {@code --release 8} (source set {@code bootstrap}), unlike the rest
 * of the cloud. A Java 8 to 20 runtime can therefore load it and print a readable message instead
 * of an {@code UnsupportedClassVersionError}. It must stay free of Java 9+ APIs and must not
 * reference any other Vectora class directly, because those are compiled for Java 21.
 */
public final class Bootstrap {

    /** Lowest Java feature version VectoraCloud runs on. */
    public static final int MINIMUM_JAVA = 21;

    /** Exit code when the running Java is too old. */
    public static final int EXIT_JAVA_TOO_OLD = 3;

    private static final String LAUNCHER_CLASS = "net.vectora.cloud.launcher.Launcher";

    private Bootstrap() {
    }

    /**
     * Checks the Java version and starts the launcher.
     *
     * @param args command line arguments, passed on unchanged
     * @throws Exception anything the launcher throws, unwrapped from the reflective call
     */
    public static void main(String[] args) throws Exception {
        int feature = featureVersion(System.getProperty("java.specification.version"));
        if (feature < MINIMUM_JAVA) {
            String language = language(args, System.getProperty("user.language"));
            System.err.println(tooOldMessage(feature, System.getProperty("java.home"), language));
            System.exit(EXIT_JAVA_TOO_OLD);
            return;
        }
        Method main = Class.forName(LAUNCHER_CLASS).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /**
     * Turns {@code java.specification.version} into a feature version.
     *
     * @param specificationVersion {@code "1.8"} style up to Java 8, {@code "17"} style from Java 9
     * @return the feature version, or 0 if the value cannot be read
     */
    public static int featureVersion(String specificationVersion) {
        if (specificationVersion == null) {
            return 0;
        }
        String value = specificationVersion.trim();
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        int dot = value.indexOf('.');
        if (dot >= 0) {
            value = value.substring(0, dot);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Picks the message language: {@code --lang de|en} wins, otherwise the JVM's
     * {@code user.language}. Anything but German falls back to English.
     *
     * @param args         raw command line arguments
     * @param userLanguage value of {@code user.language}, may be {@code null}
     * @return {@code "de"} or {@code "en"}
     */
    public static String language(String[] args, String userLanguage) {
        String chosen = userLanguage;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--lang") && i + 1 < args.length) {
                chosen = args[i + 1];
            } else if (arg.startsWith("--lang=")) {
                chosen = arg.substring("--lang=".length());
            }
        }
        return "de".equalsIgnoreCase(chosen) ? "de" : "en";
    }

    /**
     * Builds the message shown when the running Java is too old.
     *
     * @param found    feature version found, 0 if unknown
     * @param javaHome {@code java.home} of the running JVM, may be {@code null}
     * @param language {@code "de"} or {@code "en"}
     * @return a multi-line message
     */
    public static String tooOldMessage(int found, String javaHome, String language) {
        String version = found > 0 ? String.valueOf(found) : "?";
        String home = javaHome == null ? "?" : javaHome;
        String nl = System.lineSeparator();
        if ("de".equals(language)) {
            return "VectoraCloud braucht Java " + MINIMUM_JAVA + " oder neuer." + nl
                    + "Gestartet wurde es mit Java " + version + " (" + home + ")." + nl
                    + "Installiere ein JDK oder JRE ab Version " + MINIMUM_JAVA
                    + ", zum Beispiel Eclipse Temurin von https://adoptium.net/," + nl
                    + "und starte VectoraCloud damit: \"<pfad-zu-java-" + MINIMUM_JAVA
                    + ">/bin/java\" -jar VectoraCloud.jar";
        }
        return "VectoraCloud needs Java " + MINIMUM_JAVA + " or newer." + nl
                + "It was started with Java " + version + " (" + home + ")." + nl
                + "Install a JDK or JRE " + MINIMUM_JAVA
                + " or newer, for example Eclipse Temurin from https://adoptium.net/," + nl
                + "and start VectoraCloud with it: \"<path-to-java-" + MINIMUM_JAVA
                + ">/bin/java\" -jar VectoraCloud.jar";
    }
}
