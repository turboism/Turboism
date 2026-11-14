package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.text.AttributedCharacterIterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * Verifies font fallback behavior for startup subtitles across Korean, mixed-script, and bundled
 * catalog quotes when the default logical Dialog font lacks glyphs.
 */
class StartupSubtitleFontTest {

    private static final String KOREAN_QUOTE = "직면하라, 그리고 창조하라.";
    private static final String MIXED_QUOTE = "Hello 세계 世界";

    @Test
    void keepsBaseFontWhenItAlreadyDisplaysTheText() {
        final Font base = scriptFont("Dialog", cp -> cp < 128);
        final StartupSubtitleFont.ResolvedText resolved = StartupSubtitleFont.resolve(
                base,
                "Wake up, samurai.",
                family -> {
                    throw new AssertionError("Fast path must not probe fallback fonts: " + family);
                },
                List::of);

        assertFalse(resolved.isAttributed());
        assertEquals(base, resolved.singleFont());
    }

    @Test
    void selectsMalgunGothicForKoreanWhenDialogLacksHangulAndRejectsDialogAliases() {
        final Font dialogWithoutKorean = scriptFont(Font.DIALOG, cp -> !isHangul(cp));
        final Font malgunGothic = scriptFont("Malgun Gothic", cp -> cp < 128 || isHangul(cp));

        final StartupSubtitleFont.ResolvedText resolved = StartupSubtitleFont.resolve(
                dialogWithoutKorean,
                KOREAN_QUOTE,
                family -> "Malgun Gothic".equals(family) ? malgunGothic : dialogWithoutKorean,
                List::of);

        assertFalse(resolved.isAttributed());
        assertEquals(malgunGothic, resolved.singleFont());
        assertEquals(-1, resolved.singleFont().canDisplayUpTo(KOREAN_QUOTE));
    }

    @Test
    void fallsBackToPerRunAttributedTextWhenNoSingleFontCoversMixedScripts() {
        final Font latinOnly = scriptFont(Font.DIALOG, cp -> cp < 128);
        final Font koreanOnly = scriptFont("Malgun Gothic", StartupSubtitleFontTest::isHangul);
        final Font chineseOnly = scriptFont("Microsoft YaHei", StartupSubtitleFontTest::isHan);
        final Map<String, Font> fonts = Map.of(
                "Malgun Gothic", koreanOnly,
                "Microsoft YaHei", chineseOnly);

        final StartupSubtitleFont.ResolvedText resolved = StartupSubtitleFont.resolve(
                latinOnly, MIXED_QUOTE, family -> fonts.getOrDefault(family, latinOnly), List::of);

        assertTrue(resolved.isAttributed());
        final AttributedCharacterIterator iterator = resolved.iterator();
        assertNotNull(iterator);
        for (int index = 0; index < MIXED_QUOTE.length(); ) {
            iterator.setIndex(index);
            final Font runFont = (Font) iterator.getAttribute(TextAttribute.FONT);
            final int codePoint = MIXED_QUOTE.codePointAt(index);
            assertNotNull(runFont);
            assertTrue(runFont.canDisplay(codePoint), "Run font must display code point at index " + index);
            index += Character.charCount(codePoint);
        }
    }

    @Test
    void resolvesEveryBundledStartupQuoteAgainstARestrictedLatinFont() throws IOException {
        final Font latinOnly = new Font("Liberation Sans", Font.PLAIN, 14);
        final List<String> quotes;
        try (InputStream stream =
                StartupMottoCatalog.class.getClassLoader().getResourceAsStream(StartupMottoCatalog.RESOURCE)) {
            quotes = StartupMottoCatalog.parse(stream);
        }
        assertFalse(quotes.isEmpty());
        for (final String quote : quotes) {
            final StartupSubtitleFont.ResolvedText resolved = StartupSubtitleFont.resolve(latinOnly, quote);
            if (!resolved.isAttributed()) {
                assertEquals(-1, resolved.singleFont().canDisplayUpTo(quote), "Missing glyph for quote: " + quote);
            } else {
                final AttributedCharacterIterator iterator = resolved.iterator();
                for (int index = 0; index < quote.length(); ) {
                    iterator.setIndex(index);
                    final Font runFont = (Font) iterator.getAttribute(TextAttribute.FONT);
                    final int codePoint = quote.codePointAt(index);
                    assertTrue(runFont.canDisplay(codePoint), "Missing glyph at index " + index + " in " + quote);
                    index += Character.charCount(codePoint);
                }
            }
        }
    }

    private static boolean isHangul(final int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HANGUL;
    }

    private static boolean isHan(final int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static Font scriptFont(final String family, final IntPredicate supportedCodePoints) {
        return new Font(family, Font.PLAIN, 14) {
            @Override
            public String getFamily(final Locale locale) {
                return family;
            }

            @Override
            public boolean canDisplay(final char ch) {
                return supportedCodePoints.test(ch);
            }

            @Override
            public boolean canDisplay(final int codePoint) {
                return supportedCodePoints.test(codePoint);
            }

            @Override
            public int canDisplayUpTo(final String text) {
                for (int index = 0; index < text.length(); ) {
                    final int codePoint = text.codePointAt(index);
                    if (!supportedCodePoints.test(codePoint)) {
                        return index;
                    }
                    index += Character.charCount(codePoint);
                }
                return -1;
            }
        };
    }
}
