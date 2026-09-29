package dev.turboism.validation.triweave;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import com.live2d.graphics3d.editableMesh.triangulation.j;
import com.live2d.graphics3d.editableMesh.triangulation.k;

/**
 * Woven-callsite recorder for the A/B equivalence dump. Invoked once per normal return of
 * the target method (the woven code wraps this call in catch(Throwable)); call sequence is
 * per successful return only. Records the ordered edge endpoint-index sequence as SHA-256
 * over the full canonical encoding plus a bounded raw prefix, to the isolated dump sink.
 *
 * Identical collection code runs in dump-only and dump+weave modes — the mode string is
 * recorded, never branched on. Pure reads only: k.a(), j.a(), j.b(), TriPoint.getIndex().
 */
public final class Capture {
    private Capture() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger CAPTURE_ERRORS = new AtomicInteger();
    /** 0 = unchecked, 1 = resolvable, 2 = missing. */
    private static final AtomicInteger HELPER_STATE = new AtomicInteger();

    public static void onReturn(Object result) {
        int seq = SEQ.incrementAndGet();
        Sink.Params p = Sink.params();
        if (p == null || seq > p.captureN) return;
        try {
            record(seq, result, p);
        } catch (Throwable t) {
            CAPTURE_ERRORS.incrementAndGet();
            StringBuilder sb = new StringBuilder(512);
            head(sb, seq, p);
            Sink.field(sb, "captureError", t.getClass().getName());
            emit(sb);
        }
    }

    private static void record(int seq, Object result, Sink.Params p) {
        k list = (k) result;
        java.util.ArrayList<j> edges = list.a();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder raw = new StringBuilder();
        int count = 0;
        boolean truncated = false;
        for (j edge : edges) {
            int i0 = edge.a().getIndex();
            int i1 = edge.b().getIndex();
            String pair = i0 + "," + i1 + ";";
            md.update(pair.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            count++;
            if (!truncated) {
                if (raw.length() + pair.length() <= Sink.edgesFieldCap() - 32) {
                    raw.append(pair);
                } else {
                    truncated = true;
                }
            }
        }
        StringBuilder sb = new StringBuilder(1024);
        head(sb, seq, p);
        Sink.field(sb, "edgeCount", Integer.toString(count));
        Sink.field(sb, "sha256", HexFormat.of().formatHex(md.digest()));
        Sink.field(sb, "edges", raw.toString(), Sink.edgesFieldCap());
        Sink.field(sb, "edgesTruncated", Boolean.toString(truncated));
        emit(sb);
    }

    /** Fields shared by data and error records; the equality key is seq+sha256. */
    private static void head(StringBuilder sb, int seq, Sink.Params p) {
        Sink.field(sb, "seq", Integer.toString(seq));
        Sink.field(sb, "mode", p.mode);
        Sink.field(sb, "runId", p.runId);
        Sink.field(sb, "phase", p.phase);
        Sink.field(sb, "helperLinked", helperLinked(p));
        Sink.field(sb, "helperQueries", Integer.toString(Counters.QUERIES.get()));
        Sink.field(sb, "newBoxCalls", Integer.toString(Counters.NEWBOX_CALLS.get()));
        Sink.field(sb, "thread", Thread.currentThread().getName());
    }

    private static void emit(StringBuilder sb) {
        Sink.dumpLine(sb.toString());
    }

    /**
     * One-shot resolvability probe of the woven-path helper through this class's loader —
     * records whether the helper could even be linked by the capture callsite's context.
     * Presence of Helper is never required in dump-only mode.
     */
    private static String helperLinked(Sink.Params p) {
        int state = HELPER_STATE.get();
        if (state == 0) {
            try {
                Class.forName(p.helperInternal.replace('/', '.'), false,
                    Capture.class.getClassLoader());
                state = 1;
            } catch (Throwable t) {
                state = 2;
            }
            HELPER_STATE.compareAndSet(0, state);
            state = HELPER_STATE.get();
        }
        return state == 1 ? "true" : "false";
    }

    /** Self-check visibility. */
    public static String status() {
        return "captureSeq=" + SEQ.get() + " captureErrors=" + CAPTURE_ERRORS.get();
    }
}
