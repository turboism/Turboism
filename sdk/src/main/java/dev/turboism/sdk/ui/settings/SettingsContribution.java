package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.OptionalInt;

/** One plugin-owned control placed inside a named settings tab. */
@PreviewApi
public record SettingsContribution(
    String id,
    SettingsTab tab,
    OptionalInt index,
    SettingsControl control
) {
    public SettingsContribution {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("id contains unsupported characters");
        }
        tab = Objects.requireNonNull(tab, "tab");
        index = Objects.requireNonNull(index, "index");
        control = Objects.requireNonNull(control, "control");
    }

    public SettingsContribution(
        final String id,
        final SettingsTab tab,
        final SettingsControl control
    ) {
        this(id, tab, OptionalInt.empty(), control);
    }
}
