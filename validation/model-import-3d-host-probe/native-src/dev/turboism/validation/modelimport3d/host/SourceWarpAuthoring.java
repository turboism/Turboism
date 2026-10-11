package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SDK-only assembly of a frozen candidate. Does not establish visibility or material admission.
 * Caller owns a native transaction, memory budget and failure recovery. */
final class SourceWarpAuthoring {
    private SourceWarpAuthoring() { }
    enum Stage { IDENTITY, ATTACHED, NEUTRAL, BOUND, BAKED, MATERIAL }
    @FunctionalInterface interface Observer { void completed(Stage stage); }
    record Created(DeformerId warpId,List<ArtMeshId> meshIds) {
        Created { meshIds=List.copyOf(meshIds); }
    }
    record MeshInput(ArtMeshGeometry sourceGeometry,List<Point2> defaultLocal) {
        MeshInput {
            defaultLocal=List.copyOf(defaultLocal);
            if(defaultLocal.size()!=sourceGeometry.positions().size())throw new IllegalArgumentException("source/default vertex count");
        }
    }
    static Created assemble(CubismModel model,SourceWarpFixture source,ParameterId yaw,ParameterId pitch,Observer observer) {
        return assemble(model,source,yaw,pitch,observer,null);
    }
    static Created assemble(CubismModel model,SourceWarpFixture source,ParameterId yaw,ParameterId pitch,Observer observer,SourceWarpAppearance.Plan appearance) {
        var inputs=appearance==null ? source.meshes.stream().map(m->{var geometry=m.geometry();return new MeshInput(geometry,geometry.positions());}).toList() : appearance.inputs;
        if(inputs.size()!=source.meshes.size())throw new IllegalArgumentException("image inputs/source mesh count");
        if(yaw.equals(pitch) || model.parameters().all().stream().anyMatch(p->p.id().equals(yaw) || p.id().equals(pitch)))
            throw new IllegalArgumentException("candidate parameter identities must be new and distinct");
        var part=model.parts().create("3D source Warp candidate",null,-1);
        var warp=model.deformers().createWarp("3D source Warp",part,-1,source.rows,source.columns);
        var identity=new ArrayList<Point2>();
        for(int r=0;r<=source.rows;r++)for(int c=0;c<=source.columns;c++)
            identity.add(new Point2((float)c/source.columns,(float)r/source.rows));
        warp.replaceGrid(new WarpGrid(source.rows,source.columns,true,identity));
        observer.completed(Stage.IDENTITY);
        var meshes=new ArrayList<ArtMeshId>();
        for(int i=0;i<inputs.size();i++) {
            var input=inputs.get(i);
            var mesh=appearance==null ? model.drawables().create("3D source surface "+i,part,-1,input.sourceGeometry())
                : model.drawables().createWithSourceGeometry("3D source surface "+i,part,-1,input.sourceGeometry(),input.defaultLocal());
            mesh.setCulling(true);meshes.add(mesh.id());
        }
        var created=new Created(warp.id(),meshes);
        // Verified image API rejects deformable-parented targets. Bind at the root first.
        if(appearance!=null){SourceWarpAppearance.bind(model,created,appearance);observer.completed(Stage.MATERIAL);}
        for(var id:meshes)model.drawables().find(id).setTargetDeformer(Optional.of(warp.id()));
        verifyInputs(model,created,inputs);
        observer.completed(Stage.ATTACHED);
        // The neutral grid folds chart space only after native parent attachment.
        warp.replaceGrid(new WarpGrid(source.rows,source.columns,true,source.controls(144)));
        verifyInputs(model,created,inputs);observer.completed(Stage.NEUTRAL);
        model.parameters().createMany(List.of(
            new ParameterDefinition(yaw,"3D Yaw",-30,0,30,ParameterType.NORMAL,false),
            new ParameterDefinition(pitch,"3D Pitch",-30,0,30,ParameterType.NORMAL,false)));
        var keys=new ArrayList<Double>();for(int i=0;i<17;i++)keys.add(-30+3.75*i);
        for(var axis:List.of(yaw,pitch)) {
            var points=new ArrayList<ParameterBindingPoint>();
            for(int i=0;i<17;i++)points.add(new ParameterBindingPoint(new ParameterBindingPointId("WARP_"+axis.value()+"_"+i),keys.get(i).floatValue()));
            model.parameterBindings(axis).bind(ParameterBindingTarget.warpDeformer(warp.id()),points);
        }
        verifyInputs(model,created,inputs);observer.completed(Stage.BOUND);
        var before=warp.authoringSnapshot();var controls=new ArrayList<SurfaceWarpKeyformBake.Controls>();
        for(int i=0;i<source.poseCount();i++)controls.add(new SurfaceWarpKeyformBake.Controls(source.pose(i),source.controls(i)));
        var bake=SurfaceWarpKeyformBake.compile(before,yaw,pitch,new SurfaceKeyPlan.Grid(keys,keys,.1),controls);
        bake.write(warp);bake.verify(warp);verifyInputs(model,created,inputs);observer.completed(Stage.BAKED);
        return created;
    }
    static void verifyMeshes(CubismModel model,Created created,List<ArtMeshGeometry> expected) {
        verifyInputs(model,created,expected.stream().map(g->new MeshInput(g,g.positions())).toList());
    }
    static void verifyInputs(CubismModel model,Created created,List<MeshInput> expected) {
        verifyInputs(model,created,expected,Optional.of(created.warpId()));
    }
    static void verifyInputs(CubismModel model,Created created,List<MeshInput> expected,Optional<DeformerId> expectedParent) {
        if(created.meshIds().size()!=expected.size())throw new IllegalStateException("source mesh count changed");
        for(int i=0;i<expected.size();i++) {
            var mesh=model.drawables().find(created.meshIds().get(i));var actual=mesh.authoringSnapshot();var input=expected.get(i);var original=input.sourceGeometry();
            if(!mesh.parentDeformerId().equals(expectedParent) || !mesh.culling()
                || !actual.sourceGeometry().equals(original) || actual.forms().size()!=1
                || !actual.forms().get(0).positions().equals(input.defaultLocal()) || !actual.axes().isEmpty() || !actual.morphKeys().isEmpty())
                throw new IllegalStateException("native assembly changed source Warp local/UV/indices or ownership");
        }
    }
}
