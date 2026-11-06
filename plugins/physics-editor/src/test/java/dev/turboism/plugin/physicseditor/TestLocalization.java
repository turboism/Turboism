package dev.turboism.plugin.physicseditor;

import dev.turboism.sdk.i18n.PluginLocalization;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The plugin's real English catalog, exposed as a {@link PluginLocalization} so a test asserts the
 * strings a user actually sees instead of falling back to the fail-closed sentinel.
 */
final class TestLocalization implements PluginLocalization {

    static final TestLocalization ENGLISH = new TestLocalization(Locale.ENGLISH);

    private final ResourceBundle bundle;

    private TestLocalization(final Locale locale) {
        this.bundle = ResourceBundle.getBundle("META-INF/turboism/i18n/messages", locale);
    }

    @Override
    public Locale locale() {
        return bundle.getLocale();
    }

    @Override
    public String text(final String key) {
        return bundle.getString(key);
    }

    @Override
    public String format(final String key, final Object... arguments) {
        return MessageFormat.format(bundle.getString(key), arguments);
    }

    @Override
    public boolean contains(final String key) {
        return bundle.containsKey(key);
    }

    /** @return the raw catalog value, for asserting a control's rendered label */
    String raw(final String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException absent) {
            throw new AssertionError("missing fixture key " + key, absent);
        }
    }
}
