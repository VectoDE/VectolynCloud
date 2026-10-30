package net.vectolyn.cloud.database.sql;

/**
 * ClusterFuzzLite harness for {@link ServerVersion#parse}: the version string comes from the server
 * and must never make the parser throw.
 */
public final class ServerVersionFuzzer {

    private ServerVersionFuzzer() {
    }

    public static void fuzzerTestOneInput(byte[] input) {
        ServerVersion.parse(new String(input, java.nio.charset.StandardCharsets.UTF_8))
                .ifPresent(v -> {
                    if (v.major() < 0 || v.minor() < 0 || v.patch() < 0) {
                        throw new IllegalStateException("negative version part");
                    }
                });
    }
}
