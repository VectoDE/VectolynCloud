package net.vectolyn.tools.releasebanner;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Command line entry for the version rules: checks one release tag with {@link ReleaseVersion}
 * and writes what the release workflow needs, as {@code key=value} lines:
 *
 * <pre>
 * channel=rc
 * prerelease=true
 * </pre>
 *
 * <p>The tag comes from the environment variable {@code RELEASE_TAG}, the output file from
 * {@code --out}. A tag that breaks the rules ends with exit code 2, the reason on stderr and no
 * file. release.yml runs it through check-tag.sh before anything is built.
 */
public final class ReleaseTagCheck {

    private ReleaseTagCheck() {
    }

    public static void main(String[] args) {
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, System.getenv());
        } catch (IllegalArgumentException e) {
            // One line starting with "error: "; release.yml turns it into the annotation of the run.
            err.println("error: " + e.getMessage());
            code = 2;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Checks the tag and writes the result file.
     *
     * @return 0
     * @throws IllegalArgumentException for a wrong command line or a tag that breaks the rules
     * @throws IOException              if the file cannot be written
     */
    static int run(String[] args, Map<String, String> env) throws IOException {
        if (args.length != 2 || !"--out".equals(args[0]) || args[1].isEmpty()) {
            throw new IllegalArgumentException("usage: ReleaseTagCheck --out <file> (tag in RELEASE_TAG)");
        }
        ReleaseVersion version = ReleaseVersion.parse(env.get("RELEASE_TAG"));
        // LF on every platform: the lines go into $GITHUB_OUTPUT and are compared byte for byte.
        Files.writeString(Path.of(args[1]), String.join("\n", lines(version)) + "\n", StandardCharsets.UTF_8);
        return 0;
    }

    /** The result lines for a checked tag. */
    static List<String> lines(ReleaseVersion version) {
        return List.of("channel=" + version.channel().id(), "prerelease=" + version.prerelease());
    }
}
