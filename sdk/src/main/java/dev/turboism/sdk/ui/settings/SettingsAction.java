package dev.turboism.sdk.ui.settings;

/** Starts one explicit, user-initiated settings operation without exposing a UI toolkit. */
@FunctionalInterface
public interface SettingsAction {

    /** @return a newly started asynchronous operation handle */
    SettingsActionHandle start();
}
