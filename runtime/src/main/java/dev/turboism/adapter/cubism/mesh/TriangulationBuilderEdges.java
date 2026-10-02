package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;

/**
 * Method-local undirected endpoint membership for a leased native edge builder.
 * No host objects or global state are retained. A refusal permanently restores
 * the native membership calls for the remainder of that builder invocation.
 */
public final class TriangulationBuilderEdges {
    private static final int MAX_CAPACITY = 1 << 20;
    private long[] table = new long[16];
    private int size;
    private boolean zero;

    private TriangulationBuilderEdges() {}

    /** Create local bookkeeping, or null when ordinary allocation fails. */
    public static TriangulationBuilderEdges create() {
        try { return new TriangulationBuilderEdges(); }
        catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Record an endpoint pair before its native append: 1 means already seen,
     * 0 means first occurrence, and -1 requires the original membership call.
     * The caller must preserve native append ordering and hold a definition lease.
     */
    public static int seen(final TriangulationBuilderEdges state, final int a, final int b) {
        if (state == null || state.table == null) return -1;
        try { return state.record(TriangulationEdgeIndex.key(a, b)); }
        catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            state.table = null;
            return -1;
        }
    }

    private int record(final long key) {
        if (key == 0) {
            if (zero) return 1;
            zero = true;
            return 0;
        }
        int at = slot(table, key);
        if (table[at] == key) return 1;
        if (size >= table.length / 2) {
            if (table.length == MAX_CAPACITY) {
                table = null;
                return -1;
            }
            final long[] grown = new long[table.length * 2];
            for (final long existing : table) {
                if (existing != 0) grown[slot(grown, existing)] = existing;
            }
            table = grown;
            at = slot(table, key);
        }
        table[at] = key;
        size++;
        return 0;
    }

    private static int slot(final long[] values, long key) {
        final long wanted = key;
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        int at = (int) key & (values.length - 1);
        while (values[at] != 0 && values[at] != wanted) at = (at + 1) & (values.length - 1);
        return at;
    }
}
