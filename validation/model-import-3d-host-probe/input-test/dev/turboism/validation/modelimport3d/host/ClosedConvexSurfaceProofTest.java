package dev.turboism.validation.modelimport3d.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Actual seam-split input plus topology and visibility counterexamples. No native evidence. */
public final class ClosedConvexSurfaceProofTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var surfaces=GlbSurfaceReader.read(Files.readAllBytes(Path.of(args[0])));
        var original=surfaces.get(0);var proof=ClosedConvexSurfaceProof.verify(surfaces);
        check(proof.sourceSlots()==24 && proof.geometricVertices()==8 && proof.triangles()==12 && proof.edges()==18,
            "cube seam topology");
        check(proof.sixVolume().signum()>0,"outward nonzero volume");
        check(original.equals(surfaces.get(0)) && original.positions().size()==24,"source slots unchanged");
        reject(List.of(copy(original,original.positions(),original.indices().subList(3,36),false,1d)));
        var reversed=new ArrayList<>(original.indices());
        int swap=reversed.get(0);reversed.set(0,reversed.get(1));reversed.set(1,swap);
        reject(List.of(copy(original,original.positions(),reversed,false,1d)));
        var duplicate=new ArrayList<>(original.indices());duplicate.addAll(original.indices().subList(0,3));
        reject(List.of(copy(original,original.positions(),duplicate,false,1d)));
        var seam=new ArrayList<>(original.positions());var v=seam.get(0);
        seam.set(0,new SurfaceProjection.Vertex(Math.nextUp(v.x()),v.y(),v.z()));
        reject(List.of(copy(original,seam,original.indices(),false,1d)));
        // Move every seam copy of one corner inside the cube: closure survives but convexity does not.
        var concave=original.positions().stream().map(p->p.equals(v)?new SurfaceProjection.Vertex(0,0,0):p).toList();
        reject(List.of(copy(original,concave,original.indices(),false,1d)));
        var flat=original.positions().stream().map(p->new SurfaceProjection.Vertex(p.x(),p.y(),0)).toList();
        reject(List.of(copy(original,flat,original.indices(),false,1d)));
        reject(List.of(copy(original,original.positions(),original.indices(),true,1d)));
        reject(List.of(copy(original,original.positions(),original.indices(),false,.5d)));
        reject(List.of(original,original));
        reject(List.of());
        var translated=original.positions().stream().map(p->new SurfaceProjection.Vertex(p.x()+16,p.y()-8,p.z()+4)).toList();
        check(ClosedConvexSurfaceProof.verify(List.of(copy(original,translated,original.indices(),false,1d)))
            .sixVolume().compareTo(proof.sixVolume())==0,"translation preserves volume");
        System.out.println("ClosedConvexSurfaceProofTest: "+checks+" checks PASS; nativeCullingVerified=false");
    }
    private static GlbSurfaceReader.Surface copy(GlbSurfaceReader.Surface s,List<SurfaceProjection.Vertex> p,
            List<Integer> indices,boolean reflected,double alpha) {
        return new GlbSurfaceReader.Surface(s.node(),s.mesh(),s.primitive(),s.material(),p,s.sourceNormals(),
            s.textureUvs(),indices,List.of(.8,.8,.8,alpha),s.doubleSided(),reflected);
    }
    private static void reject(List<GlbSurfaceReader.Surface> surfaces) {
        try { ClosedConvexSurfaceProof.verify(surfaces); }
        catch(IllegalArgumentException expected) { checks++;return; }
        throw new AssertionError("unsafe surface accepted");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message);checks++; }
}
