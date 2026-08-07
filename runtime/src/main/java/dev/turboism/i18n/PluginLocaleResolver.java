package dev.turboism.i18n;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;

final class PluginLocaleResolver {

    private PluginLocaleResolver() {
    }

    static Locale resolve(
        final String pluginId,
        final String explicitLocale,
        final Locale displayLocale,
        final Locale jvmDisplayLocale,
        final LocalizationDiagnosticSink diagnostics
    ) {
        return resolveWithSource(
            pluginId,
            explicitLocale,
            displayLocale,
            jvmDisplayLocale,
            diagnostics
        ).locale();
    }

    static Resolution resolveWithSource(
        final String pluginId,
        final String explicitLocale,
        final Locale displayLocale,
        final Locale jvmDisplayLocale,
        final LocalizationDiagnosticSink diagnostics
    ) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (explicitLocale != null && !explicitLocale.isBlank()) {
            final Locale parsed = parse(explicitLocale);
            if (parsed != null) {
                return new Resolution(
                    normalize(parsed),
                    "PREVIEW_OPTION",
                    explicitLocale
                );
            }
            diagnostics.record(new LocalizationDiagnostic(
                "I18N_INVALID_EXPLICIT_LOCALE",
                pluginId,
                "",
                explicitLocale,
                "Explicit plugin locale is invalid; the next locale source was selected."
            ));
        }
        if (displayLocale != null) {
            return new Resolution(
                normalize(displayLocale),
                "DISPLAY_LOCALE",
                displayLocale.toLanguageTag()
            );
        }
        if (jvmDisplayLocale != null) {
            return new Resolution(
                normalize(jvmDisplayLocale),
                "JVM_DISPLAY_DEFAULT",
                jvmDisplayLocale.toLanguageTag()
            );
        }
        final Locale fallback = Locale.getDefault(Locale.Category.DISPLAY);
        return new Resolution(
            normalize(fallback),
            "JVM_DISPLAY_DEFAULT",
            fallback.toLanguageTag()
        );
    }

    private static Locale parse(final String languageTag) {
        if (languageTag.indexOf('_') >= 0) {
            return null;
        }
        try {
            final Locale locale = new Locale.Builder().setLanguageTag(languageTag).build();
            if (locale.getLanguage().isBlank() || "und".equals(locale.getLanguage())) {
                return null;
            }
            return locale;
        } catch (IllformedLocaleException exception) {
            return null;
        }
    }

    record Resolution(Locale locale, String source, String requestedLocale) {
        Resolution {
            locale = Objects.requireNonNull(locale, "locale");
            source = Objects.requireNonNull(source, "source");
            requestedLocale = Objects.requireNonNull(requestedLocale, "requestedLocale");
        }
    }

    static Locale normalize(final Locale locale) {
        if (!"zh".equals(locale.getLanguage()) || !locale.getScript().isBlank()) {
            return locale;
        }
        final String script = switch (locale.getCountry()) {
            case "CN", "SG" -> "Hans";
            case "TW", "HK", "MO" -> "Hant";
            // 简体为默认：跟随 Cubism 宿主语言版本（-Duser.language=zh）；
            // Wine 下的 zh-US 等无 script 中文也归 Hans。
            default -> "Hans";
        };
        return new Locale.Builder()
            .setLanguage("zh")
            .setScript(script)
            .build();
    }
}
