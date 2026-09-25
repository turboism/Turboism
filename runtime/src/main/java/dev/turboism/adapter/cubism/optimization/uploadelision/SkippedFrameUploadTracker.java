package dev.turboism.adapter.cubism.optimization.uploadelision;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Map;

/**
 * Pure decision core for the skipped-frame upload elision experiment, kept free
 * of host-class references so its semantics are unit-testable. The bridge
 * extracts {@code (gl, bufferName, byteSize, buffer, position, limit, kind)}
 * and the frame-skipped/armed flags and delegates here.
 *
 * <p>An upload may be suppressed only while {@code skipped && armed} and a
 * recorded baseline exists for the same {@code (gl, name)}. In
 * {@link Compare#IDENTITY} mode the buffer object identity, byte size and
 * position/limit must match the baseline — no payload bytes are compared.
 * In {@link Compare#CONTENT} mode a matching meta signature is additionally
 * verified by raw-bit comparison against a private snapshot of the last
 * uploaded payload; different buffer objects carrying identical bytes elide
 * the same way, and a same-object in-place refill still passes. Snapshots cost
 * memory: {@code snapshotBytes} reports the live retained payload bytes,
 * {@code snapshotBytesPeak} the high-water mark, and
 * {@code compares}/{@code compareNanos} the comparison work.</p>
 *
 * <p>Every executed upload (skipped frame or not) re-records the baseline, so
 * the first qualifying upload always passes. Context changes, buffer lifecycle
 * events, non-skipped frames and observer failures clear the whole table.
 * Entries live in an open-addressed table that grows from
 * {@link #INITIAL_SLOTS} up to {@link #MAX_SLOTS} on probe exhaustion and
 * rehashes on growth; {@code failedInserts} counts the rare cases where even
 * the capped table had no free slot (undercount, never overcount).</p>
 */
final class SkippedFrameUploadTracker {

    private static final int INITIAL_SLOTS = 512;
    private static final int MAX_SLOTS = 16_384;
    private static final int PROBES = 8;
    /** Default retained-payload ceiling; configurable via the bridge. */
    static final long DEFAULT_SNAPSHOT_BUDGET = 64L * 1024 * 1024;

    /** Wrapper kind for per-side accounting. */
    enum Kind { FLOAT, INDEX }

    /** Baseline matching strategy, selected once at install time. */
    enum Compare {
        /** Object identity + size + region only; no payload reads. */
        IDENTITY,
        /** Meta signature plus raw-bit comparison against a snapshot. */
        CONTENT
    }

    /** Pass provenance, counted per call for the reason breakdown. */
    enum PassReason {
        /** Frame not skipped or the leg gate disarmed. */
        GATE,
        /** No baseline recorded for this {@code (gl, name)}. */
        NO_BASELINE,
        /** Baseline exists but byte size differs. */
        SIZE,
        /** Identity mode: the buffer object differs from the baseline. */
        BUFFER,
        /** Baseline exists but position/limit differs. */
        REGION,
        /** Content mode: meta matched but payload bytes differ. */
        CONTENT
    }

    /** Clear provenance, recorded per kind for the close marker. */
    enum ClearKind { CONTEXT, NON_SKIPPED, LIFECYCLE, EXCEPTION, THREAD }

    private final Compare compare;
    private final long maxSnapshotBytes;
    private Entry[] entries;
    private Object currentGl;
    private Thread observedThread;
    private boolean clearedForRun = true;
    private long calls, elided, passed, clears, contextClears, nonSkippedClears,
        lifecycleClears, exceptionClears, threadClears, observerFailures,
        occupied, snapshotBudgetSkips;
    private long peakOccupied, failedInserts, grows;
    private final long[] kindCalls = new long[Kind.values().length];
    private final long[] kindElided = new long[Kind.values().length];
    private final long[] kindPassed = new long[Kind.values().length];
    private final long[] passReasons = new long[PassReason.values().length];
    private long compares, compareNanos, contentElided, snapshotBytes,
        snapshotBytesPeak;

    private static final class Entry {
        Object gl, buffer, snapshot;
        int name, position, limit;
        long size, snapshotSize;
        boolean occupied;
    }

    SkippedFrameUploadTracker() {
        this(Compare.IDENTITY);
    }

    SkippedFrameUploadTracker(final Compare compare) {
        this(compare, DEFAULT_SNAPSHOT_BUDGET);
    }

    SkippedFrameUploadTracker(final Compare compare, final long maxSnapshotBytes) {
        this.compare = compare;
        this.maxSnapshotBytes = maxSnapshotBytes;
        entries = newTable(INITIAL_SLOTS);
    }

    private static Entry[] newTable(final int slots) {
        final Entry[] table = new Entry[slots];
        for (int i = 0; i < slots; i++) table[i] = new Entry();
        return table;
    }

    /**
     * Decides one guarded upload call. Returns {@code true} only for a provable
     * repeat under this experiment's rules; every other outcome records the
     * executed upload as the new baseline and returns {@code false}.
     */
    boolean consider(final Object gl, final int name, final long size, final Object buffer,
                     final int position, final int limit,
                     final boolean skipped, final boolean armed, final Kind kind) {
        calls++;
        if (kind != null) kindCalls[kind.ordinal()]++;
        try {
            // GL calls are expected on a single render thread; a second thread
            // means an unreviewed sharing pattern — clear and fail open.
            final Thread thread = Thread.currentThread();
            if (observedThread == null) {
                observedThread = thread;
            } else if (observedThread != thread) {
                observedThread = thread;
                observerFailures++;
                clearAll(ClearKind.THREAD);
            }
            if (gl != currentGl) {
                currentGl = gl;
                clearAll(ClearKind.CONTEXT);
            }
            if (!skipped) {
                if (!clearedForRun) {
                    clearedForRun = true;
                    clearAll(ClearKind.NON_SKIPPED);
                }
            } else {
                clearedForRun = false;
            }
            int slot = locate(gl, name);
            if (slot < 0) {
                slot = growAndLocate(gl, name);
                if (slot < 0) {
                    failedInserts++;
                    passed++;
                    if (kind != null) kindPassed[kind.ordinal()]++;
                    passReasons[PassReason.NO_BASELINE.ordinal()]++;
                    return false;
                }
            }
            final Entry entry = entries[slot];
            final boolean matched = entry.occupied && entry.gl == gl
                && entry.name == name;
            final PassReason reason;
            final boolean elide;
            if (!skipped || !armed) {
                reason = PassReason.GATE;
                elide = false;
            } else if (!matched) {
                reason = PassReason.NO_BASELINE;
                elide = false;
            } else if (entry.size != size) {
                reason = PassReason.SIZE;
                elide = false;
            } else if (entry.position != position || entry.limit != limit) {
                reason = PassReason.REGION;
                elide = false;
            } else if (compare == Compare.CONTENT) {
                // Always compare bytes in content mode: the host can refill the
                // same buffer object in place, so identity alone proves nothing.
                reason = null;
                elide = contentEqual(entry, (Buffer) buffer);
                if (!elide) {
                    refreshSnapshot(entry, (Buffer) buffer, size);
                    record(entry, gl, name, size, buffer, position, limit);
                    passed++;
                    if (kind != null) kindPassed[kind.ordinal()]++;
                    passReasons[PassReason.CONTENT.ordinal()]++;
                    return false;
                }
                contentElided++;
            } else if (entry.buffer == buffer) {
                reason = null;
                elide = true;
            } else {
                reason = PassReason.BUFFER;
                elide = false;
            }
            if (elide) {
                elided++;
                if (kind != null) kindElided[kind.ordinal()]++;
                return true;
            }
            if (compare == Compare.CONTENT) {
                refreshSnapshot(entry, (Buffer) buffer, size);
            }
            record(entry, gl, name, size, buffer, position, limit);
            passed++;
            if (kind != null) kindPassed[kind.ordinal()]++;
            passReasons[reason.ordinal()]++;
            return false;
        } catch (Throwable observerFailure) {
            observerFailures++;
            try { clearAll(ClearKind.EXCEPTION); } catch (Throwable ignored) { }
            return false;
        }
    }

    /** External clear notification (buffer lifecycle or upload exception). */
    void clearedExternally(final ClearKind kind) {
        clearAll(kind);
    }

    /** Observer-side bookkeeping failure: count it and clear, never elide. */
    void observerFailed() {
        observerFailures++;
        try { clearAll(ClearKind.EXCEPTION); } catch (Throwable ignored) { }
    }

    private void record(final Entry entry, final Object gl, final int name, final long size,
                        final Object buffer, final int position, final int limit) {
        entry.gl = gl;
        entry.name = name;
        entry.size = size;
        entry.buffer = buffer;
        entry.position = position;
        entry.limit = limit;
        if (!entry.occupied) {
            entry.occupied = true;
            occupied++;
            if (occupied > peakOccupied) peakOccupied = occupied;
        }
    }

    private boolean contentEqual(final Entry entry, final Buffer buffer) {
        final Buffer snapshot = (Buffer) entry.snapshot;
        // Compare by element type: the snapshot is a heap copy while the host
        // payload is a direct buffer — class identity must not be consulted.
        if (snapshot == null || snapshot.remaining() != buffer.remaining()) {
            return false;
        }
        final Buffer current = buffer.duplicate();
        final long started = System.nanoTime();
        try {
            if (snapshot instanceof ByteBuffer s && current instanceof ByteBuffer c) {
                return s.mismatch(c) < 0;
            } else if (snapshot instanceof IntBuffer s && current instanceof IntBuffer c) {
                return s.mismatch(c) < 0;
            } else if (snapshot instanceof FloatBuffer s && current instanceof FloatBuffer c) {
                return floatsBitwiseEqual(s, c);
            } else if (snapshot instanceof ShortBuffer s && current instanceof ShortBuffer c) {
                return s.mismatch(c) < 0;
            } else if (snapshot instanceof LongBuffer s && current instanceof LongBuffer c) {
                return s.mismatch(c) < 0;
            } else if (snapshot instanceof DoubleBuffer s && current instanceof DoubleBuffer c) {
                return doublesBitwiseEqual(s, c);
            } else if (snapshot instanceof CharBuffer s && current instanceof CharBuffer c) {
                return s.mismatch(c) < 0;
            }
            return false;
        } finally {
            compares++;
            compareNanos += System.nanoTime() - started;
        }
    }

    /**
     * Raw-bit comparison: {@link FloatBuffer#mismatch} treats signed zeros and
     * different NaN payloads as equal, although their uploaded bits differ.
     * {@code floatToRawIntBits} preserves the payload bits (and signed zero).
     */
    private static boolean floatsBitwiseEqual(final FloatBuffer a, final FloatBuffer b) {
        for (int i = 0; i < a.remaining(); i++) {
            if (Float.floatToRawIntBits(a.get(a.position() + i))
                != Float.floatToRawIntBits(b.get(b.position() + i))) {
                return false;
            }
        }
        return true;
    }

    /** Raw-bit comparison; see {@link #floatsBitwiseEqual}. */
    private static boolean doublesBitwiseEqual(final DoubleBuffer a, final DoubleBuffer b) {
        for (int i = 0; i < a.remaining(); i++) {
            if (Double.doubleToRawLongBits(a.get(a.position() + i))
                != Double.doubleToRawLongBits(b.get(b.position() + i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Stores a same-element-type heap copy of the buffer's remaining region
     * for later raw-bit comparisons. Capacity is bounded by
     * the table size; snapshots replace older ones per entry.
     */
    private void refreshSnapshot(final Entry entry, final Buffer buffer, final long size) {
        // Retained-payload budget: refuse the copy and keep any older snapshot
        // (stale content just mismatches and passes — fail-open, no eviction).
        if (snapshotBytes - entry.snapshotSize + size > maxSnapshotBytes) {
            snapshotBudgetSkips++;
            return;
        }
        final Buffer duplicate = buffer.duplicate();
        final Buffer copy;
        if (duplicate instanceof ByteBuffer b) {
            copy = ByteBuffer.allocate(b.remaining()).put(b);
        } else if (duplicate instanceof IntBuffer b) {
            copy = IntBuffer.allocate(b.remaining()).put(b);
        } else if (duplicate instanceof FloatBuffer b) {
            copy = FloatBuffer.allocate(b.remaining()).put(b);
        } else if (duplicate instanceof ShortBuffer b) {
            copy = ShortBuffer.allocate(b.remaining()).put(b);
        } else if (duplicate instanceof LongBuffer b) {
            copy = LongBuffer.allocate(b.remaining()).put(b);
        } else if (duplicate instanceof DoubleBuffer b) {
            copy = DoubleBuffer.allocate(b.remaining()).put(b);
        } else if (duplicate instanceof CharBuffer b) {
            copy = CharBuffer.allocate(b.remaining()).put(b);
        } else {
            snapshotBytes -= entry.snapshotSize;
            entry.snapshot = null;
            entry.snapshotSize = 0;
            return;
        }
        copy.flip();
        snapshotBytes += size - entry.snapshotSize;
        if (snapshotBytes > snapshotBytesPeak) snapshotBytesPeak = snapshotBytes;
        entry.snapshot = copy;
        entry.snapshotSize = size;
    }

    private int locate(final Object gl, final int name) {
        int slot = mix(System.identityHashCode(gl) ^ name) & (entries.length - 1);
        for (int probe = 0; probe < PROBES; probe++, slot = (slot + 1) & (entries.length - 1)) {
            final Entry entry = entries[slot];
            if (!entry.occupied || (entry.gl == gl && entry.name == name)) return slot;
        }
        return -1;
    }

    /**
     * Doubles the table (bounded by {@link #MAX_SLOTS}) and rehashes the live
     * entries, then retries {@link #locate}. Returns -1 only at the cap with
     * every probed slot occupied.
     */
    private int growAndLocate(final Object gl, final int name) {
        while (entries.length < MAX_SLOTS) {
            final Entry[] bigger = newTable(Math.min(entries.length * 2, MAX_SLOTS));
            for (final Entry entry : entries) {
                if (!entry.occupied) continue;
                int slot = mix(System.identityHashCode(entry.gl) ^ entry.name)
                    & (bigger.length - 1);
                while (bigger[slot].occupied) slot = (slot + 1) & (bigger.length - 1);
                final Entry moved = bigger[slot];
                moved.gl = entry.gl;
                moved.name = entry.name;
                moved.size = entry.size;
                moved.buffer = entry.buffer;
                moved.position = entry.position;
                moved.limit = entry.limit;
                moved.snapshot = entry.snapshot;
                moved.snapshotSize = entry.snapshotSize;
                moved.occupied = true;
            }
            entries = bigger;
            grows++;
            final int slot = locate(gl, name);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    private static int mix(final int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        return mixed;
    }

    private void clearAll(final ClearKind kind) {
        for (final Entry entry : entries) {
            entry.occupied = false;
            entry.snapshot = null;
            entry.snapshotSize = 0;
        }
        snapshotBytes = 0;
        occupied = 0L;
        clears++;
        switch (kind) {
            case CONTEXT -> contextClears++;
            case NON_SKIPPED -> nonSkippedClears++;
            case LIFECYCLE -> lifecycleClears++;
            case EXCEPTION -> exceptionClears++;
            case THREAD -> threadClears++;
        }
    }

    /** Work counts only; no interaction benefit is inferred from them. */
    Map<String, Long> snapshot(final boolean armed) {
        final Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put("calls", calls);
        result.put("elided", elided);
        result.put("passed", passed);
        result.put("clears", clears);
        result.put("contextClears", contextClears);
        result.put("nonSkippedClears", nonSkippedClears);
        result.put("lifecycleClears", lifecycleClears);
        result.put("exceptionClears", exceptionClears);
        result.put("threadClears", threadClears);
        result.put("observerFailures", observerFailures);
        result.put("snapshotBudgetSkips", snapshotBudgetSkips);
        result.put("entries", occupied);
        result.put("capacity", (long) entries.length);
        result.put("peakEntries", peakOccupied);
        result.put("failedInserts", failedInserts);
        result.put("grows", grows);
        result.put("armed", armed ? 1L : 0L);
        result.put("floatCalls", kindCalls[Kind.FLOAT.ordinal()]);
        result.put("floatElided", kindElided[Kind.FLOAT.ordinal()]);
        result.put("floatPassed", kindPassed[Kind.FLOAT.ordinal()]);
        result.put("indexCalls", kindCalls[Kind.INDEX.ordinal()]);
        result.put("indexElided", kindElided[Kind.INDEX.ordinal()]);
        result.put("indexPassed", kindPassed[Kind.INDEX.ordinal()]);
        result.put("passGate", passReasons[PassReason.GATE.ordinal()]);
        result.put("passNoBaseline", passReasons[PassReason.NO_BASELINE.ordinal()]);
        result.put("passSize", passReasons[PassReason.SIZE.ordinal()]);
        result.put("passBuffer", passReasons[PassReason.BUFFER.ordinal()]);
        result.put("passRegion", passReasons[PassReason.REGION.ordinal()]);
        result.put("passContent", passReasons[PassReason.CONTENT.ordinal()]);
        result.put("compares", compares);
        result.put("compareNanos", compareNanos);
        result.put("contentElided", contentElided);
        result.put("snapshotBytes", snapshotBytes);
        result.put("snapshotBytesPeak", snapshotBytesPeak);
        result.put("mode", compare == Compare.CONTENT ? 1L : 0L);
        return Map.copyOf(result);
    }
}
