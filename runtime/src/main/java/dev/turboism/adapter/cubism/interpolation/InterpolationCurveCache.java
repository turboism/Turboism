package dev.turboism.adapter.cubism.interpolation;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, detached numerical results. Never retains a host form, array, owner or class loader. */
final class InterpolationCurveCache {
    static final int MAX_ENTRIES = 32;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 1024 * 1024;

    record Statistics(long hits, long misses, long evictions, int entries, long bytes, long byteBudget) {}

    static final class Key {
        private final long domain;
        private final int[] input;
        private final int hash;

        Key(long domain, int[] input) {
            this(domain, input, true);
        }

        /** Caller transfers a freshly constructed, never-mutated signature. */
        static Key owned(long domain, int[] input) {
            return new Key(domain, input, false);
        }

        private Key(long domain, int[] input, boolean copy) {
            this.domain = domain;
            this.input = copy ? input.clone() : input;
            this.hash = 31 * Long.hashCode(domain) + Arrays.hashCode(this.input);
        }

        Key detached() {
            return new Key(domain, input);
        }

        long bytes() {
            return 32L + 4L * input.length;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && domain == key.domain && Arrays.equals(input, key.input);
        }
    }

    static final class Result {
        private final float[] values;
        private final boolean[] present;
        private boolean frozen;

        Result(int coordinates) {
            if (coordinates <= 0 || coordinates % 2 != 0) throw new IllegalArgumentException("coordinate count");
            values = new float[coordinates];
            present = new boolean[coordinates / 2];
        }

        void set(int offset, float x, float y) {
            if (frozen) throw new IllegalStateException("published curve result");
            values[offset] = x;
            values[offset + 1] = y;
            present[offset / 2] = true;
        }

        boolean present(int offset) {
            return present[offset / 2];
        }

        float get(int offset) {
            return values[offset];
        }

        long bytes() {
            return 64L + 4L * values.length + present.length;
        }
    }

    private final LinkedHashMap<Key, Result> entries = new LinkedHashMap<>(16, .75f, true);
    private long hits, misses, evictions, bytes;
    private boolean enabled;

    synchronized void enabled(boolean value) {
        enabled = value;
        if (!value) {
            entries.clear();
            bytes = 0;
        }
    }

    synchronized Result get(Key key) {
        if (!enabled) return null;
        Result result = entries.get(key);
        if (result == null) misses++;
        else hits++;
        return result;
    }

    /** First sight retains only a signature; allocate the result only on a repeated input. */
    synchronized boolean admit(Key key) {
        if (!enabled) return false;
        if (entries.containsKey(key)) return true;
        store(key, null);
        return false;
    }

    synchronized void put(Key key, Result result) {
        if (!enabled || size(key, result) > MAX_ENTRY_BYTES) return;
        result.frozen = true;
        store(key, result);
    }

    private static long size(Key key, Result result) {
        return key.bytes() + (result == null ? 0 : result.bytes()) + 64;
    }

    private void store(Key key, Result result) {
        long size = size(key, result);
        if (size > MAX_ENTRY_BYTES) return;
        boolean replacing = entries.containsKey(key);
        Result previous = entries.remove(key);
        if (replacing) bytes -= size(key, previous);
        while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES)) {
            Iterator<Map.Entry<Key, Result>> iterator = entries.entrySet().iterator();
            Map.Entry<Key, Result> oldest = iterator.next();
            bytes -= size(oldest.getKey(), oldest.getValue());
            iterator.remove();
            evictions++;
        }
        entries.put(key, result);
        bytes += size;
    }

    synchronized Statistics statistics() {
        return new Statistics(hits, misses, evictions, entries.size(), bytes, MAX_BYTES);
    }
}
