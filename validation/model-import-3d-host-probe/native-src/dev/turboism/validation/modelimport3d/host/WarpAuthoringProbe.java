package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.WarpKeyformPositions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.IntConsumer;

/** Real tetrahedron through an identity-then-folded Warp; API gate, not apple/render acceptance. */
final class WarpAuthoringProbe {
    static final ParameterId YAW=new ParameterId("ParamMI3DWarpYaw"),PITCH=new ParameterId("ParamMI3DWarpPitch");
    static final List<SurfaceProjection.Vertex> SOURCE=List.of(new SurfaceProjection.Vertex(0,0,.35),
        new SurfaceProjection.Vertex(.3,.1,-.1),new SurfaceProjection.Vertex(-.3,.1,-.1),new SurfaceProjection.Vertex(0,-.3,-.1));
    static final SurfaceProjection.Camera CAMERA=new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0));
    static final List<Point2> LOCAL=List.of(new Point2(0,0),new Point2(1,0),new Point2(0,1),new Point2(1,1));
    static final List<Integer> INDICES=List.of(0,1,2,0,3,1,0,2,3,1,3,2);
    private WarpAuthoringProbe() { }
    static void run(Supplier<CubismModel> current,Supplier<HistorySnapshot> history,IntConsumer restore,
            AuthoringTransactionService transactions,Map<String,Object> data)throws Exception {
        data.put("status","RUNNING");data.put("scope","TETRAHEDRON_WARP_STORED_AND_CALCULATED_API_ONLY");
        data.put("source",SOURCE.stream().map(v->List.of(v.x(),v.y(),v.z())).toList());
        data.put("local",LOCAL.stream().map(p->List.of(p.x(),p.y())).toList());data.put("indices",INDICES);
        var keys=new ArrayList<Double>();for(int i=0;i<17;i++)keys.add(-30+3.75*i);
        var grid=new SurfaceKeyPlan.Grid(keys,keys,.1);var controls=new ArrayList<SurfaceWarpKeyformBake.Controls>();
        for(double y:keys)for(double p:keys)controls.add(new SurfaceWarpKeyformBake.Controls(new SurfaceWarpKeyformBake.Pose((float)y,(float)p),
            SurfaceProjection.project(SOURCE,CAMERA,new SurfaceProjection.Pose(y,p)).positions()));
        var root=HostProbe.edt(history::get);data.put("rootHistory",HostProbe.historyData(root));
        var warpId=HostProbe.edt(()->{
            var model=current.get();var warp=model.deformers().createWarp("MI3D_WARP_TETRA",null,-1,1,1);
            warp.replaceGrid(new WarpGrid(1,1,true,LOCAL));return warp.id();
        });
        var meshId=HostProbe.edt(()->{
            var mesh=current.get().drawables().create("MI3D_WARP_TETRA_SURFACE",null,-1,new ArtMeshGeometry(LOCAL,LOCAL,INDICES));
            mesh.setTargetDeformer(Optional.of(warpId));var actual=mesh.authoringSnapshot();
            NativeAssertions.require(actual.sourceGeometry().uvs().equals(LOCAL)
                && actual.sourceGeometry().triangleIndices().equals(INDICES),"identity attachment changed source UV/indices");
            for(var form:actual.forms())NativeAssertions.require(form.positions().equals(LOCAL),"identity attachment changed local stored positions");
            data.put("attached",NativeAssertions.snapshot(actual));return mesh.id();
        });
        HostProbe.edt(()->{
            var model=current.get();var warp=model.warpDeformers().find(warpId);
            var neutral=SurfaceProjection.project(SOURCE,CAMERA,new SurfaceProjection.Pose(0,0)).positions();
            warp.replaceGrid(new WarpGrid(1,1,true,neutral));
            var unbound=warp.authoringSnapshot();data.put("unbound",snapshot(unbound));
            NativeAssertions.require(unbound.forms().size()==1 && unbound.forms().get(0).controlPoints().equals(neutral),"neutral grid did not reach stored default");
            model.parameters().createMany(List.of(new ParameterDefinition(YAW,"MI3D Warp Yaw",-30,0,30,ParameterType.NORMAL,false),
                new ParameterDefinition(PITCH,"MI3D Warp Pitch",-30,0,30,ParameterType.NORMAL,false)));
            var floatKeys=SurfaceKeyformBake.bindingKeys(keys);
            for(var axis:List.of(YAW,PITCH)) {
                var points=new ArrayList<ParameterBindingPoint>();for(int i=0;i<floatKeys.size();i++)
                    points.add(new ParameterBindingPoint(new ParameterBindingPointId("WARP_"+axis.value()+"_"+i),floatKeys.get(i)));
                model.parameterBindings(axis).bind(ParameterBindingTarget.warpDeformer(warpId),points);
            }
            return null;
        });
        var before=HostProbe.edt(()->current.get().warpDeformers().find(warpId).authoringSnapshot());data.put("before",snapshot(before));
        var plan=SurfaceWarpKeyformBake.compile(before,YAW,PITCH,grid,controls);
        var beforeHistory=HostProbe.edt(history::get);data.put("writeBefore",HostProbe.historyData(beforeHistory));
        var result=HostProbe.edt(()->transactions.execute(AuthoringTransactionOptions.of("MI3D stored Warp bake"),()->{
            plan.write(current.get().warpDeformers().find(warpId));return null;}));
        data.put("writeOutcome",result.outcome().name());var afterHistory=HostProbe.edt(history::get);data.put("writeAfter",HostProbe.historyData(afterHistory));
        NativeAssertions.require(result.outcome()==AuthoringTransactionOutcome.COMMITTED && afterHistory.position()==beforeHistory.position()+1,"Warp bake not one native Undo entry");
        HostProbe.edt(()->{plan.verify(current.get().warpDeformers().find(warpId));return null;});
        data.put("written",snapshot(HostProbe.edt(()->current.get().warpDeformers().find(warpId).authoringSnapshot())));
        HostProbe.edt(()->{restore.accept(beforeHistory.position());return null;});
        var undone=HostProbe.edt(()->current.get().warpDeformers().find(warpId).authoringSnapshot());data.put("undone",snapshot(undone));
        NativeAssertions.require(undone.equals(before),"Warp Undo before-image mismatch");
        HostProbe.edt(()->{restore.accept(afterHistory.position());plan.verify(current.get().warpDeformers().find(warpId));return null;});
        data.put("redone",snapshot(HostProbe.edt(()->current.get().warpDeformers().find(warpId).authoringSnapshot())));
        var stable=HostProbe.edt(()->current.get().warpDeformers().find(warpId).authoringSnapshot());
        var first=plan.edits().get(0);var changed=first.controlPoints().stream().map(p->new Point2(p.x()+7,p.y()+3)).toList();
        var failureBefore=HostProbe.edt(history::get);
        var failed=HostProbe.edt(()->transactions.execute(AuthoringTransactionOptions.of("MI3D Warp injected failure"),()->{
            current.get().warpDeformers().find(warpId).setOrdinaryKeyformPositions(stable,List.of(new WarpKeyformPositions(first.coordinates(),first.formGuid(),changed)));
            throw new IllegalStateException("MI3D_WARP_INJECTED_FAILURE");}));
        data.put("failureOutcome",failed.outcome().name());data.put("failureBefore",HostProbe.historyData(failureBefore));
        data.put("failureAfter",HostProbe.historyData(HostProbe.edt(history::get)));
        var recovered=HostProbe.edt(()->current.get().warpDeformers().find(warpId).authoringSnapshot());data.put("recovered",snapshot(recovered));
        NativeAssertions.require(failed.outcome()==AuthoringTransactionOutcome.ROLLED_BACK && recovered.equals(stable)
            && HostProbe.edt(history::get).position()==failureBefore.position(),"Warp root failure did not recover exact forms/history");
        var samples=new ArrayList<Map<String,Object>>();data.put("samples",samples);
        var poses=new ArrayList<SurfaceProjection.Pose>();for(double y:List.of(-30d,0d,30d))for(double p:List.of(-30d,0d,30d))poses.add(new SurfaceProjection.Pose(y,p));
        poses.add(new SurfaceProjection.Pose(-13.125,9.375));poses.add(new SurfaceProjection.Pose(16.875,-24.375));
        for(var pose:poses) {
            var row=new LinkedHashMap<String,Object>();samples.add(row);row.put("pose",List.of(pose.yaw(),pose.pitch()));
            var requested=HostProbe.edt(()->{current.get().parameters().find(YAW).setValue((float)pose.yaw());current.get().parameters().find(PITCH).setValue((float)pose.pitch());
                return current.get().drawables().find(meshId).evaluationSnapshot();});
            row.put("requested",EvaluationProbe.evaluation(requested));
            var actual=EvaluationProbe.awaitConsumed(requested,()->HostProbe.edt(()->current.get().drawables().find(meshId).evaluationSnapshot()),row::putAll,System::nanoTime,Thread::sleep);
            row.put("actual",EvaluationProbe.evaluation(actual));var expected=SurfaceProjection.project(SOURCE,CAMERA,pose).positions();
            double error=EvaluationProbe.maxError(actual,expected);row.put("maxErrorPixels",error);
            NativeAssertions.require(error>=0 && error<=.1 && actual.calculatedForm().filter(f->f.coordinateType().equals("Canvas")).isPresent(),
                "Warp native tetrahedron projection missing/wrong-space/>0.1px");
        }
        HostProbe.edt(()->{restore.accept(root.position());return null;});data.put("status","PASS");
    }
    static Map<String,Object> snapshot(WarpAuthoringSnapshot value) {
        return Map.of("id",value.deformerId().value(),"sourceGuid",value.sourceGuid(),"rows",value.rows(),"columns",value.columns(),"quad",value.quadTransform(),
            "axes",value.axes().stream().map(a->Map.of("guid",a.parameterGuid(),"id",a.parameterId().value(),"minimum",a.minimum(),"default",a.defaultValue(),"maximum",a.maximum(),"keys",a.keys())).toList(),
            "cells",value.cells().stream().map(c->Map.of("guid",c.formGuid(),"coordinates",c.coordinates().stream().map(p->List.of(p.parameterGuid(),p.value())).toList())).toList(),
            "forms",value.forms().stream().map(f->Map.of("guid",f.formGuid(),"positions",f.controlPoints().stream().map(p->List.of(p.x(),p.y())).toList())).toList());
    }
}
