package dev.turboism.adapter.cubism.optimization.uploadelision;

import java.util.Map;

/**
 * Pure decision core for the skipped-frame upload elision experiment, kept free
 * of host-class references so its semantics are unit-testable. The bridge
 * extracts {@code (gl, bufferName, byteSize, buffer, position, limit)} and the
 * frame-skipped/armed flags and delegates here.
 *
 * <p>An upload may be suppressed only while {@code skipped && armed} and a
 * recorded baseline exists for the same {@code (gl, name)} with identical
 * {@code size}, identical buffer object and identical {@code position}/
 * {@code limit}. Every executed upload (skipped frame or not) re-records the
 * baseline, so the first qualifying upload always passes. Context changes,
 * buffer lifecycle events, non-skipped frames and observer failures clear the
 * whole table. Entries are stored in a fixed open-addressed table probed at
 * most {@link #PROBES} times — a crowded slot records nothing (undercount).</p>
 */
final class SkippedFrameUploadTracker {

    private static final int SLOTS = 512;
    private static final int PROBES = 8;

    /** Clear provenance, recorded per kind for the close marker. */
    enum ClearKind { CONTEXT, NON_SKIPPED, LIFECYCLE, EXCEPTION }

    private final Entry[] entries = new Entry[SLOTS];
    private Object currentGl;
    private boolean clearedForRun = true;
    private long calls, elided, passed, clears, contextClears, nonSkippedClears,
        lifecycleClears, exceptionClears, observerFailures, occupied;

    private static final class Entry {
        Object gl, buffer;
        int name, position, limit;
        long size;
        boolean occupied;
    }

    SkippedFrameUploadTracker() {
        for (int i = 0; i < SLOTS; i++) entries[i] = new Entry();
    }

    /**
     * Decides one guarded upload call. Returns {@code true} only for a provable
     * repeat under this experiment's rules; every other outcome records the
     * executed upload as the new baseline and returns {@code false}.
     */
    boolean consider(final Object gl, final int name, final long size, final Object buffer,
                     final int position, final int limit,
                     final boolean skipped, final boolean armed) {
        calls++;
        try {
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
            final int slot = locate(gl, name);
            final Entry entry = slot >= 0 ? entries[slot] : null;
            final boolean match = entry != null && entry.occupied && entry.gl == gl
                && entry.name == name && entry.size == size && entry.buffer == buffer
                && entry.position == position && entry.limit == limit;
            if (skipped && armed && match) {
                elided++;
                return true;
            }
            if (slot >= 0) {
                entry.gl = gl;
                entry.name = name;
                entry.size = size;
                entry.buffer = buffer;
                entry.position = position;
                entry.limit = limit;
                if (!entry.occupied) { entry.occupied = true; occupied++; }
            }
            passed++;
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

    private int locate(final Object gl, final int name) {
        int slot = mix(System.identityHashCode(gl) ^ name) & (SLOTS - 1);
        for (int probe = 0; probe < PROBES; probe++, slot = (slot + 1) & (SLOTS - 1)) {
            final Entry entry = entries[slot];
            if (!entry.occupied || (entry.gl == gl && entry.name == name)) return slot;
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
        for (final Entry entry : entries) entry.occupied = false;
        occupied = 0L;
        clears++;
        switch (kind) {
            case CONTEXT -> contextClears++;
            case NON_SKIPPED -> nonSkippedClears++;
            case LIFECYCLE -> lifecycleClears++;
            case EXCEPTION -> exceptionClears++;
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
        result.put("observerFailures", observerFailures);
        result.put("entries", occupied);
        result.put("armed", armed ? 1L : 0L);
        return Map.copyOf(result);
    }
}
