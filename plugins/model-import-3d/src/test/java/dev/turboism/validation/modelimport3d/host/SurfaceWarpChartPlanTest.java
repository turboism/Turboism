package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceWarpChartPlanTest {
    private static GlbSurfaceReader.Surface surface(int id) {
        var positions=List.of(new SurfaceProjection.Vertex(0,0,id),new SurfaceProjection.Vertex(1,0,id),new SurfaceProjection.Vertex(1,1,id),new SurfaceProjection.Vertex(0,1,id));
        return new GlbSurfaceReader.Surface(id,id,0,id,positions,List.of(),List.of(new Point2(.2f,.1f),new Point2(.9f,.2f),new Point2(.8f,.9f),new Point2(.1f,.8f)),List.of(0,1,2,0,2,3),List.of(1d,1d,1d,1d),false,false);
    }
    private static List<SurfaceWarpChartPlan.ChartTriangle> attribution() {
        var result=new ArrayList<SurfaceWarpChartPlan.ChartTriangle>();
        var p=List.of(new SurfaceWarpChartPlan.Parameter(0,0),new SurfaceWarpChartPlan.Parameter(1,0),new SurfaceWarpChartPlan.Parameter(1,1),new SurfaceWarpChartPlan.Parameter(0,1));
        for(int s=0;s<2;s++) {
            result.add(new SurfaceWarpChartPlan.ChartTriangle(new SurfaceWarpChartPlan.Triangle(s,0),s,List.of(p.get(0),p.get(1),p.get(2))));
            result.add(new SurfaceWarpChartPlan.ChartTriangle(new SurfaceWarpChartPlan.Triangle(s,1),s,List.of(p.get(0),p.get(2),p.get(3))));
        }
        return result;
    }
    private static SurfaceWarpChartPlan.Layout layout(int slots,long budget) {return new SurfaceWarpChartPlan.Layout(2,1,2,3,budget,slots);}
    @Test void preservesEverySourceCornerAndRequestedPainterSequenceAcrossSlotSplits() {
        var surfaces=List.of(surface(0),surface(1));var attr=attribution();var order=attr.stream().map(SurfaceWarpChartPlan.ChartTriangle::source).toList();
        var reversed=new ArrayList<>(order);Collections.reverse(reversed);
        var plan=SurfaceWarpChartPlan.compile(surfaces,attr,reversed,layout(3,1000));assertEquals(4,plan.meshes().size());assertEquals(135,plan.controlSamples());
        assertEquals(reversed,plan.meshes().stream().flatMap(m->m.triangles().stream()).toList());
        for(var mesh:plan.meshes())for(int i=0;i<mesh.indices().size();i++) {
            var t=mesh.triangles().get(i/3);var source=surfaces.get(t.surface());int sourceSlot=source.indices().get(t.triangle()*3+i%3),slot=mesh.indices().get(i);
            assertEquals(source.positions().get(sourceSlot),mesh.sourcePositions().get(slot));assertEquals(source.textureUvs().get(sourceSlot),mesh.sourceUvs().get(slot));
            assertEquals(new SurfaceWarpChartPlan.Slot(t.surface(),sourceSlot,t.surface()),mesh.slotIdentities().get(slot));
        }
        assertEquals(surfaces,plan.sources());assertEquals(0,plan.maximumSourceResidual());
        assertThrows(UnsupportedOperationException.class,()->plan.meshes().get(0).indices().set(0,999));
    }
    @Test void budgetAndNonPermutationAreRejectedWithoutInventingOrDroppingTriangles() {
        var surfaces=List.of(surface(0),surface(1));var attr=attribution();var order=attr.stream().map(SurfaceWarpChartPlan.ChartTriangle::source).toList();
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpChartPlan.compile(surfaces,attr,order,layout(65535,134)));
        var duplicate=new ArrayList<>(order);duplicate.set(0,duplicate.get(1));assertThrows(IllegalArgumentException.class,()->SurfaceWarpChartPlan.compile(surfaces,attr,duplicate,layout(65535,1000)));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpChartPlan.compile(surfaces,attr.subList(1,4),order,layout(65535,1000)));
    }
    @Test void chartConflictFailsAndProjectionRetainsOneFixedCamera() {
        var surfaces=List.of(surface(0),surface(1));var attr=attribution();var order=attr.stream().map(SurfaceWarpChartPlan.ChartTriangle::source).toList();
        var conflicting=new ArrayList<>(attr);var c=conflicting.get(1);conflicting.set(1,new SurfaceWarpChartPlan.ChartTriangle(c.source(),c.chart(),List.of(new SurfaceWarpChartPlan.Parameter(.5,0),c.corners().get(1),c.corners().get(2))));
        assertThrows(IllegalArgumentException.class,()->SurfaceWarpChartPlan.compile(surfaces,conflicting,order,layout(65535,1000)));
        var plan=SurfaceWarpChartPlan.compile(surfaces,attr,order,layout(65535,1000));var camera=new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0));
        for(var pose:List.of(new SurfaceProjection.Pose(0,0),new SurfaceProjection.Pose(-13,21)))assertEquals(SurfaceProjection.project(plan.sourceControls(),camera,pose).positions(),plan.projectControls(camera,pose));
    }
}
