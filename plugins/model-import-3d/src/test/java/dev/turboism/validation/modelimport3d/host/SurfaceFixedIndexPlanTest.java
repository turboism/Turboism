package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceFixedIndexPlanTest {
    private static GlbSurfaceReader.Surface source(int node,double z,int material) {
        return new GlbSurfaceReader.Surface(node,0,0,material,List.of(
            new SurfaceProjection.Vertex(0,0,z),new SurfaceProjection.Vertex(1,0,z),new SurfaceProjection.Vertex(0,1,z),
            new SurfaceProjection.Vertex(0,0,z),new SurfaceProjection.Vertex(1,0,z),new SurfaceProjection.Vertex(0,1,z)),
            List.of(),List.of(new Point2(0,0),new Point2(1,0),new Point2(0,1),new Point2(0,0),new Point2(1,0),new Point2(0,1)),
            List.of(0,1,2,3,4,5),List.of(1d,1d,1d,1d),false,false);
    }
    @Test void sameAppearanceRunsPackWithoutWeldingEqualSlotsOrInstances() {
        var sources=List.of(source(0,0,-1),source(1,1,-1));
        var plan=SurfaceFixedIndexPlan.compile(sources,new SurfaceFixedIndexPlan.Budget(65535,10,100,100));
        assertEquals(1,plan.patches().size());assertEquals(12,plan.expandedSlots());assertEquals(12,plan.indices());
        var patch=plan.patches().get(0);assertEquals(List.of(1,1,0,0),patch.triangles().stream().map(SurfaceFixedIndexPlan.Triangle::surface).toList());
        assertEquals(12,new HashSet<>(patch.slots()).size());verify(sources,plan);
    }
    @Test void appearanceAndNativeVertexLimitsSplitTheFixedSequenceWithoutLosingTriangles() {
        var sources=List.of(source(0,0,0),source(1,1,1));
        assertEquals(2,SurfaceFixedIndexPlan.compile(sources,new SurfaceFixedIndexPlan.Budget(65535,10,100,100)).patches().size());
        var limited=SurfaceFixedIndexPlan.compile(sources,new SurfaceFixedIndexPlan.Budget(3,10,100,100));
        assertEquals(4,limited.patches().size());verify(sources,limited);
        for(var p:limited.patches())assertEquals(3,p.slots().size());
    }
    @Test void aggregateBudgetsRejectBeforeNativeCreation() {
        var sources=List.of(source(0,0,-1),source(1,1,-1));
        assertThrows(IllegalArgumentException.class,()->SurfaceFixedIndexPlan.compile(sources,new SurfaceFixedIndexPlan.Budget(65535,10,6,100)));
        assertThrows(IllegalArgumentException.class,()->SurfaceFixedIndexPlan.compile(sources,new SurfaceFixedIndexPlan.Budget(3,1,100,100)));
        assertThrows(IllegalArgumentException.class,()->SurfaceFixedIndexPlan.compile(sources,new SurfaceFixedIndexPlan.Budget(65535,10,100,9)));
    }
    private static void verify(List<GlbSurfaceReader.Surface> sources,SurfaceFixedIndexPlan.Plan plan) {
        var seen=new HashSet<String>();
        for(var patch:plan.patches())for(int t=0;t<patch.triangles().size();t++) {
            var original=patch.triangles().get(t);assertTrue(seen.add(original.surface()+":"+original.triangle()));
            var source=sources.get(original.surface());
            for(int corner=0;corner<3;corner++) {
                int local=patch.indices().get(t*3+corner),sourceSlot=source.indices().get(original.triangle()*3+corner);
                assertEquals(new SurfaceFixedIndexPlan.Slot(original.surface(),sourceSlot),patch.slots().get(local));
                assertEquals(source.positions().get(sourceSlot),patch.positions().get(local));
                assertEquals(source.textureUvs().get(sourceSlot),patch.uvs().get(local));
            }
        }
        assertEquals(sources.stream().mapToInt(s->s.indices().size()/3).sum(),seen.size());
    }
}
