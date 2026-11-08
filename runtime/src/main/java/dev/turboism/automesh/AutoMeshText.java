package dev.turboism.automesh;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Framework-owned text of the native Auto Mesh selector row and its parameter dialog.
 *
 * <p>The row and the dialog are runtime chrome, so their text comes from the runtime's own bundle
 * {@code dev.turboism.automesh.messages} and never from a plugin catalog. A plugin localizes its
 * own algorithm and parameter labels, and the runtime renders those verbatim.</p>
 */
public final class AutoMeshText {

    private static final String BUNDLE = "dev.turboism.automesh.messages";

    private AutoMeshText() {}

    /**
     * Resolves one message of the Auto Mesh chrome.
     *
     * @param locale the host-effective locale; {@code null} resolves to English
     * @param key the message key
     * @return the localized text, or the key itself when the bundle does not declare it
     */
    public static String text(final Locale locale, final String key) {
        Objects.requireNonNull(key, "key");
        final ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, locale == null ? Locale.ENGLISH : locale);
        try {
            return bundle.getString(key);
        } catch (MissingResourceException missing) {
            // A missing key must never blank a control the user needs to reach the native tool.
            return key;
        }
    }
}
