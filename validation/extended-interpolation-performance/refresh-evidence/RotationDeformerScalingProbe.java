package dev.turboism.probe;

import com.live2d.cubism.doc.model.deformer.rotation.*;
import com.live2d.cubism.doc.model.deformer.warp.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.CParameterGuid;
import com.live2d.doc.CoordType;
import com.live2d.graphics3d.type.GVector2;

import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * Isolated dynamic probe for Cubism Editor official classes.
 * Measures CRotationDeformerForm vs CWarpDeformerForm scaling across inserted points.
 */
public final class RotationDeformerScalingProbe {
    public static void main(String[] args) {
        try {
            System.out.println("=== ROTATION DEFORMER SCALING & BEHAVIOR PROBE (Cubism 5.3.04) ===");
            verifyRotationDeformerPointStructure();
            benchmarkRotationDeformerScaling();
            System.out.println("\n=== ROTATION DEFORMER PROBE COMPLETED SUCCESSFULLY ===");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void verifyRotationDeformerPointStructure() {
        System.out.println("\n--- Step 1: Verify Point References & Field Mapping ---");
        var rotSource = new CRotationDeformerSource();
        var form = new CRotationDeformerForm(rotSource, null, new CoordType("Canvas"));
        form.setOriginX(120.0f);
        form.setOriginY(340.0f);
        form.setAngle(30.0f);
        form.setScale(1.2f);

        var pointRefs = form.getAllPointRef();
        System.out.println("CRotationDeformerForm.getAllPointRef().size() = " + pointRefs.size());
        if (pointRefs.size() != 1) {
            throw new AssertionError("CRotationDeformerForm.getAllPointRef() must have size 1, got: " + pointRefs.size());
        }
        GVector2 originPos = pointRefs.get(0).getPos();
        System.out.printf(Locale.ROOT, "PointRef[0].getPos() = [%.3f, %.3f], originX=%.3f, originY=%.3f%n",
                originPos.getX(), originPos.getY(), form.getOriginX(), form.getOriginY());
        if (Math.abs(originPos.getX() - 120.0f) > 1e-4 || Math.abs(originPos.getY() - 340.0f) > 1e-4) {
            throw new AssertionError("Origin position mismatch");
        }
        System.out.println("Confirmed: RotationDeformerForm has exactly 1 point reference pointing to (originX, originY).");
    }

    private static void benchmarkRotationDeformerScaling() {
        System.out.println("\n--- Step 2: Scaling Benchmark Across Inserted Points (ELLIPSE & SNS) ---");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);

        var rotSource = new CRotationDeformerSource();
        var ext = new CExtendedInterpolationExtension(rotSource);
        var paramGuid = new CParameterGuid(new UUID(10, 20));

        System.out.printf(Locale.ROOT, "%-16s | %-10s | %-12s | %-10s | %-12s | %-14s%n",
                "Object Type", "Type", "Inserted/Int", "Forms Count", "Time (ms)", "Alloc (bytes)");
        System.out.println("-----------------+------------+--------------+------------+--------------+---------------");

        for (var type : new ExtendedInterpolationType[]{ExtendedInterpolationType.ELLIPSE, ExtendedInterpolationType.SNS_CURVE}) {
            for (int inserted : new int[]{1, 4, 16, 64}) {
                int stride = inserted + 1;
                int count = 2 * stride + 1;
                var allForms = new ArrayList<CRotationDeformerForm>();
                var sourceForms = new ArrayList<CRotationDeformerForm>();
                var keys = new ArrayList<CExtendedInterpolationExtension.b>();

                for (int i = 0; i < count; i++) {
                    boolean isExtra = (i % stride != 0);
                    float paramVal = -60f + 120f * i / (count - 1);
                    var f = new CRotationDeformerForm(rotSource, null, new CoordType("Canvas"));
                    float rad = (float) Math.toRadians(paramVal);
                    // Arc path for origin
                    f.setOriginX((float) (100.0 + 50.0 * Math.cos(rad)));
                    f.setOriginY((float) (200.0 + 50.0 * Math.sin(rad)));
                    f.setAngle(paramVal);
                    f.setScale(1.0f);

                    allForms.add(f);
                    if (!isExtra) sourceForms.add(f);
                    keys.add(new CExtendedInterpolationExtension.b(
                            paramGuid, paramVal, isExtra, i / stride, (i % stride) / (float) stride));
                }

                // Warmup
                for (int w = 0; w < 500; w++) {
                    ext.updateInterpolatedForms_common(allForms, sourceForms, keys, type, 1.0f);
                }

                // Measure
                int repeats = 200;
                long startAlloc = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                long startTime = System.nanoTime();
                for (int r = 0; r < repeats; r++) {
                    ext.updateInterpolatedForms_common(allForms, sourceForms, keys, type, 1.0f);
                }
                long elapsed = System.nanoTime() - startTime;
                long alloc = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - startAlloc;

                double msPerCall = (elapsed / (double) repeats) / 1_000_000.0;
                double bytesPerCall = alloc / (double) repeats;

                System.out.printf(Locale.ROOT, "%-16s | %-10s | %-12d | %-10d | %12.4f | %14.1f%n",
                        "RotationDeformer", type.name(), inserted, count, msPerCall, bytesPerCall);
            }
        }
    }
}
