package dev.turboism.core.runtime.work;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The full per-plugin executor footprint: the task executor plus the event-delivery lane.
 *
 * <p>One set belongs to exactly one plugin generation. The registry hands a set to the
 * generation's event owner at lifecycle fencing ({@link PluginWorkExecutorRegistry#claim}) and
 * reclaims it at the terminal cleanup tail ({@link PluginWorkExecutorRegistry#release}), so an
 * unloaded generation's worker, timeout, and event threads all die with it and a reloaded
 * generation starts from a fresh circuit-breaker window. The event lane is created lazily so a
 * plugin that never receives events pays nothing for it.</p>
 */
public final class PluginExecutorSet {

    private final String pluginId;
    private final PluginWorkExecutor taskExecutor;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final int eventQueueCapacity;
    private volatile PluginEventLane eventLane;
    private volatile boolean closed;

    PluginExecutorSet(
        String pluginId,
        PluginWorkExecutorConfiguration configuration,
        Consumer<PluginWorkBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this.pluginId = pluginId;
        this.taskExecutor = new PluginWorkExecutor(
            pluginId,
            configuration,
            diagnosticSink,
            clock
        );
        this.eventQueueCapacity = configuration.queueCapacity();
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    /** Returns this generation's task executor. */
    public PluginWorkExecutor tasks() {
        return taskExecutor;
    }

    /**
     * Returns this generation's serial event-delivery lane, created on first use. A lane
     * obtained after {@link #shutdown} is already closed and refuses submissions.
     */
    public PluginEventLane events() {
        PluginEventLane lane = eventLane;
        if (lane == null) {
            synchronized (this) {
                lane = eventLane;
                if (lane == null) {
                    lane = new PluginEventLane(pluginId, eventQueueCapacity, diagnosticSink);
                    eventLane = lane;
                    if (closed) {
                        lane.shutdown();
                    }
                }
            }
        }
        return lane;
    }

    /**
     * Shuts the whole set down: the event lane first, then the task executor. Idempotent.
     * Queued event drains are dropped only by the bounded forced-shutdown tail.
     */
    public void shutdown() {
        closed = true;
        synchronized (this) {
            if (eventLane != null) {
                eventLane.shutdown();
            }
        }
        taskExecutor.shutdown();
    }

    /** Returns {@code true} once every owned pool has terminated. */
    public boolean isTerminated() {
        final PluginEventLane lane = eventLane;
        return taskExecutor.isTerminated() && (lane == null || lane.isTerminated());
    }
}
