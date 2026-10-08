package dev.turboism.validation.triweave.shadow;

import java.util.LinkedHashSet;

/**
 * Fixture-side scenario builder + counter readout, so the selfcheck driver only needs one
 * reflective call per operation. All counter state lives in THIS classloader's copies of
 * ShadowK/ShadowCounters — a fresh FixtureLoader gives independent counts.
 */
public final class ShadowWorld {
    private ShadowWorld() {}

    static ShadowPoint pt(int idx) { return new ShadowPoint(idx * 1.5f, idx * -2.5f, idx); }
    static ShadowJ edge(int a, int b) { return new ShadowJ(pt(a), pt(b)); }
    static ShadowL tri(ShadowJ d, ShadowJ e, ShadowJ f) { return new ShadowL(d, e, f); }

    /** Named inputs used by the selfcheck scenarios. */
    public static LinkedHashSet<ShadowL> input(String name) {
        LinkedHashSet<ShadowL> s = new LinkedHashSet<>();
        switch (name) {
            case "empty" -> { /* nothing */ }
            case "single" -> s.add(tri(edge(1, 2), edge(2, 3), edge(1, 3)));
            case "shared" -> {
                s.add(tri(edge(1, 2), edge(2, 3), edge(1, 3)));
                // edge (2,3) reused reversed as (3,2): the undirected membership hit
                s.add(tri(edge(3, 2), edge(2, 4), edge(3, 4)));
            }
            case "nullEdge" -> s.add(new ShadowL(null, edge(1, 2), edge(2, 3)));
            case "getterFault" -> s.add(new ShadowL(null, null, null, 2,
                new RuntimeException("getter-fault-e")));
            default -> throw new IllegalArgumentException("unknown input " + name);
        }
        return s;
    }

    /** Canonical ordered endpoint-index sequence of a returned k ("i0,i1;..."). */
    public static String indexSeq(Object k) {
        StringBuilder sb = new StringBuilder();
        for (ShadowJ e : ((ShadowK) k).items()) {
            sb.append(e.x().index()).append(',').append(e.y().index()).append(';');
        }
        return sb.toString();
    }

    public static String stats() {
        return "newBoxCalls=" + ShadowCounters.NEWBOX_CALLS.get()
            + " queries=" + ShadowCounters.QUERIES.get()
            + " hits=" + ShadowCounters.HITS.get()
            + " originalQueries=" + ShadowK.ORIGINAL_QUERIES.get();
    }

    public static void reset() {
        ShadowCounters.reset();
        ShadowK.ORIGINAL_QUERIES.set(0);
    }
}
