package dev.turboism.adapter.cubism.interpolation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Detached fixed-point inputs and actual per-point notification masks; never retains host objects. */
final class LocalInterpolationCache {
    static final int MAX_ENTRIES = 64;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 1024 * 1024;

    record Statistics(long hits, long misses, long evictions, int entries, long bytes, long byteBudget) {}

    private final LinkedHashMap<InterpolationCurveCache.Key, boolean[]> entries = new LinkedHashMap<>(16, .75f, true);
    // Small detached scratch pool shared across invocations; checked-out arrays are exclusive.
    static final long MAX_SCRATCH_BYTES = 1024 * 1024;
    private final ArrayList<Object> scratch = new ArrayList<>();
    private long scratchBytes;
    private final LinkedHashMap<InterpolationCurveCache.Key, Integer> admissions = new LinkedHashMap<>(16, .75f, true);
    private long admissionBytes;
    private boolean enabled;
    private long bytes, hits, misses, evictions;

    synchronized void enabled(boolean value) {
        enabled = value;
        if (!value) {
            entries.clear();
            admissions.clear();
            admissionBytes = 0;
            scratch.clear();
            scratchBytes = 0;
            bytes = 0;
        }
    }

    synchronized boolean[] get(InterpolationCurveCache.Key key) {
        if (!enabled) return null;
        boolean[] result = entries.get(key);
        if (result == null) misses++;
        else hits++;
        return result;
    }

    synchronized void put(InterpolationCurveCache.Key key, boolean[] actualWrites) {
        long size = size(key, actualWrites);
        if (!enabled || size > MAX_ENTRY_BYTES) return;
        boolean[] previous = entries.remove(key);
        if (previous != null) bytes -= size(key, previous);
        while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES)) {
            Iterator<Map.Entry<InterpolationCurveCache.Key, boolean[]>> iterator =
                    entries.entrySet().iterator();
            var oldest = iterator.next();
            bytes -= size(oldest.getKey(), oldest.getValue());
            iterator.remove();
            evictions++;
        }
        entries.put(key.detached(), actualWrites.clone());
        bytes += size;
    }

    /** Avoid full target snapshots for transient source inputs, independently of curve-result eviction. */
    synchronized boolean admit(InterpolationCurveCache.Key key) {
        if (!enabled || key.bytes() + 64 > MAX_ENTRY_BYTES) return false;
        Integer count = admissions.get(key);
        if (count != null) {
            if (count < 4) admissions.put(key, count + 1);
            return count >= 4;
        }
        long size = key.bytes() + 64;
        while (!admissions.isEmpty() && (admissions.size() >= 128 || admissionBytes + size > MAX_ENTRY_BYTES)) {
            var iterator = admissions.entrySet().iterator();
            var old = iterator.next();
            admissionBytes -= old.getKey().bytes() + 64;
            iterator.remove();
        }
        admissions.put(key.detached(), 1);
        admissionBytes += size;
        return false;
    }

    synchronized int[] borrowInput(int length) {
        for (int i = 0; i < scratch.size(); i++) {
            Object value = scratch.get(i);
            if (value instanceof int[] input && input.length == length) {
                scratch.remove(i);
                scratchBytes -= 4L * length;
                return input;
            }
        }
        return new int[length];
    }

    synchronized boolean[] borrowWrites(int length) {
        for (int i = 0; i < scratch.size(); i++) {
            Object value = scratch.get(i);
            if (value instanceof boolean[] writes && writes.length == length) {
                scratch.remove(i);
                scratchBytes -= length;
                Arrays.fill(writes, false);
                return writes;
            }
        }
        return new boolean[length];
    }

    synchronized void release(Object value) {
        if (!enabled || value == null) return;
        long size = value instanceof int[] input ? 4L * input.length : ((boolean[]) value).length;
        if (scratch.size() < 8 && scratchBytes + size <= MAX_SCRATCH_BYTES) {
            scratch.add(value);
            scratchBytes += size;
        }
    }

    private static long size(InterpolationCurveCache.Key key, boolean[] writes) {
        return key.bytes() + 96L + writes.length;
    }

    synchronized Statistics statistics() {
        return new Statistics(hits, misses, evictions, entries.size(), bytes, MAX_BYTES);
    }
}
