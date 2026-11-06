package dev.turboism.testkit;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
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
     * queue capacity eight and no diagnostic sink.
     */
    public static RuntimeScheduler scheduler() {
        return scheduler(Clock.systemUTC(), 1, 8);
    }

    /** Returns a scheduler with the supplied clock, worker count and queue capacity. */
    public static RuntimeScheduler scheduler(final Clock clock, final int workers, final int queueCapacity) {
        return scheduler(clock, workers, queueCapacity, ignored -> {}, ignored -> {});
    }

    /** Returns a scheduler that records diagnostics both from executors and from the scheduler. */
    public static RuntimeScheduler scheduler(
            final Clock clock,
            final int workers,
            final int queueCapacity,
            final List<PluginWorkBudgetEvent> diagnostics) {
        return scheduler(clock, workers, queueCapacity, diagnostics::add, diagnostics::add);
    }

    /** Returns a scheduler with explicit diagnostic sinks. */
    public static RuntimeScheduler scheduler(
            final Clock clock,
            final int workers,
            final int queueCapacity,
            final Consumer<PluginWorkBudgetEvent> executorDiagnostics,
            final Consumer<PluginWorkBudgetEvent> schedulerDiagnostics) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(executorDiagnostics, "executorDiagnostics");
        Objects.requireNonNull(schedulerDiagnostics, "schedulerDiagnostics");
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(workers, queueCapacity, executorDiagnostics, clock),
                schedulerDiagnostics);
    }

    /** Returns a scheduler with an explicit executor registry. */
    public static RuntimeScheduler scheduler(final PluginWorkExecutorRegistry registry) {
        return new RuntimeScheduler(new DefaultWorkBudgetPolicy(), registry, ignored -> {});
    }

    /** Returns an empty host snapshot source: no project, document, model, selection or host. */
    public static HostSnapshotSource emptyHostSnapshotSource() {
        return RecordingHostSnapshotSource.empty();
    }
}
