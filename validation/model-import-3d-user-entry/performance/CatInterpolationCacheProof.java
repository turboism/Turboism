package dev.turboism.diagnostic;

import com.live2d.serialize.UUSerialize;
import com.live2d.cubism.doc.model.*;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import java.io.File;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.security.MessageDigest;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;

/** Full official 2D cache call chain; does not simulate Editor history or mouse input. */
public final class CatInterpolationCacheProof {
    static final boolean PRODUCTION = Boolean.getBoolean("cat.diagnostic.productionRuntime");
    static void enabled(boolean value) {
        if (PRODUCTION) NativeMeshInterpolationBridge.enabled(value);
        else CatInterpolationDiagnosticAgent.enabled = value;
    }
    static long accepted() { return PRODUCTION ? NativeMeshInterpolationBridge.acceptedCalls() : CatInterpolationDiagnosticAgent.accepted; }
    static long refused() { return PRODUCTION ? NativeMeshInterpolationBridge.refusedCalls() : CatInterpolationDiagnosticAgent.refused; }
    static CExtendedInterpolationExtension extension(CArtMeshSource mesh) {
        return mesh.getExtensions().stream().filter(CExtendedInterpolationExtension.class::isInstance)
            .map(CExtendedInterpolationExtension.class::cast).findFirst().orElseThrow();
    }
    static List<ACForm> forms(CModelSource model) {
        var forms = new ArrayList<ACForm>();
        for (var mesh : model.getAllArtMeshes()) {
            forms.addAll(mesh.getKeyforms());
            forms.addAll(extension(mesh).getNormalExtendedKeyforms());
        }
        return forms;
    }
    static void integer(MessageDigest digest, int value) {
        digest.update((byte)(value >>> 24)); digest.update((byte)(value >>> 16));
        digest.update((byte)(value >>> 8)); digest.update((byte)value);
    }
    static String bank(List<ACForm> forms) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var form : forms) {
            var xy = ((CArtMeshForm)form).getPositions(); integer(digest, xy.length);
            for (float value : xy) integer(digest, Float.floatToRawIntBits(value));
            var meshForm = (CArtMeshForm)form;
            integer(digest, meshForm.getDrawOrder()); integer(digest, Float.floatToRawIntBits(meshForm.getOpacity()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static long allocated() {
        return ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    static void update(CModelSource model, boolean optimized) {
        enabled(optimized);
        for (var mesh : model.getAllArtMeshes()) extension(mesh).updateInterpolatedForms(true);
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        enabled(false);
        ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).setThreadAllocatedMemoryEnabled(true);
        long readAllocation = allocated(), readStart = System.nanoTime();
        var model = (CModelSource)UUSerialize.INSTANCE.deserializeFromFile(new File(args[0]), new HashMap<>(), -1, false);
        System.out.println("[CAT_PERF_CACHE] deserializeNs="+(System.nanoTime()-readStart)
            +" deserializeThreadAllocatedBytes="+(allocated()-readAllocation));
        boolean optimizedModel = Boolean.getBoolean("cat.diagnostic.optimizedModel");
        long slots = model.getAllArtMeshes().stream().mapToLong(m -> m.getDefaultKeyForm().getPositions().length / 2).sum();
        if (model.getAllArtMeshes().size()!=512 || slots!=(optimizedModel?35397:48497))
            throw new IllegalStateException("unexpected admitted model inventory");
        boolean optimizedSetup = Boolean.getBoolean("cat.diagnostic.optimizedSetup");
        enabled(optimizedSetup);
        long setupAllocation = allocated(), setupStart = System.nanoTime();
        model.setup();
        System.out.println("[CAT_PERF_CACHE] optimizedSetup="+optimizedSetup+" setupNs="+(System.nanoTime()-setupStart)
            +" setupThreadAllocatedBytes="+(allocated()-setupAllocation)+" setupAccepted="+accepted()
            +" setupRefused="+refused());
        long initialAccepted = accepted(), initialRefused = refused();
        if (PRODUCTION ? CatRuntimeInterpolationAgent.installation == null || !CatRuntimeInterpolationAgent.installation.patched()
                       : !CatInterpolationDiagnosticAgent.transformed) throw new IllegalStateException("no diagnostic hook");
        ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).setThreadAllocatedMemoryEnabled(true);
        var forms = forms(model);
        // Edit one ordinary pose in every mesh, preserving other parameter bindings.
        for (var mesh : model.getAllArtMeshes()) {
            var form = mesh.getDefaultKeyForm(); var xy = form.getPositions().clone();
            for (int p = 0; p < xy.length; p += 2) { xy[p] += .125f; xy[p+1] -= .0625f; }
            form.setPositions(xy);
        }
        var before = forms.stream().mapToInt(ACForm::getEditVersion).toArray();
        long allocation = allocated(), start = System.nanoTime(); update(model, false);
        long officialNs = System.nanoTime()-start, officialBytes = allocated()-allocation;
        String officialBank = bank(forms);
        var middle = forms.stream().mapToInt(ACForm::getEditVersion).toArray();
        allocation = allocated(); start = System.nanoTime(); update(model, true);
        long fastNs = System.nanoTime()-start, fastBytes = allocated()-allocation;
        String fastBank = bank(forms);
        if (!officialBank.equals(fastBank)) throw new IllegalStateException("full cache position/metadata bank mismatch");
        if (!optimizedModel && !officialBank.equals("533c3b09e54b53bb9a336d9e076ab41f41950a903297e18b5e7756eb793c89a0"))
            throw new IllegalStateException("bank differs from frozen Cat official baseline");
        for (int i=0; i<forms.size(); i++)
            if (middle[i]-before[i] != forms.get(i).getEditVersion()-middle[i])
                throw new IllegalStateException("update version delta mismatch form="+i);
        if (accepted()-initialAccepted == 0 || refused()-initialRefused != 0)
            throw new IllegalStateException("unexpected fast-path coverage");
        if (PRODUCTION && Boolean.getBoolean("turboism.meshInterpolation.directUpdates") && NativeMeshInterpolationBridge.directUpdateBindings()==0)
            throw new IllegalStateException("direct per-point update dispatch not bound");
        System.out.println("[CAT_PERF_CACHE] directUpdateBindings="+(PRODUCTION?NativeMeshInterpolationBridge.directUpdateBindings():0));
        System.out.println("[CAT_PERF_CACHE] meshes="+model.getAllArtMeshes().size()+" forms="+forms.size()
            +" exactBank="+officialBank+" versionDeltasExact=true accepted="+(accepted()-initialAccepted)
            +" refused="+(refused()-initialRefused)+" productionRuntime="+PRODUCTION+" originalNs="+officialNs+" fastNs="+fastNs
            +" originalBytes="+officialBytes+" fastBytes="+fastBytes);
        System.out.println("[CAT_PERF_CACHE] PASS_FULL_CACHE_DIAGNOSTIC_NOT_EDITOR_DRAG_FIX NO_SAVE=true");
    }
}
