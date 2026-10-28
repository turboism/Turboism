package dev.turboism.diagnostic.extended;

import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.CParameterGuid;
import com.live2d.doc.CoordType;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Isolated real-host kernel probe. No Editor startup, hooks, or model files. */
public final class NativePointScalingProbe {
    static volatile float sink;
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var mesh = new CArtMeshSource();
        var extension = new CExtendedInterpolationExtension(mesh);
        var guid = new CParameterGuid(new UUID(0, 1));
        int vertices = Integer.parseInt(args[0]);
        int repeats = Integer.parseInt(args[1]);
        System.out.println("type,vertices,insertedPerInterval,forms,round,repeats,nsPerCall,bytesPerCall");
        for (var type : ExtendedInterpolationType.values()) {
            if (type == ExtendedInterpolationType.LINEAR) continue;
            for (int inserted : new int[]{1, 4, 16, 64}) {
                var all = new ArrayList<CArtMeshForm>();
                var source = new ArrayList<CArtMeshForm>();
                var keys = new ArrayList<CExtendedInterpolationExtension.b>();
                int stride = inserted + 1, count = 2 * stride + 1;
                for (int i = 0; i < count; i++) {
                    boolean extra = i % stride != 0;
                    float angle = -60f + 120f * i / (count - 1);
                    float[] xy = new float[vertices * 2];
                    for (int p = 0; p < xy.length; p += 2) {
                        xy[p] = (float) (40 * Math.cos(Math.toRadians(angle)) + p * .123);
                        xy[p+1] = (float) (25 * Math.sin(Math.toRadians(angle)) - p * .234);
                    }
                    var form = new CArtMeshForm(mesh, 500 + i, .8f, xy, new CoordType("DeformerLocal"));
                    all.add(form); if (!extra) source.add(form);
                    keys.add(new CExtendedInterpolationExtension.b(guid, angle, extra, i / stride, (i % stride) / (float) stride));
                }
                for (int i = 0; i < 1000; i++) extension.updateInterpolatedForms_common(all, source, keys, type, 1f);
                for (int round = 0; round < 5; round++) {
                    long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                    long start = System.nanoTime();
                    for (int i = 0; i < repeats; i++) extension.updateInterpolatedForms_common(all, source, keys, type, 1f);
                    long elapsed = System.nanoTime() - start;
                    long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
                    sink = all.get(1).getPositions()[0];
                    if (!Float.isFinite(sink)) throw new AssertionError("nonfinite output");
                    System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%.1f,%.1f%n", type.name(), vertices, inserted, count, round, repeats, elapsed/(double)repeats, bytes/(double)repeats);
                }
            }
        }
    }
}
