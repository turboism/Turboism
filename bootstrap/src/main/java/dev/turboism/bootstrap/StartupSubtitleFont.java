package dev.turboism.bootstrap;

import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves AWT fonts for startup subtitles when {@link Font#DIALOG} lacks glyphs for a script.
 *
 * <p>Non-Korean Windows installations often omit Gulim, which leaves OpenJDK's logical Dialog
 * font without Hangul coverage even though Malgun Gothic is preinstalled. Probing explicit
 * fallback families avoids missing-glyph boxes without scanning every font on the fast path.
 */
final class StartupSubtitleFont {

    private static final List<String> KOREAN_FONT_FAMILIES = List.of(
            "Malgun Gothic",
            "Apple SD Gothic Neo",
            "Noto Sans CJK KR",
            "Noto Sans KR",
            "NanumGothic",
            "NanumBarunGothic",
            "UnDotum",
            "Gulim",
            "Dotum",
            "AppleGothic");

    private static final List<String> JAPANESE_FONT_FAMILIES = List.of(
            "Yu Gothic UI",
            "Yu Gothic",
            "Meiryo UI",
            "Meiryo",
            "Hiragino Sans",
            "Hiragino Kaku Gothic ProN",
            "Noto Sans CJK JP",
            "Noto Sans JP",
            "MS UI Gothic",
            "MS Gothic",
            "VL Gothic");

    private static final List<String> SIMPLIFIED_CHINESE_FONT_FAMILIES = List.of(
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Noto Sans SC",
            "SimSun",
            "WenQuanYi Micro Hei",
            "WenQuanYi Zen Hei");

    private static final List<String> TRADITIONAL_CHINESE_FONT_FAMILIES = List.of(
            "Microsoft JhengHei UI",
            "Microsoft JhengHei",
            "PingFang TC",
            "Noto Sans CJK TC",
            "Noto Sans TC",
            "PMingLiU",
            "MingLiU");

    private static final List<String> GENERAL_FONT_FAMILIES =
            List.of("Segoe UI", "Arial Unicode MS", "Segoe UI Symbol", "DejaVu Sans");

    private static final Set<String> TRADITIONAL_CHINESE_REGIONS = Set.of("TW", "HK", "MO", "Hant");

    private StartupSubtitleFont() {}

    /**
     * Resolves displayable font coverage for {@code text}, preferring {@code baseFont}.
     *
     * @param baseFont primary UI font
     * @param text subtitle string to display
     * @return resolved text using {@code baseFont}, a single fallback font, or per-run attributes
     */
    static ResolvedText resolve(final Font baseFont, final String text) {
        return resolve(
                baseFont,
                text,
                family -> new Font(family, baseFont.getStyle(), baseFont.getSize()),
                InstalledFamilies::get);
    }

    static ResolvedText resolve(
            final Font baseFont,
            final String text,
            final Function<String, Font> fontLoader,
            final java.util.function.Supplier<List<String>> installedFamiliesSupplier) {
        if (text.isEmpty() || baseFont.canDisplayUpTo(text) == -1) {
            return new ResolvedText(text, baseFont, null);
        }
        final List<String> prioritized = prioritizedFamilies(text);
        final Font singlePrioritized = findSingleFont(text, baseFont, prioritized, fontLoader);
        if (singlePrioritized != null) {
            return new ResolvedText(text, singlePrioritized, null);
        }
        final List<String> installed = installedFamiliesSupplier.get();
        final Font singleInstalled =
                findSingleFont(text, baseFont, remainingFamilies(installed, prioritized), fontLoader);
        if (singleInstalled != null) {
            return new ResolvedText(text, singleInstalled, null);
        }
        return resolveRuns(text, baseFont, prioritized, installed, fontLoader);
    }

    private static Font findSingleFont(
            final String text,
            final Font baseFont,
            final List<String> families,
            final Function<String, Font> fontLoader) {
        for (final String family : families) {
            final Font candidate = loadDistinctFont(family, baseFont, fontLoader);
            if (candidate != null && candidate.canDisplayUpTo(text) == -1) {
                return candidate;
            }
        }
        return null;
    }

    private static ResolvedText resolveRuns(
            final String text,
            final Font baseFont,
            final List<String> prioritized,
            final List<String> installed,
            final Function<String, Font> fontLoader) {
        final List<String> remaining = remainingFamilies(installed, prioritized);
        final List<FontRun> runs = new ArrayList<>();
        int offset = 0;
        int runStart = 0;
        Font currentFont = null;
        while (offset < text.length()) {
            final int codePoint = text.codePointAt(offset);
            final Font nextFont = fontForCodePoint(codePoint, baseFont, prioritized, remaining, fontLoader);
            if (currentFont == null) {
                currentFont = nextFont;
            } else if (!Objects.equals(currentFont, nextFont)) {
                runs.add(new FontRun(runStart, offset, currentFont));
                runStart = offset;
                currentFont = nextFont;
            }
            offset += Character.charCount(codePoint);
        }
        if (currentFont != null) {
            runs.add(new FontRun(runStart, text.length(), currentFont));
        }
        if (runs.size() == 1) {
            return new ResolvedText(text, runs.get(0).font(), null);
        }
        final AttributedString attributed = new AttributedString(text);
        for (final FontRun run : runs) {
            attributed.addAttribute(TextAttribute.FONT, run.font(), run.start(), run.end());
        }
        return new ResolvedText(text, null, attributed);
    }

    private static Font fontForCodePoint(
            final int codePoint,
            final Font baseFont,
            final List<String> prioritized,
            final List<String> remaining,
            final Function<String, Font> fontLoader) {
        if (baseFont.canDisplay(codePoint)) {
            return baseFont;
        }
        for (final String family : prioritizedForCodePoint(codePoint, prioritized)) {
            final Font candidate = loadDistinctFont(family, baseFont, fontLoader);
            if (candidate != null && candidate.canDisplay(codePoint)) {
                return candidate;
            }
        }
        for (final String family : remaining) {
            final Font candidate = loadDistinctFont(family, baseFont, fontLoader);
            if (candidate != null && candidate.canDisplay(codePoint)) {
                return candidate;
            }
        }
        return baseFont;
    }

    private static Font loadDistinctFont(
            final String family, final Font baseFont, final Function<String, Font> fontLoader) {
        final Font candidate = fontLoader.apply(family);
        if (candidate == null) {
            return null;
        }
        if (!Font.DIALOG.equalsIgnoreCase(family)
                && Font.DIALOG.equalsIgnoreCase(candidate.getFamily(Locale.ENGLISH))) {
            return null;
        }
        if (Objects.equals(candidate, baseFont)) {
            return null;
        }
        return candidate;
    }

    private static List<String> prioritizedFamilies(final String text) {
        boolean hasHangul = false;
        boolean hasKana = false;
        boolean hasHan = false;
        for (int offset = 0; offset < text.length(); ) {
            final int codePoint = text.codePointAt(offset);
            final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HANGUL) {
                hasHangul = true;
            } else if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                hasKana = true;
            } else if (script == Character.UnicodeScript.HAN) {
                hasHan = true;
            }
            offset += Character.charCount(codePoint);
        }
        return orderedFamilies(hasHangul, hasKana, hasHan);
    }

    private static List<String> prioritizedForCodePoint(final int codePoint, final List<String> defaultOrder) {
        final Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HANGUL) {
            return orderedFamilies(true, false, false);
        }
        if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
            return orderedFamilies(false, true, false);
        }
        if (script == Character.UnicodeScript.HAN) {
            return orderedFamilies(false, false, true);
        }
        return defaultOrder;
    }

    private static List<String> orderedFamilies(
            final boolean preferKorean, final boolean preferJapanese, final boolean preferHan) {
        final Set<String> ordered = new LinkedHashSet<>();
        if (preferKorean) {
            ordered.addAll(KOREAN_FONT_FAMILIES);
        }
        if (preferJapanese) {
            ordered.addAll(JAPANESE_FONT_FAMILIES);
        }
        if (preferHan) {
            addChineseFamilies(ordered);
        }
        ordered.addAll(GENERAL_FONT_FAMILIES);
        ordered.addAll(KOREAN_FONT_FAMILIES);
        ordered.addAll(JAPANESE_FONT_FAMILIES);
        addChineseFamilies(ordered);
        return List.copyOf(ordered);
    }

    private static void addChineseFamilies(final Set<String> target) {
        final Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        final boolean traditional = TRADITIONAL_CHINESE_REGIONS.contains(display.getCountry())
                || TRADITIONAL_CHINESE_REGIONS.contains(display.getScript());
        if (traditional) {
            target.addAll(TRADITIONAL_CHINESE_FONT_FAMILIES);
            target.addAll(SIMPLIFIED_CHINESE_FONT_FAMILIES);
        } else {
            target.addAll(SIMPLIFIED_CHINESE_FONT_FAMILIES);
            target.addAll(TRADITIONAL_CHINESE_FONT_FAMILIES);
        }
    }

    private static List<String> remainingFamilies(final List<String> installed, final List<String> alreadyTried) {
        if (installed.isEmpty()) {
            return List.of();
        }
        final Set<String> tried = Set.copyOf(alreadyTried);
        final List<String> remaining = new ArrayList<>();
        for (final String family : installed) {
            if (!tried.contains(family)) {
                remaining.add(family);
            }
        }
        return remaining;
    }

    private record FontRun(int start, int end, Font font) {}

    /** Holds a subtitle string together with the font or attributed runs needed to draw it. */
    static final class ResolvedText {

        private final String text;
        private final Font singleFont;
        private final AttributedString attributed;

        private ResolvedText(final String text, final Font singleFont, final AttributedString attributed) {
            this.text = text;
            this.singleFont = singleFont;
            this.attributed = attributed;
        }

        String text() {
            return text;
        }

        Font singleFont() {
            return singleFont;
        }

        boolean isAttributed() {
            return attributed != null;
        }

        AttributedCharacterIterator iterator() {
            return attributed != null ? attributed.getIterator() : null;
        }

        int width(final Component component, final Font referenceFont) {
            if (text.isEmpty()) {
                return 0;
            }
            if (singleFont != null) {
                return component.getFontMetrics(singleFont).stringWidth(text);
            }
            return (int) Math.ceil(layout(component, referenceFont).getAdvance());
        }

        int height(final Component component, final Font referenceFont) {
            if (singleFont != null) {
                return component.getFontMetrics(singleFont).getHeight();
            }
            final TextLayout textLayout = layout(component, referenceFont);
            return (int) Math.ceil(textLayout.getAscent() + textLayout.getDescent() + textLayout.getLeading());
        }

        int ascent(final Component component, final Font referenceFont) {
            if (singleFont != null) {
                return component.getFontMetrics(singleFont).getAscent();
            }
            return (int) Math.ceil(layout(component, referenceFont).getAscent());
        }

        void draw(final Graphics2D graphics, final int x, final int baselineY) {
            if (text.isEmpty()) {
                return;
            }
            if (singleFont != null) {
                graphics.setFont(singleFont);
                graphics.drawString(text, x, baselineY);
            } else {
                graphics.drawString(attributed.getIterator(), x, baselineY);
            }
        }

        private TextLayout layout(final Component component, final Font referenceFont) {
            final FontMetrics metrics = component.getFontMetrics(referenceFont);
            final FontRenderContext context = metrics.getFontRenderContext();
            return new TextLayout(attributed.getIterator(), context);
        }
    }

    /** Caches the full system font family scan so it runs only if curated candidates fail. */
    private static final class InstalledFamilies {

        private static final List<String> NAMES =
                List.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT));

        private InstalledFamilies() {}

        private static List<String> get() {
            return NAMES;
        }
    }
}
