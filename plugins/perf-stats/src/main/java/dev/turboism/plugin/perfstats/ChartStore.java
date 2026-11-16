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
    static final String KEY_MEMORY = "memory";
    static final String KEY_HEAP = "heap";
    static final String KEY_NONHEAP = "nonheap";

    private static final double BYTES_PER_MEBIBYTE = 1024.0 * 1024.0;
    private static final int MIN_CAPACITY = 2;

    private final int capacity;
    private final Map<String, ArrayDeque<Double>> series = new LinkedHashMap<>();

    ChartStore(final int capacity) {
        if (capacity < MIN_CAPACITY) {
            throw new IllegalArgumentException("capacity must be at least " + MIN_CAPACITY);
        }
        this.capacity = capacity;
        for (String key : List.of(KEY_CPU, KEY_HEAP, KEY_NONHEAP)) {
            series.put(key, new ArrayDeque<>());
        }
    }

    void append(final PerformanceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (series) {
            append(KEY_CPU, snapshot.cpuPercent());
            append(KEY_HEAP, bytesToMebibytes(snapshot.jvmHeapBytes()));
            append(KEY_NONHEAP, bytesToMebibytes(snapshot.jvmNonHeapBytes()));
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
        return bytes / BYTES_PER_MEBIBYTE;
    }
}
