package dev.turboism.ui.appearance;

import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicAppearanceHostProviderTest {

    @Test
    void followsConnectionAndDeactivationWithoutExposingOldDelegate() {
        final DynamicAppearanceHostProvider dynamic = new DynamicAppearanceHostProvider();
        final RecordingProvider first = new RecordingProvider("first");
        final RecordingProvider second = new RecordingProvider("second");

        assertFalse(dynamic.isAvailable());
        dynamic.connect(first);
        assertTrue(dynamic.isAvailable());
        assertEquals("first", dynamic.readStatus().appearanceId().orElseThrow());

        final AppearanceHostProvider.RestorePoint stale = dynamic.captureRestorePoint();
        dynamic.connect(second);
        assertEquals("second", dynamic.readStatus().appearanceId().orElseThrow());
        assertThrows(IllegalStateException.class, () -> dynamic.restore(stale));
        assertEquals(0, first.restoreCount);

        dynamic.deactivate();
        assertFalse(dynamic.isAvailable());
    }

    private static final class RecordingProvider implements AppearanceHostProvider {
        private final String id;
        private int restoreCount;

        private RecordingProvider(final String id) {
            this.id = id;
        }

        @Override public boolean isAvailable() { return true; }
        @Override public AppearanceStatus readStatus() {
            return new AppearanceStatus(
                AppearanceStatus.Availability.AVAILABLE,
                AppearanceStatus.Source.NATIVE,
                Optional.of(id), AppearanceBase.DARK, 0, Optional.empty()
            );
        }
        @Override public RestorePoint captureRestorePoint() { return new Point(); }
        @Override public ApplyOutcome apply(final AppearanceRequest request) { return ApplyOutcome.APPLIED; }
        @Override public void restore(final RestorePoint restorePoint) { restoreCount++; }
        private static final class Point implements RestorePoint { }
    }
}
