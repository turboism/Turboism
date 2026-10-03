package dev.turboism.adapter.cubism.mesh;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-confined literal endpoint-to-first-slot bookkeeping for a future admitted mesh loop.
 * No host objects, loaders or user equality callbacks enter this primitive table. This class
 * supplies storage only: a complete definition/mutation lease is required before host queries
 * may use it. It is not wired into the production transformer.
 */
final class NativeMeshEdgeTable implements AutoCloseable {
    static final int UNKNOWN = -2;
    static final int ABSENT = -1;
    static final int MAX_ENTRIES = 16_384;
    // Declared buffer reservation, including a conservative table/array header allowance.
    // This is not a JVM heap or RSS limit.
    static final int PROCESS_BUDGET_BYTES = 4 * 1024 * 1024;
    private static final AtomicInteger RESERVED = new AtomicInteger();
    private final int entryLimit;
    private final int bytes;
    private Thread owner;
    private long[] keys;
    private int[] slots;
    private int size;

    private NativeMeshEdgeTable(int entryLimit, int capacity, int bytes) {
        this.entryLimit = entryLimit;
        this.bytes = bytes;
        owner = Thread.currentThread();
        keys = new long[capacity];
        slots = new int[capacity];
        Arrays.fill(slots, ABSENT);
    }

    /** Null requires native fallback before any table allocation. */
    static NativeMeshEdgeTable reserve(int entryLimit) {
        if (entryLimit < 1 || entryLimit > MAX_ENTRIES) return null;
        int capacity = 16;
        while (capacity < 2 * entryLimit) capacity *= 2;
        int bytes = 12 * capacity + 1024;
        for (; ; ) {
            int used = RESERVED.get();
            if (used > PROCESS_BUDGET_BYTES - bytes) return null;
            if (RESERVED.compareAndSet(used, used + bytes)) break;
        }
        try {
            return new NativeMeshEdgeTable(entryLimit, capacity, bytes);
        } catch (RuntimeException | Error failure) {
            RESERVED.addAndGet(-bytes);
            throw failure;
        }
    }

    /**
     * Record the lowest physical list slot for this literal pair. Reversed stored endpoints
     * are distinct. Refused capacity or slot input permanently discards this partial table.
     */
    boolean putFirst(int first, int second, int nativeSlot) {
        if (owner != Thread.currentThread() || keys == null) return false;
        if (nativeSlot < 0) {
            close();
            return false;
        }
        long key = key(first, second);
        int at = position(key);
        if (slots[at] != ABSENT) {
            slots[at] = Math.min(slots[at], nativeSlot);
            return true;
        }
        if (size == entryLimit) {
            close();
            return false;
        }
        keys[at] = key;
        slots[at] = nativeSlot;
        size++;
        return true;
    }

    /** Native slot, ABSENT, or UNKNOWN when the table cannot answer. */
    int find(int first, int second) {
        if (owner != Thread.currentThread() || keys == null) return UNKNOWN;
        return slots[position(key(first, second))];
    }

    private static long key(int first, int second) {
        return ((long) first << 32) | (second & 0xffffffffL);
    }

    private int position(long wanted) {
        long hash = wanted;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        int at = (int) hash & (keys.length - 1);
        while (slots[at] != ABSENT && keys[at] != wanted) at = (at + 1) & (keys.length - 1);
        return at;
    }

    /** Release primitive buffers and the reservation on the owning thread; repeat close is safe. */
    @Override
    public void close() {
        if (owner == null) return;
        if (owner != Thread.currentThread())
            throw new IllegalStateException("mesh edge table belongs to another thread");
        keys = null;
        slots = null;
        owner = null;
        RESERVED.addAndGet(-bytes);
    }

    static int reservedBytes() {
        return RESERVED.get();
    }
}
