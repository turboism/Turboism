package dev.turboism.plugin.atlasdalsoo.dalsoo;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.*;

public class KernelAllocationBench {
    static final ThreadMXBean MEMORY = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--fallback")) {
            fallback();
            return;
        }
        if (args.length > 0 && args[0].equals("--retention")) {
            retention();
            return;
        }
        System.out.println("shape,count,rotation,abey,medianMs,allocatedMB,placed,hash");
        for (String shape : List.of("lshape", "star")) {
            int count = shape.equals("star") ? 25 : 60;
            for (var rotation : TextureAtlasRotationMode.values()) {
                boolean abey = rotation == TextureAtlasRotationMode.FREE;

                double[] times = new double[7], allocations = new double[7];
                String reference = null;
                int placed = 0;
                for (int run = 0; run < 10; run++) {
                    List<SourcePoly> sources = sources(shape, count);
                    long allocated = MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId());
                    long start = System.nanoTime();
                    var result = new PolygonPack(300, 300, rotation, abey, .7, null)
                        .pack(sources, () -> false, ignored -> {});
                    double elapsed = (System.nanoTime() - start) / 1e6;
                    double bytes = MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
                    String signature = signature(result);
                    if (reference != null && !reference.equals(signature)) throw new AssertionError("nondeterministic");
                    reference = signature;
                    placed = result.outcomes.size();
                    validate(result, sources(shape, count));
                    if (run >= 3) { times[run - 3] = elapsed; allocations[run - 3] = bytes / 1048576; }
                }
                Arrays.sort(times); Arrays.sort(allocations);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(reference.getBytes(StandardCharsets.UTF_8)));
                System.out.printf(Locale.ROOT, "%s,%d,%s,%s,%.3f,%.3f,%d,%s%n",
                    shape, count, rotation, abey, times[3], allocations[3], placed, hash);
            }
        }
    }
    static void fallback() {
        List<SourcePoly> sources = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            double[][] square = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            double[][] buffered = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            sources.add(SourcePoly.builder(i, "fixed-" + i, square, buffered)
                .fixPosition(true).issuedPlaced(true)
                .issuedPosition(new double[]{2 * (i % 64), 2 * (i / 64)}).build());
        }
        double[][] large = {{0, 0}, {5000, 0}, {5000, 5000}, {0, 5000}};
        sources.add(SourcePoly.builder(1000, "overflow", large, large).build());
        long allocated = MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId());
        long start = System.nanoTime();
        var result = new PolygonPack(4096, 4096, TextureAtlasRotationMode.QUARTER, false, .7, null)
            .pack(sources, () -> false, ignored -> {});
        if (result.outcomes.size() != 1000 || !result.unplacedIds.equals(List.of(1000)))
            throw new AssertionError("fallback identity/overflow");
        System.out.printf(Locale.ROOT, "fallbackMs=%.3f allocatedMB=%.3f placed=%d overflow=%s%n",
            (System.nanoTime() - start) / 1e6,
            (MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated) / 1048576.0,
            result.outcomes.size(), result.unplacedIds);
    }
    static void retention() {
        System.out.println("completedRuns,postGcHeapBytes");
        for (int i = 1; i <= 40; i++) {
            var result = new PolygonPack(300, 300, TextureAtlasRotationMode.QUARTER, false, .7, null)
                .pack(sources("lshape", 60), () -> false, ignored -> {});
            if (result.outcomes.size() != 34) throw new AssertionError("retention fixture changed");
            if (i % 5 == 0) {
                result = null;
                System.gc();
                System.out.println(i + "," + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            }
        }
    }
    static List<SourcePoly> sources(String shape, int count) {
        Random r = new Random(97 + count);
        List<SourcePoly> sources = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double w = 16 + r.nextInt(65), h = 16 + r.nextInt(65);
            double[][] pts;
            if (shape.equals("star")) {
                pts = new double[10][2];
                for (int j = 0; j < pts.length; j++) {
                    double radius = (j % 2 == 0 ? 1 : .45) * w / 2;
                    pts[j] = new double[]{w / 2 + radius * Math.cos(j * Math.PI / 5),
                        w / 2 + radius * Math.sin(j * Math.PI / 5)};
                }
            } else pts = new double[][]{{0,0},{w,0},{w,h/2},{w/2,h/2},{w/2,h},{0,h}};
            double[][] buffered = new double[pts.length][2];
            double cy = shape.equals("star") ? w / 2 : h / 2;
            for (int j = 0; j < pts.length; j++) {
                buffered[j][0] = w / 2 + (pts[j][0] - w / 2) * 1.05;
                buffered[j][1] = cy + (pts[j][1] - cy) * 1.05;
            }
            sources.add(SourcePoly.builder(i, "item-" + i, pts, buffered).build());
        }
        return sources;
    }
    static String signature(PolygonPack.Result result) {
        StringBuilder s = new StringBuilder();
        for (var o : result.outcomes) s.append(o.id).append(':').append(Arrays.toString(o.cosSin))
            .append(Arrays.toString(o.trans)).append(';');
        return s.append(result.unplacedIds).toString();
    }
    static void validate(PolygonPack.Result result, List<SourcePoly> sources) {
        List<Area> placed = new ArrayList<>();
        for (var o : result.outcomes) {
            double[][] ring = Geom.rotate(sources.get(o.id).inpts, o.cosSin);
            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < ring.length; i++) {
                double x = ring[i][0] + o.trans[0], y = ring[i][1] + o.trans[1];
                if (x < -1e-5 || y < -1e-5 || x > 300.00001 || y > 300.00001)
                    throw new AssertionError("bounds");
                if (i == 0) path.moveTo(x,y); else path.lineTo(x,y);
            }
            path.closePath(); Area area = new Area(path);
            for (Area other : placed) {
                Area overlap = new Area(area); overlap.intersect(other);
                // Ignore floating point slivers at exact edge contact.
                var b = overlap.getBounds2D();
                if (b.getWidth() * b.getHeight() > 1e-3) throw new AssertionError("overlap");
            }
            placed.add(area);
        }
    }
}
