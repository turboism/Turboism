package dev.turboism.validation.triweave;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared woven-path counters. Helper increments them; Capture samples them into every dump
 * record — identical collection code in both modes, so a woven-mode leg that silently fell
 * back to the original query path is visible as {@code helperQueries=0} in its own dumps.
 * Kept separate from Helper so the dump-only mode never links Helper.
 */
public final class Counters {
    private Counters() {}

    public static final AtomicInteger NEWBOX_CALLS = new AtomicInteger();
    public static final AtomicInteger QUERIES = new AtomicInteger();
    public static final AtomicInteger HITS = new AtomicInteger();
}
