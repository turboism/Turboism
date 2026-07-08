package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class PluginExecutorRegistry {

    private final int workerCount;
    private final int queueCapacity;
    private final Consumer<CallbackBudgetEvent> diagnosticSink;
    private final Clock clock;
    private final ConcurrentMap<String, PluginCallbackExecutor> executors = new ConcurrentHashMap<>();

    public PluginExecutorRegistry(
        int workerCount,
        int queueCapacity,
        Consumer<CallbackBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this.workerCount = requirePositive(workerCount, "workerCount");
        this.queueCapacity = requirePositive(queueCapacity, "queueCapacity");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PluginCallbackExecutor get(String pluginId) {
        String id = requireText(pluginId, "pluginId");
        return executors.computeIfAbsent(id, this::createExecutor);
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
        return new PluginCallbackExecutor(pluginId, workerCount, queueCapacity, diagnosticSink, clock);
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
