package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceStatus;

/** Runtime-only native host provider for semantic appearance state. */
public interface AppearanceHostProvider {

    boolean isAvailable();

    AppearanceStatus readStatus();

    RestorePoint captureRestorePoint();

    ApplyOutcome apply(AppearanceRequest request);

    void restore(RestorePoint restorePoint);

    interface RestorePoint {
    }

    enum ApplyOutcome {
        APPLIED,
        NO_CHANGE
    }
}
