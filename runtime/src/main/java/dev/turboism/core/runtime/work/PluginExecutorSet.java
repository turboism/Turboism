package dev.turboism.core.runtime.work;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The full per-plugin executor footprint: the task executor plus the event-delivery and
 * long-task lanes.
 *
 * <p>One set belongs to exactly one plugin generation. The registry hands a set to the
 * generation's event owner at lifecycle fencing ({@link PluginWorkExecutorRegistry#claim}) and
 * reclaims it at the terminal cleanup tail ({@link PluginWorkExecutorRegistry#release}), so an
 * unloaded generation's worker, timeout, and event threads all die with it and a reloaded
 * generation starts from a fresh circuit-breaker window. The lanes are created lazily so a
 * plugin that never receives events or long work pays nothing for them.</p>
 */
public final class PluginExecutorSet {

    private final String pluginId;
    private final PluginWorkExecutor taskExecutor;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final PluginWorkExecutorConfiguration configuration;
    private volatile PluginEventLane eventLane;
    private volatile PluginLongLane longLane;
    private volatile boolean closed;

    PluginExecutorSet(
            String pluginId,
            PluginWorkExecutorConfiguration configuration,
            Consumer<PluginWorkBudgetEvent> diagnosticSink,
            Clock clock) {
        this.pluginId = pluginId;
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.taskExecutor = new PluginWorkExecutor(pluginId, configuration, diagnosticSink, clock);
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
                    lane = new PluginEventLane(pluginId, configuration.queueCapacity(), diagnosticSink);
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
     * Returns this generation's long-task lane, created on first use. A lane obtained after
     * {@link #shutdown} is already closed and refuses submissions.
     */
    public PluginLongLane longLane() {
        PluginLongLane lane = longLane;
        if (lane == null) {
            synchronized (this) {
                lane = longLane;
                if (lane == null) {
                    lane = new PluginLongLane(
                            pluginId,
                            configuration.longLaneConcurrency(),
                            configuration.queueCapacity(),
                            configuration.longRunningThreshold(),
                            configuration.longRunningReportInterval(),
                            diagnosticSink);
                    longLane = lane;
                    if (closed) {
                        lane.shutdown();
                    }
                }
            }
        }
        return lane;
    }

    /**
     * Shuts the whole set down: the event lane first, then the long-task lane, then the task
     * executor. Idempotent. Queued event drains are dropped only by the bounded
     * forced-shutdown tail; the long lane cancels cooperatively and never interrupts.
     */
    public void shutdown() {
        closed = true;
        synchronized (this) {
            if (eventLane != null) {
                eventLane.shutdown();
            }
            if (longLane != null) {
                longLane.shutdown();
            }
        }
        taskExecutor.shutdown();
    }

    /** Returns {@code true} once every owned pool has terminated. */
    public boolean isTerminated() {
        final PluginEventLane events = eventLane;
        final PluginLongLane longs = longLane;
        return taskExecutor.isTerminated()
                && (events == null || events.isTerminated())
                && (longs == null || longs.isTerminated());
    }
}
