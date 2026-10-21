package dev.turboism.plugin.atlasdalsoo.dalsoo;

import com.sun.management.ThreadMXBean;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Single-shot heavy workload. No host; fixed scale; independent geometry validation outside timing. */
public final class HeavyKernelBench {
    private static final double PAGE = 4096;

    public static void main(String[] args) throws Exception {
        final String shape = args[0];
        final int count = Integer.parseInt(args[1]);
        final TextureAtlasRotationMode rotation = TextureAtlasRotationMode.valueOf(args[2]);
        final boolean abey = Boolean.parseBoolean(args[3]);
        final int seconds = Integer.parseInt(args[4]);
        final double fill = Double.parseDouble(args[5]);
        final List<SourcePoly> input = input(shape, count, fill);
        final ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final AtomicLong peak = new AtomicLong();
        final Thread sampler = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    peak.accumulateAndGet(heap(), Math::max);
                    Thread.sleep(25);
                }
            } catch (InterruptedException done) {
                Thread.currentThread().interrupt();
            }
        }, "heavy-heap-sampler");
        sampler.setDaemon(true);
        System.gc();
        final long initialHeap = heap();
        final long gcStart = gcMillis();
        final long allocStart = memory.getThreadAllocatedBytes(Thread.currentThread().getId());
        final long start = System.nanoTime();
        final long deadline = start + seconds * 1_000_000_000L;
        final int[] progress = {0};
        sampler.start();
        PolygonPack.Result result = null;
        boolean cancelled = false;
        try {
            result = new PolygonPack(PAGE, PAGE, rotation, abey, .7, null)
                .pack(input, () -> System.nanoTime() >= deadline, placed -> {
                    progress[0] = placed;
                    if (placed % 100 == 0) System.err.println("placed=" + placed + "/" + count);
                });
        } catch (PackingCancelledException limit) {
            cancelled = true;
        } finally {
            peak.accumulateAndGet(heap(), Math::max);
            sampler.interrupt();
            sampler.join();
        }
        final double elapsedMs = (System.nanoTime() - start) / 1e6;
        final double allocatedMiB = (memory.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocStart) / 1048576.0;
        final long gcMs = gcMillis() - gcStart;
        double coverage = 0;
        String hash = "cancelled";
        if (result != null) {
            coverage = validate(result, input);
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(KernelAllocationBench.signature(result).getBytes(StandardCharsets.UTF_8)));
        }
        if (result != null && args.length > 6) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[6]), layout(result, input, shape, rotation, coverage));
        }
        result = null;
        input.clear();
        System.gc();
        System.out.printf(Locale.ROOT,
            "{\"shape\":\"%s\",\"count\":%d,\"vertices\":%d,\"rotation\":\"%s\",\"abey\":%s,\"targetFill\":%.2f,\"budgetSeconds\":%d,\"status\":\"%s\",\"elapsedMs\":%.3f,\"placed\":%d,\"coverage\":%.6f,\"allocatedMiB\":%.3f,\"sampledPeakHeapMiB\":%.3f,\"initialHeapMiB\":%.3f,\"postGcHeapMiB\":%.3f,\"gcMs\":%d,\"hash\":\"%s\"}%n",
            shape, count, vertices(shape, count), rotation, abey, fill, seconds, cancelled ? "BUDGET_CANCELLED" : "PASS",
            elapsedMs, progress[0], coverage, allocatedMiB, peak.get() / 1048576.0, initialHeap / 1048576.0,
            heap() / 1048576.0, gcMs, hash);
    }

    private static String layout(PolygonPack.Result result, List<SourcePoly> input,
            String shape, TextureAtlasRotationMode rotation, double coverage) {
        final StringBuilder json = new StringBuilder("{\"page\":4096,\"count\":")
            .append(input.size()).append(",\"shape\":\"").append(shape)
            .append("\",\"rotation\":\"").append(rotation).append("\",\"coverage\":")
            .append(coverage).append(",\"placed\":[");
        boolean first = true;
        for (PackOutcome out : result.outcomes) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"id\":").append(out.id).append(",\"points\":[");
            final double[][] points = Geom.rotate(input.get(out.id).inpts, out.cosSin);
            for (int i = 0; i < points.length; i++) {
                if (i > 0) json.append(',');
                json.append('[').append(points[i][0] + out.trans[0]).append(',')
                    .append(points[i][1] + out.trans[1]).append(']');
            }
            json.append("]}");
        }
        json.append("],\"overflow\":").append(result.unplacedIds).append('}');
        return json.toString();
    }

    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }
    private static int vertices(String shape, int count) {
        if (shape.equals("lshape")) return count * 6;
        int total = 0;
        for (int i = 0; i < count; i++) total += i % 3 == 0 ? 32 : i % 3 == 1 ? 6 : 4;
        return total;
    }
    private static List<SourcePoly> input(String shape, int count, double fill) {
        final Random random = new Random(9931 + count);
        final List<double[][]> rings = new ArrayList<>();
        double totalArea = 0;
        for (int i = 0; i < count; i++) {
            final double w = 20 + random.nextInt(120), h = 20 + random.nextInt(120);
            double[][] ring;
            if (!shape.equals("lshape") && i % 3 == 0) {
                ring = new double[32][2];
                for (int v = 0; v < 32; v++) {
                    final double radius = v % 2 == 0 ? 1 : .42;
                    final double angle = v * 2 * Math.PI / 32;
                    ring[v] = new double[]{w / 2 * (1 + radius * Math.cos(angle)),
                        h / 2 * (1 + radius * Math.sin(angle))};
                }
            } else if (shape.equals("lshape") || i % 3 == 1) {
                ring = new double[][]{{0,0},{w,0},{w,h*.3},{w*.3,h*.3},{w*.3,h},{0,h}};
            } else {
                ring = new double[][]{{0,0},{w*.15,0},{w*.15,h*2},{0,h*2}};
            }
            rings.add(ring);
            totalArea += Geom.area(ring);
        }
        final double scale = PAGE * Math.sqrt(fill / totalArea);
        final List<SourcePoly> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double[][] raw = rings.get(i);
            for (double[] p : raw) { p[0] *= scale; p[1] *= scale; }
            final double[] bb = Geom.boundingBox(raw);
            final boolean lshape = shape.equals("lshape") || i % 3 == 1;
            final double cx = bb[2] * (lshape ? .15 : .5), cy = bb[3] * (lshape ? .15 : .5);
            final double[][] buffer = new double[raw.length][2];
            for (int v = 0; v < raw.length; v++) {
                buffer[v][0] = cx + (raw[v][0] - cx) * 1.05;
                buffer[v][1] = cy + (raw[v][1] - cy) * 1.05;
            }
            items.add(SourcePoly.builder(i, "heavy-" + i, raw, buffer).build());
        }
        return items;
    }
    private static double validate(PolygonPack.Result result, List<SourcePoly> input) {
        final List<Area> placed = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        double coverage = 0;
        for (PackOutcome out : result.outcomes) {
            if (!seen.add(out.id)) throw new AssertionError("duplicate identity");
            final double[][] ring = Geom.rotate(input.get(out.id).inpts, out.cosSin);
            final Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < ring.length; i++) {
                final double x = ring[i][0] + out.trans[0], y = ring[i][1] + out.trans[1];
                if (!Double.isFinite(x) || !Double.isFinite(y) || x < -1e-5 || y < -1e-5 || x > PAGE + 1e-5 || y > PAGE + 1e-5)
                    throw new AssertionError("bounds " + out.id);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.closePath();
            final Area area = new Area(path);
            for (Area other : placed) {
                final Area overlap = new Area(area);
                overlap.intersect(other);
                if (area(overlap) > 1e-5) throw new AssertionError("overlap " + out.id);
            }
            placed.add(area);
            coverage += Geom.area(input.get(out.id).inpts);
        }
        for (int id : result.unplacedIds) if (!seen.add(id)) throw new AssertionError("duplicate overflow");
        if (seen.size() != input.size()) throw new AssertionError("missing identity");
        return coverage / (PAGE * PAGE);
    }
    private static double area(Area area) {
        final double[] p = new double[6];
        double x = 0, y = 0, firstX = 0, firstY = 0, sum = 0;
        for (PathIterator it = area.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(p)) {
                case PathIterator.SEG_MOVETO -> { firstX = x = p[0]; firstY = y = p[1]; }
                case PathIterator.SEG_LINETO -> { sum += x * p[1] - p[0] * y; x = p[0]; y = p[1]; }
                case PathIterator.SEG_CLOSE -> sum += x * firstY - firstX * y;
                default -> throw new AssertionError("nonlinear polygon");
            }
        }
        return Math.abs(sum) / 2;
    }
}
