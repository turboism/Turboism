package dev.turboism.i18n;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the host-language source: Cubism applies {@code File → Environment
 * Settings → General → Language} to the process default locale, while the
 * launcher's {@code -Duser.language} only selects the build's language version.
 */
class CubismHostLocaleTest {

    private static final String LANGUAGE = "user.language";
    private static final String COUNTRY = "user.country";

    private final Locale originalDefault = Locale.getDefault();
    private final Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
    private final Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
    private final String originalLanguage = System.getProperty(LANGUAGE);
    private final String originalCountry = System.getProperty(COUNTRY);

    @AfterEach
    void restoreHostState() {
        Locale.setDefault(originalDefault);
        Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
        Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        restoreProperty(LANGUAGE, originalLanguage);
        restoreProperty(COUNTRY, originalCountry);
    }

    @Test
    void environmentSettingsLanguageWinsOverLauncherProperty() {
        // Chinese-language build (CubismEditor5.bat sets -Duser.language=zh) whose
        // Environment Settings language is 日本語: Cubism applies
        // Locale.setDefault(new Locale("ja", bootCountry)).
        System.setProperty(LANGUAGE, "zh");
        System.setProperty(COUNTRY, "CN");
        Locale.setDefault(new Locale("ja", "CN"));
        assertEquals(Locale.forLanguageTag("ja-CN"), CubismHostLocale.resolve());
    }

    @Test
    void environmentSettingsSimplifiedChineseNormalizesRegardlessOfBootCountry() {
        // Japanese-language build switched to 简体中文: the ja boot country must
        // not leak into the reported script.
        System.setProperty(LANGUAGE, "ja");
        System.setProperty(COUNTRY, "JP");
        Locale.setDefault(new Locale("zh", "JP"));
        assertEquals(Locale.forLanguageTag("zh-Hans"), CubismHostLocale.resolve());
    }

    @Test
    void traditionalChineseCountryStillResolvesToZhHant() {
        System.setProperty(LANGUAGE, "zh");
        System.setProperty(COUNTRY, "TW");
        Locale.setDefault(new Locale("zh", "TW"));
        assertEquals(Locale.forLanguageTag("zh-Hant"), CubismHostLocale.resolve());
    }

    @Test
    void launcherPropertiesApplyWhenTheProcessDefaultCarriesNoLanguage() {
        System.setProperty(LANGUAGE, "zh");
        System.setProperty(COUNTRY, "CN");
        Locale.setDefault(new Locale("", ""));
        assertEquals(Locale.forLanguageTag("zh-Hans"), CubismHostLocale.resolve());
    }

    @Test
    void launcherPropertiesWinOverAWineRewrittenCountry() {
        System.setProperty(LANGUAGE, "zh");
        System.setProperty(COUNTRY, "US");
        Locale.setDefault(new Locale("", ""));
        assertEquals(Locale.forLanguageTag("zh-Hans"), CubismHostLocale.resolve());
    }

    private static void restoreProperty(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
