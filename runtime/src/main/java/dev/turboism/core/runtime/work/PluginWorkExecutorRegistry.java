package dev.turboism.core.runtime.work;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Owns one {@link PluginExecutorSet} per plugin id, created on first use from a single shared
 * budget configuration.
 *
 * <p>Isolation is per plugin: one plugin exhausting its queue or tripping its breaker cannot
 * affect another's executor. A generation claims its set at lifecycle fencing and releases it at
 * the terminal cleanup tail — releasing is a compare-and-remove against the captured set, so a
 * stale release can never shut down a newer generation's replacement. Backed by a concurrent
 * map, so lookup and creation are safe from any thread.</p>
 */
public final class PluginWorkExecutorRegistry {

    private final PluginWorkExecutorConfiguration configuration;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final Clock clock;
    private final ConcurrentMap<String, PluginExecutorSet> executors = new ConcurrentHashMap<>();

    public PluginWorkExecutorRegistry(
        int workerCount,
        int queueCapacity,
        Consumer<PluginWorkBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this(500L, workerCount, queueCapacity, diagnosticSink, clock);
    }

    public PluginWorkExecutorRegistry(
        long timeoutMillis,
        int workerCount,
        int queueCapacity,
        Consumer<PluginWorkBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this.configuration = PluginWorkExecutorConfiguration.of(
            timeoutMillis,
            requirePositive(workerCount, "workerCount"),
            requirePositive(queueCapacity, "queueCapacity"),
            50.0f
        );
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param pluginId the owning plugin, must not be blank
     * @return this plugin's task executor, created on first request and reused afterwards
     * @throws NullPointerException if {@code pluginId} is {@code null}
     * @throws IllegalArgumentException if {@code pluginId} is blank
     */
    public PluginWorkExecutor get(String pluginId) {
        return claim(pluginId).tasks();
    }

    /**
     * Claims this plugin's executor set for one generation, creating it on first use.
     *
     * <p>The returned set is the object a later {@link #release} must name: capturing it at
     * fencing is what makes the terminal release generation-safe.
     *
     * @param pluginId the owning plugin, must not be blank
     * @return the executor set currently mapped for the plugin
     */
    public PluginExecutorSet claim(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        return executors.computeIfAbsent(id, this::createSet);
    }

    /**
     * Releases the executor set a generation captured at fencing. If the registry still maps the
     * plugin to that set it is removed first; either way the captured set is shut down, so the
     * generation's worker, timeout and event threads die with it. When the registry already maps
     * a newer set, only the captured one is closed — a newer generation's executor is never
     * shut down by a stale release.
     *
     * @param pluginId the owning plugin, must not be blank
     * @param expected the set captured by this generation's earlier {@link #claim}
     */
    public void release(String pluginId, PluginExecutorSet expected) {
        String id = requireText(pluginId, "pluginId");
        Objects.requireNonNull(expected, "expected");
        executors.remove(id, expected);
        expected.shutdown();
    }

    /**
     * Returns this plugin's event-delivery lane, creating the executor set and the lane on first
     * use. The lane is lazily constructed inside the set, so plugins that never receive events
     * pay no lane cost.
     *
     * @param pluginId the owning plugin, must not be blank
     * @return the serial event-delivery lane for this plugin
     */
    public PluginEventLane eventLane(String pluginId) {
        return claim(pluginId).events();
    }

    /**
     * Convenience for {@link PluginWorkExecutor#submitCompletion} that creates the plugin's executor if
     * it does not exist yet.
     *
     * @param pluginId the owning plugin, must not be blank
     * @param task the task being run, used to attribute diagnostics
     * @param work the body to run
     * @return the admission decision plus a stage completing with the work's terminal result
     */
    public PluginWorkSubmission submitCompletion(
        String pluginId,
        PluginTask task,
        Runnable work
    ) {
        return get(pluginId).submitCompletion(task, work);
    }

    /**
     * Removes this plugin's executor set and shuts it down; a later {@link #get} creates a fresh
     * one.
     *
     * <p>A no-op when the plugin has no executor.
     *
     * @param pluginId the owning plugin, must not be blank
     * @throws IllegalArgumentException if {@code pluginId} is blank
     */
    public void shutdown(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        PluginExecutorSet set = executors.remove(id);
        if (set != null) {
            set.shutdown();
        }
    }

    /**
     * Removes and shuts down every registered executor set.
     *
     * <p>Each entry is removed with a compare-and-remove, so an executor replaced concurrently is left
     * to its new owner rather than shut down from under it.
     */
    public void shutdownAll() {
        executors.forEach((pluginId, set) -> {
            if (executors.remove(pluginId, set)) {
                set.shutdown();
            }
        });
    }

    private PluginExecutorSet createSet(String pluginId) {
        return new PluginExecutorSet(
            pluginId,
            configuration,
            diagnosticSink,
            clock
        );
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
