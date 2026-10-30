package net.vectolyn.tools.releasebanner;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Text layer of the release banner at 1x (1280 x 320), a port of tools/brand3.py
 * {@code release_text} of the brand set (branding/release-banner-spec.json and
 * release-banner-template.svg describe the same rules).
 *
 * <p>Text column x 80 to 860, the 3D mark of the background owns the rest. From top to bottom,
 * centred as one block: the wordmark VECTOLYN + CLOUD (u 3) with the pre-release badge 12 px to its
 * right, 20 px gap, the version (u 12, else 10, 8 or 6, the largest that fits 780 px), 24 px gap,
 * one line with the title (u 3), a 6 x 6 gold square and the date (u 3). Without a title the line
 * holds only the date.
 */
public final class BannerLayout {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 320;
    static final int X = 80;
    static final int TEXT_RIGHT = 860;
    static final int MAX_WIDTH = TEXT_RIGHT - X;
    static final int WORDMARK_U = 3;
    static final int[] VERSION_U = {12, 10, 8, 6};
    static final int TITLE_U = 3;
    static final int DATE_U = 3;
    static final int BADGE_U = 3;
    static final int GAP_WORDMARK_VERSION = 20;
    static final int GAP_VERSION_TITLE = 24;
    static final int GAP_BADGE = 12;
    static final int SEPARATOR_GAP = 18;
    static final int TITLE_MAX_CHARS = 48;
    static final String ELLIPSIS = "…";
    /** Trimmed from the end of a shortened title before the ellipsis. */
    private static final String TRIM = " ,.:;-";

    static final Color TEXT = new Color(0xECEEF4);
    static final Color MUTED = new Color(0x9EA3B8);
    static final Color GOLD = new Color(0xFFD23F);
    static final Color CYAN = new Color(0x00E5FF);

    /** A filled shape in 1x pixel coordinates. */
    public record Layer(Shape shape, Color color) {
        public Layer {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(color, "color");
        }
    }

    /**
     * The laid out banner.
     *
     * @param layers  shapes in paint order
     * @param versionU font pixel of the version line
     * @param title    the title as shown (shortened if needed), empty if there is none
     * @param date     the date as shown
     * @param badge    the badge, empty for a stable release
     */
    public record Banner(List<Layer> layers, int versionU, String title, String date, Optional<Badge> badge) {
        public Banner {
            layers = List.copyOf(layers);
        }
    }

    private final PixelFont font;

    public BannerLayout(PixelFont font) {
        this.font = Objects.requireNonNull(font, "font");
    }

    /**
     * Lays out the banner.
     *
     * @param version the release tag
     * @param rawTitle first line of the tag message, may be empty
     * @param date     release date
     * @throws IllegalArgumentException if the title contains a forbidden character or one the font
     *                                  cannot draw, or if the version does not fit even at u 6
     */
    public Banner layout(ReleaseVersion version, String rawTitle, LocalDate date) {
        String title = cleanTitle(rawTitle);
        font.requireGlyphs(title, "title");

        int versionU = versionU(version.tag());
        String dateText = ReleaseDate.format(date);
        double dateWidth = font.width(dateText, DATE_U);
        title = fitTitle(title, dateWidth);

        int hw = 7 * WORDMARK_U;
        int hv = 7 * versionU;
        int ht = 7 * TITLE_U;
        int block = hw + GAP_WORDMARK_VERSION + hv + GAP_VERSION_TITLE + ht;
        // Always a whole number for the u ladder above; Math.round keeps it right for any other.
        int y0 = (int) Math.round((HEIGHT - block) / 2.0);
        int yv = y0 + hw + GAP_WORDMARK_VERSION;
        int yt = yv + hv + GAP_VERSION_TITLE;

        List<Layer> layers = new ArrayList<>();
        // Wordmark: VECTOLYN + CLOUD as one word, one font pixel apart, the colour separates them.
        double w1 = font.width("VECTOLYN", WORDMARK_U);
        layers.add(new Layer(font.outline("VECTOLYN", X, y0, WORDMARK_U), TEXT));
        layers.add(new Layer(font.outline("CLOUD", X + w1 + WORDMARK_U, y0, WORDMARK_U), CYAN));
        double wordmarkWidth = w1 + WORDMARK_U + font.width("CLOUD", WORDMARK_U);

        Optional<Badge> badge = version.badge();
        badge.ifPresent(b -> addBadge(layers, b, X + wordmarkWidth + GAP_BADGE, y0 + hw / 2.0 - 9 * BADGE_U / 2.0,
                BADGE_U));

        addVersion(layers, version, X, yv, versionU);

        double cx = X;
        if (!title.isEmpty()) {
            layers.add(new Layer(font.outline(title, X, yt, TITLE_U), TEXT));
            cx = X + font.width(title, TITLE_U) + SEPARATOR_GAP - 3;
            layers.add(new Layer(new Rectangle2D.Double(Math.rint(cx), yt + 9, 6, 6), GOLD));
            cx += 3 + SEPARATOR_GAP;
        }
        layers.add(new Layer(font.outline(dateText, cx, yt, DATE_U), MUTED));
        return new Banner(layers, versionU, title, dateText, badge);
    }

    /** The largest version size that fits the text column. */
    int versionU(String tag) {
        font.requireGlyphs(tag, "version");
        for (int u : VERSION_U) {
            if (font.width(tag, u) <= MAX_WIDTH) {
                return u;
            }
        }
        throw new IllegalArgumentException("version " + tag + " is too long for the banner: "
                + Math.round(font.width(tag, VERSION_U[VERSION_U.length - 1])) + " px at the smallest size, "
                + MAX_WIDTH + " px available");
    }

    /** Strips surrounding white space and rejects characters that must not be drawn. */
    static String cleanTitle(String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.strip();
        title.codePoints().filter(Text::forbidden).findFirst().ifPresent(cp -> {
            throw new IllegalArgumentException("title contains the control or format character "
                    + Text.codePointName(cp) + ": " + Text.printable(title));
        });
        return title;
    }

    /**
     * At most 48 characters, cut at a word end with an ellipsis; then shortened word by word
     * until title, separator and date fit 780 px.
     */
    String fitTitle(String title, double dateWidth) {
        title = truncate(title);
        double room = MAX_WIDTH - (title.isEmpty() ? 0 : dateWidth + 2 * SEPARATOR_GAP);
        while (!title.isEmpty() && font.width(title, TITLE_U) > room) {
            String core = Text.rstrip(title, ELLIPSIS);
            int space = core.lastIndexOf(' ');
            String shorter = space >= 0 ? core.substring(0, space) : Text.dropLast(core);
            String next = Text.rstrip(shorter, TRIM) + ELLIPSIS;
            if (next.equals(title)) {
                // Only an ellipsis is left and even that does not fit: no title at all.
                return "";
            }
            title = next;
        }
        return title;
    }

    /** The 48 character rule alone: cut at the last word end, trim punctuation, add the ellipsis. */
    static String truncate(String title) {
        if (Text.length(title) <= TITLE_MAX_CHARS) {
            return title;
        }
        String head = Text.head(title, TITLE_MAX_CHARS);
        // brand3 always drops the word after the last space of the first 48 characters. When
        // character 49 is a space that word is complete, so it stays (the only deviation).
        if (!Character.isWhitespace(Text.codePointAt(title, TITLE_MAX_CHARS))) {
            head = Text.beforeLast(head, ' ');
        }
        return Text.rstrip(head, TRIM) + ELLIPSIS;
    }

    private void addVersion(List<Layer> layers, ReleaseVersion version, double x, double y, int u) {
        String tag = version.tag();
        // Runs of one colour: the points of MAJOR.MINOR.PATCH gold, everything else white.
        int start = 0;
        Color current = versionColor(tag, 0, version.coreLength());
        for (int i = 1; i <= tag.length(); i++) {
            Color next = i < tag.length() ? versionColor(tag, i, version.coreLength()) : null;
            if (!current.equals(next)) {
                layers.add(new Layer(font.outline(tag, start, i, x, y, u), current));
                start = i;
                current = next;
            }
        }
    }

    private static Color versionColor(String tag, int i, int coreLength) {
        return tag.charAt(i) == '.' && i < coreLength ? GOLD : TEXT;
    }

    /** Pixel badge: corners cut by one font pixel, padding 2u by 1u, 9u high, text in Obsidian. */
    private void addBadge(List<Layer> layers, Badge badge, double x, double y, int u) {
        double w = font.width(badge.label(), u);
        double pw = w + 4 * u;
        double ph = 9 * u;
        double[][] pts = {
            {u, 0}, {pw - u, 0}, {pw - u, u}, {pw, u}, {pw, ph - u}, {pw - u, ph - u},
            {pw - u, ph}, {u, ph}, {u, ph - u}, {0, ph - u}, {0, u}, {u, u},
        };
        Path2D.Double shape = new Path2D.Double();
        for (int i = 0; i < pts.length; i++) {
            // Whole pixels, as in the brand set's SVG.
            double px = Math.rint(x + pts[i][0]);
            double py = Math.rint(y + pts[i][1]);
            if (i == 0) {
                shape.moveTo(px, py);
            } else {
                shape.lineTo(px, py);
            }
        }
        shape.closePath();
        layers.add(new Layer(shape, badge.fill()));
        layers.add(new Layer(font.outline(badge.label(), x + 2 * u, y + u, u), Badge.TEXT));
    }
}
