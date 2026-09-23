package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceStatus;

/** Runtime-only native host provider for semantic appearance state. */
public interface AppearanceHostProvider {

    /**
     * @return whether the host supports semantic appearance operations at all
     */
    boolean isAvailable();

    /**
     * @return the host's current appearance state
     */
    AppearanceStatus readStatus();

    /**
     * @return an opaque snapshot of host appearance state suitable for {@link #restore}
     */
    RestorePoint captureRestorePoint();

    /**
     * Applies one appearance request to the host.
     *
     * @param request the requested appearance
     * @return whether host state actually changed
     */
    ApplyOutcome apply(AppearanceRequest request);

    /**
     * Reverts host appearance state to a previously captured restore point.
     *
     * @param restorePoint a value returned by {@link #captureRestorePoint()}
     */
    void restore(RestorePoint restorePoint);

    /** Opaque host appearance state captured for later restoration. */
    interface RestorePoint {
    }

    /** Whether an {@link #apply} call changed host appearance state. */
    enum ApplyOutcome {
        /** Host state changed. */
        APPLIED,
        /** The request matched current state; nothing was written. */
        NO_CHANGE
    }
}
