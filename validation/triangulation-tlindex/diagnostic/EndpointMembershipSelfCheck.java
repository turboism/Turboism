package dev.turboism.validation.triangulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Random;

/** Owned-only feasibility check. Never transforms or loads a Cubism class. */
public final class EndpointMembershipSelfCheck {
    private EndpointMembershipSelfCheck() { }
    private static int checks;
    private static volatile int sink;

    private static final class Edge {
        private final int a;
        private final int b;
        private Edge(final int a, final int b) { this.a = a; this.b = b; }
    }

    private static long ordered(final int a, final int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    private static long unordered(final Edge edge) {
        return ordered(Math.min(edge.a, edge.b), Math.max(edge.a, edge.b));
    }

    private static boolean nativeScan(final ArrayList<Edge> edges, final Edge query,
                                      final boolean directed) {
        Objects.requireNonNull(query);
        for (final Edge edge : edges) {
            if (edge.a == query.a && edge.b == query.b
                    || !directed && edge.a == query.b && edge.b == query.a) return true;
        }
        return false;
    }

    private static final class Membership {
        private final ArrayList<Edge> edges = new ArrayList<>();
        private HashMap<Long, Integer> oriented;
        private HashMap<Long, Integer> unoriented;
        private boolean escaped;

        private void add(final Edge edge) {
            Objects.requireNonNull(edge);
            edges.add(edge);
            if (oriented != null) change(edge, 1);
        }

        private boolean remove(final Edge edge) {
            Objects.requireNonNull(edge);
            final boolean removed = edges.remove(edge); // Edge has identity equals.
            if (removed && oriented != null) change(edge, -1);
            return removed;
        }

        private void change(final Edge edge, final int delta) {
            changeOne(oriented, ordered(edge.a, edge.b), delta);
            changeOne(unoriented, unordered(edge), delta);
        }

        private static void changeOne(final HashMap<Long, Integer> map,
                                      final long key, final int delta) {
            final int count = map.getOrDefault(key, 0) + delta;
            if (count == 0) map.remove(key);
            else map.put(key, count);
        }

        private boolean contains(final Edge query, final boolean directed) {
            Objects.requireNonNull(query);
            if (escaped || edges.size() < 32) return nativeScan(edges, query, directed);
            if (oriented == null) {
                oriented = new HashMap<>();
                unoriented = new HashMap<>();
                for (final Edge edge : edges) change(edge, 1);
            }
            return directed ? oriented.containsKey(ordered(query.a, query.b))
                    : unoriented.containsKey(unordered(query));
        }

        private ArrayList<Edge> expose() {
            // Escape ends this object's indexed epoch permanently. Size-only checks
            // cannot detect replacements or edits through retained aliases.
            escaped = true;
            oriented = null;
            unoriented = null;
            return edges;
        }

        private Iterator<Edge> iterator() { return expose().iterator(); }
    }

    private static void check(final boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }

    private static void verify(final Membership membership, final Edge query) {
        for (final boolean directed : new boolean[] {true, false}) {
            check(membership.contains(query, directed)
                    == nativeScan(membership.edges, query, directed));
        }
    }

    private static void controls() {
        final Membership m = new Membership();
        final Random random = new Random(52);
        final ArrayList<Edge> live = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            if (live.isEmpty() || random.nextInt(3) != 0) {
                final Edge edge = new Edge(random.nextInt(71) - 35, random.nextInt(71) - 35);
                live.add(edge);
                m.add(edge);
            } else {
                final Edge removed = live.remove(random.nextInt(live.size()));
                check(m.remove(removed));
                check(!m.remove(removed));
            }
            verify(m, new Edge(random.nextInt(71) - 35, random.nextInt(71) - 35));
        }
        final Edge first = new Edge(Integer.MIN_VALUE, Integer.MAX_VALUE);
        final Edge duplicate = new Edge(first.a, first.b);
        m.add(first); m.add(duplicate);
        check(m.contains(first, true));
        check(!m.contains(new Edge(first.b, first.a), true));
        check(m.contains(new Edge(first.b, first.a), false));
        check(m.remove(first)); check(m.contains(first, true));
        check(m.remove(duplicate)); check(!m.contains(first, false));
        final Edge degenerate = new Edge(-1, -1);
        m.add(degenerate); verify(m, degenerate);
        final ArrayList<Edge> alias = m.expose();
        final Edge replacement = new Edge(100000, 200000);
        final int originalSize = alias.size();
        alias.set(0, replacement);
        check(alias.size() == originalSize); verify(m, replacement);
        Collections.reverse(alias); verify(m, replacement);
        alias.subList(0, alias.size() / 2).clear(); verify(m, replacement);
        final Iterator<Edge> iterator = m.iterator();
        while (iterator.hasNext()) { iterator.next(); iterator.remove(); }
        check(alias.isEmpty()); verify(m, replacement);
        alias.add(replacement); alias.add(null);
        check(m.contains(replacement, true)); // Native early return precedes null.
        try {
            m.contains(new Edge(777777, 888888), false);
            throw new AssertionError("native null-element failure was hidden");
        } catch (NullPointerException expected) { checks++; }
        alias.clear();
        try {
            m.contains(null, false);
            throw new AssertionError("native null-argument failure was hidden");
        } catch (NullPointerException expected) { checks++; }
        check(m.oriented == null && m.unoriented == null && m.escaped);
    }

    private static Edge[] queries() {
        final Edge[] values = new Edge[8192];
        for (int i = 0; i < values.length; i++) {
            values[i] = i % 2 == 0 ? new Edge(i % 4096, i % 4096 + 1)
                    : new Edge(-i - 1, -i - 2);
        }
        return values;
    }

    private static long benchmark(final boolean indexed, final Edge[] queries) {
        final long start = System.nanoTime();
        final Membership m = new Membership();
        for (int i = 0; i < 4096; i++) m.add(new Edge(i, i + 1));
        int hits = 0;
        for (final Edge query : queries) {
            if (indexed ? m.contains(query, false) : nativeScan(m.edges, query, false)) hits++;
        }
        sink = hits;
        if (hits != 4096) throw new AssertionError("benchmark result");
        return System.nanoTime() - start;
    }

    private static ArrayList<Edge> build(final Edge[][] triangles, final boolean indexed) {
        final ArrayList<Edge> result = new ArrayList<>();
        final HashSet<Long> seen = indexed ? new HashSet<>() : null;
        for (final Edge[] triangle : triangles) {
            // Match the native builder: obtain all three edges before membership.
            final Edge first = triangle[0], second = triangle[1], third = triangle[2];
            for (final Edge edge : new Edge[] {first, second, third}) {
                Objects.requireNonNull(edge);
                final boolean fresh = seen != null ? seen.add(unordered(edge))
                        : !nativeScan(result, edge, false);
                if (fresh) result.add(edge);
            }
        }
        return result;
    }

    private static Edge[][] triangles(final int count) {
        final Edge[][] result = new Edge[count][3];
        for (int i = 0; i < count; i++) {
            result[i][0] = new Edge(i, i + 1);
            result[i][1] = new Edge(i + 1, i + 2);
            result[i][2] = new Edge(i + 2, i);
        }
        return result;
    }

    private static void builderControls() {
        final Random random = new Random(5203);
        for (int run = 0; run < 100; run++) {
            final Edge[][] input = new Edge[50 + run][3];
            for (final Edge[] triangle : input) {
                for (int i = 0; i < 3; i++) {
                    triangle[i] = new Edge(random.nextInt(31) - 15, random.nextInt(31) - 15);
                }
            }
            final ArrayList<Edge> expected = build(input, false);
            final ArrayList<Edge> actual = build(input, true);
            check(expected.size() == actual.size());
            for (int i = 0; i < expected.size(); i++) check(expected.get(i) == actual.get(i));
        }
        final Edge first = new Edge(Integer.MIN_VALUE, Integer.MAX_VALUE);
        final Edge reversed = new Edge(first.b, first.a);
        final Edge same = new Edge(first.a, first.b);
        final Edge loop = new Edge(-1, -1);
        final ArrayList<Edge> actual = build(new Edge[][] {
                {first, reversed, same}, {loop, first, new Edge(-1, -1)}}, true);
        check(actual.size() == 2 && actual.get(0) == first && actual.get(1) == loop);
        check(build(new Edge[0][3], true).isEmpty());
        for (final boolean indexed : new boolean[] {false, true}) {
            try {
                build(new Edge[][] {{first, null, reversed}}, indexed);
                throw new AssertionError("null failure hidden");
            } catch (NullPointerException expected) { checks++; }
        }
    }

    private static long benchmarkBuilder(final Edge[][] triangles, final boolean indexed) {
        final long start = System.nanoTime();
        final ArrayList<Edge> result = build(triangles, indexed);
        sink = result.size();
        if (sink != triangles.length * 2 + 1) throw new AssertionError("builder result");
        return System.nanoTime() - start;
    }

    public static void main(final String[] args) {
        controls();
        builderControls();
        final Edge[] queries = queries();
        for (int i = 0; i < 24; i++) { benchmark(false, queries); benchmark(true, queries); }
        System.out.println("ownedControls=" + checks + " status=PASS");
        for (int i = 0; i < 5; i++) {
            final long baseline;
            final long candidate;
            if (i % 2 == 0) { baseline = benchmark(false, queries); candidate = benchmark(true, queries); }
            else { candidate = benchmark(true, queries); baseline = benchmark(false, queries); }
            System.out.println("round=" + (i + 1) + " baselineNanos=" + baseline
                    + " candidateNanos=" + candidate + " hits=" + sink);
        }
        final Edge[][] triangles = triangles(2048);
        for (int i = 0; i < 24; i++) {
            benchmarkBuilder(triangles, false); benchmarkBuilder(triangles, true);
        }
        for (int i = 0; i < 5; i++) {
            final long baseline;
            final long candidate;
            if (i % 2 == 0) {
                baseline = benchmarkBuilder(triangles, false);
                candidate = benchmarkBuilder(triangles, true);
            } else {
                candidate = benchmarkBuilder(triangles, true);
                baseline = benchmarkBuilder(triangles, false);
            }
            System.out.println("builderRound=" + (i + 1) + " baselineNanos=" + baseline
                    + " candidateNanos=" + candidate + " orderedEdges=" + sink);
        }
        System.out.println("scope=OWNED_LOCAL_BUILDER_ONLY nativeIntegration=UNPROVEN");
    }
}
