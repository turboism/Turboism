package dev.turboism.adapter.cubism.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.runtime.work.PluginWorkExecutorConfiguration;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LifecycleCallbackExecutorTest {
    @Test
    void tracksAcceptedCallbacksAndTranslatesQuiescenceTimeout() throws Exception {
        final PluginWorkExecutorRegistry executors = new PluginWorkExecutorRegistry(
                PluginWorkExecutorConfiguration.of(10_000L, 1, 1, 50.0f)
                        .withShutdownTimeout(java.time.Duration.ofMillis(20)),
                ignored -> {},
                Clock.systemUTC());
        final LifecycleCallbackExecutor callbacks = new LifecycleCallbackExecutor(
                "Test", executors, new dev.turboism.core.runtime.QuiescencePolicy(java.time.Duration.ofMillis(50)));
        final CountDownLatch callbackStarted = new CountDownLatch(1);
        final CountDownLatch releaseCallback = new CountDownLatch(1);
        final CountDownLatch callbackCompleted = new CountDownLatch(1);
        callbacks.submit("plugin-a", "test.operation", () -> {
            callbackStarted.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                callbackCompleted.countDown();
            }
        });

        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
        final IllegalStateException failure = assertThrows(IllegalStateException.class, callbacks::awaitIdle);
        assertEquals("Test lifecycle callbacks did not quiesce.", failure.getMessage());

        releaseCallback.countDown();
        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS));
        callbacks.awaitIdle();
        callbacks.close();
        callbacks.close();
    }

    @Test
    void manyCompletedCallbacksLeaveNoResidueInThePendingSet() throws Exception {
        final PluginWorkExecutorRegistry executors =
                new PluginWorkExecutorRegistry(5_000L, 1, 1_024, ignored -> {}, Clock.systemUTC());
        final LifecycleCallbackExecutor callbacks = new LifecycleCallbackExecutor(
                "Test", executors, new dev.turboism.core.runtime.QuiescencePolicy(java.time.Duration.ofSeconds(5)));
        final int submissions = 256;
        final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < submissions; i++) {
            callbacks.submit("plugin-a", "test.operation", completed::incrementAndGet);
        }
        callbacks.awaitIdle();
        assertEquals(submissions, completed.get(), "every accepted callback must run");
        assertEquals(0, callbacks.pendingCallbacks(), "completed callbacks must retire from the tracked set");
        callbacks.close();
    }
}
