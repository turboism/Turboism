package dev.turboism.validation.triweave.shadow;

import java.util.concurrent.atomic.AtomicInteger;

/** Fixture-side counterpart of the agent's Counters: ShadowHelper increments, ShadowCapture
 *  samples into every dump record. Same field semantics, different class — the dump schema
 *  is identical across modes. */
public final class ShadowCounters {
    private ShadowCounters() {}

    public static final AtomicInteger NEWBOX_CALLS = new AtomicInteger();
    public static final AtomicInteger QUERIES = new AtomicInteger();
    public static final AtomicInteger HITS = new AtomicInteger();

    public static void reset() {
        NEWBOX_CALLS.set(0); QUERIES.set(0); HITS.set(0);
    }
}
