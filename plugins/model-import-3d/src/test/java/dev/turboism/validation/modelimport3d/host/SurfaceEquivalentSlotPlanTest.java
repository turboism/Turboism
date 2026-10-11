package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceEquivalentSlotPlanTest {
    private static SurfaceWarpChartPlan.Plan input(boolean distinctUv,boolean distinctNormal,boolean distinctLocal,boolean distinctChart) {
        var positions=List.of(new SurfaceProjection.Vertex(0,0,0),new SurfaceProjection.Vertex(1,0,0),
            new SurfaceProjection.Vertex(1,1,0),new SurfaceProjection.Vertex(0,1,0),
            new SurfaceProjection.Vertex(0,0,0),new SurfaceProjection.Vertex(1,1,0));
        var uvs=List.of(new Point2(0,0),new Point2(1,0),new Point2(1,1),new Point2(0,1),
            new Point2(distinctUv?.1f:0,0),new Point2(1,1));
        var local=new java.util.ArrayList<>(uvs);if(distinctLocal)local.set(4,new Point2(.5f,0));
        var normal=new SurfaceProjection.Vertex(0,0,1);
        var normals=distinctNormal?List.of(normal,normal,normal,normal,new SurfaceProjection.Vertex(0,0,-1),normal):List.<SurfaceProjection.Vertex>of();
        var indices=List.of(0,1,2,4,5,3);
        var source=new GlbSurfaceReader.Surface(0,0,0,0,positions,normals,uvs,indices,List.of(1d,1d,1d,1d),false,false);
        var identities=java.util.stream.IntStream.range(0,6).mapToObj(i->new SurfaceWarpChartPlan.Slot(0,i,distinctChart && i==4?1:0)).toList();
        var triangles=List.of(new SurfaceWarpChartPlan.Triangle(0,0),new SurfaceWarpChartPlan.Triangle(0,1));
        var mesh=new SurfaceWarpChartPlan.Mesh(positions,uvs,local,identities,indices,triangles);
        return new SurfaceWarpChartPlan.Plan(List.of(source),List.of(mesh),3,3,positions,0,144);
    }
    @Test void exactDuplicateRemovalRetainsEveryFaceCornerAndOriginalSource() {
        var original=input(false,false,false,false);var result=SurfaceEquivalentSlotPlan.compile(original);
        assertEquals(6,result.originalSlots());assertEquals(4,result.optimizedSlots());assertEquals(2,result.removedSlots());
        assertEquals(List.of(List.of(0,1,2,3,0,2)),result.oldToNew());
        assertEquals(original.sources(),result.plan().sources());assertEquals(original.sourceControls(),result.plan().sourceControls());
        assertEquals(original.controlSamples(),result.plan().controlSamples());
        var before=original.meshes().get(0);var after=result.plan().meshes().get(0);
        assertEquals(before.triangles(),after.triangles());assertEquals(List.of(0,1,2,0,2,3),after.indices());
        for(int corner=0;corner<before.indices().size();corner++) {
            int a=before.indices().get(corner),b=after.indices().get(corner);
            assertEquals(before.sourcePositions().get(a),after.sourcePositions().get(b));
            assertEquals(before.sourceUvs().get(a),after.sourceUvs().get(b));
            assertEquals(before.warpLocal().get(a),after.warpLocal().get(b));
        }
        assertEquals(6,before.sourcePositions().size());
        assertThrows(UnsupportedOperationException.class,()->result.oldToNew().get(0).set(0,999));
        assertEquals(0,SurfaceEquivalentSlotPlan.compile(result.plan()).removedSlots());
    }
    @Test void seamsNormalsChartsAndWarpCoordinatesAreNeverCollapsed() {
        for(var original:List.of(input(true,false,false,false),input(false,true,false,false),
                input(false,false,true,false),input(false,false,false,true))) {
            var optimized=SurfaceEquivalentSlotPlan.compile(original);
            assertEquals(5,optimized.optimizedSlots());assertNotEquals(optimized.oldToNew().get(0).get(0),optimized.oldToNew().get(0).get(4));
        }
    }
    @Test void invalidSourceProvenanceAndIndicesFailClosed() {
        var p=input(false,false,false,false);var m=p.meshes().get(0);
        var bad=new SurfaceWarpChartPlan.Mesh(m.sourcePositions(),m.sourceUvs(),m.warpLocal(),m.slotIdentities(),List.of(0,1,999,4,5,3),m.triangles());
        var plan=new SurfaceWarpChartPlan.Plan(p.sources(),List.of(bad),p.columns(),p.rows(),p.sourceControls(),0,p.controlSamples());
        assertThrows(IllegalArgumentException.class,()->SurfaceEquivalentSlotPlan.compile(plan));
    }
}
