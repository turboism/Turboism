package dev.turboism.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.DisposableScope;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UiHostLocaleRuntimeTest {

    @Test
    void defaultSourceResolvesHostLanguageProperties() {
        withProperties(Map.of("user.language", "zh", "user.country", "CN"), () -> {
            assertEquals(Locale.forLanguageTag("zh-Hans"), UiHostStateSource.DEFAULT.hostLocale());
        });
    }

    @Test
    void traditionalChineseCountryResolvesToZhHant() {
        withProperties(Map.of("user.language", "zh", "user.country", "TW"), () -> {
            assertEquals(Locale.forLanguageTag("zh-Hant"), UiHostStateSource.DEFAULT.hostLocale());
        });
    }

    @Test
    void blankLanguageFallsBackToDisplayLocale() {
        final Locale original = Locale.getDefault(Locale.Category.DISPLAY);
        final Locale display = new Locale("ja", "JP");
        try {
            Locale.setDefault(Locale.Category.DISPLAY, display);
            withProperties(Map.of("user.language", "", "user.country", "CN"), () -> {
                assertEquals(display, UiHostStateSource.DEFAULT.hostLocale());
            });
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, original);
        }
    }

    @Test
    void runtimeServiceDelegatesToStateSource() {
        final Locale delegated = new Locale("fr", "FR");
        final UiHostStateSource source = new UiHostStateSource() {
            @Override
            public java.util.Locale hostLocale() {
                return delegated;
            }
        };
        final RuntimeUiHostCapabilityService service = new RuntimeUiHostCapabilityService(
            PermissionChecker.allowAll(),
            "plugin.test",
            source,
            new DisposableScope()
        );
        assertEquals(delegated, service.hostLocale());
        assertNotNull(service.hostLocale());
    }

    private static void withProperties(final Map<String, String> props, final Runnable body) {
        final Map<String, String> saved = new HashMap<>();
        for (final Map.Entry<String, String> entry : props.entrySet()) {
            saved.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), entry.getValue());
        }
        try {
            body.run();
        } finally {
            for (final Map.Entry<String, String> entry : saved.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
