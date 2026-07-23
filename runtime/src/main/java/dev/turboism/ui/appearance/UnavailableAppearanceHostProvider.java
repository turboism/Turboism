package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceStatus;

import java.util.Optional;

public final class UnavailableAppearanceHostProvider implements AppearanceHostProvider {

    private static final AppearanceStatus STATUS = new AppearanceStatus(
        AppearanceStatus.Availability.UNAVAILABLE,
        AppearanceStatus.Source.NATIVE,
        Optional.empty(),
        AppearanceBase.NATIVE,
        0,
        Optional.of("appearance.provider.unavailable")
    );

    @Override public boolean isAvailable() { return false; }
    @Override public AppearanceStatus readStatus() { return STATUS; }
    @Override public RestorePoint captureRestorePoint() { throw unavailable(); }
    @Override public ApplyOutcome apply(final AppearanceRequest request) { throw unavailable(); }
    @Override public void restore(final RestorePoint restorePoint) { throw unavailable(); }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Appearance host provider is unavailable");
    }
}
