package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ExtendedInterpolationParallelBridgeTest {

    @Test
    void schedulesHeaviestFirstWithCallerParticipationAndWorkerFlag() throws Exception {
        final Object light = new Object();
        final Object medium = new Object();
        final Object heavy = new Object();
        final List<ExtendedInterpolationParallelBridge.WeightedExtension> work = new ArrayList<>(List.of(
                new ExtendedInterpolationParallelBridge.WeightedExtension(light, 10L),
                new ExtendedInterpolationParallelBridge.WeightedExtension(medium, 100L),
                new ExtendedInterpolationParallelBridge.WeightedExtension(heavy, 1000L)));
        final Thread caller = Thread.currentThread();
        final AtomicBoolean callerParticipated = new AtomicBoolean(false);
        final AtomicBoolean allSawWorkerFlag = new AtomicBoolean(true);
        final CountDownLatch callerRan = new CountDownLatch(1);
        final List<Object> visited = new CopyOnWriteArrayList<>();

        final Set<Object> completed = ExtendedInterpolationParallelBridge.executeParallel(work, ext -> {
            if (!ExtendedInterpolationParallelBridge.isWorkerThread()) {
                allSawWorkerFlag.set(false);
            }
            if (Thread.currentThread() == caller) {
                callerParticipated.set(true);
                callerRan.countDown();
            } else {
                // Hold background workers briefly so the 3rd item deterministically falls to the caller thread.
                callerRan.await(2, TimeUnit.SECONDS);
            }
            visited.add(ext);
        });

        assertEquals(heavy, work.get(0).extension());
        assertEquals(medium, work.get(1).extension());
        assertEquals(light, work.get(2).extension());
        assertTrue(allSawWorkerFlag.get());
        assertTrue(callerParticipated.get());
        assertFalse(ExtendedInterpolationParallelBridge.isWorkerThread());
        assertEquals(3, completed.size());
        assertTrue(completed.containsAll(List.of(light, medium, heavy)));
        assertEquals(3, visited.size());
    }

    @Test
    void isolatesNonFatalExtensionFailureFromSiblingCompletion() throws Exception {
        final Object goodA = new Object();
        final Object failing = new Object();
        final Object goodB = new Object();
        final List<ExtendedInterpolationParallelBridge.WeightedExtension> work = new ArrayList<>(List.of(
                new ExtendedInterpolationParallelBridge.WeightedExtension(goodA, 50L),
                new ExtendedInterpolationParallelBridge.WeightedExtension(failing, 25L),
                new ExtendedInterpolationParallelBridge.WeightedExtension(goodB, 10L)));

        final Set<Object> completed = ExtendedInterpolationParallelBridge.executeParallel(work, ext -> {
            if (ext == failing) {
                throw new IllegalStateException("simulated failure");
            }
        });

        assertEquals(2, completed.size());
        assertTrue(completed.contains(goodA));
        assertTrue(completed.contains(goodB));
        assertFalse(completed.contains(failing));
    }

    @Test
    void consumePrecomputedReturnsFalseWhenUnregistered() {
        assertFalse(ExtendedInterpolationParallelBridge.consumePrecomputed(null));
        assertFalse(ExtendedInterpolationParallelBridge.consumePrecomputed(new Object()));
    }
}
