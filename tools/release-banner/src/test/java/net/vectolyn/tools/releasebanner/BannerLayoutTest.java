package net.vectolyn.tools.releasebanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BannerLayoutTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 30);
    private static final String E = BannerLayout.ELLIPSIS;

    private final PixelFont font = BrandFiles.font();
    private final BannerLayout layout = new BannerLayout(font);

    private double dateWidth() {
        return font.width("Oct 30, 2026", BannerLayout.DATE_U);
    }

    /**
     * Ink widths from the reference generator (tools/brand3.py vec_text: HarfBuzz on the variable
     * font at wght 700, kern on, liga off). Equal widths mean equal kerning and tracking.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "VECTOLYN|3|141.8068",
        "CLOUD|3|91.1714",
        "BETA|3|71.0117",
        "PRE-RELEASE|3|196.5110",
        "v0.2.0|12|343.2134",
        "v0.3.0-beta.2|10|675.2488",
        "Stage 2: cloud core|3|289.1130",
        "Oct 30, 2026|3|187.7633",
        "To AV Yo LY|3|154.4132",
        "…|3|17.6200",
        "v10.12.3-beta.12|8|640.1400",
        "LTS|3|48.8833",
        "v0.2.0-dev.3|12|714.1726",
        "v1.4.0-lts|12|542.6578",
        "v1.0.0-dev.20261030|6|593.1226",
    })
    void widthsMatchTheReferenceGenerator(String text, int u, double expected) {
        assertEquals(expected, font.width(text, u), 0.02, text);
    }

    @Test
    void inkStartsAtXAndCapTopSitsAtY() {
        // brand3: vec_text("V", 80, 75, 3) -> M85.8 96.1 ... H80 ... V74.8 H95.8 ...
        Rectangle2D b = font.outline("V", 80, 75, 3).getBounds2D();
        assertEquals(80.0, b.getMinX(), 0.06);
        assertEquals(95.8, b.getMaxX(), 0.06);
        assertEquals(74.8, b.getMinY(), 0.06);
        assertEquals(96.1, b.getMaxY(), 0.06);
    }

    @ParameterizedTest
    @CsvSource({
        "v1.0.0, 12",
        "v1.3.0-rc.1, 12",
        "v0.2.0-dev.3, 12",
        "v1.4.0-lts, 12",
        "v1.3.0-alpha.1, 10",
        "v1.3.0-beta.2, 10",
        "v10.12.3-beta.12, 8",
        "v1.0.0-dev.20261030, 6",
    })
    void versionTakesTheLargestSizeThatFits(String tag, int u) {
        assertEquals(u, layout.versionU(tag));
        assertEquals(u, layout.layout(ReleaseVersion.parse(tag), "", DATE).versionU());
    }

    @Test
    void versionTooLongEvenAtTheSmallestSizeFails() {
        ReleaseVersion v = ReleaseVersion.parse("v1.0.0-dev.20261030123456");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> layout.layout(v, "", DATE));
        assertTrue(e.getMessage().contains("too long"), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // Results of brand3.release_text for the same input.
        "Stage 2: cloud core|Stage 2: cloud core",
        "Stage 12: a much longer release title that needs to be shortened somewhere|Stage 12: a much longer release" + E,
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA|AAAAAAAAAAAAAAAAAAAAAAAAAAAA" + E,
        "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW|WWWWWWWWWWWWWWWWWWWWW" + E,
        "MMMM MMMM MMMM MMMM MMMM MMMM MMMM MMMM MMMM MM|MMMM MMMM MMMM MMMM MMMM" + E,
        "Cluster, nodes; templates: and more - with lots of words here.|Cluster, nodes; templates: and" + E,
        "Short. Then more words follow here to pass the limit, yes|Short. Then more words follow" + E,
        "'  padded title  '|padded title",
    })
    void titleIsShortenedLikeTheReference(String title, String shown) {
        assertEquals(shown, layout.layout(ReleaseVersion.parse("v1.0.0"), title, DATE).title());
    }

    @Test
    void longTitleIsCutTo48CharactersAtAWordEnd() {
        // Narrow enough that the width rule does not apply, so only the 48 character rule acts.
        String title = "a bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp qq rr ss";
        String cut = BannerLayout.truncate(title);
        assertEquals("a bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp" + E, cut);
        assertTrue(Text.length(cut) <= BannerLayout.TITLE_MAX_CHARS + 1);
        // Code points, not chars: 50 characters outside the BMP are cut like 50 letters.
        String wide = "𝐀".repeat(50);
        assertEquals("𝐀".repeat(48) + E, BannerLayout.truncate(wide));
    }

    @Test
    void wordEndingExactlyAtCharacter48IsKept() {
        // 48 characters of whole words, then a space: brand3 would drop "klmnop" as well.
        String title = "abcde fghij klmno pqrst uvwxy abcde fghij klmnop tail";
        assertEquals(' ', title.charAt(48));
        assertEquals("abcde fghij klmno pqrst uvwxy abcde fghij klmnop" + E, BannerLayout.truncate(title));
    }

    @Test
    void punctuationBeforeTheEllipsisIsTrimmed() {
        String title = "Stage two, three; four: five - six. seven eight, nine ten";
        assertEquals("Stage two, three; four: five - six. seven eight" + E, BannerLayout.truncate(title));
        String dashes = "One - two - three - four - five - six - seven -- eight";
        assertEquals("One - two - three - four - five - six - seven" + E, BannerLayout.truncate(dashes));
    }

    @Test
    void singleOverlongWordIsCutCharacterByCharacter() {
        String title = "W".repeat(60);
        String shown = layout.fitTitle(title, dateWidth());
        assertTrue(shown.endsWith(E));
        assertTrue(font.width(shown, 3) <= BannerLayout.MAX_WIDTH - dateWidth() - 2 * BannerLayout.SEPARATOR_GAP);
        // One more character would not fit.
        String longer = shown.substring(0, shown.length() - 1) + "W" + E;
        assertTrue(font.width(longer, 3) > BannerLayout.MAX_WIDTH - dateWidth() - 2 * BannerLayout.SEPARATOR_GAP);
    }

    @Test
    void shortTitleIsUnchanged() {
        assertEquals("Stage 2: cloud core", layout.fitTitle("Stage 2: cloud core", dateWidth()));
        String exactly48 = "x".repeat(48);
        assertEquals(exactly48, BannerLayout.truncate(exactly48));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t \n"})
    void emptyTitleLeavesOnlyTheDate(String title) {
        BannerLayout.Banner withTitle = layout.layout(ReleaseVersion.parse("v1.0.0"), "Stage 2: cloud core", DATE);
        BannerLayout.Banner without = layout.layout(ReleaseVersion.parse("v1.0.0"), title, DATE);
        assertEquals("", without.title());
        // Title and separator square are gone.
        assertEquals(withTitle.layers().size() - 2, without.layers().size());
        // The date starts at the text column.
        Rectangle2D date = without.layers().getLast().shape().getBounds2D();
        assertEquals(BannerLayout.X, date.getMinX(), 0.06);
        assertEquals("Oct 30, 2026", without.date());
    }

    @Test
    void dateFollowsTitleAndSeparator() {
        BannerLayout.Banner b = layout.layout(ReleaseVersion.parse("v1.0.0"), "Stage 2: cloud core", DATE);
        double tw = font.width("Stage 2: cloud core", 3);
        Rectangle2D sep = b.layers().get(b.layers().size() - 2).shape().getBounds2D();
        assertEquals(Math.rint(BannerLayout.X + tw + 15), sep.getMinX());
        assertEquals(6, sep.getWidth());
        assertEquals(BannerLayout.GOLD, b.layers().get(b.layers().size() - 2).color());
        Rectangle2D date = b.layers().getLast().shape().getBounds2D();
        assertEquals(BannerLayout.X + tw + 36, date.getMinX(), 0.06);
        assertTrue(date.getMaxX() <= BannerLayout.TEXT_RIGHT);
    }

    @Test
    void blockIsCentredVertically() {
        // u 12: block 21 + 20 + 84 + 24 + 21 = 170, so the wordmark's cap top is at 75.
        BannerLayout.Banner b = layout.layout(ReleaseVersion.parse("v1.0.0"), "", DATE);
        Rectangle2D wordmark = b.layers().getFirst().shape().getBounds2D();
        assertEquals(75, wordmark.getMinY(), 0.3);
        // u 10: 156, cap top at 82.
        BannerLayout.Banner b10 = layout.layout(ReleaseVersion.parse("v1.3.0-beta.2"), "", DATE);
        assertEquals(82, b10.layers().getFirst().shape().getBounds2D().getMinY(), 0.3);
    }

    @Test
    void stableHasNoBadgeAndPrereleaseHasOne() {
        BannerLayout.Banner stable = layout.layout(ReleaseVersion.parse("v1.0.0"), "x", DATE);
        assertFalse(stable.badge().isPresent());
        assertTrue(stable.layers().stream().noneMatch(l -> l.color().equals(Badge.TEXT)));

        BannerLayout.Banner beta = layout.layout(ReleaseVersion.parse("v1.3.0-beta.2"), "x", DATE);
        assertEquals(Badge.BETA, beta.badge().orElseThrow());
        BannerLayout.Layer plate = beta.layers().stream().filter(l -> l.color().equals(Badge.BETA.fill()))
                .findFirst().orElseThrow();
        Rectangle2D r = plate.shape().getBounds2D();
        // 12 px right of the wordmark, 9u high, centred on the wordmark's cap height (y0 - 3).
        double wordmark = font.width("VECTOLYN", 3) + 3 + font.width("CLOUD", 3);
        assertEquals(Math.rint(BannerLayout.X + wordmark + 12), r.getMinX());
        assertEquals(27, r.getHeight());
        assertEquals(82 - 3, r.getMinY());
    }

    @Test
    void ltsAndDevGetTheirBadges() {
        BannerLayout.Banner lts = layout.layout(ReleaseVersion.parse("v1.4.0-lts"), "x", DATE);
        assertEquals(Badge.LTS, lts.badge().orElseThrow());
        assertTrue(lts.layers().stream().anyMatch(l -> l.color().equals(Badge.LTS.fill())));
        // Badge width: label + 2u padding on each side.
        BannerLayout.Layer plate = lts.layers().stream().filter(l -> l.color().equals(Badge.LTS.fill()))
                .findFirst().orElseThrow();
        assertEquals(Math.round(font.width("LTS", 3) + 12), Math.round(plate.shape().getBounds2D().getWidth()));

        BannerLayout.Banner dev = layout.layout(ReleaseVersion.parse("v0.2.0-dev.3"), "x", DATE);
        assertEquals(Badge.PRE_RELEASE, dev.badge().orElseThrow());
    }

    @Test
    void onlyThePointsOfTheVersionCoreAreGold() {
        // alpha: the badge is red, so every gold layer belongs to the version.
        BannerLayout.Banner b = layout.layout(ReleaseVersion.parse("v1.3.0-alpha.1"), "", DATE);
        long gold = b.layers().stream().filter(l -> l.color().equals(BannerLayout.GOLD)).count();
        assertEquals(2, gold);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Stage\t2", "Stage\u0000", "a‮b", "line two", "zero​width", "bell\u0007"})
    void controlAndFormatCharactersAreRejected(String title) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> layout.layout(ReleaseVersion.parse("v1.0.0"), title, DATE));
        assertTrue(e.getMessage().contains("U+"), e.getMessage());
    }

    @Test
    void characterWithoutGlyphFailsAndNamesTheCodePoint() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> layout.layout(ReleaseVersion.parse("v1.0.0"), "Launch 🚀", DATE));
        assertTrue(e.getMessage().contains("U+1F680"), e.getMessage());
        IllegalArgumentException cjk = assertThrows(IllegalArgumentException.class,
                () -> layout.layout(ReleaseVersion.parse("v1.0.0"), "日本", DATE));
        assertTrue(cjk.getMessage().contains("U+65E5"), cjk.getMessage());
    }

    @Test
    void latinCyrillicAndTypographicCharactersAreFine() {
        String title = "Über café – “quoted” вет";
        assertEquals(title, layout.layout(ReleaseVersion.parse("v1.0.0"), title, DATE).title());
    }

    @Test
    void everythingStaysInsideTheTextColumn() {
        for (String tag : new String[] {"v1.0.0", "v10.12.3-beta.12", "v1.0.0-dev.20261030", "v1.4.0-lts"}) {
            BannerLayout.Banner b = layout.layout(ReleaseVersion.parse(tag),
                    "Stage 12: a much longer release title that needs to be shortened somewhere", DATE);
            for (BannerLayout.Layer l : b.layers()) {
                Rectangle2D r = l.shape().getBounds2D();
                // Lower case letters have a smaller side bearing than the capitals ("v": 50 units
                // instead of 61), so their ink starts up to 11 units early, as in brand3.
                assertTrue(r.getMinX() >= BannerLayout.X - 11 * 12 / PixelFont.PX_UNIT - 0.01, tag + " " + r);
                assertTrue(r.getMaxX() <= BannerLayout.TEXT_RIGHT + 0.1, tag + " " + r);
                assertTrue(r.getMinY() >= 0 && r.getMaxY() <= BannerLayout.HEIGHT, tag);
            }
        }
    }
}
