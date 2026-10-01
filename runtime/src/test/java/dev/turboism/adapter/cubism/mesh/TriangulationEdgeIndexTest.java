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
        int equalityCalls;

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
            equalityCalls++;
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
    void containsSkipsCollisionEqualityOnlyForKnownLiveIdentities() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final List<Tri> triangles = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            final Tri t = tri(i, i, i + 1, i + 2);
            triangles.add(t);
            TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic);
        }
        TriangulationEdgeIndex.tryQuery(set, 0, 1);
        int nativeEqualityCalls = 0;
        for (final Tri t : triangles) {
            t.equalityCalls = 0;
            assertTrue(set.contains(t));
            nativeEqualityCalls += t.equalityCalls;
            t.equalityCalls = 0;
            assertTrue(TriangulationEdgeIndex.contains(set, t));
            assertEquals(0, t.equalityCalls, "known live identity avoids collision-tree equality");
        }
        assertTrue(nativeEqualityCalls > 0, "native control actually exercised collision equality");
        final Tri equalDifferentIndices = tri(10, 900, 901, 902);
        assertEquals(set.contains(equalDifferentIndices),
                TriangulationEdgeIndex.contains(set, equalDifferentIndices));
        final Tri absent = tri(1000, 0, 1, 2);
        assertFalse(TriangulationEdgeIndex.contains(set, absent));
        triangles.get(10).coords[0] = 999f;
        assertEquals(set.contains(triangles.get(10)),
                TriangulationEdgeIndex.contains(set, triangles.get(10)));
        assertEquals(set.contains(equalDifferentIndices),
                TriangulationEdgeIndex.contains(set, equalDifferentIndices));
    }

    @Test
    void containsFallsBackAfterSideRemovalDirtyStateAndClear() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri removed = tri(1, 1, 2, 3);
        final Tri survivor = tri(2, 3, 4, 5);
        TriangulationEdgeIndex.add(set, removed, 1, 2, 3);
        TriangulationEdgeIndex.add(set, survivor, 3, 4, 5);
        assertTrue(TriangulationEdgeIndex.contains(set, survivor)); // initial dirty state
        TriangulationEdgeIndex.tryQuery(set, 1, 2);
        final Iterator<Tri> iterator = set.iterator();
        assertSame(removed, iterator.next());
        iterator.remove();
        assertFalse(TriangulationEdgeIndex.contains(set, removed));
        final Tri replacement = tri(3, 6, 7, 8);
        TriangulationEdgeIndex.add(set, replacement, 6, 7, 8);
        assertFalse(TriangulationEdgeIndex.contains(set, removed)); // same size, but dirty
        assertTrue(TriangulationEdgeIndex.contains(set, replacement));
        TriangulationEdgeIndex.st(set).dead = true;
        assertEquals(set.contains(tri(2, 90, 91, 92)),
                TriangulationEdgeIndex.contains(set, tri(2, 90, 91, 92)));
        TriangulationEdgeIndex.clear(set);
        assertFalse(TriangulationEdgeIndex.contains(set, survivor));
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
    void removalDoesNotAddAnEqualityScanAheadOfTheNativeOperation() {
        final LinkedHashSet<Tri> nativeSet = new LinkedHashSet<>();
        final LinkedHashSet<Tri> indexedSet = new LinkedHashSet<>();
        final List<Tri> triangles = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            final Tri t = tri(i, 1, 2, i + 3);
            nativeSet.add(t);
            TriangulationEdgeIndex.add(indexedSet, t, t.ia, t.ib, t.ic);
            triangles.add(t);
        }
        TriangulationEdgeIndex.tryQuery(indexedSet, 1, 2);
        final Tri victim = triangles.get(triangles.size() - 1);
        victim.equalityCalls = 0;
        assertTrue(nativeSet.remove(victim));
        final int nativeComparisons = victim.equalityCalls;
        victim.equalityCalls = 0;
        assertTrue(TriangulationEdgeIndex.remove(indexedSet, victim));
        assertEquals(nativeComparisons, victim.equalityCalls,
                "bookkeeping must not repeat the host's expensive element equality");
        assertEquals(new ArrayList<>(nativeSet), TriangulationEdgeIndex.tryQuery(indexedSet, 1, 2));
    }

    @Test
    void consecutiveRemovalsAndReplacementAddsShareOneIdentityScan() {
        final class CountingSet extends LinkedHashSet<Tri> {
            int visits;

            @Override
            public Iterator<Tri> iterator() {
                final Iterator<Tri> actual = super.iterator();
                return new Iterator<>() {
                    @Override public boolean hasNext() { return actual.hasNext(); }
                    @Override public Tri next() { visits++; return actual.next(); }
                    @Override public void remove() { actual.remove(); }
                };
            }
        }
        final var set = new CountingSet();
        final List<Tri> triangles = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            final Tri t = tri(i, 1, 2, i + 3);
            triangles.add(t);
            TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic);
        }
        TriangulationEdgeIndex.tryQuery(set, 1, 2);
        set.visits = 0;
        assertTrue(TriangulationEdgeIndex.remove(set, triangles.get(0)));
        assertTrue(TriangulationEdgeIndex.remove(set, triangles.get(1)));
        final Tri first = tri(1000, 1, 2, 1003);
        final Tri second = tri(1001, 1, 2, 1004);
        assertTrue(TriangulationEdgeIndex.add(set, first, first.ia, first.ib, first.ic));
        assertTrue(TriangulationEdgeIndex.add(set, second, second.ia, second.ib, second.ic));
        final List<?> hits = TriangulationEdgeIndex.tryQuery(set, 1, 2);
        assertEquals(set.size(), set.visits,
                "the native two-remove/two-add sequence needs one survivor traversal");
        assertEquals(scan(set, 1, 2), hits);
        assertSame(first, hits.get(hits.size() - 2));
        assertSame(second, hits.get(hits.size() - 1));
    }

    @Test
    void pendingRemovalCannotShortcutMembershipForAnAbsentIdentity() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri first = tri(1, 1, 2, 3);
        final Tri second = tri(2, 1, 2, 4);
        TriangulationEdgeIndex.add(set, first, 1, 2, 3);
        TriangulationEdgeIndex.add(set, second, 1, 2, 4);
        TriangulationEdgeIndex.tryQuery(set, 1, 2);
        assertTrue(TriangulationEdgeIndex.remove(set, first));
        assertFalse(TriangulationEdgeIndex.contains(set, first));
        assertTrue(TriangulationEdgeIndex.contains(set, second));
        assertEquals(List.of(second), TriangulationEdgeIndex.tryQuery(set, 1, 2));
    }

    @Test
    void pendingIdentityReinsertionKeepsTheActualInsertionOrder() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri first = tri(1, 1, 2, 3);
        final Tri second = tri(2, 1, 2, 4);
        final Tri survivor = tri(3, 1, 2, 5);
        for (final Tri t : List.of(first, second, survivor)) {
            TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic);
        }
        TriangulationEdgeIndex.tryQuery(set, 1, 2);
        assertTrue(TriangulationEdgeIndex.remove(set, first));
        assertTrue(TriangulationEdgeIndex.remove(set, second));
        assertTrue(TriangulationEdgeIndex.add(set, first, 1, 2, 3));
        final List<?> actual = TriangulationEdgeIndex.tryQuery(set, 1, 2);
        assertEquals(2, actual.size());
        assertSame(survivor, actual.get(0));
        assertSame(first, actual.get(1));
    }

    @Test
    void pendingBudgetOverflowAndSideRemovalRebuildFromLiveIdentities() {
        for (final boolean overflow : List.of(false, true)) {
            final LinkedHashSet<Tri> set = new LinkedHashSet<>();
            final List<Tri> triangles = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final Tri t = tri(i, 1, 2, i + 3);
                triangles.add(t);
                TriangulationEdgeIndex.add(set, t, t.ia, t.ib, t.ic);
            }
            TriangulationEdgeIndex.tryQuery(set, 1, 2);
            for (int i = 0; i < (overflow ? 9 : 2); i++) {
                assertTrue(TriangulationEdgeIndex.remove(set, triangles.get(i)));
            }
            if (!overflow) {
                final Iterator<Tri> iterator = set.iterator();
                iterator.next();
                iterator.remove();
            }
            final List<?> actual = TriangulationEdgeIndex.tryQuery(set, 1, 2);
            final List<Tri> expected = new ArrayList<>(set);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
        }
    }

    @Test
    void clearReleasesUnresolvedRemovalReferences() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri first = tri(1, 1, 2, 3);
        final Tri second = tri(2, 1, 2, 4);
        TriangulationEdgeIndex.add(set, first, 1, 2, 3);
        TriangulationEdgeIndex.add(set, second, 1, 2, 4);
        TriangulationEdgeIndex.tryQuery(set, 1, 2);
        final var oldState = TriangulationEdgeIndex.st(set);
        TriangulationEdgeIndex.remove(set, first);
        TriangulationEdgeIndex.remove(set, second);
        TriangulationEdgeIndex.clear(set);
        assertTrue(oldState.keys.isEmpty());
        assertTrue(oldState.byKey.isEmpty());
        for (final Object reference : oldState.pending) assertNull(reference);
        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(set, 1, 2));
    }

    @Test
    void failedPendingMembershipScanPermanentlyDeclinesTheIndex() {
        final class FailingSet extends LinkedHashSet<Tri> {
            boolean fail;
            @Override public Iterator<Tri> iterator() {
                if (fail) throw new IllegalStateException("scan unavailable");
                return super.iterator();
            }
        }
        final var set = new FailingSet();
        final Tri first = tri(1, 1, 2, 3);
        final Tri second = tri(2, 1, 2, 4);
        TriangulationEdgeIndex.add(set, first, 1, 2, 3);
        TriangulationEdgeIndex.add(set, second, 1, 2, 4);
        TriangulationEdgeIndex.tryQuery(set, 1, 2);
        TriangulationEdgeIndex.remove(set, first);
        set.fail = true;
        assertTrue(TriangulationEdgeIndex.contains(set, second)); // native fallback still works
        set.fail = false;
        assertNull(TriangulationEdgeIndex.tryQuery(set, 1, 2));
        TriangulationEdgeIndex.clear(set);
    }

    @Test
    void removalReconcilesStaleIdentityKeysAndEqualReplacement() {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();
        final Tri old = tri(1, 1, 2, 3);
        final Tri survivor = tri(2, 1, 2, 4);
        TriangulationEdgeIndex.add(set, old, 1, 2, 3);
        TriangulationEdgeIndex.add(set, survivor, 1, 2, 4);
        TriangulationEdgeIndex.tryQuery(set, 1, 2);
        final Iterator<Tri> iterator = set.iterator();
        iterator.next();
        iterator.remove();
        final Tri replacement = tri(1, 40, 41, 42);
        TriangulationEdgeIndex.add(set, replacement, 40, 41, 42);
        assertTrue(TriangulationEdgeIndex.remove(set, old));
        assertEquals(List.of(survivor), TriangulationEdgeIndex.tryQuery(set, 1, 2));
        assertEquals(List.of(), TriangulationEdgeIndex.tryQuery(set, 40, 41));
    }

    @Test
    void nativeRemovalWinsWhenStoredEqualityHasMutated() {
        final LinkedHashSet<Tri> nativeSet = new LinkedHashSet<>();
        final LinkedHashSet<Tri> indexedSet = new LinkedHashSet<>();
        final List<Tri> triangles = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            final Tri t = tri(i, 1, 2, i + 3);
            nativeSet.add(t);
            TriangulationEdgeIndex.add(indexedSet, t, t.ia, t.ib, t.ic);
            triangles.add(t);
        }
        TriangulationEdgeIndex.tryQuery(indexedSet, 1, 2);
        // Mutating equality can leave multiple equal identities in an existing hash tree.
        // The exact native victim, not insertion order or the argument identity, must win.
        for (Tri t : triangles) java.util.Arrays.fill(t.coords, 0f);
        final Tri probe = triangles.get(triangles.size() - 1);
        assertEquals(nativeSet.remove(probe), TriangulationEdgeIndex.remove(indexedSet, probe));
        final List<?> actual = TriangulationEdgeIndex.tryQuery(indexedSet, 1, 2);
        final List<Tri> expected = new ArrayList<>(nativeSet);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
    }

    @Test
    void aPendingPairDoesNotGuessVictimsAfterEqualityMutation() {
        final LinkedHashSet<Tri> nativeSet = new LinkedHashSet<>();
        final LinkedHashSet<Tri> indexedSet = new LinkedHashSet<>();
        final List<Tri> triangles = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            final Tri t = tri(i, 1, 2, i + 3);
            triangles.add(t);
            nativeSet.add(t);
            TriangulationEdgeIndex.add(indexedSet, t, t.ia, t.ib, t.ic);
        }
        TriangulationEdgeIndex.tryQuery(indexedSet, 1, 2);
        for (final Tri t : triangles) java.util.Arrays.fill(t.coords, 0f);
        for (int i = 31; i >= 30; i--) {
            final Tri probe = triangles.get(i);
            assertEquals(nativeSet.remove(probe), TriangulationEdgeIndex.remove(indexedSet, probe));
        }
        final List<?> actual = TriangulationEdgeIndex.tryQuery(indexedSet, 1, 2);
        final List<Tri> expected = new ArrayList<>(nativeSet);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
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
