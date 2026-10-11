package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Full source Warp candidate assembly observations. Deliberately not a visibility/render gate. */
final class SourceWarpCandidateProbe {
    private SourceWarpCandidateProbe() { }
    static final ParameterId YAW=new ParameterId("ParamMI3DSourceWarpYaw"),PITCH=new ParameterId("ParamMI3DSourceWarpPitch");
    static void run(SourceWarpFixture source,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,
            IntConsumer restore,AuthoringTransactionService transactions,java.nio.file.Path stateDir,Map<String,Object> data) throws Exception {
        run(source,current,history,restore,transactions,stateDir,data,null);
    }
    static void run(SourceWarpFixture source,Supplier<CubismModel> current,Supplier<HistorySnapshot> history,
            IntConsumer restore,AuthoringTransactionService transactions,java.nio.file.Path stateDir,Map<String,Object> data,
            SourceWarpAppearance.Plan appearance) throws Exception {
        var captures=stateDir.resolve("source-warp-capture");java.nio.file.Files.createDirectory(captures);
        data.put("scope",appearance==null ? "FULL_SOURCE_WARP_CANDIDATE_GEOMETRY_ONLY" : "FULL_SOURCE_WARP_TEXTURED_CAPTURE_ONLY");data.put("status","RUNNING");
        data.put("materials",appearance==null ? "NOT_BOUND_SOURCE_OBJ_HAS_NO_MATERIAL" : "SOURCE_GLB_TEXTURES_SHARED_ATLAS");data.put("visibilityAcceptance",false);
        data.put("vertices",source.meshes.stream().map(m->m.sourceVertex.length).toList());
        data.put("triangles",source.meshes.stream().map(m->m.indices.length/3).toList());
        var root=HostProbe.edt(history::get);var stages=new ArrayList<String>();data.put("stages",stages);
        data.put("historyBefore",HostProbe.historyData(root));
        var result=HostProbe.edt(()->transactions.execute(AuthoringTransactionOptions.of("3D original source Warp candidate"),()->
            SourceWarpAuthoring.assemble(current.get(),source,YAW,PITCH,s->stages.add(s.name()),appearance)));
        data.put("assemblyOutcome",result.outcome().name());
        NativeAssertions.require(result.outcome()==AuthoringTransactionOutcome.COMMITTED,"source Warp candidate assembly failed: "+result);
        var created=result.value().orElseThrow();
        var after=HostProbe.edt(history::get);data.put("historyAfter",HostProbe.historyData(after));
        NativeAssertions.require(after.position()==root.position()+1,"candidate assembly not one native Undo entry");
        var inputs=appearance==null ? source.meshes.stream().map(m->{var g=m.geometry();return new SourceWarpAuthoring.MeshInput(g,g.positions());}).toList() : appearance.inputs;
        var stored=HostProbe.edt(()->{
            SourceWarpAuthoring.verifyInputs(current.get(),created,inputs);
            return current.get().warpDeformers().find(created.warpId()).authoringSnapshot();
        });
        var imageInputs=appearance==null ? null : HostProbe.edt(()->SourceWarpPixelCapture.verifyBindings(current.get(),created));
        data.put("sourceMeshSha256",HostProbe.edt(()->created.meshIds().stream().map(id->geometryDigest(current.get().drawables().find(id).authoringSnapshot().sourceGeometry())).toList()));
        data.put("storedForms",stored.forms().size());data.put("controlPointsPerForm",(source.rows+1)*(source.columns+1));
        // No millions-of-points JSON dump. Each form's raw binary32 coordinate digest is observed.
        var forms=new ArrayList<Map<String,Object>>();data.put("forms",forms);
        for(var cell:stored.cells()) {
            var form=stored.forms().stream().filter(f->f.formGuid().equals(cell.formGuid())).findFirst().orElseThrow();
            forms.add(Map.of("guid",cell.formGuid(),"coordinates",cell.coordinates().stream().map(c->List.of(c.parameterGuid(),c.value())).toList(),"xySha256",digest(form.controlPoints())));
        }
        data.put("axes",stored.axes().stream().map(a->Map.of("id",a.parameterId().value(),"guid",a.parameterGuid(),"keys",a.keys())).toList());
        var camera=new SurfaceProjection.Camera(512,512+2.954462184876577*130.6125549674034,2.954462184876577,
            new SurfaceProjection.Vertex(0,130.6125549674034,0));
        var samples=new ArrayList<Map<String,Object>>();data.put("samples",samples);
        var poses=new ArrayList<SurfaceProjection.Pose>();for(double y:List.of(-30d,0d,30d))for(double p:List.of(-30d,0d,30d))poses.add(new SurfaceProjection.Pose(y,p));
        poses.add(new SurfaceProjection.Pose(-13.125,9.375));poses.add(new SurfaceProjection.Pose(16.875,-24.375));
        var frames=new ArrayList<Map<String,Object>>();
        if(appearance!=null)data.put("pixelFrames",frames);
        int ordinal=0;
        for(var pose:poses) {
            HostProbe.edt(()->{current.get().parameters().find(YAW).setValue((float)pose.yaw());current.get().parameters().find(PITCH).setValue((float)pose.pitch());return null;});
            for(int i=0;i<source.meshes.size();i++) {
                int index=i;var id=created.meshIds().get(i);var row=new LinkedHashMap<String,Object>();samples.add(row);
                row.put("pose",List.of(pose.yaw(),pose.pitch()));row.put("mesh",i);
                var requested=HostProbe.edt(()->current.get().drawables().find(id).evaluationSnapshot());
                var actual=EvaluationProbe.awaitConsumed(requested,()->HostProbe.edt(()->current.get().drawables().find(id).evaluationSnapshot()),row::putAll,System::nanoTime,Thread::sleep);
                var xyz=source.meshes.get(index).source3D;var vertices=new ArrayList<SurfaceProjection.Vertex>();
                for(int k=0;k<xyz.length;k+=3)vertices.add(new SurfaceProjection.Vertex(xyz[k],xyz[k+1],xyz[k+2]));
                double error=EvaluationProbe.maxError(actual,SurfaceProjection.project(vertices,camera,pose).positions());
                row.put("maxErrorPixels",error);row.put("coordinateType",actual.calculatedForm().map(f->f.coordinateType()).orElse("MISSING"));
                row.put("xySha256",actual.calculatedForm().map(f->digest(f.positions())).orElse("MISSING"));
                row.put("live",parameterState(actual.liveParameters()));row.put("consumed",parameterState(actual.consumedParameters()));
                var file=captures.resolve("sample-"+(samples.size()-1)+".xy");
                try(var output=new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(file,java.nio.file.StandardOpenOption.CREATE_NEW)))) {
                    for(var point:actual.calculatedForm().orElseThrow().positions()){output.writeFloat(point.x());output.writeFloat(point.y());}
                }
                row.put("capture","source-warp-capture/"+file.getFileName());
                NativeAssertions.require(error>=0 && error<=1 && "Canvas".equals(row.get("coordinateType")),"candidate source projection exceeds exploratory 1px geometry gate");
            }
            if(appearance!=null) {
                var frame=new LinkedHashMap<String,Object>();frames.add(frame);
                SourceWarpPixelCapture.capture(created,appearance,current,history,pose,stateDir.resolve("source-warp-pixels"),ordinal,frame);
            }
            ordinal++;
        }
        // Check the entire authored grid across evaluation/rendering; history alone is insufficient.
        HostProbe.edt(()->{NativeAssertions.require(current.get().warpDeformers().find(created.warpId()).authoringSnapshot().equals(stored),"candidate evaluation/render changed authored controls");return null;});
        data.put("authoredFormsAfterSamplingExact",true);
        HostProbe.edt(()->{restore.accept(root.position());return null;});data.put("historyAfterUndo",HostProbe.historyData(HostProbe.edt(history::get)));
        NativeAssertions.require(HostProbe.edt(history::get).position()==root.position(),"candidate Undo did not return root history");
        HostProbe.edt(()->{restore.accept(after.position());SourceWarpAuthoring.verifyInputs(current.get(),created,inputs);
            NativeAssertions.require(current.get().warpDeformers().find(created.warpId()).authoringSnapshot().equals(stored),"candidate Redo did not restore complete Warp forms");
            if(imageInputs!=null)NativeAssertions.require(imageInputs.equals(SourceWarpPixelCapture.verifyBindings(current.get(),created)),"candidate Redo changed image identity");
            return null;});data.put("redoSourceMeshesAndWarpExact",true);
        if(imageInputs!=null)data.put("redoImageInputsExact",true);
        HostProbe.edt(()->{restore.accept(root.position());return null;});data.put("status","PASS");
    }
    static String geometryDigest(dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry) {
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");var bytes=java.nio.ByteBuffer.allocate(8192);
            for(var points:List.of(geometry.positions(),geometry.uvs()))for(var p:points) {
                if(bytes.remaining()<8){hash.update(bytes.array(),0,bytes.position());bytes.clear();}
                bytes.putFloat(p.x()).putFloat(p.y());
            }
            for(int index:geometry.triangleIndices()) {
                if(bytes.remaining()<4){hash.update(bytes.array(),0,bytes.position());bytes.clear();}bytes.putInt(index);
            }
            hash.update(bytes.array(),0,bytes.position());return java.util.HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static Map<String,Object> parameterState(dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState state) {
        return Map.of("updateVersion",state.updateVersion(),"parameters",state.parameters().stream().map(p->Map.of("id",p.parameterId().value(),"guid",p.parameterGuid(),"value",p.value())).toList());
    }
    static String digest(List<Point2> points) {
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");var bytes=java.nio.ByteBuffer.allocate(8192);
            for(var p:points) { if(bytes.remaining()<8){hash.update(bytes.array(),0,bytes.position());bytes.clear();}bytes.putFloat(p.x()).putFloat(p.y()); }
            hash.update(bytes.array(),0,bytes.position());return java.util.HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
