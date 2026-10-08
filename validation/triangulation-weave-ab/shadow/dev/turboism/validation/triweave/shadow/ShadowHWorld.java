package dev.turboism.validation.triweave.shadow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Fixture-side scenario builder for {@link ShadowH}, so the selfcheck driver
 * only needs one reflective call per operation. Inputs are named, deterministic
 * and cover the gate branches (flag/share/contains hit+miss), the Phase-4
 * drain with re-add, and the Phase-5 addAll on the second list.
 */
public final class ShadowHWorld {
    private ShadowHWorld() {}

    static ShadowJ e(int a, int b) {
        return new ShadowJ(new ShadowPoint(a * 1.5f, a * -2.5f, a),
                           new ShadowPoint(b * 1.5f, b * -2.5f, b));
    }

    /** Named inputs -> a ready ShadowH instance. */
    public static ShadowH input(String name) {
        switch (name) {
            case "dmNull" -> {
                return new ShadowH(null, tris(tA()), List.of());
            }
            case "dmEmpty" -> {
                return new ShadowH(new ArrayList<>(), tris(), List.of());
            }
            case "dmEmptyTris" -> {
                return new ShadowH(List.of(e(1, 2)), tris(), List.of());
            }
            case "dmShared" -> {
                List<ShadowJ> edges = new ArrayList<>(List.of(e(2, 5), e(4, 9)));
                LinkedHashSet<ShadowL> tris = tris(tA(), tB());
                // Phase-5 payload merged into the SECOND list post-window.
                List<ShadowJ> extra = List.of(e(40, 41), e(42, 43));
                return new ShadowH(edges, tris, extra);
            }
            case "dmShare" -> {
                // Every candidate shares an endpoint index with the edge ->
                // share-skip branch only.
                return new ShadowH(List.of(e(3, 4)), tris(tA()), List.of());
            }
            default -> throw new IllegalArgumentException("unknown h-input " + name);
        }
    }

    static LinkedHashSet<ShadowL> tris(ShadowL... ts) {
        return new LinkedHashSet<>(List.of(ts));
    }

    /** Triangle with edge index pairs (3,4),(4,5),(3,5). */
    static ShadowL tA() { return new ShadowL(e(3, 4), e(4, 5), e(3, 5)); }
    /** Second triangle (6,7),(7,8),(6,8) — mixed flag/share outcomes vs dmShared edges. */
    static ShadowL tB() { return new ShadowL(e(6, 7), e(7, 8), e(6, 8)); }

    /** Canonical ordered endpoint-index sequence of an edge list. */
    public static String edgeSeq(Object list) {
        if (list == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (Object o : (List<?>) list) {
            ShadowJ e = (ShadowJ) o;
            sb.append(e.x().index()).append(',').append(e.y().index()).append(';');
        }
        return sb.toString();
    }

    public static String stats() {
        return "matchListCreated=" + ShadowMatchList.CREATED.get()
            + " containsCalls=" + ShadowMatchList.CONTAINS_CALLS.get()
            + " addCalls=" + ShadowMatchList.ADD_CALLS.get();
    }

    public static void reset() {
        ShadowMatchList.reset();
    }
}
