package dev.turboism.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLocaleResolverTest {

    private static final String PROPERTY = "turboism.locale";

    private final List<String> diagnostics = new ArrayList<>();
    private String previousProperty;

    @BeforeEach
    void captureProperty() {
        previousProperty = System.getProperty(PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previousProperty == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previousProperty);
        }
    }

    private Locale resolve(final String configured, final Locale host, final Locale jvm) {
        return PluginLocaleResolver.resolveStartup(configured, host, jvm, diagnostics::add);
    }

    @Test
    void propertyWinsOverConfigHostAndJvm() {
        System.setProperty(PROPERTY, "ja");
        assertEquals(Locale.JAPANESE, resolve("ko", Locale.KOREAN, Locale.ENGLISH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void configWinsOverHostAndJvmWhenPropertyIsAbsent() {
        System.clearProperty(PROPERTY);
        assertEquals(Locale.KOREAN, resolve("ko", Locale.JAPANESE, Locale.ENGLISH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void systemSentinelFallsThroughToTheNextSource() {
        System.setProperty(PROPERTY, "system");
        assertEquals(Locale.KOREAN, resolve("ko", Locale.JAPANESE, Locale.ENGLISH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void systemSentinelIsCaseInsensitive() {
        System.setProperty(PROPERTY, "SYSTEM");
        assertEquals(Locale.KOREAN, resolve("ko", Locale.JAPANESE, Locale.ENGLISH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void hostDisplayLocaleIsUsedWhenNoExplicitChoiceExists() {
        System.clearProperty(PROPERTY);
        assertEquals(Locale.GERMAN, resolve("", Locale.GERMAN, Locale.ENGLISH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void jvmDisplayLocaleIsUsedWhenHostLocaleIsAbsent() {
        System.clearProperty(PROPERTY);
        assertEquals(Locale.FRENCH, resolve("", null, Locale.FRENCH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void jvmDefaultDisplayLocaleIsTheFinalSource() {
        System.clearProperty(PROPERTY);
        final Locale expected = Locale.getDefault(Locale.Category.DISPLAY);
        assertEquals(expected, resolve("", null, null));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void syntacticallyInvalidPropertyEmitsDiagnosticAndFallsThrough() {
        System.setProperty(PROPERTY, "no_such_tag!!");
        assertEquals(Locale.KOREAN, resolve("ko", Locale.JAPANESE, Locale.ENGLISH));
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).startsWith("I18N_INVALID_OPERATOR_LOCALE:"));
    }

    @Test
    void underscoreFormIsSyntacticallyInvalidForTheProperty() {
        System.setProperty(PROPERTY, "zh_CN");
        assertEquals(Locale.KOREAN, resolve("ko", Locale.JAPANESE, Locale.ENGLISH));
        assertTrue(diagnostics.get(0).startsWith("I18N_INVALID_OPERATOR_LOCALE:"));
    }

    @Test
    void unsupportedButWellFormedPropertyEmitsDiagnosticAndFallsThrough() {
        System.setProperty(PROPERTY, "fr");
        assertEquals(Locale.KOREAN, resolve("ko", Locale.JAPANESE, Locale.ENGLISH));
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).startsWith("I18N_UNSUPPORTED_OPERATOR_LOCALE:"));
        assertFalse("fr".equals(resolve("ko", Locale.KOREAN, Locale.ENGLISH).getLanguage()));
    }

    @Test
    void unsupportedPropertyNeverBecomesTheEffectivePluginLocale() {
        System.setProperty(PROPERTY, "fr");
        final Locale effective = resolve("", Locale.KOREAN, Locale.ENGLISH);
        assertEquals(Locale.KOREAN, effective);
        assertTrue(diagnostics.get(0).startsWith("I18N_UNSUPPORTED_OPERATOR_LOCALE:"));
    }

    @Test
    void syntacticallyInvalidConfiguredLocaleEmitsDiagnosticAndFallsThrough() {
        System.clearProperty(PROPERTY);
        assertEquals(Locale.JAPANESE, resolve("no_such_tag!!", Locale.JAPANESE, Locale.ENGLISH));
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).startsWith("I18N_INVALID_CONFIGURED_LOCALE:"));
    }

    @Test
    void unsupportedButWellFormedConfiguredLocaleEmitsDiagnosticAndFallsThrough() {
        System.clearProperty(PROPERTY);
        assertEquals(Locale.JAPANESE, resolve("fr", Locale.JAPANESE, Locale.ENGLISH));
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).startsWith("I18N_UNSUPPORTED_CONFIGURED_LOCALE:"));
    }

    @Test
    void chineseCanonicalFormsAreAccepted() {
        System.clearProperty(PROPERTY);
        assertEquals(Locale.forLanguageTag("zh-Hans"), resolve("zh-Hans", Locale.ENGLISH, Locale.ENGLISH));
        assertEquals(Locale.forLanguageTag("zh-Hant"), resolve("zh-Hant", Locale.ENGLISH, Locale.ENGLISH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void chineseCanonicalFormsAreAcceptedFromTheProperty() {
        System.setProperty(PROPERTY, "zh-Hans");
        assertEquals(Locale.forLanguageTag("zh-Hans"), resolve("ko", Locale.KOREAN, Locale.ENGLISH));
        assertTrue(diagnostics.isEmpty());
    }

    @Test
    void regionScopedChineseWithoutScriptIsUnsupportedAsAnExplicitChoice() {
        System.setProperty(PROPERTY, "zh-CN");
        assertEquals(Locale.KOREAN, resolve("ko", Locale.KOREAN, Locale.ENGLISH));
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).startsWith("I18N_UNSUPPORTED_OPERATOR_LOCALE:"));
    }
}
