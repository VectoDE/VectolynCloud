package net.vectolyn.tools.releasebanner;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Pixelify Sans Bold set the way the brand generator sets it (tools/brand3.py {@code vec_text}):
 * HarfBuzz shaping with kerning and without ligatures, tracking -0.0286 em, sizes in whole font
 * pixels.
 *
 * <p>One font pixel {@code u} is 91.43 font units (upm 1000), so the font size is
 * {@code u / 0.09143}. The cap height is 7u; {@code y} is the top of the capitals, the baseline lies
 * 631 units below it. The ink of the first letter starts at {@code x}: the pen starts one left
 * side bearing (61 units) earlier, and the ink width is the advance width minus two bearings.
 *
 * <p>Glyphs are laid out once at 1000 pt, where one unit is one point, and their unhinted
 * outlines are scaled by hand. That keeps every size an exact multiple of the same geometry.
 */
public final class PixelFont {

    /** One font pixel in font units. */
    static final double PX_UNIT = 91.43;
    /** Top of the capitals above the baseline, in font units. */
    static final double PX_CAP = 631;
    /** Left side bearing of the capitals, in font units. */
    static final double PX_LSB = 61;
    /** Tracking in em. */
    static final double TRACK = -0.0286;
    /** Units per em of Pixelify Sans; the layout size, so one unit is one point. */
    private static final float UPM = 1000f;

    private final Font font;
    private final FontRenderContext frc = new FontRenderContext(null, true, true);

    private PixelFont(Font font) {
        this.font = font;
    }

    /**
     * Loads a static TrueType font (Java2D cannot pick an instance of a variable font).
     *
     * @throws IOException if the file cannot be read or is not a TrueType font, or if it lacks a
     *                     glyph the banner always needs
     */
    public static PixelFont load(Path ttf) throws IOException {
        Font base;
        try {
            base = Font.createFont(Font.TRUETYPE_FONT, ttf.toFile());
        } catch (FontFormatException e) {
            throw new IOException("not a TrueType font: " + ttf, e);
        }
        Map<TextAttribute, Object> attributes = Map.of(
                TextAttribute.SIZE, UPM,
                TextAttribute.KERNING, TextAttribute.KERNING_ON,
                TextAttribute.LIGATURES, 0);
        PixelFont pf = new PixelFont(base.deriveFont(attributes));
        // Everything the layout itself writes: ellipsis, digits, month names, badges, wordmark.
        String needed = BannerLayout.ELLIPSIS + "0123456789,.-+ VECTOLYNCLOUDALPHABETARCPRE"
                + "JanFebMarAprMayJunJulAugSepOctNovDec";
        pf.requireGlyphs(needed, "font " + ttf);
        return pf;
    }

    /**
     * Fails if the font has no glyph for a character of {@code s}, naming the first such code point.
     *
     * @param what what {@code s} is, for the message ("title", "version")
     */
    public void requireGlyphs(String s, String what) {
        s.codePoints().forEach(cp -> {
            if (!font.canDisplay(cp)) {
                throw new IllegalArgumentException(what + ": the font has no glyph for " + Text.codePointName(cp)
                        + (Text.forbidden(cp) ? "" : " '" + new String(Character.toChars(cp)) + "'"));
            }
        });
    }

    /** Ink width of {@code s} at font pixel {@code u}, in pixels. */
    public double width(String s, double u) {
        if (s.isEmpty()) {
            return 0;
        }
        return (advance(layout(s)) - 2 * PX_LSB) * scale(u);
    }

    /** Outline of {@code s} with the cap top at {@code y} and the ink starting at {@code x}. */
    public Shape outline(String s, double x, double y, double u) {
        return outline(s, x, y, u, 0);
    }

    /**
     * Outline of {@code s[from..to)} placed exactly where it would sit inside the whole string,
     * the way brand3 colours parts of a line: the part is shaped on its own and starts after the
     * advance of {@code s[0..from)} plus one tracking step.
     */
    public Shape outline(String s, int from, int to, double x, double y, double u) {
        double pre = from == 0 ? 0 : advance(layout(s.substring(0, from))) + TRACK * UPM;
        return outline(s.substring(from, to), x, y, u, pre);
    }

    private Shape outline(String s, double x, double y, double u, double preUnits) {
        Path2D.Double out = new Path2D.Double(Path2D.WIND_NON_ZERO);
        if (s.isEmpty()) {
            return out;
        }
        double sc = scale(u);
        GlyphVector gv = layout(s);
        AffineTransform toPixels = new AffineTransform();
        toPixels.translate(x - PX_LSB * sc, y + PX_CAP * sc);
        toPixels.scale(sc, sc);
        toPixels.translate(preUnits, 0);
        for (int i = 0; i < gv.getNumGlyphs(); i++) {
            // getGlyphOutline places the glyph at its laid out (kerned) position; add the tracking.
            Shape glyph = gv.getGlyphOutline(i);
            AffineTransform at = new AffineTransform(toPixels);
            at.translate(i * TRACK * UPM, 0);
            out.append(at.createTransformedShape(glyph), false);
        }
        return out;
    }

    private GlyphVector layout(String s) {
        char[] chars = s.toCharArray();
        return font.layoutGlyphVector(frc, chars, 0, chars.length, Font.LAYOUT_LEFT_TO_RIGHT);
    }

    /** Advance of the whole run in font units, with tracking between the glyphs (none after the last). */
    private static double advance(GlyphVector gv) {
        int n = gv.getNumGlyphs();
        return gv.getGlyphPosition(n).getX() + TRACK * UPM * (n - 1);
    }

    private static double scale(double u) {
        return u / PX_UNIT;
    }
}
