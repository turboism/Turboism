package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Exercises the actual production assembly, never the legacy fixture assembler.
 * Native captures require separate source-depth/UV pixel review. */
final class CanonicalWarpProbe {
    private CanonicalWarpProbe() { }
    static void runSource(byte[] glb,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,
            IntConsumer restore,AuthoringTransactionService transactions,Path stateDir,Map<String,Object> data) throws Exception {
        var request=new SurfaceImport.Request("Apple",new dev.turboism.sdk.cubism.id.ParameterId("ParamMI3DCanonicalYaw"),new dev.turboism.sdk.cubism.id.ParameterId("ParamMI3DCanonicalPitch"),
            new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0)),30,30,SurfaceImportPlan.MAX_PROJECTED_VERTICES);
        run(SurfaceWarpImportCandidate.prepareFramed(glb,request,SurfaceWarpImportCandidate.DEFAULT),current,history,restore,transactions,stateDir,data);
    }
    static void run(SurfaceWarpImportCandidate.Prepared candidate,Supplier<CubismModel> current,
            Supplier<HistorySnapshot> history,IntConsumer restore,AuthoringTransactionService transactions,
            Path stateDir,Map<String,Object> data) throws Exception {
        runPrepared(candidate,candidate.authoring(),observer->SurfaceWarpAuthoring.apply(candidate.authoring(),current,transactions,observer),
            current,history,restore,transactions,stateDir,data,false);
    }
    static void runPrepared(SurfaceWarpImportCandidate.Prepared candidate,SurfaceWarpAuthoring.Prepared p,
            Function<SurfaceWarpAuthoring.Observer,AuthoringTransactionResult<SurfaceWarpAuthoring.Created>> assembler,
            Supplier<CubismModel> current,Supplier<HistorySnapshot> history,IntConsumer restore,AuthoringTransactionService transactions,
            Path stateDir,Map<String,Object> data,boolean layered) throws Exception {
        if(!candidate.inputSha256().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("canonical source byte identity required");
        var root=HostProbe.edt(history::get);var baseline=HostProbe.edt(()->inventory(current.get()));
        var captures=stateDir.resolve("canonical-warp-capture");Files.createDirectory(captures);
        data.put("status","RUNNING");data.put("scope",layered?"CANONICAL_LAYERED_GLB_SOURCE_WARP_GEOMETRY_AND_CAPTURE_ONLY":"CANONICAL_GLB_SOURCE_WARP_GEOMETRY_AND_CAPTURE_ONLY");data.put("visibilityAcceptance",false);
        data.put("sourceSha256",candidate.inputSha256());data.put("historyBefore",HostProbe.historyData(root));data.put("baseline",baseline);
        data.put("vertices",p.geometry.meshes().stream().map(m->m.sourcePositions().size()).toList());data.put("triangles",p.geometry.meshes().stream().map(m->m.triangles().size()).toList());
        data.put("idealErrorBoundPixels",p.idealErrorBoundPixels);data.put("camera",Map.of("center",List.of(p.request.camera().centerX(),p.request.camera().centerY()),"scale",p.request.camera().scale(),"pivot",List.of(p.request.camera().pivot().x(),p.request.camera().pivot().y(),p.request.camera().pivot().z())));
        var stages=new ArrayList<String>();data.put("stages",stages);
        var result=HostProbe.edt(()->assembler.apply(new SurfaceWarpAuthoring.Observer() {
            @Override public void completed(SurfaceWarpAuthoring.Stage stage){stages.add(stage.name());}
            @Override public void parentRoundTrip(SurfaceWarpAuthoring.Created created){
                data.put("maximumParentLocalDrift",created.maximumParentLocalDrift());
                data.put("parentRoundTripErrorBoundPixels",created.parentRoundTripErrorBoundPixels());
                data.put("totalErrorBoundPixels",p.idealErrorBoundPixels+created.parentRoundTripErrorBoundPixels());
            }
        }));
        data.put("assemblyOutcome",result.outcome().name());
        data.put("assemblyDiagnostic",result.diagnosticId().orElse(null));
        data.put("historyAfterAssembly",HostProbe.historyData(HostProbe.edt(history::get)));
        data.put("inventoryAfterAssembly",HostProbe.edt(()->inventory(current.get())));
        require(result.outcome()==AuthoringTransactionOutcome.COMMITTED,"canonical production assembly failed: "+result.diagnosticId().orElse("no diagnostic"));
        require(stages.equals(List.of("IDENTITY","MATERIAL","ATTACHED","NEUTRAL","BOUND","BAKED")),"canonical stage inventory");
        var created=result.value().orElseThrow();var after=HostProbe.edt(history::get);data.put("historyAfter",HostProbe.historyData(after));
        data.put("maximumParentLocalDrift",created.maximumParentLocalDrift());
        data.put("parentRoundTripErrorBoundPixels",created.parentRoundTripErrorBoundPixels());
        data.put("totalErrorBoundPixels",p.idealErrorBoundPixels+created.parentRoundTripErrorBoundPixels());
        require(after.position()==root.position()+1,"canonical import is not one Undo entry");
        var stored=HostProbe.edt(()->{verify(current.get(),created,p);return current.get().warpDeformers().find(created.warpId()).authoringSnapshot();});
        var committedInventory=HostProbe.edt(()->inventory(current.get()));var bindings=HostProbe.edt(()->bindings(current.get(),created));
        data.put("storedForms",stored.forms().size());data.put("controlPointsPerForm",p.geometry.sourceControls().size());
        data.put("axes",stored.axes().stream().map(a->Map.of("id",a.parameterId().value(),"guid",a.parameterGuid(),"keys",a.keys())).toList());
        var formMap=new HashMap<String,WarpAuthoringSnapshot.Form>();for(var f:stored.forms())formMap.put(f.formGuid(),f);
        data.put("forms",stored.cells().stream().map(c->Map.of("guid",c.formGuid(),"coordinates",c.coordinates().stream().map(v->List.of(v.parameterGuid(),v.value())).toList(),"xySha256",SourceWarpCandidateProbe.digest(formMap.get(c.formGuid()).controlPoints()))).toList());
        data.put("sourceMeshSha256",created.sourceSnapshots().stream().map(s->SourceWarpCandidateProbe.geometryDigest(s.sourceGeometry())).toList());
        if(layered) {
            var layerForms=new ArrayList<Map<String,Object>>();
            for(int mesh=0;mesh<created.sourceSnapshots().size();mesh++) {
                var snapshot=created.sourceSnapshots().get(mesh);var formsByGuid=new HashMap<String,ArtMeshAuthoringSnapshot.Form>();snapshot.forms().forEach(f->formsByGuid.put(f.formGuid(),f));
                var local=snapshot.forms().get(0).positions();require(snapshot.forms().stream().allMatch(f->f.positions().equals(local)),"layer local positions changed across forms");
                String digest=SourceWarpCandidateProbe.digest(local);
                var localFile=captures.resolve("layer-local-"+mesh+".xy");
                try(var output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(localFile,StandardOpenOption.CREATE_NEW)))){for(var point:local){output.writeFloat(point.x());output.writeFloat(point.y());}}
                layerForms.add(Map.of("mesh",mesh,"artMeshId",snapshot.artMeshId().value(),"sourceGuid",snapshot.sourceGuid(),
                    "axes",snapshot.axes().stream().map(a->Map.of("id",a.parameterId().value(),"guid",a.parameterGuid(),"keys",a.keys())).toList(),
                    "localSha256",digest,"localCapture","canonical-warp-capture/"+localFile.getFileName(),"forms",snapshot.cells().stream().map(c->Map.of("guid",c.formGuid(),"coordinates",c.coordinates().stream().map(v->List.of(v.parameterGuid(),v.value())).toList(),"drawOrder",formsByGuid.get(c.formGuid()).drawOrder())).toList()));
            }
            data.put("layerForms",layerForms);
        }
        // Fail after a real form write; then re-read all controls and source/image state.
        var first=stored.cells().get(0);var changed=formMap.get(first.formGuid()).controlPoints().stream().map(v->new Point2(v.x()+7,v.y()+3)).toList();
        var failureBefore=HostProbe.edt(history::get);
        var failed=HostProbe.edt(()->transactions.execute(AuthoringTransactionOptions.of("Canonical Warp injected failure"),()->{
            current.get().warpDeformers().find(created.warpId()).setOrdinaryKeyformPositions(stored,List.of(new WarpKeyformPositions(first.coordinates(),first.formGuid(),changed)));
            throw new IllegalStateException("MI3D_CANONICAL_WARP_INJECTED_FAILURE");}));
        data.put("failureOutcome",failed.outcome().name());data.put("failureBefore",HostProbe.historyData(failureBefore));data.put("failureAfter",HostProbe.historyData(HostProbe.edt(history::get)));
        require(failed.outcome()==AuthoringTransactionOutcome.ROLLED_BACK && HostProbe.edt(history::get).position()==failureBefore.position(),"canonical failed write did not roll back history");
        HostProbe.edt(()->{verify(current.get(),created,p);require(stored.equals(current.get().warpDeformers().find(created.warpId()).authoringSnapshot()) && bindings.equals(bindings(current.get(),created)) && committedInventory.equals(inventory(current.get())),"canonical rollback did not restore all source/control/image state");return null;});
        data.put("failedWriteRollbackExact",true);
        var poses=new ArrayList<SurfaceProjection.Pose>();for(double y:List.of(-30d,0d,30d))for(double pitch:List.of(-30d,0d,30d))poses.add(new SurfaceProjection.Pose(y,pitch));
        poses.add(new SurfaceProjection.Pose(-13.125,9.375));poses.add(new SurfaceProjection.Pose(16.875,-24.375));
        var samples=new ArrayList<Map<String,Object>>();var frames=new ArrayList<Map<String,Object>>();data.put("samples",samples);data.put("pixelFrames",frames);
        for(int ordinal=0;ordinal<poses.size();ordinal++) {
            var pose=poses.get(ordinal);
            // Capture every requested counter before returning control to the EDT.
            // Waiting for mesh 0 first lets the same native pass consume mesh 1;
            // sampling its baseline afterwards would wait for an unscheduled pass.
            var requestedMeshes=HostProbe.edt(()->{
                current.get().parameters().find(p.request.yaw()).setValue((float)pose.yaw());
                current.get().parameters().find(p.request.pitch()).setValue((float)pose.pitch());
                return created.meshIds().stream().map(id->current.get().drawables().find(id).evaluationSnapshot()).toList();
            });
            for(int mesh=0;mesh<created.meshIds().size();mesh++) {
                var id=created.meshIds().get(mesh);var row=new LinkedHashMap<String,Object>();samples.add(row);row.put("pose",List.of(pose.yaw(),pose.pitch()));row.put("mesh",mesh);
                var requested=requestedMeshes.get(mesh);requirePose(requested.liveParameters(),p,pose);
                var actual=EvaluationProbe.awaitConsumed(requested,()->HostProbe.edt(()->current.get().drawables().find(id).evaluationSnapshot()),progress->{
                    if(!layered){row.putAll(progress);return;}
                    var filtered=new LinkedHashMap<>(progress);
                    @SuppressWarnings("unchecked") var observation=new LinkedHashMap<>((Map<String,Object>)progress.get("lastObservation"));
                    if(observation.get("calculated") instanceof Map<?,?> calculated) {
                        var detail=new LinkedHashMap<String,Object>();calculated.forEach((key,value)->{if(!key.equals("positions"))detail.put((String)key,value);});
                        observation.put("calculated",detail);
                    }
                    filtered.put("lastObservation",observation);row.putAll(filtered);
                },System::nanoTime,Thread::sleep);
                requirePose(actual.liveParameters(),p,pose);requirePose(actual.consumedParameters(),p,pose);
                var points=actual.calculatedForm().orElseThrow();double error=EvaluationProbe.maxError(actual,SurfaceProjection.project(p.geometry.meshes().get(mesh).sourcePositions(),p.request.camera(),pose).positions());
                require(error>=0 && error<=1 && points.coordinateType().equals("Canvas"),"canonical source evaluation exceeds exploratory 1px gate/wrong space");
                row.put("maxErrorPixels",error);row.put("coordinateType",points.coordinateType());row.put("xySha256",SourceWarpCandidateProbe.digest(points.positions()));
                row.put("live",SourceWarpCandidateProbe.parameterState(actual.liveParameters()));row.put("consumed",SourceWarpCandidateProbe.parameterState(actual.consumedParameters()));
                var file=captures.resolve("sample-"+(samples.size()-1)+".xy");
                try(var stream=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)))){for(var v:points.positions()){stream.writeFloat(v.x());stream.writeFloat(v.y());}}
                row.put("capture","canonical-warp-capture/"+file.getFileName());
            }
            var frame=new LinkedHashMap<String,Object>();frames.add(frame);capture(current,history,created,p,pose,stateDir.resolve("canonical-warp-pixels"),ordinal,frame);
        }
        HostProbe.edt(()->{verify(current.get(),created,p);require(stored.equals(current.get().warpDeformers().find(created.warpId()).authoringSnapshot()),"canonical render/evaluation changed authored forms");return null;});data.put("authoredFormsAfterSamplingExact",true);
        HostProbe.edt(()->{restore.accept(root.position());require(baseline.equals(inventory(current.get())),"canonical Undo left source/control/parameter/image objects");return null;});
        require(HostProbe.edt(history::get).position()==root.position(),"canonical Undo history");data.put("historyAfterUndo",HostProbe.historyData(HostProbe.edt(history::get)));data.put("undoInventoryExact",true);
        HostProbe.edt(()->{restore.accept(after.position());verify(current.get(),created,p);require(stored.equals(current.get().warpDeformers().find(created.warpId()).authoringSnapshot()) && bindings.equals(bindings(current.get(),created)) && committedInventory.equals(inventory(current.get())),"canonical Redo changed source/control/image identity");return null;});
        data.put("historyAfterRedo",HostProbe.historyData(HostProbe.edt(history::get)));data.put("redoSourceMeshesWarpImagesExact",true);
        HostProbe.edt(()->{restore.accept(root.position());require(baseline.equals(inventory(current.get())),"canonical final cleanup differs");return null;});data.put("status","PASS");
    }
    private static void verify(CubismModel model,SurfaceWarpAuthoring.Created created,SurfaceWarpAuthoring.Prepared p) {
        SurfaceWarpAuthoring.verifyInputs(model,created,p.images.inputs,Optional.of(created.warpId()));SurfaceWarpAuthoring.verifyImageInputs(model,created);
    }
    private static List<ArtMeshTextureInput> bindings(CubismModel model,SurfaceWarpAuthoring.Created created) {
        return created.meshIds().stream().map(id->model.textures().textureInput(id).orElseThrow()).toList();
    }
    private static Map<String,Object> inventory(CubismModel model) {
        return Map.of("meshes",model.drawables().all().stream().map(m->m.id().value()).sorted().toList(),"deformers",model.deformers().all().stream().map(d->d.id().value()).sorted().toList(),
            "parts",model.parts().all().stream().map(p->p.id().value()).sorted().toList(),"parameters",model.parameters().all().stream().map(p->p.id().value()).sorted().toList(),
            "images",model.textures().modelImageGroups().stream().map(g->Map.of("name",g.groupName(),"memo",g.memo(),"entries",g.modelImages().stream().map(i->Map.of("id",i.id().value(),"name",i.name(),"width",i.width(),"height",i.height())).toList())).toList());
    }
    private static void requirePose(ArtMeshEvaluationSnapshot.ParameterState state,SurfaceWarpAuthoring.Prepared p,SurfaceProjection.Pose pose) {
        var values=new HashMap<dev.turboism.sdk.cubism.id.ParameterId,Float>();for(var v:state.parameters())require(values.put(v.parameterId(),v.value())==null,"duplicate live/consumed parameter");
        require(Float.valueOf((float)pose.yaw()).equals(values.get(p.request.yaw())) && Float.valueOf((float)pose.pitch()).equals(values.get(p.request.pitch())),"canonical capture parameter mismatch");
    }
    private static void capture(Supplier<CubismModel> current,Supplier<HistorySnapshot> history,SurfaceWarpAuthoring.Created created,SurfaceWarpAuthoring.Prepared p,SurfaceProjection.Pose pose,Path directory,int ordinal,Map<String,Object> data) throws Exception {
        var pixels=HostProbe.edt(()->{
            var model=current.get();verify(model,created,p);var before=history.get();var imageInputs=bindings(model,created);var beforeWarp=model.warpDeformers().find(created.warpId()).authoringSnapshot();
            require(model.canvas().widthPixels()==1024 && model.canvas().heightPixels()==1024,"canonical capture canvas");
            data.put("historyBefore",HostProbe.historyData(before));var raster=model.renderPixels(1024,1024);verify(model,created,p);
            require(before.equals(history.get()) && imageInputs.equals(bindings(model,created)) && beforeWarp.equals(model.warpDeformers().find(created.warpId()).authoringSnapshot()),"canonical render mutated authored history/source/forms/images");
            data.put("historyAfter",HostProbe.historyData(history.get()));
            for(var id:created.meshIds()){var evaluation=model.drawables().find(id).evaluationSnapshot();requirePose(evaluation.liveParameters(),p,pose);requirePose(evaluation.consumedParameters(),p,pose);}
            return raster;
        });
        byte[] png=SourceWarpPixelCapture.encode(pixels);Files.createDirectories(directory);var file=directory.resolve(ordinal+".png");Files.write(file,png,StandardOpenOption.CREATE_NEW);
        data.put("pose",List.of(pose.yaw(),pose.pitch()));data.put("artifact",directory.getFileName()+"/"+file.getFileName());data.put("artifactBytes",png.length);data.put("artifactSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png)));data.put("status","CAPTURED_NATIVE_NOT_YET_INDEPENDENTLY_VERIFIED");
    }
    private static void require(boolean condition,String reason) {if(!condition)throw new IllegalStateException(reason);}
}
