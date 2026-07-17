package dev.turboism.storage;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.task.RuntimePluginTaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageIoExecutorTest {

    private DisposableScope scope;
    private RuntimeScheduler runtimeScheduler;
    private StorageIoExecutor io;

    @AfterEach
    void cleanup() throws Exception {
        if (scope != null) {
            scope.close();
        }
        if (runtimeScheduler != null && !runtimeScheduler.isClosed()) {
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void closeSettlesQueuedOperationAsCanceledWithoutExecutingItsAction() throws Exception {
        createExecutor();
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch holdFirst = new CountDownLatch(1);
        final AtomicBoolean queuedActionRan = new AtomicBoolean(false);

        io.submit(
            () -> {
                firstStarted.countDown();
                try {
                    holdFirst.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "first";
            },
            () -> "first-canceled",
            () -> "first-unavailable"
        );
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        final var queued = io.submit(
            () -> {
                queuedActionRan.set(true);
                return "side-effect";
            },
            () -> "queued-canceled",
            () -> "queued-unavailable"
        );

        io.close();

        assertEquals(
            "queued-canceled",
            queued.toCompletableFuture().get(2, TimeUnit.SECONDS)
        );
        assertFalse(queuedActionRan.get());
    }

    @Test
    void unexpectedIoFailureIsSanitizedBeforePluginObservation() throws Exception {
        createExecutor();
        final var stage = io.submit(
            () -> {
                throw new IllegalStateException("private /secret/project/model.cmo3");
            },
            () -> "canceled",
            () -> "unavailable"
        );

        final ExecutionException failure = assertThrows(
            ExecutionException.class,
            () -> stage.toCompletableFuture().get(2, TimeUnit.SECONDS)
        );
        assertEquals(
            "Plugin storage operation failed safely.",
            failure.getCause().getMessage()
        );
        assertFalse(failure.getCause().getMessage().contains("secret"));
        assertFalse(failure.getCause().getMessage().contains("cmo3"));
    }

    private void createExecutor() {
        scope = new DisposableScope();
        runtimeScheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(
                1,
                16,
                event -> { },
                Clock.systemUTC()
            ),
            SidecarDispatcher.noop(),
            event -> { }
        );
        final RuntimePluginTaskScheduler taskScheduler = new RuntimePluginTaskScheduler(
            "dev.turboism.plugin.storage-io-test",
            runtimeScheduler,
            scope
        );
        io = new StorageIoExecutor(
            "dev.turboism.plugin.storage-io-test",
            taskScheduler,
            scope
        );
    }
}
