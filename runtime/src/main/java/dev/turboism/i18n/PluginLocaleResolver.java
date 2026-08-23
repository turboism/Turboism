package dev.turboism.i18n;

import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decides the one locale the runtime and every plugin UI is built with, from the operator's
 * property, the persisted configuration, the Cubism host, and the JVM.
 *
 * <p>A static utility with no instances and no state: it reads the {@code turboism.locale} system
 * property and the locales it is handed, and reports every rejection through the supplied
 * diagnostics consumer rather than throwing. Explicit operator or configuration choices are
 * restricted to {@code en}, {@code ja}, {@code ko}, {@code zh-Hans} and {@code zh-Hant}, plus the
 * {@code system} sentinel meaning "defer to the next source"; any other tag, well-formed or not,
 * is refused and the resolution falls through.
 */
public final class PluginLocaleResolver {

    /** The only explicit operator/config locale choices (plus the {@code system} sentinel). */
    private static final java.util.Set<String> SUPPORTED_EXPLICIT_LOCALES = java.util.Set.of(
        "en", "ja", "ko", "zh-Hans", "zh-Hant"
    );

    private PluginLocaleResolver() {
    }

    /**
     * Resolves the one startup locale used by runtime and plugin UI construction.
     *
     * <p>The frozen precedence is: valid {@code turboism.locale} JVM property, then a valid
     * persisted runtime locale, then the Cubism host display locale, then the JVM display
     * locale, then the base catalog fallback. {@code system} means "use the next source".
     * Explicit operator/config choices are limited to {@code en}, {@code ja}, {@code ko},
     * {@code zh-Hans} and {@code zh-Hant}; an arbitrary well-formed tag (for example
     * {@code fr}) is unsupported and emits a diagnostic before falling through.</p>
     */
    public static Locale resolveStartup(
        final String configuredLocale,
        final Locale hostDisplayLocale,
        final Locale jvmDisplayLocale,
        final Consumer<String> diagnostics
    ) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        final String operatorLocale = operatorLocale();
        if (operatorLocale != null && !operatorLocale.isBlank() && !"system".equalsIgnoreCase(operatorLocale)) {
            final Locale parsed = parse(operatorLocale);
            if (parsed == null) {
                diagnostics.accept("I18N_INVALID_OPERATOR_LOCALE: Explicit JVM locale is invalid; "
                    + "the next locale source was selected.");
            } else if (!SUPPORTED_EXPLICIT_LOCALES.contains(operatorLocale)) {
                diagnostics.accept("I18N_UNSUPPORTED_OPERATOR_LOCALE: Explicit JVM locale is unsupported; "
                    + "the next locale source was selected.");
            } else {
                return normalize(parsed);
            }
        }
        if (configuredLocale != null && !configuredLocale.isBlank() && !"system".equalsIgnoreCase(configuredLocale)) {
            final Locale parsed = parse(configuredLocale);
            if (parsed == null) {
                diagnostics.accept("I18N_INVALID_CONFIGURED_LOCALE: Configured locale is invalid; "
                    + "the next locale source was selected.");
            } else if (!SUPPORTED_EXPLICIT_LOCALES.contains(configuredLocale)) {
                diagnostics.accept("I18N_UNSUPPORTED_CONFIGURED_LOCALE: Configured locale is unsupported; "
                    + "the next locale source was selected.");
            } else {
                return normalize(parsed);
            }
        }
        if (hostDisplayLocale != null) {
            return normalize(hostDisplayLocale);
        }
        if (jvmDisplayLocale != null) {
            return normalize(jvmDisplayLocale);
        }
        return normalize(Locale.getDefault(Locale.Category.DISPLAY));
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
        final String operatorLocale = operatorLocale();
        if (operatorLocale != null && !operatorLocale.isBlank() && !"system".equalsIgnoreCase(operatorLocale)) {
            final Locale parsed = parse(operatorLocale);
            if (parsed != null) {
                return new Resolution(normalize(parsed), "JVM_PROPERTY", operatorLocale);
            }
            diagnostics.record(new LocalizationDiagnostic(
                "I18N_INVALID_OPERATOR_LOCALE",
                pluginId,
                "",
                operatorLocale,
                "Explicit JVM locale is invalid; the next locale source was selected."
            ));
        }
        if (explicitLocale != null && !explicitLocale.isBlank() && !"system".equalsIgnoreCase(explicitLocale)) {
            final Locale parsed = parse(explicitLocale);
            if (parsed != null) {
                return new Resolution(normalize(parsed), "CONFIGURED", explicitLocale);
            }
            diagnostics.record(new LocalizationDiagnostic(
                "I18N_INVALID_EXPLICIT_LOCALE",
                pluginId,
                "",
                explicitLocale,
                "Configured plugin locale is invalid; the next locale source was selected."
            ));
        }
        if (displayLocale != null) {
            return new Resolution(normalize(displayLocale), "DISPLAY_LOCALE", displayLocale.toLanguageTag());
        }
        if (jvmDisplayLocale != null) {
            return new Resolution(normalize(jvmDisplayLocale), "JVM_DISPLAY_DEFAULT", jvmDisplayLocale.toLanguageTag());
        }
        final Locale fallback = Locale.getDefault(Locale.Category.DISPLAY);
        return new Resolution(normalize(fallback), "JVM_DISPLAY_DEFAULT", fallback.toLanguageTag());
    }

    private static String operatorLocale() {
        try {
            return System.getProperty("turboism.locale");
        } catch (SecurityException denied) {
            return null;
        }
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
