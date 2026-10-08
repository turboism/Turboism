package dev.turboism.adapter.cubism.mesh;

import java.util.LinkedHashSet;

/** Exercises the generated real helper, including native return/exception preservation. */
public final class ContainsDiagnosticSelfCheck {
    private static int checks;

    private static void require(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }

    private static LinkedHashSet<Object> clean(Object value) {
        LinkedHashSet<Object> set = new LinkedHashSet<>();
        TriangulationEdgeIndex.add(set, value, 1, 2, 3);
        require(TriangulationEdgeIndex.tryQuery(set, 1, 2).size() == 1);
        return set;
    }

    private static void counts(int reason, long attempts, long yes, long no) {
        require(ContainsDiagnostic.COUNTS.get(reason * 3) == attempts);
        require(ContainsDiagnostic.COUNTS.get(reason * 3 + 1) == yes);
        require(ContainsDiagnostic.COUNTS.get(reason * 3 + 2) == no);
    }

    public static void main(String[] args) {
        Object value = new String("same");
        LinkedHashSet<Object> set = clean(value);
        require(TriangulationEdgeIndex.contains(set, value));
        counts(0, 1, 1, 0);

        require(TriangulationEdgeIndex.contains(set, new String("same")));
        require(!TriangulationEdgeIndex.contains(set, "absent"));
        counts(5, 2, 1, 1);

        TriangulationEdgeIndex.st(set).dead = true;
        require(TriangulationEdgeIndex.contains(set, value));
        counts(1, 1, 1, 0);

        set = clean(value);
        TriangulationEdgeIndex.st(set).dirty = true;
        require(TriangulationEdgeIndex.contains(set, value));
        counts(2, 1, 1, 0);

        set = clean(value);
        set.iterator().next();
        set.clear(); // deliberately unwoven shrink: diagnostic must classify it
        require(!TriangulationEdgeIndex.contains(set, value));
        counts(3, 1, 0, 1);

        set = clean(value);
        TriangulationEdgeIndex.st(set).keys.clear();
        require(TriangulationEdgeIndex.contains(set, value));
        counts(4, 1, 1, 0);

        try {
            TriangulationEdgeIndex.contains(null, value);
            throw new AssertionError("native null exception swallowed");
        } catch (NullPointerException expected) {
            counts(6, 1, 0, 0);
        }

        final RuntimeException sentinel = new IllegalStateException("native");
        LinkedHashSet<Object> throwing = new LinkedHashSet<>() {
            @Override public boolean contains(Object ignored) { throw sentinel; }
        };
        try {
            TriangulationEdgeIndex.contains(throwing, value);
            throw new AssertionError("native exception swallowed");
        } catch (RuntimeException actual) {
            require(actual == sentinel);
            counts(2, 2, 1, 0);
        }
        System.out.println("CONTAINS_DIAGNOSTIC_SELF_CHECK PASS checks=" + checks);
    }
}
