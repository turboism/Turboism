package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.PreviewApi;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** One user-initiated HTTPS link offered after a settings change is rejected. */
@PreviewApi
public record SettingsLink(String label, URI uri, String openFailureMessage) {
    public SettingsLink {
        label = requireText(label, "label", 64);
        uri = Objects.requireNonNull(uri, "uri");
        final String scheme = uri.getScheme() == null
            ? ""
            : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("settings links must use an absolute HTTPS URI");
        }
        openFailureMessage = requireText(openFailureMessage, "openFailureMessage", 512);
    }

    private static String requireText(final String value, final String name, final int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1-" + max + " characters");
        }
        return value;
    }
}
