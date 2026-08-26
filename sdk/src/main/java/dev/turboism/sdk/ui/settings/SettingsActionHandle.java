package dev.turboism.sdk.ui.settings;

import java.util.concurrent.CompletionStage;

/** Progress, cancellation, and completion for one settings action. */
public interface SettingsActionHandle {

    /** @return the latest immutable progress snapshot */
    SettingsActionProgress progress();

    /** @return the terminal user-facing result */
    CompletionStage<SettingsActionResult> completion();

    /** @return true only when this call requested cancellation */
    boolean cancel();
}
