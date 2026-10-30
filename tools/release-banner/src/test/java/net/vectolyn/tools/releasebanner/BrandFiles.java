package net.vectolyn.tools.releasebanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** The brand files the Gradle test task passes in (branding/fonts/, branding/png/). */
final class BrandFiles {

    private static PixelFont font;

    private BrandFiles() {
    }

    static Path path(String property) {
        String value = System.getProperty(property);
        if (value == null) {
            throw new IllegalStateException("system property " + property + " is not set (run the tests through Gradle)");
        }
        return Path.of(value);
    }

    static Path fontFile() {
        return path("banner.font");
    }

    static Path background() {
        return path("banner.background");
    }

    static Path background2x() {
        return path("banner.background2x");
    }

    static synchronized PixelFont font() {
        if (font == null) {
            try {
                font = PixelFont.load(fontFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return font;
    }
}
