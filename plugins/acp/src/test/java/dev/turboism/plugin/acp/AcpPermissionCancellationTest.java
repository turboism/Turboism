package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class AcpPermissionCancellationTest {
    @Test
    void concurrentRetirementInvokesEachOwnerOnce() throws Exception {
        var cancellation = new AcpPermissionCancellation();
        var calls = new AtomicInteger();
        cancellation.onCancel(calls::incrementAndGet);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                cancellation.cancel();
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                cancellation.cancel();
                return null;
            });
            start.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertTrue(cancellation.isCancelled());
            assertEquals(1, calls.get());
            cancellation.onCancel(calls::incrementAndGet);
            assertEquals(2, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void DetachedAndBrokenOwnersDoNotPreventOtherCleanup() {
        var cancellation = new AcpPermissionCancellation();
        var calls = new AtomicInteger();
        var detached = cancellation.onCancel(() -> fail("Detached owner must not run"));
        detached.close();
        cancellation.onCancel(() -> {
            throw new IllegalStateException("UI cleanup failed");
        });
        cancellation.onCancel(calls::incrementAndGet);
        cancellation.cancel();
        assertEquals(1, calls.get());
    }
}
