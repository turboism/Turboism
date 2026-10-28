package dev.turboism.probe;

import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension;
import com.live2d.doc.CoordType;
import kotlin.collections.CollectionsKt;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolated dynamic audit for the proposed P3 firstOrNull shortcut.
 * Verifies that replacing firstOrNull(getAllExtendedKeyforms()) with an isEmpty-guarded
 * check produces identical results across all edge cases (including non-empty list with null head),
 * while completely eliminating O(N) ArrayList allocations.
 */
public final class FirstOrNullAuditProbe {
    public static void main(String[] args) {
        try {
            System.out.println("=== AUDIT: firstOrNull(getAllExtendedKeyforms()) OPTIMIZATION PROBE ===");
            testEquivalenceAcrossAllEdgeCases();
            benchmarkAllocationAndLatency();
            System.out.println("\n=== AUDIT PROBE COMPLETED SUCCESSFULLY ===");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /** Official logic: creates a full combined list via CollectionsKt.plus, then takes firstOrNull. */
    private static ACForm officialFirstOrNull(CExtendedInterpolationExtension ext) {
        return (ACForm) CollectionsKt.firstOrNull(ext.getAllExtendedKeyforms());
    }

    /**
     * Proposed patch logic:
     * Must be guarded by normal.isEmpty(), NOT normal.firstOrNull() == null.
     * If normal is non-empty but its first element is null, plus() would have returned null,
     * so it must NOT fall through to morph.
     */
    private static ACForm patchedFirstOrNull(CExtendedInterpolationExtension ext) {
        List<ACForm> normal = ext.getNormalExtendedKeyforms();
        if (!normal.isEmpty()) {
            return (ACForm) CollectionsKt.firstOrNull(normal);
        }
        return (ACForm) CollectionsKt.firstOrNull(ext.getMorphTargetExtendedKeyforms());
    }

    private static void testEquivalenceAcrossAllEdgeCases() throws Exception {
        System.out.println("\n--- Step 1: Branch & Equivalence Verification Across 4 Scenarios ---");
        var mesh = new CArtMeshSource();
        var ext = new CExtendedInterpolationExtension(mesh);

        Field normalField = CExtendedInterpolationExtension.class.getDeclaredField("normalExtendedKeyforms");
        normalField.setAccessible(true);
        Field morphField = CExtendedInterpolationExtension.class.getDeclaredField("morphTargetExtendedKeyforms");
        morphField.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ACForm> normalList = (List<ACForm>) normalField.get(ext);
        @SuppressWarnings("unchecked")
        List<ACForm> morphList = (List<ACForm>) morphField.get(ext);

        var fNormal1 = new CArtMeshForm(mesh, 1, 1f, new float[]{0, 0}, new CoordType("Canvas"));
        var fNormal2 = new CArtMeshForm(mesh, 2, 1f, new float[]{1, 1}, new CoordType("Canvas"));
        var fMorph1 = new CArtMeshForm(mesh, 10, 1f, new float[]{2, 2}, new CoordType("Canvas"));
        var fMorph2 = new CArtMeshForm(mesh, 11, 1f, new float[]{3, 3}, new CoordType("Canvas"));

        // Scenario 1: Both Normal and Morph exist
        normalList.clear(); morphList.clear();
        normalList.add(fNormal1); normalList.add(fNormal2);
        morphList.add(fMorph1); morphList.add(fMorph2);
        ACForm off1 = officialFirstOrNull(ext);
        ACForm pat1 = patchedFirstOrNull(ext);
        System.out.println("Scenario 1 (Normal + Morph):");
        System.out.println("  Official: " + off1.getGuid() + ", Patched: " + pat1.getGuid());
        if (off1 != pat1 || off1 != fNormal1) throw new AssertionError("Mismatch in Scenario 1");

        // Scenario 2: Morph-only (Normal is empty)
        normalList.clear(); morphList.clear();
        morphList.add(fMorph1); morphList.add(fMorph2);
        ACForm off2 = officialFirstOrNull(ext);
        ACForm pat2 = patchedFirstOrNull(ext);
        System.out.println("Scenario 2 (Morph-only, Normal empty):");
        System.out.println("  Official: " + (off2 != null ? off2.getGuid() : "null") + ", Patched: " + (pat2 != null ? pat2.getGuid() : "null"));
        if (off2 != pat2 || off2 != fMorph1) throw new AssertionError("Mismatch in Scenario 2");

        // Scenario 3: Non-empty Normal with null head (Edge case: must NOT fall through to Morph!)
        normalList.clear(); morphList.clear();
        normalList.add(null); normalList.add(fNormal2);
        morphList.add(fMorph1); morphList.add(fMorph2);
        ACForm off3 = officialFirstOrNull(ext);
        ACForm pat3 = patchedFirstOrNull(ext);
        System.out.println("Scenario 3 (Normal non-empty with null head):");
        System.out.println("  Official: " + off3 + ", Patched: " + pat3);
        if (off3 != null || pat3 != null) throw new AssertionError("Mismatch in Scenario 3: fell through to morph!");

        // Scenario 4: Both empty
        normalList.clear(); morphList.clear();
        ACForm off4 = officialFirstOrNull(ext);
        ACForm pat4 = patchedFirstOrNull(ext);
        System.out.println("Scenario 4 (Both Empty):");
        System.out.println("  Official: " + off4 + ", Patched: " + pat4);
        if (off4 != null || pat4 != null) throw new AssertionError("Mismatch in Scenario 4");

        System.out.println("Result: In all 4 scenarios, patched logic produces bit-for-bit identical reference as official!");
    }

    private static void benchmarkAllocationAndLatency() throws Exception {
        System.out.println("\n--- Step 2: Allocation & Latency Benchmark ---");
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);

        var mesh = new CArtMeshSource();
        var ext = new CExtendedInterpolationExtension(mesh);

        Field normalField = CExtendedInterpolationExtension.class.getDeclaredField("normalExtendedKeyforms");
        normalField.setAccessible(true);
        Field morphField = CExtendedInterpolationExtension.class.getDeclaredField("morphTargetExtendedKeyforms");
        morphField.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<ACForm> normalList = (List<ACForm>) normalField.get(ext);
        @SuppressWarnings("unchecked")
        List<ACForm> morphList = (List<ACForm>) morphField.get(ext);

        // Populate with 131 forms (normal) + 20 forms (morph)
        normalList.clear();
        for (int i = 0; i < 131; i++) {
            normalList.add(new CArtMeshForm(mesh, i, 1f, new float[]{i, i}, new CoordType("Canvas")));
        }
        for (int i = 0; i < 20; i++) {
            morphList.add(new CArtMeshForm(mesh, 1000 + i, 1f, new float[]{i, i}, new CoordType("Canvas")));
        }
        int totalForms = normalList.size() + morphList.size();
        System.out.println("Total forms in extension: " + totalForms + " (normal: " + normalList.size() + ", morph: " + morphList.size() + ")");

        // Warmup
        for (int i = 0; i < 20000; i++) {
            officialFirstOrNull(ext);
            patchedFirstOrNull(ext);
        }

        int iterations = 100_000;

        // Official
        long allocBefore = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            officialFirstOrNull(ext);
        }
        long officialTime = System.nanoTime() - start;
        long officialAlloc = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocBefore;

        // Patched
        allocBefore = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            patchedFirstOrNull(ext);
        }
        long patchedTime = System.nanoTime() - start;
        long patchedAlloc = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocBefore;

        System.out.printf(java.util.Locale.ROOT, "Official: %.2f ns/call, %.1f bytes/call%n",
                officialTime / (double) iterations, officialAlloc / (double) iterations);
        System.out.printf(java.util.Locale.ROOT, "Patched:  %.2f ns/call, %.1f bytes/call%n",
                patchedTime / (double) iterations, patchedAlloc / (double) iterations);
        System.out.printf(java.util.Locale.ROOT, "Allocation reduction: from %.1f B to %.1f B (%.1f%% eliminated)%n",
                officialAlloc / (double) iterations, patchedAlloc / (double) iterations,
                100.0 * (1.0 - (patchedAlloc / (double) officialAlloc)));
    }
}
