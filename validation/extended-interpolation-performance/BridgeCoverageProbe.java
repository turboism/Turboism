package dev.turboism.diagnostic.extended;

import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.CParameterGuid;
import com.live2d.doc.CoordType;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Calls the current repository bridge directly, bypassing installer admission only.
 * Bridge still performs its actual exact artifact/type/input admission.
 * This is kernel coverage evidence, not bootstrap/Editor acceptance. */
public final class BridgeCoverageProbe {
    public static void main(String[] args) {
        try { run(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static void run() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        NativeMeshInterpolationBridge.enabled(true);
        var mesh = new CArtMeshSource();
        var extension = new CExtendedInterpolationExtension(mesh);
        var guid = new CParameterGuid(new UUID(0, 1));
        var all = new ArrayList<CArtMeshForm>();
        var sources = new ArrayList<CArtMeshForm>();
        var keys = new ArrayList<CExtendedInterpolationExtension.b>();
        int stride = 65, count = 2 * stride + 1, vertices = 256;
        for (int i = 0; i < count; i++) {
            boolean inserted = i % stride != 0;
            float angle = -60f + 120f * i / (count - 1);
            float[] xy = new float[vertices * 2];
            for (int p = 0; p < xy.length; p += 2) {
                xy[p] = (float) (40 * Math.cos(Math.toRadians(angle)) + p * .123);
                xy[p+1] = (float) (25 * Math.sin(Math.toRadians(angle)) - p * .234);
            }
            var form = new CArtMeshForm(mesh, 500 + i, .8f, xy, new CoordType("DeformerLocal"));
            all.add(form); if (!inserted) sources.add(form);
            keys.add(new CExtendedInterpolationExtension.b(guid, angle, inserted, i / stride, (i % stride) / (float) stride));
        }
        System.out.println("mode,round,nsPerCall,bytesPerCall");
        for (boolean coefficients : new boolean[]{false, true}) {
            System.setProperty("turboism.meshInterpolation.coefficients", Boolean.toString(coefficients));
            for (int round = 0; round < 10; round++) {
                sources.get(round % 3).getPositions()[0] += .125f;
                int[] before = all.stream().mapToInt(CArtMeshForm::getEditVersion).toArray();
                extension.updateInterpolatedForms_common(all, sources, keys, ExtendedInterpolationType.ELLIPSE, 1f);
                int[] deltas = new int[count];
                float[][] expected = new float[count][];
                for (int i = 0; i < count; i++) {
                    expected[i] = all.get(i).getPositions().clone();
                    deltas[i] = all.get(i).getEditVersion() - before[i];
                    before[i] = all.get(i).getEditVersion();
                }
                if (!NativeMeshInterpolationBridge.tryUpdate(extension, all, sources, keys, ExtendedInterpolationType.ELLIPSE, 1f)) throw new AssertionError("ELLIPSE declined");
                for (int i = 0; i < count; i++) {
                    if (all.get(i).getEditVersion() - before[i] != deltas[i]) throw new AssertionError("edit version delta");
                    for (int p = 0; p < expected[i].length; p++)
                        if (Float.floatToRawIntBits(expected[i][p]) != Float.floatToRawIntBits(all.get(i).getPositions()[p])) throw new AssertionError("coordinate bits");
                }
            }
            for (int i = 0; i < 100; i++) NativeMeshInterpolationBridge.tryUpdate(extension, all, sources, keys, ExtendedInterpolationType.ELLIPSE, 1f);
            measure("bridge-coefficients-" + coefficients, bean, () -> {
                if (!NativeMeshInterpolationBridge.tryUpdate(extension, all, sources, keys, ExtendedInterpolationType.ELLIPSE, 1f)) throw new AssertionError("declined during measurement");
            });
        }
        for (int i = 0; i < 100; i++) extension.updateInterpolatedForms_common(all, sources, keys, ExtendedInterpolationType.ELLIPSE, 1f);
        measure("official", bean, () -> extension.updateInterpolatedForms_common(all, sources, keys, ExtendedInterpolationType.ELLIPSE, 1f));
        int[] versions = all.stream().mapToInt(CArtMeshForm::getEditVersion).toArray();
        if (NativeMeshInterpolationBridge.tryUpdate(extension, all, sources, keys, ExtendedInterpolationType.SNS_CURVE, 1f)) throw new AssertionError("SNS unexpectedly accepted");
        for (int i = 0; i < count; i++) if (versions[i] != all.get(i).getEditVersion()) throw new AssertionError("SNS refusal wrote target");
        System.out.println("PASS ellipseCoordinateBitsAndEditDeltas=true changedSourceRounds=20 snsDeclinedWithoutMutation=true accepted=" + NativeMeshInterpolationBridge.acceptedCalls() + " refused=" + NativeMeshInterpolationBridge.refusedCalls());
    }
    static void measure(String mode, com.sun.management.ThreadMXBean bean, Runnable action) {
        int repeats = 100;
        for (int round = 0; round < 5; round++) {
            long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            for (int i = 0; i < repeats; i++) action.run();
            long elapsed = System.nanoTime() - start;
            bytes = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - bytes;
            System.out.printf(Locale.ROOT, "%s,%d,%.1f,%.1f%n", mode, round, elapsed/(double)repeats, bytes/(double)repeats);
        }
    }
}
