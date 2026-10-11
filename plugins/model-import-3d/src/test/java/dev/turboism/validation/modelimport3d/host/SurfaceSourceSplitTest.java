package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceSourceSplitTest {
    private static SurfaceSourceSplit.Source source(int triangle) {
        return new SurfaceSourceSplit.Source(4,7,2,triangle,List.of(
            new SurfaceProjection.Vertex(0,0,0),new SurfaceProjection.Vertex(2,0,0),new SurfaceProjection.Vertex(0,2,0)),
            List.of(new Point2(.1f,.2f),new Point2(.9f,.2f),new Point2(.1f,.8f)));
    }
    private static double area(SurfaceSourceSplit.Polygon p) {
        double sum=0;
        for(int i=0;i<p.corners().size();i++) {
            var a=p.corners().get(i).position(p.source());var b=p.corners().get((i+1)%p.corners().size()).position(p.source());
            sum+=a.x()*b.y()-a.y()*b.x();
        }
        return sum/2;
    }
    @Test void crossingKeepsWindingSharedCutAndSourceUv() {
        var source=source(9);var split=SurfaceSourceSplit.split(SurfaceSourceSplit.Polygon.triangle(source),new SurfaceSourceSplit.Plane(1,0,0,-1));
        assertNull(split.coplanar());assertEquals(4,split.negative().corners().size());assertEquals(3,split.positive().corners().size());
        assertEquals(2,area(split.negative())+area(split.positive()),1e-14);
        for(var p:List.of(split.negative(),split.positive())) {
            assertSame(source,p.source());assertTrue(area(p)>0);
            for(var corner:p.corners()) {
                var pos=corner.position(source);var uv=corner.uv(source);
                assertEquals(source.uvs().get(0).x()+((double)source.uvs().get(1).x()-source.uvs().get(0).x())*pos.x()/2,uv.u(),1e-14);
                assertEquals(source.uvs().get(0).y()+((double)source.uvs().get(2).y()-source.uvs().get(0).y())*pos.y()/2,uv.v(),1e-14);
            }
        }
        var common=new ArrayList<>(split.negative().corners());common.retainAll(split.positive().corners());
        assertEquals(2,common.size());for(var c:common)assertEquals(1,c.position(source).x(),1e-14);
    }
    @Test void coplanarAndEdgeContactsDoNotCreateDuplicateFragments() {
        var p=SurfaceSourceSplit.Polygon.triangle(source(1));
        assertSame(p,SurfaceSourceSplit.split(p,new SurfaceSourceSplit.Plane(0,0,1,0)).coplanar());
        var edge=SurfaceSourceSplit.split(p,new SurfaceSourceSplit.Plane(1,0,0,0));
        assertSame(p,edge.positive());assertNull(edge.negative());
        var vertex=SurfaceSourceSplit.split(p,new SurfaceSourceSplit.Plane(1,1,0,0));
        assertSame(p,vertex.positive());assertNull(vertex.negative());
        var through=SurfaceSourceSplit.split(p,new SurfaceSourceSplit.Plane(1,-1,0,0));
        assertEquals(3,through.positive().corners().size());assertEquals(3,through.negative().corners().size());
    }
    @Test void repeatedCutsPreserveCoverageAndDistinctSourceIdentities() {
        var random=new Random(401903);var original=source(3);
        var pieces=new ArrayList<>(List.of(SurfaceSourceSplit.Polygon.triangle(original)));
        for(int step=0;step<30;step++) {
            double angle=random.nextDouble()*Math.PI*2;var plane=new SurfaceSourceSplit.Plane(Math.cos(angle),Math.sin(angle),0,random.nextDouble()*3-1.5);
            var next=new ArrayList<SurfaceSourceSplit.Polygon>();
            for(var piece:pieces) {
                var split=SurfaceSourceSplit.split(piece,plane);
                if(split.negative()!=null) {
                    next.add(split.negative());for(var c:split.negative().corners())assertTrue(plane.distance(c.position(original))<=split.tolerance()*2);
                }
                if(split.positive()!=null) {
                    next.add(split.positive());for(var c:split.positive().corners())assertTrue(plane.distance(c.position(original))>=-split.tolerance()*2);
                }
                if(split.coplanar()!=null)next.add(split.coplanar());
            }
            pieces=next;assertEquals(2,pieces.stream().mapToDouble(SurfaceSourceSplitTest::area).sum(),1e-12);
            for(var p:pieces){assertSame(original,p.source());assertTrue(area(p)>0);}
        }
        var other=SurfaceSourceSplit.Polygon.triangle(source(4));assertNotEquals(original,other.source());
        assertEquals(original.positions(),other.source().positions());
    }
    @Test void invalidNumericInputsFailBeforePartitioning() {
        assertThrows(IllegalArgumentException.class,()->new SurfaceSourceSplit.Plane(0,0,0,1));
        assertThrows(IllegalArgumentException.class,()->new SurfaceSourceSplit.Plane(1,0,0,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new SurfaceSourceSplit.Bary(-.1,.5,.6));
        assertThrows(IllegalArgumentException.class,()->new SurfaceSourceSplit.Bary(0,0,.9));
    }
}
