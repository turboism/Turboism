package dev.turboism.ui.appearance;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceChangedEvent;
import dev.turboism.sdk.appearance.AppearancePalette;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppearanceCoordinatorTest {

    @Test
    void appliesPublishesOnceAndRestoresOwnedBaseline() {
        RecordingProvider provider = new RecordingProvider();
        RuntimeScheduler scheduler = scheduler();
        RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        RuntimeEventBroker.Owner observer = broker.admit("plugin.observer");
        List<AppearanceChangedEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        broker.subscribe(observer.key(), AppearanceChangedEvent.class, events::add);
        observer.activate();
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider);
        coordinator.attachEventBroker(broker);
        AppearanceRequest request = request("theme.one", 0);

        AppearanceApplyResult applied = coordinator.apply("plugin.one", 1, request);
        AppearanceRestoreResult restored = coordinator.restore("plugin.one", 1);

        assertEquals(AppearanceApplyResult.Outcome.APPLIED, applied.outcome());
        assertEquals(AppearanceRestoreResult.Outcome.RESTORED, restored.outcome());
        assertEquals(1, provider.captureCount);
        assertEquals(1, provider.restoreCount);
        awaitSize(events, 2);
        scheduler.shutdown();
    }

    @Test
    void rejectsRevisionAndForeignOwnerBeforeProviderMutation() {
        RecordingProvider provider = new RecordingProvider();
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider);

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
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider);

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
            new UnavailableAppearanceHostProvider()
        );
        assertEquals(
            AppearanceApplyResult.Outcome.UNAVAILABLE,
            unavailable.apply("plugin", 1, request("theme", 0)).outcome()
        );

        RecordingProvider provider = new RecordingProvider();
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider);
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
        AppearanceCoordinator coordinator = new AppearanceCoordinator(provider);
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

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(
                1,
                8,
                ignored -> { },
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC)
            ),
            new NoOpSidecarDispatcher(),
            ignored -> { }
        );
    }

    private static void awaitSize(final List<?> values, final int expected) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (values.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, values.size());
    }

    private static final class NoOpSidecarDispatcher implements SidecarDispatcher {
        @Override
        public CompletionStage<SidecarResult> dispatch(
            final PluginTask task,
            final Runnable callback
        ) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
