package net.vectolyn.tools.releasebanner;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command line entry: renders {@code <name>.png} (1280 x 320) and {@code <name>-2x.png}
 * (2560 x 640) for one release.
 *
 * <pre>
 * ReleaseBanner --font PixelifySans-Bold.ttf --background bg.png --background-2x bg@2x.png
 *               --out-dir dist [--name VectolynCloud-1.2.3-banner]
 *               [--version v1.2.3] [--title "Stage 2: cloud core"] [--date 2026-10-30]
 * </pre>
 *
 * <p>Version, title and date fall back to the environment variables {@code BANNER_VERSION},
 * {@code BANNER_TITLE} and {@code BANNER_DATE}. The Gradle task passes them that way, so a title
 * never goes through a command line (and its quoting rules on Windows). Every value is checked
 * strictly; anything wrong ends with exit code 2 and no file is written.
 */
public final class ReleaseBanner {

    private static final Set<String> OPTIONS = Set.of(
            "--font", "--background", "--background-2x", "--out-dir", "--name", "--version", "--title", "--date");

    private ReleaseBanner() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        int code;
        try {
            code = run(args, System.getenv(), out);
        } catch (IllegalArgumentException e) {
            err.println("release banner: " + e.getMessage());
            code = 2;
        } catch (IOException e) {
            err.println("release banner: " + e.getMessage());
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * Renders both files.
     *
     * @return 0
     * @throws IllegalArgumentException for a missing or invalid option or value
     * @throws IOException              if a file cannot be read or written
     */
    static int run(String[] args, Map<String, String> env, PrintStream out) throws IOException {
        Map<String, String> opts = parse(args);
        Path font = Path.of(required(opts, "--font"));
        Path background = Path.of(required(opts, "--background"));
        Path background2x = Path.of(required(opts, "--background-2x"));
        Path outDir = Path.of(required(opts, "--out-dir"));

        ReleaseVersion version = ReleaseVersion.parse(value(opts, env, "--version", "BANNER_VERSION"));
        String title = value(opts, env, "--title", "BANNER_TITLE");
        LocalDate date = ReleaseDate.parse(value(opts, env, "--date", "BANNER_DATE"));
        String name = opts.getOrDefault("--name", "VectolynCloud-" + version.withoutPrefix() + "-banner");
        if (!name.matches("[0-9A-Za-z][0-9A-Za-z._+-]*")) {
            throw new IllegalArgumentException("--name may only contain letters, digits and . _ + -: "
                    + Text.printable(name));
        }

        BannerLayout.Banner banner = new BannerLayout(PixelFont.load(font)).layout(version, title, date);
        Path file1x = outDir.resolve(name + ".png");
        Path file2x = outDir.resolve(name + "-2x.png");
        // Both images are rendered before anything is written.
        var image1x = BannerRenderer.render(banner, BannerRenderer.read(background), 1);
        var image2x = BannerRenderer.render(banner, BannerRenderer.read(background2x), 2);
        BannerRenderer.write(image1x, file1x);
        BannerRenderer.write(image2x, file2x);

        out.println("Release banner for " + version.tag() + ": version u " + banner.versionU()
                + ", badge " + banner.badge().map(Badge::label).orElse("none")
                + ", title " + (banner.title().isEmpty() ? "none" : "\"" + banner.title() + "\"")
                + ", date " + banner.date());
        for (Path p : List.of(file1x, file2x)) {
            out.println("  " + p);
        }
        return 0;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String key = args[i];
            if (!OPTIONS.contains(key)) {
                throw new IllegalArgumentException("unknown option " + Text.printable(key));
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("option " + key + " needs a value");
            }
            if (opts.put(key, args[i + 1]) != null) {
                throw new IllegalArgumentException("option " + key + " given twice");
            }
        }
        return opts;
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("option " + key + " is required");
        }
        return v;
    }

    /** The option if given, else the environment variable; the title may be missing (no title). */
    private static String value(Map<String, String> opts, Map<String, String> env, String key, String variable) {
        String v = opts.containsKey(key) ? opts.get(key) : env.get(variable);
        if (v == null && "--title".equals(key)) {
            return "";
        }
        return v;
    }
}
