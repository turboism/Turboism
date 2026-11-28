package dev.turboism.adapter.cubism.optimization.meshedit;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides fast array fingerprinting, spatial grid sizing, and weak-identity caching helpers for
 * mesh editing accelerators.
 */
final class MeshArrayFingerprint {

    private static final long HASH_SEED = 0x9E3779B97F4A7C15L;
    private static final long HASH_MULT_A = 0xBF58476D1CE4E5B9L;
    private static final long HASH_MULT_B = 0x94D049BB133111EBL;
    private static final int SHIFT_TOP = 30;
    private static final int SHIFT_MID = 27;
    private static final int SHIFT_LOW = 31;
    private static final int UNROLL_STRIDE = 4;
    private static final long INT_MASK = 0xffffffffL;
    private static final double MIN_CELL_DIMENSION = 1.0e-4;
    private static final int MIN_GRID_SIDE = 4;
    private static final int MAX_GRID_SIDE = 96;

    private MeshArrayFingerprint() {}

    /**
     * Computes a deterministic 64-bit digest over a position array and an index array.
     *
     * <p>Detects in-place array edits without allocating or mutating caller state.
     *
     * @param positions flat vertex coordinates
     * @param indices triangle or edge index array
     * @param step coordinate stride per vertex
     * @return combined 64-bit content digest
     */
    static long fingerprint(final float[] positions, final int[] indices, final int step) {
        long hash = mix(HASH_SEED ^ (((long) positions.length) << SHIFT_TOP) ^ step);
        final int posLen = positions.length;
        int i = 0;
        for (; i + UNROLL_STRIDE <= posLen; i += UNROLL_STRIDE) {
            final long lo = (Float.floatToRawIntBits(positions[i]) & INT_MASK)
                    | ((Float.floatToRawIntBits(positions[i + 1]) & INT_MASK) << Integer.SIZE);
            final long hi = (Float.floatToRawIntBits(positions[i + 2]) & INT_MASK)
                    | ((Float.floatToRawIntBits(positions[i + 3]) & INT_MASK) << Integer.SIZE);
            hash = mix(hash ^ lo);
            hash = mix(hash ^ hi);
        }
        for (; i < posLen; i++) {
            hash = mix(hash ^ (Float.floatToRawIntBits(positions[i]) & INT_MASK));
        }

        final int idxLen = indices.length;
        hash = mix(hash ^ idxLen);
        int j = 0;
        for (; j + UNROLL_STRIDE <= idxLen; j += UNROLL_STRIDE) {
            final long lo = (indices[j] & INT_MASK) | ((indices[j + 1] & INT_MASK) << Integer.SIZE);
            final long hi = (indices[j + 2] & INT_MASK) | ((indices[j + 3] & INT_MASK) << Integer.SIZE);
            hash = mix(hash ^ lo);
            hash = mix(hash ^ hi);
        }
        for (; j < idxLen; j++) {
            hash = mix(hash ^ (indices[j] & INT_MASK));
        }
        return hash;
    }

    /**
     * Computes a uniform grid resolution matching domain aspect ratio and primitive count.
     *
     * @param spanX domain width
     * @param spanY domain height
     * @param itemCount total indexed geometric primitives
     * @return packed {@code [gridWidth, gridHeight]}
     */
    static int[] gridDimensions(final double spanX, final double spanY, final int itemCount) {
        final double safeX = Math.max(spanX, MIN_CELL_DIMENSION);
        final double safeY = Math.max(spanY, MIN_CELL_DIMENSION);
        final double area = safeX * safeY;
        final double targetCellSize = Math.sqrt(area / Math.max(1, itemCount));
        final double clampedCell = Math.max(targetCellSize, MIN_CELL_DIMENSION);
        final int width = clamp((int) Math.ceil(safeX / clampedCell), MIN_GRID_SIDE, MAX_GRID_SIDE);
        final int height = clamp((int) Math.ceil(safeY / clampedCell), MIN_GRID_SIDE, MAX_GRID_SIDE);
        return new int[] {width, height};
    }

    /**
     * Finalizes a 64-bit integer mixing step.
     *
     * @param value input bits
     * @return well-distributed 64-bit hash
     */
    static long mix(final long value) {
        long z = value;
        z = (z ^ (z >>> SHIFT_TOP)) * HASH_MULT_A;
        z = (z ^ (z >>> SHIFT_MID)) * HASH_MULT_B;
        return z ^ (z >>> SHIFT_LOW);
    }

    private static int clamp(final int value, final int min, final int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    /**
     * Thread-safe weak-keyed identity map that releases collected host instances without pinning
     * classloaders or models.
     *
     * @param <V> value type associated with each host object
     */
    static final class WeakIdentityCache<V> {

        private final ConcurrentHashMap<Key, V> entries = new ConcurrentHashMap<>();
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

        V get(final Object key) {
            expunge();
            return entries.get(new Key(key, null));
        }

        V computeIfAbsent(final Object key, final java.util.function.Function<Object, V> factory) {
            expunge();
            final Key lookup = new Key(key, null);
            final V existing = entries.get(lookup);
            if (existing != null) {
                return existing;
            }
            final V created = factory.apply(key);
            if (created == null) {
                return null;
            }
            final Key retained = new Key(key, queue);
            final V raced = entries.putIfAbsent(retained, created);
            return raced != null ? raced : created;
        }

        void remove(final Object key) {
            expunge();
            entries.remove(new Key(key, null));
        }

        private void expunge() {
            Key stale;
            while ((stale = (Key) queue.poll()) != null) {
                entries.remove(stale);
            }
        }

        private static final class Key extends WeakReference<Object> {

            private final int identityHash;

            Key(final Object referent, final ReferenceQueue<Object> queue) {
                super(referent, queue);
                this.identityHash = System.identityHashCode(referent);
            }

            @Override
            public int hashCode() {
                return identityHash;
            }

            @Override
            public boolean equals(final Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof Key key)) {
                    return false;
                }
                final Object target = get();
                return target != null && target == key.get();
            }
        }
    }
}
