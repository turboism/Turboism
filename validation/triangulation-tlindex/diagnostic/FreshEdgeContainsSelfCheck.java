package dev.turboism.validation.tlindex.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Own-type proof experiment. No official classes are instantiated or transformed. */
public final class FreshEdgeContainsSelfCheck {
    private static int checks;
    private static long referenceSearches;

    private FreshEdgeContainsSelfCheck() {}

    // Deliberately inherits Object.equals: equal endpoints must not merge identities.
    private static final class Edge {
        final int a;
        final int b;
        Edge(int a, int b) { this.a = a; this.b = b; }
    }

    private static void require(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }

    private static void original(ArrayList<Edge> list, Edge edge, boolean intersects,
            boolean constrained, List<String> trace) {
        trace.add("intersection:" + intersects);
        if (!intersects) return;
        trace.add("constraint:" + constrained);
        if (constrained) return;
        referenceSearches++;
        if (!list.contains(edge)) {
            trace.add("add");
            list.add(edge);
        }
    }

    private static void candidate(ArrayList<Edge> list, Edge edge, boolean intersects,
            boolean constrained, List<String> trace) {
        trace.add("intersection:" + intersects);
        if (!intersects) return;
        trace.add("constraint:" + constrained);
        if (constrained) return;
        trace.add("add");
        list.add(edge);
    }

    private static void sameIdentities(List<Edge> a, List<Edge> b) {
        require(a.size() == b.size());
        for (int i = 0; i < a.size(); i++) require(a.get(i) == b.get(i));
    }

    public static void main(String[] args) {
        Random random = new Random(0xF123ED6EL);
        for (int trial = 0; trial < 96; trial++) {
            ArrayList<Edge> reference = new ArrayList<>();
            ArrayList<Edge> optimized = new ArrayList<>();
            // Seed both with identical stored identities, including duplicate references.
            Edge seed = new Edge(1, 2);
            reference.add(seed); reference.add(seed);
            optimized.add(seed); optimized.add(seed);
            for (int triangle = 0; triangle < 80; triangle++) {
                Edge[] fresh = {new Edge(1, 2), new Edge(2, 1), new Edge(1, 2)};
                require(fresh[0] != fresh[2]);
                for (Edge edge : fresh) {
                    boolean intersects = random.nextBoolean();
                    boolean constrained = random.nextBoolean();
                    ArrayList<String> before = new ArrayList<>();
                    ArrayList<String> after = new ArrayList<>();
                    require(!reference.contains(edge));
                    original(reference, edge, intersects, constrained, before);
                    candidate(optimized, edge, intersects, constrained, after);
                    require(before.equals(after));
                    sameIdentities(reference, optimized);
                }
            }
        }
        // Negative controls establish why exact provenance and identity equality are required.
        ArrayList<Edge> reference = new ArrayList<>();
        ArrayList<Edge> optimized = new ArrayList<>();
        Edge reused = new Edge(3, 4);
        reference.add(reused); optimized.add(reused);
        original(reference, reused, true, false, new ArrayList<>());
        candidate(optimized, reused, true, false, new ArrayList<>());
        require(reference.size() == 1 && optimized.size() == 2);
        record ValueEdge(int a, int b) {}
        ArrayList<ValueEdge> values = new ArrayList<>();
        values.add(new ValueEdge(3, 4));
        require(values.contains(new ValueEdge(3, 4))); // fresh alone is insufficient
        require(referenceSearches > 0);
        System.out.println("Fresh-edge semantic checks PASS: " + checks
                + "; reference searches=" + referenceSearches
                + "; hostExecuted=false; bytecodeTransformTested=false");
    }
}
