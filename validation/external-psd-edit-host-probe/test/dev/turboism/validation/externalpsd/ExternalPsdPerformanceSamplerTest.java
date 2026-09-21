package dev.turboism.validation.externalpsd;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Focused offline tests; these do not establish host performance. */
public final class ExternalPsdPerformanceSamplerTest {
    public static void main(final String[] args) throws Exception {
        final AtomicLong clock = new AtomicLong();
        final AtomicLong heap = new AtomicLong(100);
        final List<Runnable> queue = new ArrayList<>();
        final ExternalPsdPerformanceSampler sampler = new ExternalPsdPerformanceSampler(
            clock::get, heap::get, () -> 50L, queue::add);
        require(!sampler.snapshot().complete(), "zero samples cannot pass");
        sampler.tick();
        clock.set(100_000_000L);
        heap.set(200L);
        sampler.tick();
        require(queue.size() == 1, "a blocked EDT cannot accumulate heartbeats");
        require(sampler.snapshot().pendingHeartbeatAgeNanos() == 100_000_000L,
            "pending delay remains visible before completion");
        require(!sampler.snapshot().complete(), "pending callback is incomplete coverage");
        clock.set(350_000_000L);
        queue.remove(0).run();
        require(sampler.snapshot().complete(), "completed heartbeat is available");
        require(sampler.snapshot().maximumQueueDelayNanos() == 350_000_000L,
            "one delayed callback measures the entire pause");
        require(sampler.snapshot().peakHeapBytes() == 200L, "memory peaks while EDT is blocked");
        require(sampler.snapshot().maximumTickGapNanos() == 100_000_000L,
            "sampler scheduling gaps are visible");
        sampler.tick();
        clock.set(400_000_000L);
        sampler.close();
        final var stopped = sampler.snapshot();
        clock.set(900_000_000L);
        queue.remove(0).run();
        sampler.tick();
        sampler.close();
        require(stopped.equals(sampler.snapshot()), "late callbacks cannot alter closed results");
        require(!stopped.complete(), "a pending final heartbeat is never silently discarded");
        require(queue.isEmpty(), "stop cancels new admission");

        final ExternalPsdPerformanceSampler rejected = new ExternalPsdPerformanceSampler(
            clock::get, heap::get, () -> 0L, task -> { throw new IllegalStateException("EDT"); });
        rejected.tick();
        require(!rejected.snapshot().complete() && rejected.snapshot().failures() == 1L,
            "dispatch failure is not a zero-delay success");
        rejected.close();
        final ExternalPsdPerformanceSampler missingMemory = new ExternalPsdPerformanceSampler(
            clock::get, () -> -1L, () -> 0L, queue::add);
        missingMemory.tick();
        require(missingMemory.snapshot().failures() == 1L && queue.isEmpty(),
            "unavailable memory is explicit and posts no heartbeat");
        missingMemory.close();
        final ExternalPsdPerformanceSampler actual = ExternalPsdPerformanceSampler.start();
        try {
            actual.tick();
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
            final var measured = actual.finish(1_000L);
            require(measured.complete() && measured.peakHeapBytes() > 0L,
                "production executor, memory bean and real EDT produce a complete sample");
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
            require(measured.equals(actual.snapshot()), "finished evidence is immutable");
        } finally {
            actual.close();
        }
        final ExternalPsdPerformanceSampler blocked = new ExternalPsdPerformanceSampler(
            clock::get, heap::get, () -> 0L, queue::add);
        blocked.tick();
        final var timedOut = blocked.finish(1L);
        require(!timedOut.complete() && timedOut.heartbeatPending(),
            "bounded finish leaves an undrained heartbeat explicitly incomplete");
        queue.remove(0).run();
        require(timedOut.equals(blocked.snapshot()), "late completion cannot repair a timeout");
        System.out.println("PASS: ExternalPsdPerformanceSamplerTest");
    }

    private static void require(final boolean accepted, final String message) {
        if (!accepted) throw new AssertionError(message);
    }
}
