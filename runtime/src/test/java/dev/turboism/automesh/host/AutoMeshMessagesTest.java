package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Locale coverage of the Auto Mesh notices.
 *
 * <p>Every reviewed runtime locale must render its own words, and a locale the catalogs do not
 * carry must fall back to the English baseline rather than to the machine's own language.</p>
 */
class AutoMeshMessagesTest {

    private static final String ENGLISH_CANCELLED = "Auto Mesh was cancelled.";

    @Test
    void everyReviewedLocaleRendersItsOwnWords() {
        for (final Locale locale : new Locale[] {
            Locale.ENGLISH,
            Locale.JAPANESE,
            Locale.KOREAN,
            Locale.forLanguageTag("zh-Hans"),
            Locale.forLanguageTag("zh-Hant")
        }) {
            final String cancelled = AutoMeshMessages.forLocale(locale).cancelled();
            assertFalse(cancelled.isBlank(), locale.toLanguageTag());
            if (!Locale.ENGLISH.equals(locale)) {
                assertFalse(cancelled.equals(ENGLISH_CANCELLED), "untranslated notice for " + locale.toLanguageTag());
            }
        }
    }

    @Test
    void unsupportedLocaleFallsBackToTheEnglishBaseline() {
        assertEquals(
                ENGLISH_CANCELLED,
                AutoMeshMessages.forLocale(Locale.forLanguageTag("de-DE")).cancelled(),
                "an unsupported locale must not render the machine's own language");
    }

    @Test
    void nullLocaleFallsBackToTheEnglishBaseline() {
        assertEquals(ENGLISH_CANCELLED, AutoMeshMessages.forLocale(null).cancelled());
    }

    @Test
    void appliedNoticeCarriesTheMeshCount() {
        assertTrue(AutoMeshMessages.forLocale(Locale.ENGLISH).applied(3).contains("3"));
    }

    @Test
    void rejectionNoticeCarriesTheAlgorithmReason() {
        assertTrue(AutoMeshMessages.forLocale(Locale.ENGLISH)
                .rejected("The texture is full.")
                .contains("The texture is full."));
    }

    @Test
    void failureNoticeCarriesTheFaultSummary() {
        assertTrue(AutoMeshMessages.forLocale(Locale.ENGLISH)
                .failed("IllegalStateException: boom")
                .contains("boom"));
    }
}
