package dev.turboism.validation.triweave.shadow;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import dev.turboism.validation.triweave.Sink;

/**
 * Shadow-profile capture callsite — byte-for-byte the same collection logic as the agent's
 * official-type Capture, typed on ShadowK/ShadowJ/ShadowPoint. Writes into the same Sink
 * with the identical field schema; mode is recorded, never branched on.
 */
public final class ShadowCapture {
    private ShadowCapture() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger CAPTURE_ERRORS = new AtomicInteger();
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
            Sink.dumpLine(sb.toString());
        }
    }

    private static void record(int seq, Object result, Sink.Params p) {
        ShadowK list = (ShadowK) result;
        java.util.ArrayList<ShadowJ> edges = list.items();
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder raw = new StringBuilder();
        int count = 0;
        boolean truncated = false;
        for (ShadowJ edge : edges) {
            int i0 = edge.x().index();
            int i1 = edge.y().index();
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
        Sink.dumpLine(sb.toString());
    }

    private static void head(StringBuilder sb, int seq, Sink.Params p) {
        Sink.field(sb, "seq", Integer.toString(seq));
        Sink.field(sb, "mode", p.mode);
        Sink.field(sb, "runId", p.runId);
        Sink.field(sb, "phase", p.phase);
        Sink.field(sb, "helperLinked", helperLinked(p));
        Sink.field(sb, "helperQueries", Integer.toString(ShadowCounters.QUERIES.get()));
        Sink.field(sb, "newBoxCalls", Integer.toString(ShadowCounters.NEWBOX_CALLS.get()));
        Sink.field(sb, "thread", Thread.currentThread().getName());
    }

    private static String helperLinked(Sink.Params p) {
        int state = HELPER_STATE.get();
        if (state == 0) {
            try {
                Class.forName(p.helperInternal.replace('/', '.'), false,
                    ShadowCapture.class.getClassLoader());
                state = 1;
            } catch (Throwable t) {
                state = 2;
            }
            HELPER_STATE.compareAndSet(0, state);
            state = HELPER_STATE.get();
        }
        return state == 1 ? "true" : "false";
    }
}
