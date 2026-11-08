package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Localization contract of the native selector row and its parameter dialog.
 *
 * <p>Every key the row and the dialog resolve is pinned here, and every key must resolve to real
 * text in every shipped locale. A key that silently falls back to its own name would leave a
 * control the user cannot read, which is why the fallback is asserted separately.</p>
 */
class AutoMeshTextTest {

    private static final Locale[] SHIPPED_LOCALES = {
        Locale.ROOT, Locale.ENGLISH, Locale.JAPANESE, Locale.KOREAN,
        Locale.forLanguageTag("zh-Hans"), Locale.forLanguageTag("zh-Hant"), Locale.forLanguageTag("zh")
    };

    private static final String[] KEYS = {
        "selector.algorithm.label",
        "selector.algorithm.tooltip",
        "selector.algorithm.native",
        "selector.preset.label",
        "selector.preset.tooltip",
        "selector.preset.none",
        "selector.parameters.button",
        "selector.parameters.tooltip",
        "dialog.parameters.title",
        "dialog.parameters.algorithm",
        "dialog.parameters.preset",
        "dialog.parameters.ok",
        "dialog.parameters.cancel",
        "dialog.parameters.reset",
        "dialog.parameters.invalid",
        "dialog.parameters.range",
        "dialog.parameters.step",
        "dialog.parameters.decimals",
        "dialog.parameters.length",
        "dialog.parameters.option",
        "dialog.parameters.number",
        "dialog.parameters.boolean"
    };

    @Test
    void everyKeyResolvesInEveryShippedLocale() {
        for (final Locale locale : SHIPPED_LOCALES) {
            for (final String key : KEYS) {
                final String text = AutoMeshText.text(locale, key);
                assertFalse(text.isBlank(), key + " must not be blank in " + locale);
                assertFalse(
                        text.equals(key),
                        key + " must be translated in " + locale + " instead of falling back to the key");
            }
        }
    }

    @Test
    void aMissingKeyFallsBackToItsOwnName() {
        assertEquals(
                "selector.absent.key",
                AutoMeshText.text(Locale.ENGLISH, "selector.absent.key"),
                "a missing key must never blank a control the user needs");
    }

    @Test
    void theNativeLabelNamesTheEditorGenerator() {
        assertTrue(
                AutoMeshText.text(Locale.ENGLISH, "selector.algorithm.native").contains("Cubism"),
                "the native entry must name the Editor's own generator");
    }

    @Test
    void everyMessagePatternKeepsItsArgumentIndexes() {
        for (final Locale locale : SHIPPED_LOCALES) {
            assertEquals(
                    java.util.Set.of(0, 1, 2),
                    indexesOf(AutoMeshText.text(locale, "dialog.parameters.range")),
                    "the range message must keep three arguments in " + locale);
            assertEquals(
                    java.util.Set.of(0, 1),
                    indexesOf(AutoMeshText.text(locale, "dialog.parameters.decimals")),
                    "the decimals message must keep two arguments in " + locale);
        }
    }

    private static java.util.Set<Integer> indexesOf(final String pattern) {
        final java.util.Set<Integer> indexes = new java.util.LinkedHashSet<>();
        final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{(\\d+)\\}").matcher(pattern);
        while (matcher.find()) {
            indexes.add(Integer.parseInt(matcher.group(1)));
        }
        return indexes;
    }
}
