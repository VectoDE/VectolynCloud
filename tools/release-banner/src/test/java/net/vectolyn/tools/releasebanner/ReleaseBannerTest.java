package net.vectolyn.tools.releasebanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseBannerTest {

    @TempDir
    Path tmp;

    private String[] args(Path out, String... extra) {
        List<String> a = new ArrayList<>(List.of(
                "--font", BrandFiles.fontFile().toString(),
                "--background", BrandFiles.background().toString(),
                "--background-2x", BrandFiles.background2x().toString(),
                "--out-dir", out.toString()));
        a.addAll(List.of(extra));
        return a.toArray(String[]::new);
    }

    private static Map<String, String> env(String version, String title, String date) {
        return Map.of("BANNER_VERSION", version, "BANNER_TITLE", title, "BANNER_DATE", date);
    }

    private String run(Path out, Map<String, String> env, String... extra) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ReleaseBanner.run(args(out, extra), env, new PrintStream(buf, true, StandardCharsets.UTF_8));
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void writesBothSizes() throws IOException {
        String log = run(tmp, env("v1.0.0-beta.2", "Stage 2: cloud core", "2026-10-30"));
        BufferedImage one = BannerRenderer.read(tmp.resolve("VectolynCloud-1.0.0-beta.2-banner.png"));
        BufferedImage two = BannerRenderer.read(tmp.resolve("VectolynCloud-1.0.0-beta.2-banner-2x.png"));
        assertEquals(1280, one.getWidth());
        assertEquals(320, one.getHeight());
        assertEquals(2560, two.getWidth());
        assertEquals(640, two.getHeight());
        assertTrue(log.contains("badge BETA"), log);
        assertTrue(log.contains("title \"Stage 2: cloud core\""), log);
        assertTrue(log.contains("date Oct 30, 2026"), log);
        try (var files = Files.list(tmp)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void outputIsByteIdenticalAcrossRuns() throws IOException {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Map<String, String> env = env("v1.0.0-rc.1", "Stage 2: cloud core", "2026-10-30");
        run(a, env);
        run(b, env);
        for (String name : List.of("VectolynCloud-1.0.0-rc.1-banner.png", "VectolynCloud-1.0.0-rc.1-banner-2x.png")) {
            assertArrayEquals(Files.readAllBytes(a.resolve(name)), Files.readAllBytes(b.resolve(name)), name);
        }
    }

    @Test
    void optionsWinOverEnvironmentAndNameCanBeSet() throws IOException {
        String log = run(tmp, env("v9.9.9", "ignored", "2020-01-01"),
                "--version", "v1.0.0", "--title", "", "--date", "2026-10-30", "--name", "preview");
        assertTrue(Files.exists(tmp.resolve("preview.png")));
        assertTrue(Files.exists(tmp.resolve("preview-2x.png")));
        assertTrue(log.contains("v1.0.0"), log);
        assertTrue(log.contains("badge none"), log);
        assertTrue(log.contains("title none"), log);
    }

    @Test
    void missingTitleMeansNoTitle() throws IOException {
        String log = run(tmp, Map.of("BANNER_VERSION", "v1.0.0", "BANNER_DATE", "2026-10-30"));
        assertTrue(log.contains("title none"), log);
    }

    @Test
    void invalidInputWritesNothing() {
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("0.2.0", "x", "2026-10-30")));
        // The version rules apply to the banner as well: 0.x only as -dev.
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("v0.2.0", "x", "2026-10-30")));
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("v1.0.0", "x", "30.10.2026")));
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("v1.0.0", "a\u0000b", "2026-10-30")));
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("v1.0.0", "🚀", "2026-10-30")));
        assertThrows(IllegalArgumentException.class,
                () -> run(tmp, Map.of("BANNER_TITLE", "x", "BANNER_DATE", "2026-10-30")));
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("v1.0.0", "x", "2026-10-30"), "--name", "../x"));
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("v1.0.0", "x", "2026-10-30"), "--bogus", "1"));
        assertThrows(IllegalArgumentException.class, () -> run(tmp, env("v1.0.0", "x", "2026-10-30"), "--name"));
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseBanner.run(new String[0], env("v1.0.0", "x", "2026-10-30"), System.out));
        assertFalse(Files.exists(tmp.resolve("VectolynCloud-0.2.0-banner.png")));
    }

    @Test
    void wrongBackgroundSizeIsRejected() throws IOException {
        BannerLayout.Banner banner = new BannerLayout(BrandFiles.font())
                .layout(ReleaseVersion.parse("v1.0.0"), "", LocalDate.of(2026, 10, 30));
        BufferedImage bg1 = BannerRenderer.read(BrandFiles.background());
        assertThrows(IllegalArgumentException.class, () -> BannerRenderer.render(banner, bg1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> run(tmp, env("v1.0.0", "x", "2026-10-30"), "--background-2x", BrandFiles.background().toString()));
    }

    @Test
    void textIsDrawnAndBackgroundOutsideTheTextIsUntouched() throws IOException {
        BannerLayout.Banner banner = new BannerLayout(BrandFiles.font())
                .layout(ReleaseVersion.parse("v1.0.0"), "Stage 2: cloud core", LocalDate.of(2026, 10, 30));
        BufferedImage bg = BannerRenderer.read(BrandFiles.background());
        BufferedImage img = BannerRenderer.render(banner, bg, 1);
        // The 3D mark on the right and the corners are left alone.
        for (int[] p : new int[][] {{1100, 160}, {5, 5}, {1275, 315}, {870, 160}}) {
            assertEquals(bg.getRGB(p[0], p[1]) & 0xFFFFFF, img.getRGB(p[0], p[1]) & 0xFFFFFF);
        }
        // Somewhere in the version line the text colour shows.
        int solid1 = count(img, 0xECEEF4, 1);
        assertTrue(solid1 > 1000, "pixels in the text colour: " + solid1);
        // The 2x image is the same layout drawn at scale 2: about four times the solid pixels.
        BufferedImage img2 = BannerRenderer.render(banner, BannerRenderer.read(BrandFiles.background2x()), 2);
        int solid2 = count(img2, 0xECEEF4, 2);
        assertNotEquals(0, solid2);
        double ratio = solid2 / (double) solid1;
        assertTrue(ratio > 3.8 && ratio < 4.4, "2x/1x solid pixel ratio " + ratio);
    }

    /** Pixels of exactly {@code rgb} in the version line (1x box 80..420 x 120..200, scaled). */
    private static int count(BufferedImage img, int rgb, int scale) {
        int n = 0;
        for (int y = 120 * scale; y < 200 * scale; y++) {
            for (int x = 80 * scale; x < 420 * scale; x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    n++;
                }
            }
        }
        return n;
    }
}
