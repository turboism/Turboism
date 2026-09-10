package dev.turboism.i18n;

import java.util.Locale;

/**
 * Resolves the Cubism Editor UI language for the running host.
 *
 * <p>Cubism applies the language chosen in {@code File → Environment Settings →
 * General → Language} to the process default locale while starting up: the
 * persisted {@code Locale.Editor} value is read from its own settings store and
 * written through {@code Locale.setDefault(new Locale(language, bootCountry))}
 * (5.2, 5.3 and 5.4 behave this way). The launcher's {@code -Duser.language}
 * only selects the language version of the build, so it does <b>not</b> track
 * that setting; the process default locale does.</p>
 */
public final class CubismHostLocale {

    private CubismHostLocale() {
    }

    /**
     * Resolves the current Cubism UI language from the host process.
     *
     * <p>Sources, in order: the locale Cubism applied to the process default
     * ({@code Locale.setDefault}), the host JVM {@code user.language}/{@code
     * user.country} properties when the process default carries no language, and
     * finally the DISPLAY locale. Wine/Proton may rewrite the raw country (e.g.
     * {@code zh-US}), which is why the result is passed through
     * {@code PluginLocaleResolver.normalize}: script-less {@code zh} is mapped by
     * country to {@code zh-Hans}/{@code zh-Hant} (unknown or blank country →
     * {@code zh-Hans}), non-zh languages are returned unchanged. Normalization is
     * idempotent, so an already-scripted {@code zh-Hans}/{@code zh-Hant} passes
     * through untouched.</p>
     *
     * @return the effective Cubism UI language, never {@code null}
     */
    public static Locale resolve() {
        return PluginLocaleResolver.normalize(appliedDefaultLocale());
    }

    private static Locale appliedDefaultLocale() {
        // The host has already applied Environment Settings → General → Language
        // before the runtime attaches (Cubism applies it while starting the
        // editor app, before CEAppCtrl is constructed), so the process default is
        // the language Cubism is actually showing. Before that call it still holds
        // the launcher's language version, which is the value the raw JVM
        // properties would report anyway.
        final Locale applied = Locale.getDefault();
        if (!applied.getLanguage().isBlank()) {
            return applied;
        }
        final String language = System.getProperty("user.language", "");
        if (!language.isBlank()) {
            return new Locale(language, System.getProperty("user.country", ""));
        }
        return Locale.getDefault(Locale.Category.DISPLAY);
    }
}
