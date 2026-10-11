package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.*;

/** Explicit layered module, never selected by the legacy canonical profile. */
final class CanonicalWarpLayerProbe {
    private CanonicalWarpLayerProbe() { }
    static void runSource(byte[] glb,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,
            IntConsumer restore,AuthoringTransactionService transactions,Path stateDir,Map<String,Object> data) throws Exception {
        var request=new SurfaceImport.Request("Apple",new dev.turboism.sdk.cubism.id.ParameterId("ParamMI3DCanonicalYaw"),new dev.turboism.sdk.cubism.id.ParameterId("ParamMI3DCanonicalPitch"),
            new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0)),30,30,SurfaceImportPlan.MAX_PROJECTED_VERTICES);
        var candidate=SurfaceWarpImportCandidate.prepareFramed(glb,request,SurfaceWarpImportCandidate.DEFAULT);
        var layered=SurfaceWarpLayerAuthoring.prepare(candidate);
        CanonicalWarpProbe.runPrepared(candidate,layered.surface(),observer->SurfaceWarpLayerAuthoring.apply(layered,current,transactions,observer),
            current,history,restore,transactions,stateDir,data,true);
    }
}
