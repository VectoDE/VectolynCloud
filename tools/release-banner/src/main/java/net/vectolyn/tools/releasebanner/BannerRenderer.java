package net.vectolyn.tools.releasebanner;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Draws a {@link BannerLayout.Banner} on the pre-rendered background (branding/png/
 * release-banner-background.png, 1280 x 320, and its @2x version, 2560 x 640). The 2x image is the
 * same 1x geometry drawn at scale 2, so both sizes show exactly the same layout.
 */
public final class BannerRenderer {

    private BannerRenderer() {
    }

    /**
     * Renders the banner at {@code scale} (1 or 2) onto a copy of {@code background}.
     *
     * @throws IllegalArgumentException if the background is not 1280 x 320 times {@code scale}
     */
    public static BufferedImage render(BannerLayout.Banner banner, BufferedImage background, int scale) {
        int w = BannerLayout.WIDTH * scale;
        int h = BannerLayout.HEIGHT * scale;
        if (background.getWidth() != w || background.getHeight() != h) {
            throw new IllegalArgumentException("background must be " + w + " x " + h + " for scale " + scale
                    + ", is " + background.getWidth() + " x " + background.getHeight());
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(background, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.scale(scale, scale);
            for (BannerLayout.Layer layer : banner.layers()) {
                g.setColor(layer.color());
                g.fill(layer.shape());
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Reads a PNG. */
    public static BufferedImage read(Path png) throws IOException {
        BufferedImage image = ImageIO.read(png.toFile());
        if (image == null) {
            throw new IOException("not an image ImageIO can read: " + png);
        }
        return image;
    }

    /** Writes a PNG (no time stamp or other varying metadata, so equal pixels give equal bytes). */
    public static void write(BufferedImage image, Path png) throws IOException {
        Path parent = png.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(image, "png", png.toFile())) {
            throw new IOException("no PNG writer available");
        }
    }
}
