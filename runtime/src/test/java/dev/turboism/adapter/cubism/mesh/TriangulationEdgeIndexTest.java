package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Differential tests for {@link TriangulationEdgeIndex}: every indexed call is compared against
 * a brute-force scan of the backing set, which is what the official method body computes.
 */
final class TriangulationEdgeIndexTest {

    /** Triangle double: index triple drives the index; coordinates drive equality. */
    private static final class Tri {
        final int ia, ib, ic;
        final float[] coords;

        Tri(final int ia, final int ib, final int ic, final float[] coords) {
            this.ia = ia;
            this.ib = ib;
            this.ic = ic;
            this.coords = coords;
        }

        boolean touches(final int ja, final int jb) {
            return pair(ia, ib, ja, jb) || pair(ib, ic, ja, jb) || pair(ic, ia, ja, jb);
        }

        private static boolean pair(final int a, final int b, final int ja, final int jb) {
            return (a == ja && b == jb) || (a == jb && b == ja);
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Tri o) || o.coords.length != coords.length) return false;
            for (int i = 0; i < coords.length; i++) {
                if (Float.compare(coords[i], o.coords[i]) != 0) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return 0; // the official constant: every element lands in one bucket
        }
    }

    /** Official-semantics oracle: the insertion-ordered scan the original body performs. */
    private static List<Tri> scan(final LinkedHashSet<Tri> set, final int ja, final int jb) {
        final List<Tri> hits = new ArrayList<>();
        for (final Tri t : set) {
            if (t.touches(ja, jb)) hits.add(t);
        }
        return hits;
    }

    private static Tri tri(final int seed, final int ia, final int ib, final int ic) {
        return new Tri(ia, ib, ic, new float[] {seed, seed + 0.5f, seed + 1f});
    }

    @Test
    void indexedQueriesMatchTheBruteForceScan() {
        final Random random = new Random(0xC0FFEE);
        for (int trial = 0; trial < 200; trial++) {
            final LinkedHashSet<Tri> set = new LinkedHashSet<>();
            final List<Tri> added = new ArrayList<>();
            // Randomly interleave adds, removes, clears and queries.
            for (int step = 0; step < 300; step++) {
                switch (random.nextInt(10)) {
                    case 0, 1, 2, 3 -> {
                        final Tri t = tri(
                                random.nextInt(500),
                                random.nextInt(12) - 2, // include negative sentinels
                                random.nextInt(12) - 2,
                                random.nextInt(12) - 2);
                        final boolean expect = !set.contains(t);
                        assertEquals(
                                expect,
                                TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic));
                        if (expect) added.add(t);
                    }
                    case 4 -> {
                        if (!added.isEmpty()) {
                            final Tri victim = added.remove(random.nextInt(added.size()));
                            // contains() uses the same equals semantics remove() applies.
                            final boolean expect = set.contains(victim);
                            assertEquals(
                                    expect,
                                    TriangulationEdgeIndex.remove(set, victim));
                        }
                    }
                    case 5 -> {
                        if (random.nextInt(4) == 0) {
                            TriangulationEdgeIndex.clear(set);
                            added.clear();
                        }
                    }
                    default -> {
                        final int ja = random.nextInt(12) - 2;
                        final int jb = random.nextInt(12) - 2;
                        final List<?> hits = TriangulationEdgeIndex.tryQuery(set, ja, jb);
                        // No side-path mutation happens in this trial, so the index must never
                        // decline; a null here would mask a divergence.
                        assertEquals(
                                scan(set, ja, jb),
                                hits,
                                "indexed result diverged from the scan at trial " + trial);
                    }
                }
            }
        }
    }

    @Test
    void duplicateAddRecordsNothingTwice() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri t = tri(1, 1, 2, 3);
        assertTrue(TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic));
        assertFalse(TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic));
        assertEquals(List.of(t), TriangulationEdgeIndex.tryQuery(set, 1, 2));
    }

    @Test
    void negativeAndDegenerateEndpointsAreIndexedCorrectly() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri negative = tri(1, -1, -2, -1);
        final Tri degenerate = tri(2, 7, 7, 7);
        TriangulationEdgeIndex.add(set, negative, negative.ia, negative.ib, negative.ic);
        TriangulationEdgeIndex.add(set, degenerate, degenerate.ia, degenerate.ib, degenerate.ic);
        assertEquals(List.of(negative), TriangulationEdgeIndex.tryQuery(set, -1, -2));
        assertEquals(List.of(negative), TriangulationEdgeIndex.tryQuery(set, -2, -1));
        assertEquals(List.of(degenerate), TriangulationEdgeIndex.tryQuery(set, 7, 7));
        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(set, -1, 7));
    }

    @Test
    void equalObjectsWithDifferentIndicesDeindexTheStoredElement() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri stored = tri(1, 1, 2, 3);
        TriangulationEdgeIndex.add(set, stored, stored.ia, stored.ib, stored.ic);
        // Same coordinates, different endpoint indices: equals says it is the same element.
        final Tri probe = tri(1, 40, 41, 42);
        assertEquals(stored, probe);
        assertTrue(TriangulationEdgeIndex.remove(set, probe));
        assertTrue(set.isEmpty());
        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(set, 1, 2),
                "the stored element's keys must be deindexed");
        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(set, 40, 41),
                "the argument's keys must never be indexed");
    }

    @Test
    void unwovenIteratorRemovalIsCaughtAndRebuiltAround() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri t1 = tri(1, 1, 2, 3);
        final Tri t2 = tri(2, 1, 2, 7);
        TriangulationEdgeIndex.add(set, t1, t1.ia, t1.ib, t1.ic);
        TriangulationEdgeIndex.add(set, t2, t2.ia, t2.ib, t2.ic);
        assertEquals(List.of(t1, t2), TriangulationEdgeIndex.tryQuery(set, 1, 2));

        final Iterator<Tri> iterator = set.iterator();
        iterator.next();
        iterator.remove(); // the side path the index cannot see

        // The size precheck detects the drift, rebuild resyncs (every remaining element has
        // recorded keys), and the answer still matches the brute-force scan.
        assertEquals(List.of(t2), TriangulationEdgeIndex.tryQuery(set, 1, 2));
        assertEquals(scan(set, 1, 2), TriangulationEdgeIndex.tryQuery(set, 2, 1));
    }

    @Test
    void anUnwovenAddMakesTheIndexDeclineForever() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri t1 = tri(1, 1, 2, 3);
        TriangulationEdgeIndex.add(set, t1, t1.ia, t1.ib, t1.ic);
        assertEquals(List.of(t1), TriangulationEdgeIndex.tryQuery(set, 1, 2));

        // The index never saw this element's vertex keys — it can no longer answer faithfully.
        set.add(tri(2, 1, 2, 7));

        assertNull(TriangulationEdgeIndex.tryQuery(set, 1, 2),
                "an element with no recorded keys must decline to the original scan");
        assertNull(TriangulationEdgeIndex.tryQuery(set, 2, 1),
                "the index stays dead once inconsistency is proven");
        // The set itself is unharmed: the official scan still answers.
        assertEquals(List.of(t1, tri(2, 1, 2, 7)), scan(set, 1, 2));
        final var deadState = TriangulationEdgeIndex.st(set);
        TriangulationEdgeIndex.clear(set);
        assertTrue(deadState.keys.isEmpty(), "clear releases recorded triangles even on a dead set");
        assertTrue(deadState.byKey.isEmpty());
        TriangulationEdgeIndex.add(set, t1, t1.ia, t1.ib, t1.ic);
        assertNull(TriangulationEdgeIndex.tryQuery(set, 1, 2),
                "clear must not reactivate an index permanently declined for this set");
        assertTrue(deadState.keys.isEmpty(), "dead states do not retain newly added triangles");
    }

    @Test
    void registryUsesObjectIdentityWithoutHashingOrComparingSets() {
        final class IdentityOnlySet extends LinkedHashSet<Tri> {
            @Override
            public int hashCode() { throw new AssertionError("Set.hashCode must not be called"); }
            @Override
            public boolean equals(final Object other) {
                throw new AssertionError("Set.equals must not be called");
            }
        }
        final var first = new IdentityOnlySet();
        final var second = new IdentityOnlySet();
        final Tri a = tri(1, 1, 2, 3);
        final Tri b = tri(1, 40, 41, 42); // equal coordinates, different index identity
        TriangulationEdgeIndex.add(first, a, 1, 2, 3);
        TriangulationEdgeIndex.add(second, b, 40, 41, 42);
        assertSame(a, TriangulationEdgeIndex.tryQuery(first, 1, 2).get(0));
        assertSame(b, TriangulationEdgeIndex.tryQuery(second, 40, 41).get(0));
        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(second, 1, 2));
        TriangulationEdgeIndex.clear(first);
        TriangulationEdgeIndex.clear(second);
    }

    @Test
    void queuedWeakIdentityKeysReleaseStateOnTheNextRegistryAccess() {
        // Deterministically exercise the ReferenceQueue path without timing a GC.
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        TriangulationEdgeIndex.add(set, tri(1, 1, 2, 3), 1, 2, 3);
        final var state = TriangulationEdgeIndex.st(set);
        synchronized (TriangulationEdgeIndex.class) {
            final var key = TriangulationEdgeIndex.STATES.keySet().stream()
                    .filter(candidate -> candidate.get() == set).findFirst().orElseThrow();
            assertTrue(key.enqueue());
            assertNull(key.get());
            final LinkedHashSet<Tri> other = new LinkedHashSet<>();
            TriangulationEdgeIndex.st(other); // drains the queue
            assertFalse(TriangulationEdgeIndex.STATES.containsKey(key));
            assertFalse(TriangulationEdgeIndex.STATES.containsValue(state));
            TriangulationEdgeIndex.clear(other);
        }
        // The simulated collection removed all recorded keys: a still-live nonempty set
        // must fall back safely, never synthesize an incomplete indexed answer.
        assertNull(TriangulationEdgeIndex.tryQuery(set, 1, 2));
        TriangulationEdgeIndex.clear(set);
    }

    @Test
    void independentSetsCanRegisterQueryAndClearConcurrently() throws Exception {
        final var executor = Executors.newFixedThreadPool(8);
        final List<java.util.concurrent.Future<?>> work = new ArrayList<>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                final int seed = worker;
                work.add(executor.submit(() -> {
                    for (int trial = 0; trial < 250; trial++) {
                        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
                        final Tri t = tri(seed + trial, seed, trial, trial + 1);
                        assertTrue(TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic));
                        assertEquals(scan(set, t.ia, t.ib),
                                TriangulationEdgeIndex.tryQuery(set, t.ia, t.ib));
                        assertTrue(TriangulationEdgeIndex.remove(set, t));
                        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(set, t.ia, t.ib));
                        TriangulationEdgeIndex.clear(set);
                    }
                }));
            }
            for (final var result : work) result.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void clearingASetReleasesItsRegisteredState() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri t = tri(1, 1, 2, 3);
        TriangulationEdgeIndex.add(set, t, 1, 2, 3);
        final var oldState = TriangulationEdgeIndex.st(set);
        TriangulationEdgeIndex.clear(set);
        org.junit.jupiter.api.Assertions.assertNotSame(oldState, TriangulationEdgeIndex.st(set),
                "clear must unregister the old state instead of retaining it for process lifetime");
        assertTrue(oldState.keys.isEmpty());
        assertTrue(oldState.byKey.isEmpty());
    }

    @Test
    void clearEmptiesIndexAndSet() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri t = tri(1, 1, 2, 3);
        TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic);
        TriangulationEdgeIndex.clear(set);
        assertTrue(set.isEmpty());
        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(set, 1, 2));
    }

    @Test
    void resultsAreSnapshots() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri t = tri(1, 1, 2, 3);
        TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic);
        final List<Object> hits = TriangulationEdgeIndex.tryQuery(set, 1, 2);
        hits.clear();
        assertEquals(List.of(t), TriangulationEdgeIndex.tryQuery(set, 1, 2));
    }

    @Test
    void bookkeepingFailuresNeverPropagate() {
        // A null set is rejected inside the bookkeeping guard; it must still decline
        // without leaking that failure into the original scan's control flow.
        assertNull(TriangulationEdgeIndex.tryQuery(null, 1, 2));
    }

    @Test
    void removeReturnsFalseForMissingElements() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri t = tri(1, 1, 2, 3);
        TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic);
        final Tri absent = tri(9, 1, 2, 3); // different coords, same indices
        assertFalse(TriangulationEdgeIndex.remove(set, absent));
        assertEquals(List.of(t), TriangulationEdgeIndex.tryQuery(set, 1, 2));
        assertSame(t, TriangulationEdgeIndex.tryQuery(set, 1, 2).get(0));
    }
}
