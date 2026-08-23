package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Stable identity and presentation metadata for a settings tab. */
@PreviewApi
public record SettingsTab(String id, String title, OptionalInt index) {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public SettingsTab {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be a lowercase settings tab id");
        }
        title = requireText(title, "title", 128);
        index = Objects.requireNonNull(index, "index");
    }

    public SettingsTab(final String id, final String title) {
        this(id, title, OptionalInt.empty());
    }

    private static String requireText(final String value, final String name, final int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1-" + max + " characters");
        }
        return value;
    }
}
