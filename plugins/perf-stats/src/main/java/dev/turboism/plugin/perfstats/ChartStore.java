package dev.turboism.plugin.perfstats;

import dev.turboism.sdk.performance.PerformanceSnapshot;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe rolling value store fed by the sampling consumer. The standalone
 * window draws from the same store, so both UI forms show the same data
 * source (the runtime sampling loop).
 */
final class ChartStore {

    static final String KEY_CPU = "cpu";
    static final String KEY_FPS = "fps";
    static final String KEY_HEAP = "heap";
    static final String KEY_NONHEAP = "nonheap";
    static final String KEY_FRAMES = "frames";
    static final String KEY_GC = "gc";

    private final int capacity;
    private final Map<String, ArrayDeque<Double>> series = new LinkedHashMap<>();
    private long lastGcPauseMillis = -1L;

    ChartStore(final int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2");
        }
        this.capacity = capacity;
        for (String key : List.of(KEY_CPU, KEY_FPS, KEY_HEAP, KEY_NONHEAP, KEY_FRAMES, KEY_GC)) {
            series.put(key, new ArrayDeque<>());
        }
    }

    void append(final PerformanceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (series) {
            append(KEY_CPU, snapshot.cpuPercent());
            append(KEY_FPS, snapshot.fps());
            append(KEY_HEAP, bytesToMebibytes(snapshot.jvmHeapBytes()));
            append(KEY_NONHEAP, bytesToMebibytes(snapshot.jvmNonHeapBytes()));
            append(KEY_FRAMES, (double) snapshot.renderedFrames());
            final long pauseMillis = snapshot.gcPauseMillis();
            final double gcPauseWindowMillis = lastGcPauseMillis < 0L
                ? 0.0
                : Math.max(0.0, (double) (pauseMillis - lastGcPauseMillis));
            lastGcPauseMillis = pauseMillis;
            append(KEY_GC, gcPauseWindowMillis);
        }
    }

    private void append(final String key, final double value) {
        final ArrayDeque<Double> values = series.get(key);
        if (values.size() == capacity) {
            values.removeFirst();
        }
        values.addLast(value);
    }

    List<Double> values(final String key) {
        synchronized (series) {
            final ArrayDeque<Double> values = series.get(key);
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    private static double bytesToMebibytes(final long bytes) {
        return bytes / (1024.0 * 1024.0);
    }
}
