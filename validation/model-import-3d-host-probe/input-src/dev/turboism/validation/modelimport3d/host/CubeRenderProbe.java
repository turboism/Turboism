package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Captures native material/culling evidence. Independent pixel oracle decides visibility acceptance. */
final class CubeRenderProbe {
    private CubeRenderProbe() { }
    static final List<Integer> PALETTE=SurfaceBaseColorPlan.FACE_PALETTE;
    static final List<SurfaceProjection.Pose> POSES=List.of(
        new SurfaceProjection.Pose(0,0),new SurfaceProjection.Pose(-30,-30),new SurfaceProjection.Pose(-30,30),
        new SurfaceProjection.Pose(30,-30),new SurfaceProjection.Pose(30,30),new SurfaceProjection.Pose(-1,0),
        new SurfaceProjection.Pose(1,0),new SurfaceProjection.Pose(0,-1),new SurfaceProjection.Pose(0,1));

    static void run(Supplier<CubismModel> current,Supplier<HistorySnapshot> history,IntConsumer restore,
            List<GlbSurfaceReader.Surface> surfaces,SurfaceImportPlan.Plan plan,SurfaceImportGeometry.Created created,
            Path state,Map<String,Object> data) throws Exception {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(),"render waits must not run on EDT");
        ClosedConvexSurfaceProof.verify(surfaces);
        NativeAssertions.require(created.patches().size()==6,"six proven cube faces required");
        Path artifacts=state.resolve("cube-render");Files.createDirectory(artifacts);
        data.put("status","RUNNING");data.put("inputSha256",CubeGeometryProbe.INPUT_SHA256);
        data.put("palette",PALETTE);data.put("poses",POSES.stream().map(p->List.of(p.yaw(),p.pitch())).toList());
        data.put("fingerprintVersion","mi3d-snapshot-fingerprint-v1");
        data.put("geometryFingerprints",created.patches().stream().map(p->SurfaceSnapshotFingerprint.of(p.bake().expected())).toList());
        var frames=new ArrayList<Map<String,Object>>();data.put("frames",frames);
        var phases=new LinkedHashMap<String,Object>();data.put("phases",phases);
        var root=edt(history::get);data.put("initialHistory",PixelSamplingProbe.historyData(root));
        var original=edt(()->state(current.get(),created));data.put("initialState",original);
        var originalPose=edt(()->new SurfaceProjection.Pose(
            current.get().parameters().find(CubeGeometryProbe.YAW).getValue(),
            current.get().parameters().find(CubeGeometryProbe.PITCH).getValue()));
        Throwable primary=null;
        try {
            var diagnostic=SurfaceBaseColorPlan.compile(surfaces,plan,SurfaceBaseColorPlan.Appearance.FACE_COLORS);
            var diagnosticBindings=edt(()->SurfaceImportAppearance.bind(current.get(),created,diagnostic));
            data.put("diagnosticBindings",bindings(diagnosticBindings));
            phase("diagnostic-off",false,current,history,created,artifacts,frames,phases);
            phase("diagnostic-on",true,current,history,created,artifacts,frames,phases);
            restoreAndConsume(current,restore,root.position(),created,originalPose);
            NativeAssertions.require(edt(()->state(current.get(),created)).equals(original),"diagnostic resources/state did not restore");
            var materialPlan=SurfaceBaseColorPlan.compile(surfaces,plan);
            var originalBindings=edt(()->SurfaceImportAppearance.bind(current.get(),created,materialPlan));
            data.put("sourceBindings",bindings(originalBindings));
            phase("source-on",true,current,history,created,artifacts,frames,phases);
        } catch(Exception | Error failure) {
            primary=failure;data.put("status","FAIL");data.put("failure",failure.toString());throw failure;
        } finally {
            try {
                restoreAndConsume(current,restore,root.position(),created,originalPose);
                var restored=edt(()->state(current.get(),created));var h=edt(history::get);
                data.put("restoredHistory",PixelSamplingProbe.historyData(h));data.put("restoredState",restored);
                NativeAssertions.require(h.position()==root.position() && restored.equals(original),"render fixture recovery failed");
                data.put("finallyRestored",true);
            } catch(Exception | Error failure) {
                data.put("finallyRestored",false);data.put("status","FAIL");data.put("recoveryFailure",failure.toString());
                if(primary!=null)primary.addSuppressed(failure);else throw failure;
            }
        }
        data.put("status","CAPTURED");data.put("failure",null);
        data.put("pixelAcceptance","REQUIRES_INDEPENDENT_ORACLE");
    }

    private static void phase(String label,boolean culling,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,
            SurfaceImportGeometry.Created created,Path artifacts,List<Map<String,Object>> frames,Map<String,Object> phases) throws Exception {
        edt(()->{for(var p:created.patches())current.get().drawables().find(p.meshId()).setCulling(culling);return null;});
        awaitCurrent(current,created);
        phases.put(label,edt(()->Map.of("history",PixelSamplingProbe.historyData(history.get()),"state",state(current.get(),created))));
        for(int index=0;index<POSES.size();index++) {
            var pose=POSES.get(index);var observations=new ArrayList<Map<String,Object>>();
            var requested=edt(()->{
                current.get().parameters().find(CubeGeometryProbe.YAW).setValue((float)pose.yaw());
                current.get().parameters().find(CubeGeometryProbe.PITCH).setValue((float)pose.pitch());
                return created.patches().stream().map(p->current.get().drawables().find(p.meshId()).evaluationSnapshot()).toList();
            });
            for(int i=0;i<6;i++) {
                var p=created.patches().get(i);var detail=new LinkedHashMap<String,Object>();observations.add(detail);
                detail.put("requested",EvaluationProbe.evaluation(requested.get(i)));
                var actual=EvaluationProbe.awaitConsumed(requested.get(i),()->edt(()->current.get().drawables().find(p.meshId()).evaluationSnapshot()),
                    detail::putAll,System::nanoTime,Thread::sleep);
                detail.put("actual",EvaluationProbe.evaluation(actual));
                var expected=SurfaceProjection.project(p.source().positions(),CubeGeometryProbe.CAMERA,pose).positions();
                NativeAssertions.require(EvaluationProbe.validGeometry(actual,expected,p.bake().expected().forms().get(0).drawOrder()),
                    "render pose geometry is stale or outside 0.1px");
            }
            final int number=index;
            frames.add(edt(()->{
                var before=state(current.get(),created);var h0=history.get();
                var pixels=current.get().renderPixels(1024,1024);
                NativeAssertions.require(pixels.width()==1024 && pixels.height()==1024,"native raster dimensions changed");
                var after=state(current.get(),created);var h1=history.get();
                var hd0=PixelSamplingProbe.historyData(h0);var hd1=PixelSamplingProbe.historyData(h1);
                hd0.remove("revision");hd1.remove("revision");
                NativeAssertions.require(before.equals(after) && hd0.equals(hd1) && h1.revision()>=h0.revision(),
                    "render changed protected authored state or history");
                var image=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0,0,1024,1024,pixels.argb(),0,1024);
                var output=new ByteArrayOutputStream();NativeAssertions.require(ImageIO.write(image,"PNG",output),"PNG encoder unavailable");
                byte[] png=output.toByteArray();NativeAssertions.require(png.length<=8*1024*1024,"render PNG budget");
                String name=label+"-"+number+".png";Files.write(artifacts.resolve(name),png,StandardOpenOption.CREATE_NEW);
                var row=new LinkedHashMap<String,Object>();row.put("phase",label);row.put("index",number);
                row.put("pose",List.of(pose.yaw(),pose.pitch()));row.put("stateBefore",before);row.put("stateAfter",after);
                row.put("historyBefore",PixelSamplingProbe.historyData(h0));row.put("historyAfter",PixelSamplingProbe.historyData(h1));
                row.put("consumption",observations);row.put("artifact","cube-render/"+name);row.put("artifactBytes",png.length);
                row.put("artifactSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));return row;
            }));
        }
    }

    // Live parameter values are not assumed to be authoring-history entries.
    // Restore them explicitly, then wait outside EDT for the editor to consume them.
    private static void restoreAndConsume(Supplier<CubismModel> current,IntConsumer restore,int position,
            SurfaceImportGeometry.Created created,SurfaceProjection.Pose pose) throws Exception {
        edt(()->{
            restore.accept(position);
            current.get().parameters().find(CubeGeometryProbe.YAW).setValue((float)pose.yaw());
            current.get().parameters().find(CubeGeometryProbe.PITCH).setValue((float)pose.pitch());
            return null;
        });
        awaitCurrent(current,created);
    }
    private static void awaitCurrent(Supplier<CubismModel> current,SurfaceImportGeometry.Created created) throws Exception {
        var requested=edt(()->created.patches().stream()
            .map(p->current.get().drawables().find(p.meshId()).evaluationSnapshot()).toList());
        for(int i=0;i<created.patches().size();i++) {
            var id=created.patches().get(i).meshId();
            if(currentParametersConsumed(requested.get(i)))continue;
            EvaluationProbe.awaitConsumed(requested.get(i),
                ()->edt(()->current.get().drawables().find(id).evaluationSnapshot()),
                ignored->{},System::nanoTime,Thread::sleep);
        }
    }

    // Material/culling changes do not change the protected source keyforms or
    // parameter values. They may schedule no new evaluation at all. In that
    // case keep the already-consumed pose; state()/the independent gate still
    // verify calculated geometry. Changed-pose frame capture above always uses
    // awaitConsumed and requires a new pass, even if values already match.
    static boolean currentParametersConsumed(ArtMeshEvaluationSnapshot observation) {
        var order=Comparator.comparing(ArtMeshEvaluationSnapshot.ParameterValue::parameterGuid);
        return observation.passEntryTimeMillis()>=0
            && observation.liveParameters().parameters().stream().sorted(order).toList()
                .equals(observation.consumedParameters().parameters().stream().sorted(order).toList());
    }

    private static Map<String,Object> state(CubismModel model,SurfaceImportGeometry.Created created) {
        var rows=new ArrayList<Map<String,Object>>();
        for(var p:created.patches()) {
            var mesh=model.drawables().find(p.meshId());var snapshot=mesh.authoringSnapshot();
            p.bake().verify(mesh);
            var evaluated=mesh.evaluationSnapshot().calculatedForm().orElseThrow();
            var input=model.textures().textureInput(p.meshId());var row=new LinkedHashMap<String,Object>();
            row.put("id",p.meshId().value());row.put("snapshotSha256",SurfaceSnapshotFingerprint.of(snapshot));
            row.put("culling",mesh.culling());row.put("visible",mesh.visible());row.put("opacity",opacityObservation(mesh));
            row.put("positions",evaluated.positions().stream().map(v->List.of(v.x(),v.y())).toList());
            row.put("drawOrder",evaluated.drawOrder());
            row.put("input",input.<Object>map(v->Map.of("kind",v.kind().name(),"modelId",v.modelId().value(),
                "generation",v.modelGeneration(),"meshId",v.artMeshId().value(),"imageId",v.modelImageId().orElseThrow().value())).orElse(null));
            rows.add(row);
        }
        var c=model.canvas();
        return Map.of("model",model.id().value(),"meshes",rows,
            "pose",List.of(model.parameters().find(CubeGeometryProbe.YAW).getValue(),model.parameters().find(CubeGeometryProbe.PITCH).getValue()),
            "canvas",List.of(c.widthPixels(),c.heightPixels(),c.originXPixels(),c.originYPixels(),c.pixelsPerUnit()),
            "instances",model.modelInstances().stream().map(v->v.renderType().name()).toList(),
            "currentInstance",model.currentModelInstance().map(v->v.renderType().name()).orElse("NONE"),
            "rawImages",model.textures().rawImages().stream().map(v->List.of(v.id().value(),v.name(),v.width(),v.height())).toList(),
            "imageGroups",model.textures().modelImageGroups().stream().map(g->Map.of("name",g.groupName(),"images",
                g.modelImages().stream().map(v->List.of(v.id().value(),v.name(),v.width(),v.height())).toList())).toList());
    }
    // Public getOpacity reads the current authored keyform, which the editor
    // legitimately leaves absent at interpolated poses. Report availability;
    // never substitute opacity=1. The independent raster gate checks visible alpha.
    static Map<String,Object> opacityObservation(Drawable mesh) {
        try {
            return Map.of("status","AVAILABLE","value",mesh.getOpacity());
        } catch(IllegalStateException failure) {
            if(!"Editor ArtMesh current keyform is unavailable.".equals(failure.getMessage()))throw failure;
            return Map.of("status","UNAVAILABLE","reason","CURRENT_AUTHORED_KEYFORM_ABSENT");
        }
    }

    private static List<Map<String,Object>> bindings(List<SurfaceImportAppearance.BoundMaterial> bindings) {
        return bindings.stream().map(b->{var r=new LinkedHashMap<String,Object>();
            r.put("meshId",b.meshId().value());r.put("surface",b.sourceSurface());r.put("material",b.sourceMaterial());r.put("argb",b.argb());
            r.put("imageId",b.binding().modelImageId().value());r.put("rawId",b.binding().rawImageId().value());
            r.put("modelId",b.binding().modelId().value());r.put("generation",b.binding().modelGeneration());return (Map<String,Object>)r;}).toList();
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        if(SwingUtilities.isEventDispatchThread())return action.call();
        var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();
    }
}
