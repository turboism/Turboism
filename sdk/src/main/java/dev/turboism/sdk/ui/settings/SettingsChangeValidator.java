package dev.turboism.sdk.ui.settings;


/** Validates a proposed settings value before the runtime updates the visible control. */
@FunctionalInterface
public interface SettingsChangeValidator<T> {

    /**
     * Decides whether the control may move from {@code currentValue} to {@code proposedValue}.
     */
    SettingsChangeDecision validate(T currentValue, T proposedValue);

    /** Returns a validator that allows every change. */
    static <T> SettingsChangeValidator<T> acceptAll() {
        return (current, proposed) -> SettingsChangeDecision.allow();
    }
}
