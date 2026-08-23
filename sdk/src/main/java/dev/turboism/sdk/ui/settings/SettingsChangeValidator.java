package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.PreviewApi;

/** Validates a proposed settings value before the runtime updates the visible control. */
@FunctionalInterface
@PreviewApi
public interface SettingsChangeValidator<T> {

    SettingsChangeDecision validate(T currentValue, T proposedValue);

    static <T> SettingsChangeValidator<T> acceptAll() {
        return (current, proposed) -> SettingsChangeDecision.allow();
    }
}
