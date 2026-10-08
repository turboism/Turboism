package dev.turboism.diagnostic;

import com.live2d.cubism.doc.model.*;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.serialize.UUSerialize;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.io.File;
import java.util.*;

/** Cheap official-library refusal/degeneracy proof; no Editor or file writes. */
public final class CatRuntimeGuardProof {
    static String state(FastMeshKernelProof.Line line) throws Exception {
        List<ACForm> forms = new ArrayList<>(line.all());
        return CatInterpolationCacheProof.bank(forms) + ":" + forms.stream().map(ACForm::getEditVersion).toList();
    }
    static void refused(CExtendedInterpolationExtension extension, FastMeshKernelProof.Line line, Object type, List<?> keys) throws Exception {
        String before = state(line);
        if (NativeMeshInterpolationBridge.tryUpdate(extension, line.all(), line.anchors(), keys, type, 1f))
            throw new IllegalStateException("invalid input accepted");
        if (!before.equals(state(line))) throw new IllegalStateException("refusal mutated forms");
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        var model = (CModelSource)UUSerialize.INSTANCE.deserializeFromFile(new File(args[0]), new HashMap<>(), -1, false);
        model.setup();
        var mesh = model.getAllArtMeshes().get(0);
        var extension = CatInterpolationCacheProof.extension(mesh);
        NativeMeshInterpolationBridge.enabled(true);
        var line = FastMeshKernelProof.line(mesh);
        refused(extension, line, ExtendedInterpolationType.LINEAR, line.keys());
        refused(extension, line, ExtendedInterpolationType.ELLIPSE, line.keys().subList(1, line.keys().size()));
        line = FastMeshKernelProof.line(mesh);
        line.all().get(1).setPositions(line.anchors().get(0).getPositions());
        refused(extension, line, ExtendedInterpolationType.ELLIPSE, line.keys());
        line = FastMeshKernelProof.line(mesh);
        line.anchors().get(0).getPositions()[0] = Float.NaN;
        refused(extension, line, ExtendedInterpolationType.ELLIPSE, line.keys());
        NativeMeshInterpolationBridge.enabled(false);
        line = FastMeshKernelProof.line(mesh);
        refused(extension, line, ExtendedInterpolationType.ELLIPSE, line.keys());
        NativeMeshInterpolationBridge.enabled(true);
        for (int scenario=0; scenario<3; scenario++) {
            var official = FastMeshKernelProof.line(mesh); var candidate = FastMeshKernelProof.line(mesh);
            if (scenario != 0) for (var bank : List.of(official, candidate)) for (var form : bank.anchors())
                Arrays.fill(form.getPositions(), scenario == 1 ? 0f : -0f);
            extension.updateInterpolatedForms_common(official.all(), official.anchors(), official.keys(), ExtendedInterpolationType.ELLIPSE, 1f);
            if (!NativeMeshInterpolationBridge.tryUpdate(extension, candidate.all(), candidate.anchors(), candidate.keys(), ExtendedInterpolationType.ELLIPSE, 1f))
                throw new IllegalStateException("supported line refused");
            if (!state(official).equals(state(candidate))) throw new IllegalStateException("line metadata or raw position bits mismatch scenario="+scenario);
        }
        NativeMeshInterpolationBridge.enabled(false);
        System.out.println("PASS_RUNTIME_GUARDS fiveNoWriteRefusals=true threeOfficialDifferentials=true NO_SAVE=true");
    }
}
