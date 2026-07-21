package dev.turboism.core.runtime.work;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

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

    public PluginWorkExecutor get(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        return executors.computeIfAbsent(id, this::createExecutor);
    }

    public PluginWorkSubmission submitCompletion(
        String pluginId,
        PluginTask task,
        Runnable work
    ) {
        return get(pluginId).submitCompletion(task, work);
    }

    public void shutdown(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        PluginWorkExecutor executor = executors.remove(id);
        if (executor != null) {
            executor.shutdown();
        }
    }

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
