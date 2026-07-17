package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class PluginExecutorRegistry {

    private final PluginCallbackExecutorConfiguration configuration;
    private final Consumer<CallbackBudgetEvent> diagnosticSink;
    private final Clock clock;
    private final ConcurrentMap<String, PluginCallbackExecutor> executors = new ConcurrentHashMap<>();

    public PluginExecutorRegistry(
        int workerCount,
        int queueCapacity,
        Consumer<CallbackBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this(500L, workerCount, queueCapacity, diagnosticSink, clock);
    }

    public PluginExecutorRegistry(
        long timeoutMillis,
        int workerCount,
        int queueCapacity,
        Consumer<CallbackBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this.configuration = PluginCallbackExecutorConfiguration.of(
            timeoutMillis,
            requirePositive(workerCount, "workerCount"),
            requirePositive(queueCapacity, "queueCapacity"),
            50.0f
        );
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PluginCallbackExecutor get(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        return executors.computeIfAbsent(id, this::createExecutor);
    }

    public CallbackSubmission submitCompletion(
        String pluginId,
        PluginTask task,
        Runnable callback
    ) {
        return get(pluginId).submitCompletion(task, callback);
    }

    public void shutdown(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        PluginCallbackExecutor executor = executors.remove(id);
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

    private PluginCallbackExecutor createExecutor(String pluginId) {
        return new PluginCallbackExecutor(
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
