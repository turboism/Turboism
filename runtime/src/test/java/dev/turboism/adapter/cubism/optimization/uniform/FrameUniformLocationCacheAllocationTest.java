package dev.turboism.adapter.cubism.optimization.uniform;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Measures the actual production cache; this is not a Cubism frame benchmark. */
final class FrameUniformLocationCacheAllocationTest {
    private static final int LOCATIONS = 39;
    private static final int QUERIES = 3350;
    private static final int FRAMES = 1000;
    private static volatile long checksumSink;

    @Test
    void repeatedCompleteFramesReuseBookkeepingStorage() {
        var management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof ThreadMXBean, "thread allocation counter unavailable");
        var counters = (ThreadMXBean) management;
        assumeTrue(counters.isThreadAllocatedMemorySupported(), "thread allocation counter unsupported");
        boolean wasEnabled = counters.isThreadAllocatedMemoryEnabled();
        if (!wasEnabled) counters.setThreadAllocatedMemoryEnabled(true);
        try (var cache = new FrameUniformLocationCache(4096)) {
            Object context = new Object();
            String[] names = new String[LOCATIONS];
            for (int index = 0; index < names.length; index++) names[index] = "uniform_" + index;
            runFrames(cache, context, names, FRAMES);
            long thread = Thread.currentThread().getId();
            long beforeBytes = counters.getThreadAllocatedBytes(thread);
            long beforeNanos = System.nanoTime();
            long checksum = runFrames(cache, context, names, FRAMES);
            long elapsedNanos = System.nanoTime() - beforeNanos;
            long allocatedBytes = counters.getThreadAllocatedBytes(thread) - beforeBytes;
            long expectedPerFrame = 0;
            for (int index = 0; index < QUERIES; index++) expectedPerFrame += 1000 + index % LOCATIONS;
            assertEquals(expectedPerFrame * FRAMES, checksum);
            assertEquals(0, cache.retained());
            double bytesPerFrame = (double) allocatedBytes / FRAMES;
            System.out.printf(java.util.Locale.ROOT,
                "CACHE_MICROBENCHMARK frames=%d queriesPerFrame=%d locationsPerFrame=%d "
                    + "allocatedBytes=%d bytesPerFrame=%.3f elapsedNanos=%d%n",
                FRAMES, QUERIES, LOCATIONS, allocatedBytes, bytesPerFrame, elapsedNanos);
            assertTrue(bytesPerFrame < 1024.0,
                "cache bookkeeping must reuse warmed storage, allocated bytes/frame=" + bytesPerFrame);
        } finally {
            if (!wasEnabled) counters.setThreadAllocatedMemoryEnabled(false);
        }
    }

    private static long runFrames(FrameUniformLocationCache cache, Object context,
                                  String[] names, int frames) {
        long checksum = 0;
        for (int frame = 0; frame < frames; frame++) {
            long token = cache.begin(context, true);
            for (int index = 0; index < names.length; index++) {
                cache.record(context, 200 + index % 3, names[index], 1000 + index);
                cache.checkedError(context, 0);
            }
            for (int index = 0; index < QUERIES; index++) {
                int key = index % names.length;
                checksum += cache.lookup(context, 200 + key % 3, names[key]);
            }
            cache.end(token);
        }
        checksumSink = checksum;
        return checksum;
    }
}
