package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpKeyformPositions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** SDK-only complete joint-grid control bake. Does not admit visibility or source charts. */
final class SurfaceWarpKeyformBake {
    private SurfaceWarpKeyformBake() { }
    record Pose(float yaw,float pitch) { }
    record Controls(Pose pose,List<Point2> points) {
        Controls { points=List.copyOf(points); }
    }
    record Plan(WarpAuthoringSnapshot before,List<WarpKeyformPositions> edits,WarpAuthoringSnapshot expected) {
        Plan { edits=List.copyOf(edits); }
        void write(WarpDeformer warp) { warp.setOrdinaryKeyformPositions(before,edits); }
        void verify(WarpDeformer warp) {
            require(warp.authoringSnapshot().equals(expected),"Warp bake protected state/readback mismatch");
        }
    }
    static Plan compile(WarpAuthoringSnapshot before,ParameterId yaw,ParameterId pitch,SurfaceKeyPlan.Grid grid,
            List<Controls> controls) {
        require(!yaw.equals(pitch) && before.axes().size()==2,"Warp bake requires exactly two distinct axes");
        var ys=SurfaceKeyformBake.bindingKeys(grid.yawKeys());var ps=SurfaceKeyformBake.bindingKeys(grid.pitchKeys());
        var ya=axis(before,yaw,ys);var pa=axis(before,pitch,ps);
        int count=Math.multiplyExact(ys.size(),ps.size());
        long samples=Math.multiplyExact((long)(before.rows()+1)*(before.columns()+1),count);
        require(count<=4096 && samples<=SurfaceImportPlan.MAX_PROJECTED_VERTICES && samples<=WarpAuthoringSnapshot.MAX_CONTROL_SAMPLES,
            "Warp bake control/shape budget exceeded");
        require(before.cells().size()==count && before.forms().size()==count && controls.size()==count,
            "Warp bake requires complete independent joint forms");
        var cells=new HashMap<Map<String,Float>,String>();var guids=new HashSet<String>();
        for(var cell:before.cells()) {
            var tuple=new HashMap<String,Float>();
            for(var coordinate:cell.coordinates())require(tuple.putIfAbsent(coordinate.parameterGuid(),coordinate.value())==null,"duplicate Warp dimension");
            require(cells.putIfAbsent(Map.copyOf(tuple),cell.formGuid())==null && guids.add(cell.formGuid()),"duplicate/shared Warp form");
        }
        var byPose=new HashMap<Pose,List<Point2>>();
        for(var item:List.copyOf(controls)) require(byPose.putIfAbsent(item.pose(),item.points())==null,"duplicate Warp control pose");
        var forms=new HashMap<String,Form>();before.forms().forEach(f->forms.put(f.formGuid(),f));
        var replacements=new HashMap<String,List<Point2>>();var edits=new java.util.ArrayList<WarpKeyformPositions>();
        for(float y:ys)for(float p:ps) {
            String guid=cells.get(Map.of(ya.parameterGuid(),y,pa.parameterGuid(),p));
            var points=byPose.get(new Pose(y,p));
            require(guid!=null && points!=null && points.size()==((long)before.rows()+1)*(before.columns()+1),"missing Warp pose or wrong controls");
            if(y==0 && p==0)require(forms.get(guid).controlPoints().equals(points),"stored Warp default is not the exact neutral grid");
            else edits.add(new WarpKeyformPositions(List.of(new Coordinate(ya.parameterGuid(),y),new Coordinate(pa.parameterGuid(),p)),guid,points));
            replacements.put(guid,points);
        }
        var expected=new WarpAuthoringSnapshot(before.deformerId(),before.sourceGuid(),before.rows(),before.columns(),before.quadTransform(),
            before.axes(),before.cells(),before.forms().stream().map(f->new Form(f.formGuid(),replacements.get(f.formGuid()))).toList());
        return new Plan(before,edits,expected);
    }
    private static WarpAuthoringSnapshot.Axis axis(WarpAuthoringSnapshot before,ParameterId id,List<Float> keys) {
        var matches=before.axes().stream().filter(a->a.parameterId().equals(id)).toList();
        require(matches.size()==1,"missing/ambiguous Warp parameter ID");var axis=matches.get(0);
        require(axis.defaultValue()==0 && axis.minimum()==keys.get(0) && axis.maximum()==keys.get(keys.size()-1)
            && axis.keys().size()==new HashSet<>(axis.keys()).size() && axis.keys().stream().sorted().toList().equals(keys),"Warp declared keys differ");
        return axis;
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
}
