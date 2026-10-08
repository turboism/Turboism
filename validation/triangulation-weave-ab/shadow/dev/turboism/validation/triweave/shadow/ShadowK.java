package dev.turboism.validation.triweave.shadow;

import java.util.ArrayList;

/**
 * Shadow analog of the official edge-list type k: live ArrayList, a raw append, and the
 * undirected/directed index-pair linear scan — same contract the official
 * {@code k.a(Lj;)Z} / {@code k.a(Lj;Z)Z} carry, including the Kotlin intrinsic NPE on a
 * null edge. Method names differ from the official ones on purpose (Config-driven shape).
 */
public class ShadowK {
    /** Test instrumentation: real linear query invocations (the un-woven path). */
    public static final java.util.concurrent.atomic.AtomicInteger ORIGINAL_QUERIES
        = new java.util.concurrent.atomic.AtomicInteger();

    private final ArrayList<ShadowJ> a = new ArrayList<>();

    public ShadowK() {}

    public ArrayList<ShadowJ> items() { return a; }

    public boolean add(ShadowJ j) {
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
        return a.add(j);
    }

    public boolean has(ShadowJ j, boolean directed) {
        ORIGINAL_QUERIES.incrementAndGet();
        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(j, "edge");
        for (ShadowJ e : a) {
            if (directed) {
                if (e.x().index() == j.x().index()
                        && e.y().index() == j.y().index()) return true;
            } else {
                if ((e.x().index() == j.x().index()
                        && e.y().index() == j.y().index())
                    || (e.x().index() == j.y().index()
                        && e.y().index() == j.x().index())) return true;
            }
        }
        return false;
    }
}
