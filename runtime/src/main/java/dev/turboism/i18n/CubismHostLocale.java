package dev.turboism.i18n;

import java.util.Locale;

/**
 * Resolves the Cubism Editor UI language (host JVM locale).
 */
public final class CubismHostLocale {

    private CubismHostLocale() {
    }

    /**
     * Resolves the current Cubism UI language from the host JVM properties.
     *
     * <p>Cubism 语言版本：CubismEditor5.bat 设置 -Duser.language=zh（5.2/5.3 一致）。</p>
     * <p>DISPLAY locale 在 Proton/Wine 下会被环境改写（zh-US），不可依赖。</p>
     *
     * <p>返回 Cubism 的<b>生效 UI 语言</b>：解析结果经
     * {@code PluginLocaleResolver.normalize} 统一归一化，原始 JVM locale 可能是 Wine
     * 改写（如 zh-US），无 script 的 zh 语言按 country
     * 归一到 zh-Hans/zh-Hant（zh-US → zh-Hans，zh-CN → zh-Hans，zh-TW → zh-Hant）；
     * 非 zh 语言原样返回。归一化幂等：已带 script 的 zh-Hans/zh-Hant 输入原样返回。</p>
     *
     * @return the effective Cubism UI language; falls back to the DISPLAY locale when
     *     {@code user.language} is blank, never {@code null}
     */
    public static Locale resolve() {
        final String language = System.getProperty("user.language", "");
        final String country = System.getProperty("user.country", "");
        final Locale resolved = language.isBlank()
            ? Locale.getDefault(Locale.Category.DISPLAY)
            : new Locale(language, country);
        return PluginLocaleResolver.normalize(resolved);
    }
}
