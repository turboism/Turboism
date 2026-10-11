package dev.turboism.validation.modelimport3d.host;

import java.util.*;
import dev.turboism.sdk.cubism.model.Point2;

public final class PlanarQuadProofTest {
    static int checks;
    static SurfaceProjection.Vertex v(double x,double y,double z) {return new SurfaceProjection.Vertex(x,y,z);}
    static GlbSurfaceReader.Surface plane(List<SurfaceProjection.Vertex> p,List<Integer> i) {
        return new GlbSurfaceReader.Surface(0,0,0,0,p,Collections.nCopies(p.size(),v(0,0,1)),
            java.util.stream.IntStream.range(0,p.size()).mapToObj(n->new Point2(n%2,n/2f)).toList(),i,List.of(1d,1d,1d,1d),false,false);
    }
    static void check(boolean ok) {if(!ok)throw new AssertionError();checks++;}
    static void reject(GlbSurfaceReader.Surface s) {
        try {PlanarQuadProof.verify(List.of(s));}catch(IllegalArgumentException expected){checks++;return;}
        throw new AssertionError("unsafe quad admitted");
    }
    public static void main(String[] args) {
        var p=List.of(v(0,0,0),v(2,0,0),v(2,1,0),v(0,1,0));var indices=List.of(0,1,2,0,2,3);
        var s=plane(p,indices);var proof=PlanarQuadProof.verify(List.of(s));check(proof.boundaryEdges()==4 && proof.sourceSlots()==4);
        var seam=plane(List.of(p.get(0),p.get(1),p.get(2),p.get(0),p.get(2),p.get(3)),List.of(0,1,2,3,4,5));
        check(PlanarQuadProof.verify(List.of(seam)).sourceSlots()==6);check(seam.positions().size()==6 && seam.indices().equals(List.of(0,1,2,3,4,5)));
        check(PlanarQuadProof.verify(List.of(plane(p.stream().map(a->v(a.x()+16,a.y()-8,a.z()+4)).toList(),indices))).equals(proof));
        check(PlanarQuadProof.verify(List.of(plane(p.stream().map(a->v(a.z(),a.y(),a.x())).toList(),indices))).equals(proof));
        reject(plane(p,List.of(0,1,2,0,3,2))); // opposite winding
        reject(plane(p,List.of(0,1,2,0,1,2))); // duplicate triangle/unused corner
        reject(plane(p,List.of(0,1,1,0,2,3))); // degenerate
        reject(plane(p,List.of(0,1,2,0,2,4))); // invalid index
        reject(plane(List.of(p.get(0),p.get(1),v(.25,.25,0),p.get(3)),indices)); // concave boundary
        reject(plane(List.of(p.get(0),p.get(1),v(2,1,Math.ulp(1d)),p.get(3)),indices)); // exact nonplanarity
        reject(plane(List.of(p.get(0),p.get(1),v(1,0,0),p.get(3)),indices)); // collinear corner
        try {PlanarQuadProof.verify(List.of(s,s));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PlanarQuadProofTest: "+checks+" checks PASS; proof only; hostExecuted=false");
    }
}
