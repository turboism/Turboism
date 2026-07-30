package dev.turboism.ui.appearance;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceChangedEvent;
import dev.turboism.sdk.appearance.AppearancePalette;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppearanceCoordinatorTest {

    @Test
    void appliesPublishesOnceAndRestoresOwnedBaseline() {
        RecordingProvider provider = new RecordingProvider();
        RecordingEventBus events = new RecordingEventBus();
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider, events);
        AppearanceRequest request = request("theme.one", 0);

        AppearanceApplyResult applied = coordinator.apply("plugin.one", 1, request);
        AppearanceRestoreResult restored = coordinator.restore("plugin.one", 1);

        assertEquals(AppearanceApplyResult.Outcome.APPLIED, applied.outcome());
        assertEquals(AppearanceRestoreResult.Outcome.RESTORED, restored.outcome());
        assertEquals(1, provider.captureCount);
        assertEquals(1, provider.restoreCount);
        assertEquals(2, events.events.size());
    }

    @Test
    void rejectsRevisionAndForeignOwnerBeforeProviderMutation() {
        RecordingProvider provider = new RecordingProvider();
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider, new RecordingEventBus());

        assertEquals(
            AppearanceApplyResult.Outcome.REJECTED,
            coordinator.apply("plugin.one", 1, request("stale", 3)).outcome()
        );
        coordinator.apply("plugin.one", 1, request("owned", 0));
        assertEquals(
            AppearanceApplyResult.Outcome.REJECTED,
            coordinator.apply("plugin.two", 1, request("foreign", 1)).outcome()
        );
        assertEquals(1, provider.applyCount);
    }

    @Test
    void failedApplyRestoresAndReleasesOwnership() {
        RecordingProvider provider = new RecordingProvider();
        provider.failApply = true;
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider, new RecordingEventBus());

        AppearanceApplyResult failed = coordinator.apply("plugin.one", 1, request("broken", 0));
        provider.failApply = false;
        AppearanceApplyResult retry = coordinator.apply("plugin.two", 1, request("working", 0));

        assertEquals(AppearanceApplyResult.Outcome.FAILED_RESTORED, failed.outcome());
        assertEquals(AppearanceApplyResult.Outcome.APPLIED, retry.outcome());
        assertEquals(1, provider.restoreCount);
    }

    @Test
    void unavailableProviderFailsClosedAndPermissionDenialPrecedesApply() {
        AppearanceCoordinator unavailable = new AppearanceCoordinator(
            new UnavailableAppearanceHostProvider(),
            new RecordingEventBus()
        );
        assertEquals(
            AppearanceApplyResult.Outcome.UNAVAILABLE,
            unavailable.apply("plugin", 1, request("theme", 0)).outcome()
        );

        RecordingProvider provider = new RecordingProvider();
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider, new RecordingEventBus());
        RuntimeAppearanceService service = new RuntimeAppearanceService(
            "plugin",
            1,
            (permission, operation) -> { throw new dev.turboism.sdk.permission.CubismPermissionException("denied"); },
            coordinator
        );
        assertThrows(
            dev.turboism.sdk.permission.CubismPermissionException.class,
            () -> service.apply(request("theme", 0))
        );
        assertEquals(0, provider.applyCount);
    }

    @Test
    void restoreAfterCoordinatorCloseIsIdempotentCleanup() {
        RecordingProvider provider = new RecordingProvider();
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider, new RecordingEventBus());
        coordinator.apply("plugin.one", 1, request("theme", 0));

        coordinator.close();
        AppearanceRestoreResult restored = coordinator.restore("plugin.one", 1);

        assertEquals(AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE, restored.outcome());
        assertEquals(1, provider.restoreCount);
    }

    private static AppearanceRequest request(final String id, final long revision) {
        return new AppearanceRequest(id, AppearanceBase.DARK, palette(), revision);
    }

    private static AppearancePalette palette() {
        return new AppearancePalette(
            "#88C0D0", "#2E3440", "#3B4252", "#434C5E", "#D8DEE9",
            "#616E88", "#5E81AC", "#ECEFF4", "#4C566A", "#242933"
        );
    }

    private static final class RecordingProvider implements AppearanceHostProvider {
        private AppearanceStatus status = nativeStatus(0);
        private int captureCount;
        private int applyCount;
        private int restoreCount;
        private boolean failApply;

        @Override public boolean isAvailable() { return true; }
        @Override public AppearanceStatus readStatus() { return status; }
        @Override public RestorePoint captureRestorePoint() { captureCount++; return new Point(status); }
        @Override public ApplyOutcome apply(final AppearanceRequest request) {
            applyCount++;
            if (failApply) throw new IllegalStateException("apply failed");
            status = new AppearanceStatus(
                AppearanceStatus.Availability.AVAILABLE,
                AppearanceStatus.Source.PLUGIN_OVERLAY,
                Optional.of(request.appearanceId()),
                request.base(),
                status.revision() + 1,
                Optional.empty()
            );
            return ApplyOutcome.APPLIED;
        }
        @Override public void restore(final RestorePoint restorePoint) {
            restoreCount++;
            status = ((Point) restorePoint).status;
        }
        private record Point(AppearanceStatus status) implements RestorePoint { }
    }

    private static AppearanceStatus nativeStatus(final long revision) {
        return new AppearanceStatus(
            AppearanceStatus.Availability.AVAILABLE,
            AppearanceStatus.Source.NATIVE,
            Optional.of("native.dark"),
            AppearanceBase.DARK,
            revision,
            Optional.empty()
        );
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AppearanceChangedEvent> events = new ArrayList<>();
        @Override public <T extends TurboismEvent> Registration subscribe(
            final Class<T> type,
            final java.util.function.Consumer<T> listener
        ) { return () -> { }; }
        @Override public <T extends TurboismEvent> void publish(final T event) {
            if (event instanceof AppearanceChangedEvent changed) events.add(changed);
        }
    }
}
