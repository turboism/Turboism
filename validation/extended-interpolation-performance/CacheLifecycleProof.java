package dev.turboism.diagnostic.extended;

import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType;
import dev.turboism.adapter.cubism.interpolation.NativeFormLookupBridge;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Observes live heap after explicit GC and proves old derived forms can be reclaimed. */
public final class CacheLifecycleProof {
    private CacheLifecycleProof() {}

    private record Dropped(List<WeakReference<?>> roots, String label) {}
    private record ReleasedToken(Object token, List<WeakReference<?>> roots) {}

    public static void main(String[] args) {
        // The official library starts a non-daemon helper. Match the existing
        // kernel carrier's explicit exit after the assertions have completed.
        try { run(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private static void run() throws Exception {
        if (!Boolean.getBoolean("extended.proof.installed")) throw new AssertionError("real installer required");
        // Resolve and initialize the official types before observing the larger model.
        for (boolean enabled : new boolean[]{false, true}) {
            var warm = StructureRuntimeProof.create(1, 2, ExtendedInterpolationType.ELLIPSE, 2, enabled);
            warm.extension().updateInterpolatedForms(true);
        }
        collect();
        InterpolationProofAgent.verifyPatched();
        ReleasedToken released = releaseToken();
        requireCollected(released.roots(), "caller-retained exited scope token");
        java.lang.ref.Reference.reachabilityFence(released.token());
        for (int kind : new int[]{0, 1, 2}) {
            for (var curve : new ExtendedInterpolationType[]{ExtendedInterpolationType.ELLIPSE, ExtendedInterpolationType.SNS_CURVE}) {
                for (boolean enabled : new boolean[]{false, true}) {
                    Dropped dropped = exercise(kind, curve, enabled);
                    requireCollected(dropped.roots(), dropped.label() + " owner/extension/current forms");
                }
            }
        }
        var stats = NativeFormLookupBridge.cacheStatistics();
        if (stats.activeScopes() != 0 || stats.activeIndexEntries() != 0) throw new AssertionError(stats);
        System.out.println("CACHE_LIFECYCLE PASS cases=12 oldFormsCollected=true droppedOwnersCollected=true stats=" + stats);
    }

    private static ReleasedToken releaseToken() throws Exception {
        var model = StructureRuntimeProof.create(1, 2, ExtendedInterpolationType.ELLIPSE, 2, true);
        List<WeakReference<?>> refs = new ArrayList<>();
        refs.add(new WeakReference<>(model.owner()));
        refs.add(new WeakReference<>(model.extension()));
        Object token = NativeFormLookupBridge.enter();
        try {
            Object form = NativeFormLookupBridge.find(model.owner(), model.extension(), model.owner().getKeyforms().get(0).getGuid());
            if (form == NativeFormLookupBridge.UNSUPPORTED) throw new AssertionError("token retention probe did not index");
        } finally { NativeFormLookupBridge.exit(token); }
        return new ReleasedToken(token, refs);
    }

    private static Dropped exercise(int kind, ExtendedInterpolationType curve, boolean enabled) throws Exception {
        collect();
        long baseline = usedHeap();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        long start = System.nanoTime();
        var model = StructureRuntimeProof.create(kind, 3, curve, 8, enabled);
        long elapsed = System.nanoTime() - start;
        allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
        int largeCount = model.extension().getNormalExtendedKeyforms().size();
        if (largeCount != 6832) throw new AssertionError("unexpected dense form count " + largeCount);
        String label = "kind=" + kind + " curve=" + curve + " mode=" + (enabled ? "optimized" : "native");
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) if (pool.getType() == MemoryType.HEAP) {
            System.out.println("CACHE_POOL_PEAK " + label + " pool=" + pool.getName() + " bytes=" + pool.getPeakUsage().getUsed());
        }
        collect();
        long largeLive = usedHeap();
        var previous = weakForms(model);
        for (var binding : model.owner().getKeyformGridSource().getKeyformBindings()) binding.setInsertPointCount(4);
        NativeMeshInterpolationBridge.enabled(enabled);
        model.extension().setupFormStructure(true, true);
        int smallCount = model.extension().getNormalExtendedKeyforms().size();
        if (smallCount != 1304) throw new AssertionError("unexpected shrunk grid " + smallCount);
        // Keep the owner alive while proving that its previous derived bank is collectible.
        requireCollected(previous, label + " old derived forms after shrink");
        long smallLive = usedHeap();
        System.out.println("CACHE_HEAP " + label + " baselinePostGc=" + baseline + " largePostGc=" + largeLive
                + " largeDelta=" + (largeLive - baseline) + " smallPostGc=" + smallLive + " largeForms=" + largeCount
                + " smallForms=" + smallCount + " creationThreadBytes=" + allocated + " creationNs=" + elapsed
                + " scope=wholeJvmPostGcUsedHeapNotRetainedGraphSize");
        if (NativeFormLookupBridge.cacheStatistics().activeIndexEntries() != 0) throw new AssertionError("live index after native return");
        List<WeakReference<?>> roots = new ArrayList<>();
        roots.add(new WeakReference<>(model.owner()));
        roots.add(new WeakReference<>(model.extension()));
        roots.addAll(weakForms(model));
        return new Dropped(roots, label);
    }

    private static List<WeakReference<?>> weakForms(StructureRuntimeProof.Model model) {
        List<WeakReference<?>> refs = new ArrayList<>();
        for (Object form : model.extension().getNormalExtendedKeyforms()) refs.add(new WeakReference<>(form));
        return refs;
    }

    private static void requireCollected(List<WeakReference<?>> refs, String label) throws Exception {
        int alive = refs.size();
        for (int attempt = 0; attempt < 12 && alive != 0; attempt++) {
            collect();
            alive = 0;
            for (var ref : refs) if (ref.get() != null) alive++;
        }
        if (alive != 0) throw new AssertionError(label + " still reachable=" + alive + "/" + refs.size());
        System.out.println("CACHE_COLLECTED " + label + " count=" + refs.size());
    }

    private static void collect() throws Exception {
        System.gc();
        Thread.sleep(60);
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
