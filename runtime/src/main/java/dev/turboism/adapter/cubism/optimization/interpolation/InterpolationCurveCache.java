package dev.turboism.adapter.cubism.optimization.interpolation;

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
        private static final int HASH_MULTIPLIER = 31;
        private static final long KEY_OVERHEAD_BYTES = 32L;
        private static final long INT_BYTES = 4L;

        private final long domain;
        private final int[] input;
        private final int length;
        private final int hash;
        private final boolean immutable;
        private final boolean cacheDetached;
        private Key detachedCache;

        Key(long domain, int[] input) {
            this(domain, input.clone(), input.length, computeHash(domain, input, input.length), true, false);
        }

        /** Caller transfers a freshly constructed, never-mutated signature or a pooled scratch array. */
        static Key owned(long domain, int[] input) {
            return new Key(domain, input, input.length, computeHash(domain, input, input.length), false, false);
        }

        /** Transient lookup view over a caller-owned scratch prefix; detaches before cache retention. */
        static Key view(long domain, int[] input, int length) {
            return new Key(domain, input, length, computeHash(domain, input, length), false, true);
        }

        private Key(long domain, int[] input, int length, int hash, boolean immutable, boolean cacheDetached) {
            this.domain = domain;
            this.input = input;
            this.length = length;
            this.hash = hash;
            this.immutable = immutable;
            this.cacheDetached = cacheDetached;
        }

        private static int computeHash(long domain, int[] input, int length) {
            int arrayHash = 1;
            for (int i = 0; i < length; i++) {
                arrayHash = HASH_MULTIPLIER * arrayHash + input[i];
            }
            return HASH_MULTIPLIER * Long.hashCode(domain) + arrayHash;
        }

        Key detached() {
            if (immutable) {
                return this;
            }
            if (cacheDetached && detachedCache != null) {
                return detachedCache;
            }
            Key copy = new Key(domain, Arrays.copyOf(input, length), length, hash, true, false);
            if (cacheDetached) {
                detachedCache = copy;
            }
            return copy;
        }

        long bytes() {
            return KEY_OVERHEAD_BYTES + INT_BYTES * length;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && domain == key.domain
                    && hash == key.hash
                    && length == key.length
                    && Arrays.equals(input, 0, length, key.input, 0, key.length);
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
        Key storedKey = key.detached();
        long size = size(storedKey, result);
        if (size > MAX_ENTRY_BYTES) return;
        boolean replacing = entries.containsKey(storedKey);
        Result previous = entries.remove(storedKey);
        if (replacing) bytes -= size(storedKey, previous);
        while (!entries.isEmpty() && (entries.size() >= MAX_ENTRIES || bytes + size > MAX_BYTES)) {
            Iterator<Map.Entry<Key, Result>> iterator = entries.entrySet().iterator();
            Map.Entry<Key, Result> oldest = iterator.next();
            bytes -= size(oldest.getKey(), oldest.getValue());
            iterator.remove();
            evictions++;
        }
        entries.put(storedKey, result);
        bytes += size;
    }

    synchronized Statistics statistics() {
        return new Statistics(hits, misses, evictions, entries.size(), bytes, MAX_BYTES);
    }
}
