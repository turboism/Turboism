package dev.turboism.validation.tlindex.diagnostic;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/** Owned-type structural experiment; it neither loads nor patches official classes. */
public final class LazyEdgeMaterializationSelfCheck {
    private static int checks;
    private static volatile long sink;

    private LazyEdgeMaterializationSelfCheck() {}

    private static final class Point {
        final int index;
        float x;
        float y;
        Point(int index, float x, float y) { this.index = index; this.x = x; this.y = y; }
    }
    private record Triangle(Point a, Point b, Point c) {}
    private static final class Edge {
        final Point a;
        final Point b;
        Edge(Point a, Point b, boolean assertions) {
            validate(a, b, assertions);
            this.a = a;
            this.b = b;
        }
    }
    private record Hit(float x, float y) {}
    private record Input(Edge[] constraints, Triangle[] triangles) {}

    private static void validate(Point a, Point b, boolean assertions) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        boolean distinct = a.index != b.index;
        if (assertions && !distinct) throw new AssertionError("duplicate index");
    }

    // Both experiment legs call this one owned endpoint primitive. It follows the
    // audited native float-operation order; it is not a second optimized geometry implementation.
    private static Hit intersect(Point a, Point b, Point c, Point d) {
        float numerator1 = (d.y - c.y) * (d.x - a.x) - (d.x - c.x) * (d.y - a.y);
        float numerator2 = (b.x - a.x) * (d.y - a.y) - (b.y - a.y) * (d.x - a.x);
        float denominator = (b.x - a.x) * (d.y - c.y) - (b.y - a.y) * (d.x - c.x);
        float t = numerator1 / denominator;
        float u = numerator2 / denominator;
        if (0f <= t && t <= 1f && 0f <= u && u <= 1f) {
            return new Hit(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y));
        }
        return null;
    }

    private static Hit intersect(Edge constraint, Edge edge) {
        return intersect(constraint.a, constraint.b, edge.a, edge.b);
    }

    // An owned stand-in for the unchanged native private predicate. This model
    // does not claim to execute/reimplement the complete native triangulator.
    private static boolean blocked(Edge constraint, Edge edge) {
        return constraint.a == edge.a || constraint.a == edge.b
                || constraint.b == edge.a || constraint.b == edge.b;
    }

    private static void note(List<String> trace, String event) {
        if (trace != null) trace.add(event);
    }

    private static ArrayList<Edge> eager(Input input, boolean assertions, List<String> trace) {
        ArrayList<Edge> result = new ArrayList<>();
        for (Edge constraint : input.constraints) {
            for (Triangle triangle : input.triangles) {
                Edge e1 = new Edge(triangle.a, triangle.b, assertions);
                Edge e2 = new Edge(triangle.b, triangle.c, assertions);
                Edge e3 = new Edge(triangle.c, triangle.a, assertions);
                note(trace, "I1"); Hit h1 = intersect(constraint, e1);
                note(trace, "I2"); Hit h2 = intersect(constraint, e2);
                note(trace, "I3"); Hit h3 = intersect(constraint, e3);
                if (h1 != null) { note(trace, "P1"); if (!blocked(constraint, e1)) result.add(e1); }
                if (h2 != null) { note(trace, "P2"); if (!blocked(constraint, e2)) result.add(e2); }
                if (h3 != null) { note(trace, "P3"); if (!blocked(constraint, e3)) result.add(e3); }
            }
        }
        return result;
    }

    private static ArrayList<Edge> lazy(Input input, boolean assertions, List<String> trace) {
        ArrayList<Edge> result = new ArrayList<>();
        for (Edge constraint : input.constraints) {
            for (Triangle triangle : input.triangles) {
                // Preserve all original checks before any intersection, including rejected edges.
                validate(triangle.a, triangle.b, assertions);
                validate(triangle.b, triangle.c, assertions);
                validate(triangle.c, triangle.a, assertions);
                note(trace, "I1"); Hit h1 = intersect(constraint.a, constraint.b, triangle.a, triangle.b);
                note(trace, "I2"); Hit h2 = intersect(constraint.a, constraint.b, triangle.b, triangle.c);
                note(trace, "I3"); Hit h3 = intersect(constraint.a, constraint.b, triangle.c, triangle.a);
                if (h1 != null) {
                    Edge e1 = new Edge(triangle.a, triangle.b, assertions);
                    note(trace, "P1"); if (!blocked(constraint, e1)) result.add(e1);
                }
                if (h2 != null) {
                    Edge e2 = new Edge(triangle.b, triangle.c, assertions);
                    note(trace, "P2"); if (!blocked(constraint, e2)) result.add(e2);
                }
                if (h3 != null) {
                    Edge e3 = new Edge(triangle.c, triangle.a, assertions);
                    note(trace, "P3"); if (!blocked(constraint, e3)) result.add(e3);
                }
            }
        }
        return result;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
        checks++;
    }

    private static void paired(Input input, boolean assertions) {
        List<String> aTrace = new ArrayList<>(), bTrace = new ArrayList<>();
        ArrayList<Edge> a = eager(input, assertions, aTrace), b = lazy(input, assertions, bTrace);
        require(aTrace.equals(bTrace), "intersection/predicate call order");
        require(a.size() == b.size(), "edge count");
        IdentityHashMap<Edge, Boolean> identities = new IdentityHashMap<>();
        for (int i = 0; i < a.size(); i++) {
            require(a.get(i).a == b.get(i).a && a.get(i).b == b.get(i).b, "ordered endpoint identities");
            require(identities.put(b.get(i), Boolean.TRUE) == null, "duplicate geometry reused edge identity");
        }
    }

    private static void invalid(Input input, boolean assertions) {
        List<String> a = new ArrayList<>(), b = new ArrayList<>();
        Throwable eagerError = failure(() -> eager(input, assertions, a));
        Throwable lazyError = failure(() -> lazy(input, assertions, b));
        require(eagerError.getClass() == lazyError.getClass()
                && Objects.equals(eagerError.getMessage(), lazyError.getMessage()), "constructor failure mismatch");
        require(a.isEmpty() && b.isEmpty(), "intersection ran before failed construction check");
    }

    private static Throwable failure(Runnable call) {
        try { call.run(); } catch (NullPointerException | AssertionError expected) { return expected; }
        throw new IllegalStateException("expected constructor failure");
    }

    private static Input workload(int constraints, int triangles, long seed) {
        return workload(constraints, triangles, seed, false);
    }

    private static Input workload(int constraints, int triangles, long seed, boolean dense) {
        Random random = new Random(seed);
        Edge[] edges = new Edge[constraints];
        Triangle[] faces = new Triangle[triangles];
        for (int i = 0; i < constraints; i++) {
            float x = random.nextFloat() * (dense ? 100f : 10_000f);
            float y = dense ? -10f : random.nextFloat() * 10_000f;
            edges[i] = new Edge(new Point(-2 * i - 1, x, y),
                    new Point(-2 * i - 2, dense ? x : x + 12f, dense ? 110f : y + 8f), true);
        }
        for (int i = 0; i < triangles; i++) {
            float scale = dense ? 100f : 10_000f, side = dense ? 25f : 500f;
            float x = random.nextFloat() * scale, y = random.nextFloat() * scale;
            faces[i] = new Triangle(new Point(3 * i, x, y), new Point(3 * i + 1, x + side, y),
                                   new Point(3 * i + 2, x, y + side));
        }
        return new Input(edges, faces);
    }

    private static void selfcheck() {
        Point a = new Point(0, 0f, 0f), b = new Point(1, 4f, 0f), c = new Point(2, 0f, 4f);
        Edge constraint = new Edge(new Point(-1, 2f, -1f), new Point(-2, 2f, 5f), true);
        Input crossing = new Input(new Edge[] {constraint, constraint}, new Triangle[] {new Triangle(a, b, c)});
        require(eager(crossing, true, null).size() == 4, "positive duplicated-constraint control");
        paired(crossing, true);
        a.x = -0f; b.y = Float.MIN_VALUE; paired(crossing, true);
        a.x = 10f; b.y = 10f; paired(crossing, true); // No cross-call coordinate cache.
        paired(new Input(new Edge[0], crossing.triangles), true);
        paired(new Input(crossing.constraints, new Triangle[0]), true);
        invalid(new Input(new Edge[] {constraint}, new Triangle[] {new Triangle(a, null, c)}), true);
        Point sameIndex = new Point(a.index, 5f, 6f);
        Input duplicate = new Input(new Edge[] {constraint}, new Triangle[] {new Triangle(a, sameIndex, c)});
        invalid(duplicate, true); paired(duplicate, false);
        float[] special = {0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE,
                           Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN};
        for (float x : special) for (float y : special) {
            paired(new Input(new Edge[] {constraint}, new Triangle[] {
                    new Triangle(new Point(0, x, y), new Point(1, y, x), new Point(2, -x, -y))}), true);
        }
        for (int i = 0; i < 500; i++) paired(workload(4, 6, i), (i & 1) == 0);
        System.out.println("LAZY_EDGE_OWN_SELFCHECK PASS checks=" + checks + " officialClassesExecuted=false");
    }

    private record Sample(long wall, long cpu, long allocated, long gcCount) {}
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
    }
    private static Sample sample(Input input, boolean lazy, com.sun.management.ThreadMXBean bean) {
        long tid = Thread.currentThread().getId();
        long gc = gcCount(), allocated = bean.getThreadAllocatedBytes(tid);
        long cpu = bean.getCurrentThreadCpuTime(), wall = System.nanoTime();
        for (int i = 0; i < 12; i++) {
            ArrayList<Edge> result = lazy ? lazy(input, false, null) : eager(input, false, null);
            long value = result.size();
            for (Edge edge : result) value = value * 31 + edge.a.index * 17L + edge.b.index;
            sink = value;
        }
        long elapsedWall = System.nanoTime() - wall, elapsedCpu = bean.getCurrentThreadCpuTime() - cpu;
        long bytes = bean.getThreadAllocatedBytes(tid) - allocated;
        return new Sample(elapsedWall, elapsedCpu, bytes, gcCount() - gc);
    }

    private static long median(Sample[] samples, int field) {
        long[] values = Arrays.stream(samples).mapToLong(s -> field == 0 ? s.wall : field == 1 ? s.cpu : s.allocated).sorted().toArray();
        return values[values.length / 2];
    }

    private static void bench(boolean dense) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        require(bean.isThreadCpuTimeSupported() && bean.isThreadAllocatedMemorySupported(), "measurement unavailable");
        if (!bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        Input input = workload(64, 512, 42, dense);
        paired(input, false);
        require(!eager(input, false, null).isEmpty(), "workload has no successful append");
        for (int i = 0; i < 24; i++) { sample(input, false, bean); sample(input, true, bean); }
        Sample[] eager = new Sample[7], lazy = new Sample[7];
        for (int i = 0; i < 7; i++) {
            if ((i & 1) == 0) { eager[i] = sample(input, false, bean); lazy[i] = sample(input, true, bean); }
            else { lazy[i] = sample(input, true, bean); eager[i] = sample(input, false, bean); }
        }
        System.out.printf(Locale.ROOT, "{\"status\":\"OWN_STRUCTURAL_EXPERIMENT_MEASURED\","
                + "\"workload\":\"%s\",\"appendedEdgesPerOperation\":%d,\"eager\":[%n",
                dense ? "dense" : "sparse", eager(input, false, null).size());
        printSamples(eager); System.out.println("],\"lazy\":["); printSamples(lazy);
        System.out.printf(Locale.ROOT, "],\"medians\":{\"eagerWallNanos\":%d,\"lazyWallNanos\":%d,"
                + "\"eagerCpuNanos\":%d,\"lazyCpuNanos\":%d,\"eagerAllocatedBytes\":%d,\"lazyAllocatedBytes\":%d},"
                + "\"officialClassesExecuted\":false,\"hostPerformanceAcceptance\":false,"
                + "\"scope\":\"12 owned stencil operations per sample after 24 alternating warmup pairs; input construction excluded; output list/checksum and result-vector allocation included; one Java thread; default JVM options; 7 alternating samples\"}%n",
                median(eager, 0), median(lazy, 0), median(eager, 1), median(lazy, 1), median(eager, 2), median(lazy, 2));
    }

    private static void printSamples(Sample[] samples) {
        for (int i = 0; i < samples.length; i++) {
            Sample s = samples[i];
            System.out.printf(Locale.ROOT, "%s{\"wallNanos\":%d,\"cpuNanos\":%d,\"allocatedBytes\":%d,\"gcCount\":%d}%n",
                              i == 0 ? "" : ",", s.wall, s.cpu, s.allocated, s.gcCount);
        }
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--bench")) bench(false);
        else if (args.length == 2 && args[0].equals("--bench") && args[1].equals("dense")) bench(true);
        else if (args.length == 0) selfcheck();
        else throw new IllegalArgumentException("expected no arguments or --bench");
    }
}
