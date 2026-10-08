package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceQuadChartDiscoveryTest {
    static GlbSurfaceReader.Surface cube(int divisions,double offset) {
        return box(divisions,divisions,divisions,offset);
    }
    private static GlbSurfaceReader.Surface box(int dx,int dy,int dz,double offset) {
        var positions=new ArrayList<SurfaceProjection.Vertex>();var uv=new ArrayList<Point2>();var indices=new ArrayList<Integer>();
        // Outward-oriented per-face slots deliberately retain every UV seam.
        int[][] corners={{0,3,2,1},{4,5,6,7},{0,1,5,4},{3,7,6,2},{0,4,7,3},{1,2,6,5}};
        int[][] xyz={{0,0,0},{1,0,0},{1,1,0},{0,1,0},{0,0,1},{1,0,1},{1,1,1},{0,1,1}};
        for(int face=0;face<6;face++) {
            int base=positions.size();var a=xyz[corners[face][0]];var b=xyz[corners[face][1]];var d=xyz[corners[face][3]];
            int[] divisions={dx,dy,dz};int columns=0,rows=0;
            for(int axis=0;axis<3;axis++) {
                if(b[axis]!=a[axis])columns=divisions[axis];
                if(d[axis]!=a[axis])rows=divisions[axis];
            }
            for(int y=0;y<=rows;y++)for(int x=0;x<=columns;x++) {
                double u=x/(double)columns,v=y/(double)rows;
                positions.add(new SurfaceProjection.Vertex(offset+a[0]+u*(b[0]-a[0])+v*(d[0]-a[0]),a[1]+u*(b[1]-a[1])+v*(d[1]-a[1]),a[2]+u*(b[2]-a[2])+v*(d[2]-a[2])));
                uv.add(new Point2((float)((face+u)/7),(float)v));
            }
            for(int y=0;y<rows;y++)for(int x=0;x<columns;x++) {
                int a0=base+y*(columns+1)+x,b0=a0+1,d0=a0+columns+1,c0=d0+1;
                indices.addAll(List.of(a0,b0,c0,a0,c0,d0));
            }
        }
        return new GlbSurfaceReader.Surface(7,8,0,2,positions,List.of(),uv,indices,List.of(1d,1d,1d,1d),false,false);
    }
    private static GlbSurfaceReader.Surface withIndices(GlbSurfaceReader.Surface s,List<Integer> indices) {
        return new GlbSurfaceReader.Surface(s.node(),s.mesh(),s.primitive(),s.material(),s.positions(),s.sourceNormals(),s.textureUvs(),indices,s.baseColor(),s.doubleSided(),s.reflected());
    }
    @Test void rectangularStripsRetainAllSourceCornersWithoutUnnecessarySquareSplits() {
        var source=box(8,2,2,0);var result=SurfaceQuadChartDiscovery.discoverRectangular(List.of(source),32);
        assertEquals(18,SurfaceQuadChartDiscovery.discover(List.of(source),32).charts().size());
        assertEquals(6,result.charts().size());
        assertEquals(4,result.charts().stream().filter(c->Math.max(c.width(),c.height())==8
            && Math.min(c.width(),c.height())==2).count());
        var plan=SurfaceWarpChartPlan.compile(List.of(source),result.attribution(),
            result.attribution().stream().map(SurfaceWarpChartPlan.ChartTriangle::source).toList(),
            new SurfaceWarpChartPlan.Layout(6,8,3,3,100000,65535));
        assertEquals(source.indices().size()/3,result.attribution().size());
        assertEquals(0,plan.maximumSourceResidual(),1e-9);
        for(var mesh:plan.meshes())for(int i=0;i<mesh.indices().size();i++) {
            int original=source.indices().get(mesh.triangles().get(i/3).triangle()*3+i%3),slot=mesh.indices().get(i);
            assertEquals(original,mesh.slotIdentities().get(slot).sourceSlot());
            assertEquals(source.positions().get(original),mesh.sourcePositions().get(slot));
            assertEquals(source.textureUvs().get(original),mesh.sourceUvs().get(slot));
        }
    }
    @Test void tracesRegularSubdivisionsWithoutMergingRenderSlotsAndCompilesAllOriginalCorners() {
        var source=cube(4,1000);var before=source.positions();var discovered=SurfaceQuadChartDiscovery.discover(List.of(source),2);
        assertEquals(24,discovered.charts().size());assertTrue(discovered.charts().stream().allMatch(c->c.width()==2 && c.height()==2 && c.quads()==4));
        assertEquals(source.indices().size()/3,discovered.attribution().size());
        var plan=SurfaceWarpChartPlan.compile(List.of(source),discovered.attribution(),discovered.attribution().stream().map(SurfaceWarpChartPlan.ChartTriangle::source).toList(),new SurfaceWarpChartPlan.Layout(24,2,6,3,100000,65535));
        assertEquals(before,source.positions());assertEquals(0,plan.maximumSourceResidual(),1e-10);
        for(var mesh:plan.meshes())for(int i=0;i<mesh.indices().size();i++) {
            int sourceSlot=source.indices().get(mesh.triangles().get(i/3).triangle()*3+i%3),slot=mesh.indices().get(i);
            assertEquals(sourceSlot,mesh.slotIdentities().get(slot).sourceSlot());
            assertEquals(source.positions().get(sourceSlot),mesh.sourcePositions().get(slot));assertEquals(source.textureUvs().get(sourceSlot),mesh.sourceUvs().get(slot));
        }
        assertThrows(UnsupportedOperationException.class,()->discovered.charts().clear());
    }
    @Test void rejectsMissingFacesBrokenPairingAndNonmanifoldDuplicate() {
        var source=cube(2,0);var ix=source.indices();
        assertThrows(IllegalArgumentException.class,()->SurfaceQuadChartDiscovery.discover(List.of(withIndices(source,ix.subList(6,ix.size()))),32));
        var broken=new ArrayList<>(ix);broken.set(3,broken.get(1));
        assertThrows(IllegalArgumentException.class,()->SurfaceQuadChartDiscovery.discover(List.of(withIndices(source,broken)),32));
        var doubled=new ArrayList<>(ix);doubled.addAll(ix.subList(0,6));
        assertThrows(IllegalArgumentException.class,()->SurfaceQuadChartDiscovery.discover(List.of(withIndices(source,doubled)),32));
    }
    @Test void independentInstancesRetainTheirOwnSourceIdentityAndBoundChartCount() {
        var sources=List.of(cube(2,0),cube(2,3));var discovered=SurfaceQuadChartDiscovery.discover(sources,32);
        assertEquals(12,discovered.charts().size());assertEquals(48,discovered.attribution().stream().filter(t->t.source().surface()==1).count());
        assertThrows(IllegalArgumentException.class,()->SurfaceQuadChartDiscovery.discover(sources,33));
        assertThrows(IllegalArgumentException.class,()->SurfaceQuadChartDiscovery.discover(List.of(cube(16,0)),1));
    }
}
