package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.QuiescencePolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Shared callback submission and quiescence mechanics for lifecycle coordinators. */
final class LifecycleCallbackExecutor implements AutoCloseable {
    private final QuiescencePolicy quiescence;

    private final String lifecycleName;
    private final PluginWorkExecutorRegistry executors;
    private final List<CompletionStage<?>> pending = new java.util.concurrent.CopyOnWriteArrayList<>();

    LifecycleCallbackExecutor(
            final String lifecycleName, final PluginWorkExecutorRegistry executors, final QuiescencePolicy quiescence) {
        this.quiescence = Objects.requireNonNull(quiescence, "quiescence");
        this.lifecycleName = requireText(lifecycleName, "lifecycleName");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    void submit(final String pluginId, final String operationId, final Runnable callback) {
        final String id = requireText(pluginId, "pluginId");
        final String operation = requireText(operationId, "operationId");
        final var submission = executors
                .get(id)
                .submit(
                        new PluginTask("event.subscribe", id, operation, "none"),
                        Objects.requireNonNull(callback, "callback"));
        if (submission.accepted()) {
            pending.add(submission.completion());
        }
    }

    void awaitIdle() {
        final CompletionStage<?>[] snapshot = pending.toArray(CompletionStage[]::new);
        try {
            CompletableFuture.allOf(Arrays.stream(snapshot)
                            .map(CompletionStage::toCompletableFuture)
                            .toArray(CompletableFuture[]::new))
                    .get(quiescence.timeout().toNanos(), TimeUnit.NANOSECONDS);
            pending.removeAll(Arrays.asList(snapshot));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw quiescenceFailure(failure);
        } catch (Exception failure) {
            throw quiescenceFailure(failure);
        }
    }

    void shutdown(final String pluginId) {
        executors.shutdown(requireText(pluginId, "pluginId"));
    }

    @Override
    public void close() {
        executors.shutdownAll();
    }

    private IllegalStateException quiescenceFailure(final Exception failure) {
        return new IllegalStateException(lifecycleName + " lifecycle callbacks did not quiesce.", failure);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
