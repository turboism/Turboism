package dev.turboism.testkit;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.contract.PluginTask;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Shared builders for runtime-owned contracts used by {@code :runtime} tests.
 *
 * <p>These helpers stay in {@code :runtime} because {@code :testing:test-support} intentionally
 * depends only on {@code :sdk}.
 */
public final class RuntimeTestKit {

    private RuntimeTestKit() {}

    /**
     * Returns the canonical scheduler used by lifecycle/event tests: system UTC clock, one worker,
     * queue capacity eight, a sidecar that reports success without running callbacks, and no
     * diagnostic sink.
     */
    public static RuntimeScheduler scheduler() {
        return scheduler(Clock.systemUTC(), 1, 8);
    }

    /** Returns a scheduler with the supplied clock, worker count, queue capacity and sidecar. */
    public static RuntimeScheduler scheduler(final Clock clock, final int workers, final int queueCapacity) {
        return scheduler(clock, workers, queueCapacity, successfulSidecar());
    }

    /** Returns a scheduler with the supplied clock, worker count, queue capacity and sidecar. */
    public static RuntimeScheduler scheduler(
            final Clock clock, final int workers, final int queueCapacity, final SidecarDispatcher dispatcher) {
        return scheduler(clock, workers, queueCapacity, dispatcher, ignored -> {}, ignored -> {});
    }

    /** Returns a scheduler that records diagnostics both from executors and from the scheduler. */
    public static RuntimeScheduler scheduler(
            final Clock clock,
            final int workers,
            final int queueCapacity,
            final SidecarDispatcher dispatcher,
            final List<PluginWorkBudgetEvent> diagnostics) {
        return scheduler(clock, workers, queueCapacity, dispatcher, diagnostics::add, diagnostics::add);
    }

    /** Returns a scheduler with explicit diagnostic sinks. */
    public static RuntimeScheduler scheduler(
            final Clock clock,
            final int workers,
            final int queueCapacity,
            final SidecarDispatcher dispatcher,
            final Consumer<PluginWorkBudgetEvent> executorDiagnostics,
            final Consumer<PluginWorkBudgetEvent> schedulerDiagnostics) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(executorDiagnostics, "executorDiagnostics");
        Objects.requireNonNull(schedulerDiagnostics, "schedulerDiagnostics");
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(workers, queueCapacity, executorDiagnostics, clock),
                dispatcher,
                schedulerDiagnostics);
    }

    /** Returns a scheduler with an explicit timeout budget and executor registry. */
    public static RuntimeScheduler scheduler(
            final PluginWorkExecutorRegistry registry, final SidecarDispatcher dispatcher) {
        return new RuntimeScheduler(new DefaultWorkBudgetPolicy(), registry, dispatcher, ignored -> {});
    }

    /**
     * Returns a sidecar dispatcher that immediately reports success without invoking the callback.
     *
     * <p>This matches the per-test {@code NoOpSidecarDispatcher} helpers that predate the kit; it is
     * deliberately distinct from {@link SidecarDispatcher#noop()}, which reports unavailability.
     */
    public static SidecarDispatcher successfulSidecar() {
        return SuccessfulSidecarDispatcher.INSTANCE;
    }

    /** Returns a sidecar dispatcher that runs the callback immediately and reports success. */
    public static SidecarDispatcher callbackRunningSidecar() {
        return (task, callback) -> {
            callback.run();
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        };
    }

    /** Returns an empty host snapshot source: no project, document, model, selection or host. */
    public static HostSnapshotSource emptyHostSnapshotSource() {
        return EmptyHostSnapshotSource.INSTANCE;
    }

    private static final class SuccessfulSidecarDispatcher implements SidecarDispatcher {
        private static final SuccessfulSidecarDispatcher INSTANCE = new SuccessfulSidecarDispatcher();

        @Override
        public CompletionStage<SidecarResult> dispatch(final PluginTask task, final Runnable callback) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }

    private static final class EmptyHostSnapshotSource implements HostSnapshotSource {
        private static final EmptyHostSnapshotSource INSTANCE = new EmptyHostSnapshotSource();

        @Override
        public Optional<HostProject> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<HostModel> activeModel() {
            return Optional.empty();
        }

        @Override
        public HostSelection selection() {
            return HostSelection.empty();
        }

        @Override
        public boolean isHostPresent() {
            return false;
        }

        @Override
        public long invalidationToken() {
            return 0L;
        }
    }
}
