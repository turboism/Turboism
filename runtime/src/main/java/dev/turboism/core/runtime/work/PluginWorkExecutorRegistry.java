package dev.turboism.core.runtime.work;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Owns one {@link PluginWorkExecutor} per plugin id, created on first use from a single shared
 * budget configuration.
 *
 * <p>Isolation is per plugin: one plugin exhausting its queue or tripping its breaker cannot affect
 * another's executor. Backed by a concurrent map, so lookup and creation are safe from any thread.
 */
public final class PluginWorkExecutorRegistry {

    private final PluginWorkExecutorConfiguration configuration;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final Clock clock;
    private final ConcurrentMap<String, PluginWorkExecutor> executors = new ConcurrentHashMap<>();

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
     * @return this plugin's executor, created on first request and reused afterwards
     * @throws NullPointerException if {@code pluginId} is {@code null}
     * @throws IllegalArgumentException if {@code pluginId} is blank
     */
    public PluginWorkExecutor get(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        return executors.computeIfAbsent(id, this::createExecutor);
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
     * Removes this plugin's executor and shuts it down; a later {@link #get} creates a fresh one.
     *
     * <p>A no-op when the plugin has no executor.
     *
     * @param pluginId the owning plugin, must not be blank
     * @throws IllegalArgumentException if {@code pluginId} is blank
     */
    public void shutdown(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        PluginWorkExecutor executor = executors.remove(id);
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * Removes and shuts down every registered executor.
     *
     * <p>Each entry is removed with a compare-and-remove, so an executor replaced concurrently is left
     * to its new owner rather than shut down from under it.
     */
    public void shutdownAll() {
        executors.forEach((pluginId, executor) -> {
            if (executors.remove(pluginId, executor)) {
                executor.shutdown();
            }
        });
    }

    private PluginWorkExecutor createExecutor(String pluginId) {
        return new PluginWorkExecutor(
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
