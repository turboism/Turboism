package dev.turboism.diagnostic.extended;

import com.live2d.cubism.doc.model.deformer.warp.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.CParameterGuid;
import com.live2d.doc.CoordType;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Official Warp kernel scaling and actual bridge refusal; no Editor mutations. */
public final class WarpCoverageProbe {
    public static void main(String[] args) {
        try { run(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static void run() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        NativeMeshInterpolationBridge.enabled(true);
        System.setProperty("turboism.meshInterpolation.coefficients", "true");
        var warp = new CWarpDeformerSource(); warp.setCol(15); warp.setRow(15);
        var extension = new CExtendedInterpolationExtension(warp);
        var guid = new CParameterGuid(new UUID(0, 1));
        System.out.println("type,insertedPerInterval,forms,round,nsPerCall,bytesPerCall");
        for (var type : ExtendedInterpolationType.values()) {
            if (type == ExtendedInterpolationType.LINEAR) continue;
            for (int inserted : new int[]{1, 64}) {
                var all = new ArrayList<CWarpDeformerForm>();
                var sources = new ArrayList<CWarpDeformerForm>();
                var keys = new ArrayList<CExtendedInterpolationExtension.b>();
                int stride = inserted + 1, count = 2 * stride + 1;
                for (int i = 0; i < count; i++) {
                    float angle = -60f + 120f * i / (count - 1);
                    float[] xy = new float[512];
                    for (int p = 0; p < xy.length; p += 2) {
                        xy[p] = (float) (40 * Math.cos(Math.toRadians(angle)) + p * .123);
                        xy[p+1] = (float) (25 * Math.sin(Math.toRadians(angle)) - p * .234);
                    }
                    var form = new CWarpDeformerForm(warp, null, new CoordType("DeformerLocal"));
                    form.setPositions(xy); all.add(form); if (i % stride == 0) sources.add(form);
                    keys.add(new CExtendedInterpolationExtension.b(guid, angle, i % stride != 0, i / stride, (i % stride)/(float)stride));
                }
                int[] before = all.stream().mapToInt(CWarpDeformerForm::getEditVersion).toArray();
                if (NativeMeshInterpolationBridge.tryUpdate(extension, all, sources, keys, type, 1f)) throw new AssertionError("Warp accepted unexpectedly");
                for (int i = 0; i < count; i++) if (all.get(i).getEditVersion() != before[i]) throw new AssertionError("refusal mutation");
                for (int i = 0; i < 1000; i++) extension.updateInterpolatedForms_common(all, sources, keys, type, 1f);
                for (int round = 0; round < 5; round++) {
                    long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), start = System.nanoTime();
                    for (int i = 0; i < 100; i++) extension.updateInterpolatedForms_common(all, sources, keys, type, 1f);
                    long elapsed = System.nanoTime() - start;
                    bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - bytes;
                    if (!Float.isFinite(all.get(1).getPositions()[0])) throw new AssertionError("nonfinite output");
                    System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%.1f,%.1f%n", type.name(), inserted, count, round, elapsed/100d, bytes/100d);
                }
            }
        }
        System.out.println("PASS warpDeclinedWithoutMutation=true refused=" + NativeMeshInterpolationBridge.refusedCalls());
    }
}
