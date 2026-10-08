package dev.turboism.validation.tlindex.diagnostic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/** Own-type semantic experiment, not a host weave or performance acceptance test. */
public final class ContainsAddFusionSelfCheck {
    private static long comparisons;
    private static long checks;

    private static final class Point {
        float x;
        float y;
        final int index;
        Point(float x, float y, int index) { this.x = x; this.y = y; this.index = index; }
        boolean same(Point other) { return x == other.x && y == other.y; }
    }

    private static final class Triangle {
        final Point[] points;
        Triangle(Point a, Point b, Point c) { points = new Point[] {a, b, c}; }
        @Override public int hashCode() { return 0; }
        @Override public boolean equals(Object other) {
            comparisons++;
            if (!(other instanceof Triangle t)) return false;
            // Same six permutations and float == semantics as the reviewed host.
            int[][] permutations = {{0, 1, 2}, {1, 2, 0}, {2, 0, 1},
                                    {2, 1, 0}, {1, 0, 2}, {0, 2, 1}};
            for (int[] p : permutations) {
                if (points[0].same(t.points[p[0]]) && points[1].same(t.points[p[1]])
                        && points[2].same(t.points[p[2]])) return true;
            }
            return false;
        }
    }

    private static final class ListModel {
        final LinkedHashSet<Triangle> triangles = new LinkedHashSet<>();
        int debugAdds;
        void add(Triangle triangle, boolean debug) {
            if (debug) debugAdds++;
            triangles.add(triangle);
        }
        void original(Triangle triangle, boolean debug) {
            if (!triangles.contains(triangle)) add(triangle, debug);
        }
        void candidate(Triangle triangle, boolean debug) {
            // Preserve the original conditional side effect in debug mode.
            if (!debug || !triangles.contains(triangle)) add(triangle, debug);
        }
    }

    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }

    private static void same(ListModel a, ListModel b) {
        require(a.triangles.size() == b.triangles.size());
        require(a.debugAdds == b.debugAdds);
        Iterator<Triangle> left = a.triangles.iterator();
        Iterator<Triangle> right = b.triangles.iterator();
        while (left.hasNext()) require(left.next() == right.next());
        require(!right.hasNext());
    }

    private static Triangle triangle(float x, int index) {
        return new Triangle(new Point(x, 0, index), new Point(x, 1, index + 1),
                            new Point(x + 1, 0, index + 2));
    }

    public static void main(String[] args) {
        long originalComparisons = 0;
        long candidateComparisons = 0;
        for (int seed = 0; seed < 8; seed++) {
            ListModel original = new ListModel();
            ListModel candidate = new ListModel();
            List<Triangle> pool = new ArrayList<>();
            for (int i = 0; i < 96; i++) pool.add(triangle(i, i * 3));
            // Geometric duplicates with distinct indices; signed zero and NaN.
            pool.add(triangle(-0.0f, -9));
            pool.add(triangle(+0.0f, 900));
            pool.add(triangle(Float.NaN, 903));
            pool.add(triangle(Float.NaN, 906));
            Random random = new Random(seed);
            // Force collision-tree size before exercising mutation/removal.
            for (Triangle triangle : pool) {
                original.original(triangle, false);
                candidate.candidate(triangle, false);
            }
            for (int step = 0; step < 2000; step++) {
                Triangle triangle = pool.get(random.nextInt(pool.size()));
                switch (random.nextInt(12)) {
                    case 0 -> {
                        // Mutate stored geometry in both sets via the same identity.
                        triangle.points[random.nextInt(3)].x = random.nextInt(8);
                    }
                    case 1 -> require(original.triangles.remove(triangle)
                                      == candidate.triangles.remove(triangle));
                    case 2 -> {
                        if (!original.triangles.isEmpty()) {
                            Iterator<Triangle> a = original.triangles.iterator();
                            Iterator<Triangle> b = candidate.triangles.iterator();
                            require(a.next() == b.next());
                            a.remove(); b.remove();
                        }
                    }
                    default -> {
                        boolean debug = step % 7 == 0;
                        comparisons = 0;
                        original.original(triangle, debug);
                        originalComparisons += comparisons;
                        comparisons = 0;
                        candidate.candidate(triangle, debug);
                        candidateComparisons += comparisons;
                    }
                }
                same(original, candidate);
                if (step % 503 == 502) {
                    original.triangles.clear(); candidate.triangles.clear();
                    same(original, candidate);
                }
            }
        }
        require(candidateComparisons < originalComparisons);
        System.out.println("FUSION_SEMANTIC_EXPERIMENT PASS checks=" + checks
                + " originalEqualityCalls=" + originalComparisons
                + " candidateEqualityCalls=" + candidateComparisons);
        System.out.println("Own fixture only; no official class execution or host performance verdict.");
    }
}
