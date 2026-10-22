package dev.turboism.test.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManualScheduledExecutorTest {
    @Test
    void advancesThroughDeadlinesAndDrainsReentrantTimersInFifoOrder() throws Exception {
        ManualScheduledExecutor timer = new ManualScheduledExecutor();
        List<String> calls = new ArrayList<>();
        timer.schedule(() -> {
            calls.add("first");
            timer.schedule(() -> calls.add("nested"), 1, TimeUnit.SECONDS);
        }, 1, TimeUnit.SECONDS);
        timer.schedule(() -> calls.add("second"), 1, TimeUnit.SECONDS);
        var result = timer.schedule(() -> 42, 2, TimeUnit.SECONDS);
        timer.advanceBy(Duration.ofMillis(999));
        assertTrue(calls.isEmpty());
        assertFalse(result.isDone());
        timer.advanceBy(Duration.ofMillis(1001));
        assertEquals(List.of("first", "second", "nested"), calls);
        assertEquals(42, result.get());
        assertEquals(0, timer.pendingTaskCount());
    }

    @Test
    void cancellationFailureAndShutdownPreserveFutureSemantics() {
        ManualScheduledExecutor timer = new ManualScheduledExecutor();
        var cancelled = timer.schedule(() -> { throw new AssertionError("cancelled callback ran"); }, 0, TimeUnit.SECONDS);
        assertTrue(cancelled.cancel(false));
        var failed = timer.schedule(() -> { throw new IllegalStateException("failure"); }, 0, TimeUnit.SECONDS);
        timer.runDueTasks();
        assertTrue(cancelled.isCancelled());
        assertThrows(ExecutionException.class, failed::get);
        timer.schedule(() -> {}, 1, TimeUnit.DAYS);
        assertEquals(1, timer.shutdownNow().size());
        assertTrue(timer.isTerminated());
        assertThrows(RejectedExecutionException.class, () -> timer.execute(() -> {}));
        assertThrows(IllegalArgumentException.class, () -> timer.advanceBy(Duration.ofNanos(-1)));
    }
}
