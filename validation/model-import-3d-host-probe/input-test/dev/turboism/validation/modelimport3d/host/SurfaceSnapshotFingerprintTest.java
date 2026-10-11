package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Emits cross-language test vectors including UTF-8, signed zero and optional morph values. */
public final class SurfaceSnapshotFingerprintTest {
    public static void main(String[] args) {
        opacityChecks();
        consumptionChecks();
        var geometry=NativeAssertions.GEOMETRY;
        var axis=new Axis("轴-guid",new ParameterId("parameter"),-1f,0f,1f,List.of(-1f,0f,1f));
        var forms=List.of(new Form("形态",geometry.positions(),500));
        var cell=new Cell("形态",List.of(new Coordinate("轴-guid",0f)));
        for(var keys:List.of(List.<MorphKey>of(),
                List.of(new MorphKey("morph","形态",Optional.empty())),
                List.of(new MorphKey("morph","形态",Optional.of(0f))),
                List.of(new MorphKey("morph","形态",Optional.of(-0f))),
                List.of(new MorphKey("morph","形态",Optional.of(.25f))))) {
            var snapshot=new ArtMeshAuthoringSnapshot(new ArtMeshId("mesh"),"源😀",geometry,
                List.of(axis),List.of(cell),forms,keys);
            System.out.println(NativeReport.json(Map.of("snapshot",NativeAssertions.snapshot(snapshot),
                "sha256",SurfaceSnapshotFingerprint.of(snapshot))));
        }
    }
    private static void consumptionChecks() {
        var a=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterValue("a",new ParameterId("x"),.25f);
        var b=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterValue("b",new ParameterId("y"),.75f);
        var live=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(50,List.of(a,b));
        var same=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(7,List.of(b,a));
        var old=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(7,List.of(a));
        var valid=new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(new ArtMeshId("mesh"),"source",Optional.empty(),live,same,1);
        NativeAssertions.require(CubeRenderProbe.currentParametersConsumed(valid),"unchanged consumed pose must not require version increment");
        NativeAssertions.require(!CubeRenderProbe.currentParametersConsumed(new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(
            valid.artMeshId(),valid.sourceGuid(),Optional.empty(),live,old,1)),"stale consumed tuple still waits");
        NativeAssertions.require(!CubeRenderProbe.currentParametersConsumed(new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(
            valid.artMeshId(),valid.sourceGuid(),Optional.empty(),live,same,-1)),"never-consumed tuple still waits");
        NativeAssertions.require(EvaluationProbe.consumptionReason(valid,valid).equals("WAITING_PASS"),
            "changed-pose sampling still requires a fresh pass");
    }

    private static void opacityChecks() {
        var available=drawable(()->.75f);
        NativeAssertions.require(CubeRenderProbe.opacityObservation(available).equals(Map.of("status","AVAILABLE","value",.75f)),
            "opacity must be observed, not substituted");
        var absent=drawable(()->{throw new IllegalStateException("Editor ArtMesh current keyform is unavailable.");});
        NativeAssertions.require(CubeRenderProbe.opacityObservation(absent).equals(
            Map.of("status","UNAVAILABLE","reason","CURRENT_AUTHORED_KEYFORM_ABSENT")),"absent current form availability");
        var broken=drawable(()->{throw new IllegalStateException("unrelated adapter failure");});
        try {CubeRenderProbe.opacityObservation(broken);throw new AssertionError("unrelated failure was hidden");}
        catch(IllegalStateException expected) {
            NativeAssertions.require(expected.getMessage().equals("unrelated adapter failure"),"wrong failure propagated");
        }
    }
    private static dev.turboism.sdk.cubism.model.Drawable drawable(java.util.function.Supplier<Float> opacity) {
        return (dev.turboism.sdk.cubism.model.Drawable)java.lang.reflect.Proxy.newProxyInstance(
            SurfaceSnapshotFingerprintTest.class.getClassLoader(),new Class<?>[]{dev.turboism.sdk.cubism.model.Drawable.class},
            (proxy,method,args)->{if(method.getName().equals("getOpacity"))return opacity.get();
                throw new UnsupportedOperationException(method.getName());});
    }

}
