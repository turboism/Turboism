package dev.turboism.sdk.ui.settings;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Represents a user-initiated HTTPS link attached to a settings control or validation refusal. */
public record SettingsLink(String label, URI uri, String openFailureMessage) {
    private static final String HTTPS_SCHEME = "https";
    private static final int MAX_LABEL_LENGTH = 256;
    private static final int MAX_OPEN_FAILURE_MESSAGE_LENGTH = 512;

    public SettingsLink {
        label = requireText(label, "label", MAX_LABEL_LENGTH);
        uri = Objects.requireNonNull(uri, "uri");
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!HTTPS_SCHEME.equals(scheme)
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("settings links must use an absolute HTTPS URI");
        }
        openFailureMessage = requireText(openFailureMessage, "openFailureMessage", MAX_OPEN_FAILURE_MESSAGE_LENGTH);
    }

    private static String requireText(final String value, final String name, final int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1-" + max + " characters");
        }
        return value;
    }
}
