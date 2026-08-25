package dev.turboism.plugin.mcp;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Uniform synchronous, UI-thread, and CompletionStage execution boundary for MCP handlers. */
final class McpExecutionBridge {

    private final UiScheduler uiScheduler;
    private final Duration timeout;

    McpExecutionBridge(final UiScheduler uiScheduler) {
        this(uiScheduler, Duration.ofSeconds(30));
    }

    McpExecutionBridge(final UiScheduler uiScheduler, final Duration timeout) {
        this.uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    <T> T execute(final Supplier<T> invocation) {
        return ui(invocation);
    }

    <T> T direct(final Supplier<T> invocation) {
        McpRequestRegistry.throwIfCancelled();
        return Objects.requireNonNull(invocation, "invocation").get();
    }

    <T> T ui(final Supplier<T> invocation) {
        McpRequestRegistry.throwIfCancelled();
        final McpRequestRegistry.Cancellation cancellation =
            McpRequestRegistry.currentCancellation();
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicInteger state = new AtomicInteger();
        final Registration cancellationRegistration = McpRequestRegistry.onCancellation(() -> {
            if (state.compareAndSet(0, 2)) result.cancel(false);
        });
        final Registration registration = uiScheduler.runOnUiThread(() -> {
            if (!state.compareAndSet(0, 1)) return;
            if (cancellation.cancelled()) {
                result.cancel(false);
            } else {
                complete(result, invocation);
            }
        });
        try {
            return await(result);
        } finally {
            registration.close();
            cancellationRegistration.close();
        }
    }

    <T> T stage(final Supplier<? extends CompletionStage<T>> invocation) {
        McpRequestRegistry.throwIfCancelled();
        final CompletableFuture<T> result = Objects.requireNonNull(
            Objects.requireNonNull(invocation, "invocation").get(),
            "stage"
        ).toCompletableFuture();
        final Registration cancellationRegistration = McpRequestRegistry.onCancellation(
            () -> result.cancel(true)
        );
        try {
            return await(result);
        } finally {
            cancellationRegistration.close();
        }
    }

    private <T> T await(final CompletableFuture<T> result) {
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            result.cancel(true);
            throw new ExecutionFailure("MCP operation was interrupted", failure);
        } catch (TimeoutException failure) {
            result.cancel(true);
            throw new ExecutionFailure("MCP operation timed out", failure);
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new ExecutionFailure("MCP operation failed", cause);
        }
    }

    private static <T> void complete(
        final CompletableFuture<T> result,
        final Supplier<T> invocation
    ) {
        try {
            result.complete(invocation.get());
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    static final class ExecutionFailure extends RuntimeException {
        ExecutionFailure(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
