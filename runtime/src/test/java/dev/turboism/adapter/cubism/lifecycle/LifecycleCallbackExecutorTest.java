package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleCallbackExecutorTest {
    @Test
    void tracksAcceptedCallbacksAndTranslatesQuiescenceTimeout() throws Exception {
        final PluginWorkExecutorRegistry executors = new PluginWorkExecutorRegistry(
            10_000L,
            1,
            1,
            ignored -> { },
            Clock.systemUTC()
        );
        final LifecycleCallbackExecutor callbacks = new LifecycleCallbackExecutor("Test", executors);
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
        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            callbacks::awaitIdle
        );
        assertEquals("Test lifecycle callbacks did not quiesce.", failure.getMessage());

        releaseCallback.countDown();
        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS));
        callbacks.awaitIdle();
        callbacks.close();
        callbacks.close();
    }
}
