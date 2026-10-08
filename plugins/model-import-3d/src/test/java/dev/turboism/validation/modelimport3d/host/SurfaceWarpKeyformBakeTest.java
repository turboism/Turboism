package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Axis;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Form;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceWarpKeyformBakeTest {
    static final ParameterId YAW=new ParameterId("Yaw"),PITCH=new ParameterId("Pitch");
    static final List<Float> KEYS=List.of(-30f,0f,30f);
    static final SurfaceKeyPlan.Grid GRID=new SurfaceKeyPlan.Grid(List.of(-30d,0d,30d),List.of(-30d,0d,30d),1);
    static List<Point2> points(float y,float p){return List.of(new Point2(y,p),new Point2(y+1,p),new Point2(y,p+1),new Point2(y+1,p+1));}
    static WarpAuthoringSnapshot before(){
        var cells=new ArrayList<Cell>();var forms=new ArrayList<Form>();int i=0;
        for(float y:KEYS)for(float p:KEYS){String guid="f"+i++;cells.add(new Cell(guid,List.of(new Coordinate("pitch-guid",p),new Coordinate("yaw-guid",y))));forms.add(new Form(guid,points(0,0)));}
        Collections.reverse(forms);Collections.reverse(cells);
        return new WarpAuthoringSnapshot(new DeformerId("Warp"),"source",1,1,true,
            List.of(new Axis("pitch-guid",PITCH,-30,0,30,KEYS),new Axis("yaw-guid",YAW,-30,0,30,KEYS)),cells,forms);
    }
    static List<SurfaceWarpKeyformBake.Controls> controls(){var values=new ArrayList<SurfaceWarpKeyformBake.Controls>();
        for(float y:KEYS)for(float p:KEYS)values.add(new SurfaceWarpKeyformBake.Controls(new SurfaceWarpKeyformBake.Pose(y,p),points(y,p)));return values;}
    @Test void resolvesFullGuidTuplesRegardlessOfStorageOrderAndLeavesDefaultUntouched(){
        var before=before();var plan=SurfaceWarpKeyformBake.compile(before,YAW,PITCH,GRID,controls());
        assertEquals(8,plan.edits().size());assertTrue(plan.edits().stream().noneMatch(e->e.formGuid().equals("f4")));
        assertEquals(before.axes(),plan.expected().axes());assertEquals(before.cells(),plan.expected().cells());
        for(var edit:plan.edits()){
            float y=edit.coordinates().stream().filter(c->c.parameterGuid().equals("yaw-guid")).findFirst().orElseThrow().value();
            float p=edit.coordinates().stream().filter(c->c.parameterGuid().equals("pitch-guid")).findFirst().orElseThrow().value();
            assertEquals(points(y,p),edit.controlPoints());assertEquals("f"+(3*KEYS.indexOf(y)+KEYS.indexOf(p)),edit.formGuid());
        }
    }
    @Test void rejectsIncompleteOrDuplicatePosesAndNeutralGeometryMismatchBeforeWriting(){
        var input=controls();input.remove(0);assertThrows(IllegalArgumentException.class,()->SurfaceWarpKeyformBake.compile(before(),YAW,PITCH,GRID,input));
        var duplicate=controls();duplicate.set(0,duplicate.get(1));assertThrows(IllegalArgumentException.class,()->SurfaceWarpKeyformBake.compile(before(),YAW,PITCH,GRID,duplicate));
        var badNeutral=controls();badNeutral.set(4,new SurfaceWarpKeyformBake.Controls(new SurfaceWarpKeyformBake.Pose(0,0),points(1,1)));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpKeyformBake.compile(before(),YAW,PITCH,GRID,badNeutral));
    }
}
