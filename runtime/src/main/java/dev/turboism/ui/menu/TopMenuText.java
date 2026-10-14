package dev.turboism.ui.menu;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Framework-owned menu chrome text, resolved from the runtime's own
 * {@code dev/turboism/ui/menu/messages} bundle — not from any plugin catalog.
 */
public final class TopMenuText {

    private static final String BUNDLE = "dev.turboism.ui.menu.messages";
    private static final String SHARED_ROOT_KEY = "menu.shared-root";
    private static final String SHARED_ROOT_FALLBACK = "Turboism";
    private static final String PLUGINS_GROUP_KEY = "menu.plugins-group";
    private static final String PLUGINS_GROUP_FALLBACK = "Plugins";

    private TopMenuText() {}

    /**
     * @param locale host-effective locale; {@code null} resolves to English
     * @return the localized display name of the shared reserved top-level menu root
     */
    public static String sharedRootLabel(final Locale locale) {
        final ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale == null ? Locale.ENGLISH : locale);
        return bundle.containsKey(SHARED_ROOT_KEY) ? bundle.getString(SHARED_ROOT_KEY) : SHARED_ROOT_FALLBACK;
    }

    /**
     * @param locale host-effective locale; {@code null} resolves to English
     * @return the localized display name of the first-party plugins submenu
     */
    public static String pluginsGroupLabel(final Locale locale) {
        final ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale == null ? Locale.ENGLISH : locale);
        return bundle.containsKey(PLUGINS_GROUP_KEY) ? bundle.getString(PLUGINS_GROUP_KEY) : PLUGINS_GROUP_FALLBACK;
    }
}
