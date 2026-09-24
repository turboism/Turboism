package dev.turboism.adapter.cubism.optimization.uploadelision;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantics of the skipped-frame upload tracker: a baseline is recorded by
 * every executed upload and a repeat may only be suppressed while the
 * frame-skipped flag and the leg gate are both live. Buffer name, byte size,
 * buffer object identity (identity mode) or payload bytes (content mode) and
 * position/limit all participate in the match; context changes, non-skipped
 * frames and lifecycle/exception clears wipe the table. Pass reasons and
 * per-kind counters are reconciled per scenario.
 */
public class SkippedFrameUploadTrackerTest {

    private final Object gl = new Object();
    private final IntBuffer buffer = IntBuffer.allocate(8);

    private boolean upload(final SkippedFrameUploadTracker tracker, final Object context,
                           final int name, final long size, final Object payload,
                           final int position, final int limit,
                           final boolean skipped, final boolean armed) {
        return tracker.consider(context, name, size, payload, position, limit, skipped, armed,
            SkippedFrameUploadTracker.Kind.INDEX);
    }

    private boolean upload(final SkippedFrameUploadTracker tracker) {
        return upload(tracker, gl, 7, 16, buffer, 0, 8, true, true);
    }

    @Test void firstUploadPassesThenMatchingRepeatElides() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        assertFalse(upload(tracker), "first upload establishes the baseline");
        assertTrue(upload(tracker), "identical repeat on a skipped frame elides");
        assertTrue(upload(tracker), "further repeats still elide");
        var stats = tracker.snapshot(true);
        assertEquals(3L, stats.get("calls"));
        assertEquals(1L, stats.get("passed"));
        assertEquals(2L, stats.get("elided"));
        assertEquals(1L, stats.get("passNoBaseline"));
        assertEquals(3L, stats.get("indexCalls"));
        assertEquals(2L, stats.get("indexElided"));
        assertEquals(1L, stats.get("indexPassed"));
        assertEquals(0L, stats.get("floatCalls"));
    }

    @Test void disarmedGateAndNonSkippedFramesAlwaysPass() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        assertFalse(upload(tracker));
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 8, true, false),
            "disarmed gate never elides");
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 8, false, true),
            "non-skipped frame never elides");
        var stats = tracker.snapshot(false);
        assertEquals(3L, stats.get("calls"));
        assertEquals(0L, stats.get("elided"));
        assertEquals(1L, stats.get("nonSkippedClears"));
        assertEquals(2L, stats.get("passGate"),
            "disarmed and non-skipped passes count as gate passes");
    }

    @Test void changedSignatureFieldsEachDefeatTheMatch() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        // Every pass re-records the baseline, so probes interleave with
        // explicit re-baselining back to (7, 16, buffer, 0..8).
        assertFalse(upload(tracker), "first upload establishes the baseline");
        assertFalse(upload(tracker, gl, 7, 32, buffer, 0, 8, true, true),
            "changed byte size passes");
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 8, true, true),
            "re-baseline is also a size mismatch against the recorded 32");
        assertFalse(upload(tracker, gl, 7, 16, IntBuffer.allocate(8), 0, 8, true, true),
            "changed buffer object passes");
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 8, true, true),
            "the previous buffer object is now a mismatch too");
        assertFalse(upload(tracker, gl, 7, 16, buffer, 1, 8, true, true),
            "changed position passes");
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 4, true, true),
            "changed limit passes");
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 8, true, true),
            "re-baseline is a region mismatch against (1,8)→recorded (0,4)");
        assertTrue(upload(tracker), "the re-recorded signature elides");
        assertFalse(upload(tracker, gl, 8, 16, buffer, 0, 8, true, true),
            "changed buffer name passes");
        assertTrue(upload(tracker, gl, 8, 16, buffer, 0, 8, true, true),
            "the just-recorded signature still elides");
        var stats = tracker.snapshot(true);
        assertEquals(11L, stats.get("calls"));
        assertEquals(9L, stats.get("passed"));
        assertEquals(2L, stats.get("elided"));
        assertEquals(2L, stats.get("passSize"));
        assertEquals(2L, stats.get("passBuffer"));
        assertEquals(3L, stats.get("passRegion"), "position, limit and re-baseline");
        assertEquals(2L, stats.get("passNoBaseline"),
            "first upload plus the unseen name 8 baseline");
    }

    @Test void nonSkippedFrameClearsThenRebaselines() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        assertFalse(upload(tracker));
        assertTrue(upload(tracker));
        // Non-skipped frame: first call clears stale entries, then records.
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 8, false, true));
        assertFalse(upload(tracker, gl, 9, 16, buffer, 0, 8, false, true),
            "same non-skipped run does not clear again");
        var mid = tracker.snapshot(true);
        assertEquals(1L, mid.get("nonSkippedClears"),
            "the clear happens once per non-skipped run");
        // Skipped frame resumes: baselines from the real uploads apply.
        assertTrue(upload(tracker, gl, 9, 16, buffer, 0, 8, true, true));
        assertTrue(upload(tracker, gl, 7, 16, buffer, 0, 8, true, true));
    }

    @Test void contextSwitchClearsEverything() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        assertFalse(upload(tracker));
        assertTrue(upload(tracker));
        Object other = new Object();
        assertFalse(upload(tracker, other, 7, 16, buffer, 0, 8, true, true),
            "new context establishes its own baseline");
        assertFalse(upload(tracker, gl, 7, 16, buffer, 0, 8, true, true),
            "returning to the first context cannot reuse cleared entries");
        var stats = tracker.snapshot(true);
        assertEquals(3L, stats.get("contextClears"),
            "initial null→gl adoption, switch away, and switch back each clear");
        assertEquals(1L, stats.get("elided"));
        assertEquals(3L, stats.get("passed"));
    }

    @Test void lifecycleAndExceptionClearsInvalidate() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        assertFalse(upload(tracker));
        tracker.clearedExternally(SkippedFrameUploadTracker.ClearKind.LIFECYCLE);
        assertFalse(upload(tracker), "buffer regeneration invalidates the baseline");
        assertTrue(upload(tracker));
        tracker.clearedExternally(SkippedFrameUploadTracker.ClearKind.EXCEPTION);
        assertFalse(upload(tracker), "an upload exception invalidates the baseline");
        var stats = tracker.snapshot(true);
        assertEquals(1L, stats.get("contextClears"), "initial context adoption");
        assertEquals(1L, stats.get("lifecycleClears"));
        assertEquals(1L, stats.get("exceptionClears"));
        assertEquals(3L, stats.get("clears"));
        assertEquals(3L, stats.get("passed"));
        assertEquals(1L, stats.get("elided"));
    }

    @Test void entriesReflectLiveBaselinesAndCountersReconcile() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        assertFalse(upload(tracker, gl, 1, 16, buffer, 0, 8, true, true));
        assertFalse(upload(tracker, gl, 2, 16, buffer, 0, 8, true, true));
        assertEquals(2L, tracker.snapshot(true).get("entries"));
        assertTrue(upload(tracker, gl, 1, 16, buffer, 0, 8, true, true));
        tracker.clearedExternally(SkippedFrameUploadTracker.ClearKind.EXCEPTION);
        assertEquals(0L, tracker.snapshot(true).get("entries"));
        var stats = tracker.snapshot(true);
        assertEquals(stats.get("calls").longValue(),
            stats.get("elided") + stats.get("passed"),
            "every consult either elides or passes");
    }

    @Test void contentModeElidesEqualBytesAcrossDifferentBufferObjects() {
        SkippedFrameUploadTracker tracker =
            new SkippedFrameUploadTracker(SkippedFrameUploadTracker.Compare.CONTENT);
        FloatBuffer first = FloatBuffer.wrap(new float[]{1f, 2f, 3f, 4f});
        FloatBuffer same = FloatBuffer.wrap(new float[]{1f, 2f, 3f, 4f});
        FloatBuffer different = FloatBuffer.wrap(new float[]{1f, 2f, 9f, 4f});

        assertFalse(upload(tracker, gl, 7, 16, first, 0, 4, true, true),
            "first upload records the snapshot");
        assertTrue(upload(tracker, gl, 7, 16, same, 0, 4, true, true),
            "a different buffer object with equal bytes elides in content mode");
        assertFalse(upload(tracker, gl, 7, 16, different, 0, 4, true, true),
            "different bytes pass and refresh the snapshot");
        assertTrue(upload(tracker, gl, 7, 16,
                FloatBuffer.wrap(new float[]{1f, 2f, 9f, 4f}), 0, 4, true, true),
            "the refreshed snapshot elides");

        var stats = tracker.snapshot(true);
        assertEquals(4L, stats.get("calls"));
        assertEquals(2L, stats.get("elided"));
        assertEquals(2L, stats.get("contentElided"), "both elisions came from compare");
        assertEquals(2L, stats.get("passed"));
        assertEquals(3L, stats.get("compares"),
            "compare runs on every meta-matching consult, identity included");
        assertEquals(16L, stats.get("snapshotBytes"), "one 16-byte snapshot retained");
        assertTrue(stats.get("compareNanos") >= 0L);
        assertEquals(1L, stats.get("passContent"));
        assertEquals(1L, stats.get("passNoBaseline"));
        assertEquals(1L, stats.get("mode"), "content mode marker");
    }

    @Test void contentModeDetectsInPlaceMutationOfTheBaselineBuffer() {
        SkippedFrameUploadTracker tracker =
            new SkippedFrameUploadTracker(SkippedFrameUploadTracker.Compare.CONTENT);
        float[] backing = {5f, 6f, 7f, 8f};
        FloatBuffer shared = FloatBuffer.wrap(backing);

        assertFalse(upload(tracker, gl, 7, 16, shared, 0, 4, true, true));
        backing[1] = -1f;   // host refills the same buffer object in place
        assertFalse(upload(tracker, gl, 7, 16, shared, 0, 4, true, true),
            "identity alone must not elide in content mode — bytes changed");
        var stats = tracker.snapshot(true);
        assertEquals(1L, stats.get("passContent"));
        assertEquals(0L, stats.get("elided"));
    }

    @Test void contentModeComparesHeapSnapshotAgainstDirectSource() {
        SkippedFrameUploadTracker tracker =
            new SkippedFrameUploadTracker(SkippedFrameUploadTracker.Compare.CONTENT);
        // The host keeps direct buffers (A.a allocates with
        // ByteBuffer.allocateDirect); the snapshot is a heap copy. The compare
        // must dispatch on element type, never on the concrete buffer class.
        final java.nio.ByteBuffer directBytes = java.nio.ByteBuffer
            .allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder());
        final IntBuffer direct = directBytes.asIntBuffer();
        for (int i = 0; i < 8; i++) direct.put(100 + i);
        direct.flip();   // the host presents position=0, limit=capacity

        assertFalse(upload(tracker, gl, 7, 32, direct, 0, 8, true, true),
            "direct source records a heap snapshot");
        assertEquals(32L, tracker.snapshot(true).get("snapshotBytes"));

        final java.nio.ByteBuffer repeatBytes = java.nio.ByteBuffer
            .allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder());
        final IntBuffer repeat = repeatBytes.asIntBuffer();
        for (int i = 0; i < 8; i++) repeat.put(100 + i);
        repeat.flip();
        assertTrue(upload(tracker, gl, 7, 32, repeat, 0, 8, true, true),
            "equal direct bytes elide despite the class difference");

        final java.nio.ByteBuffer diffBytes = java.nio.ByteBuffer
            .allocateDirect(8 * 4).order(java.nio.ByteOrder.nativeOrder());
        final IntBuffer diff = diffBytes.asIntBuffer();
        for (int i = 0; i < 8; i++) diff.put(i == 3 ? -1 : 100 + i);
        diff.flip();
        assertFalse(upload(tracker, gl, 7, 32, diff, 0, 8, true, true),
            "differing direct bytes pass");

        var stats = tracker.snapshot(true);
        assertEquals(3L, stats.get("calls"));
        assertEquals(1L, stats.get("elided"));
        assertEquals(2L, stats.get("compares"), "both meta matches ran mismatch");
        assertTrue(stats.get("compareNanos") >= 0L);
        assertEquals(1L, stats.get("passContent"));
        assertEquals(1L, stats.get("contentElided"));
    }

    @Test void tableGrowsBeyondInitialSlotsAndReportsPeak() {
        SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
        final Object context = new Object();
        final int distinct = 2_000;   // above the 512-slot first-measure ceiling
        for (int name = 1; name <= distinct; name++) {
            assertFalse(upload(tracker, context, name, 16,
                payload(context, name), 0, 8, true, true),
                "first sight of name " + name + " records a baseline");
        }
        var mid = tracker.snapshot(true);
        assertEquals((long) distinct, mid.get("entries"));
        assertEquals((long) distinct, mid.get("peakEntries"));
        assertEquals(0L, mid.get("failedInserts"), "growth covered every buffer");
        assertTrue(mid.get("capacity") >= 2048L, "table grew past 512");
        assertTrue(mid.get("grows") >= 2L, "512→1024→2048 growth happened");

        // Every recorded baseline still elides after the growth rehash.
        for (int name = 1; name <= distinct; name++) {
            assertTrue(upload(tracker, context, name, 16,
                payload(context, name), 0, 8, true, true),
                "name " + name + " must still elide after rehashing");
        }
        var stats = tracker.snapshot(true);
        assertEquals((long) distinct, stats.get("elided"));
        assertEquals((long) distinct * 2, stats.get("calls"));
        assertEquals(0L, stats.get("failedInserts"));
    }

    // The identity-mode baseline requires the same buffer object; keep one
    // stable payload per (context, name) so repeats re-send it.
    private final java.util.Map<String, IntBuffer> payloads = new java.util.HashMap<>();
    private IntBuffer payload(final Object context, final int name) {
        return payloads.computeIfAbsent(System.identityHashCode(context) + ":" + name,
            k -> IntBuffer.allocate(8));
    }
}
