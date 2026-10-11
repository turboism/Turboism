package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Candidate draw patches from connected coplanar triangles, using source indices only.
 * These are not visibility/order proofs or edit controllers. Later overlap analysis may
 * require further source-space splits before any native topology is committed.
 */
final class SurfacePatchPlan {
    private SurfacePatchPlan() { }

    record Patch(int node, int mesh, int primitive, List<Integer> sourceVertices, List<Integer> sourceTriangles,
            List<SurfaceProjection.Vertex> positions, List<Point2> textureUvs, List<Integer> indices) {
        Patch {
            sourceVertices=List.copyOf(sourceVertices); sourceTriangles=List.copyOf(sourceTriangles);
            positions=List.copyOf(positions); textureUvs=List.copyOf(textureUvs); indices=List.copyOf(indices);
        }
    }

    private record Edge(int triangle, int from, int to) { }

    static List<Patch> plan(GlbSurfaceReader.Surface surface) {
        int count=surface.indices().size()/3;
        int[] parent=new int[count]; for(int i=0;i<count;i++)parent[i]=i;
        var edges=new HashMap<Long,List<Edge>>();
        for(int triangle=0;triangle<count;triangle++) {
            for(int edge=0;edge<3;edge++) {
                int from=surface.indices().get(3*triangle+edge), to=surface.indices().get(3*triangle+(edge+1)%3);
                long identity=((long)Math.min(from,to)<<32)|Math.max(from,to);
                var incident=edges.computeIfAbsent(identity,k->new ArrayList<>());
                incident.add(new Edge(triangle,from,to));
                if(incident.size()>2)throw new IllegalArgumentException("non-manifold source edge requires explicit splitting");
            }
        }
        for(var incident:edges.values()) {
            if(incident.size()!=2)continue;
            var a=incident.get(0); var b=incident.get(1);
            // Same numerical positions/UV values are never used to establish adjacency.
            if(a.from()==b.to() && a.to()==b.from() && coplanar(surface,a.triangle(),b.triangle())) {
                int ra=root(parent,a.triangle()),rb=root(parent,b.triangle());
                parent[Math.max(ra,rb)]=Math.min(ra,rb);
            }
        }
        var groups=new LinkedHashMap<Integer,List<Integer>>();
        for(int triangle=0;triangle<count;triangle++)groups.computeIfAbsent(root(parent,triangle),k->new ArrayList<>()).add(triangle);
        var result=new ArrayList<Patch>();
        for(var triangles:groups.values()) {
            var local=new LinkedHashMap<Integer,Integer>(); var indices=new ArrayList<Integer>();
            for(int triangle:triangles)for(int corner=0;corner<3;corner++) {
                int vertex=surface.indices().get(3*triangle+corner);
                indices.add(local.computeIfAbsent(vertex,k->local.size()));
            }
            var sourceVertices=List.copyOf(local.keySet());
            result.add(new Patch(surface.node(),surface.mesh(),surface.primitive(),sourceVertices,triangles,
                sourceVertices.stream().map(surface.positions()::get).toList(),
                sourceVertices.stream().map(surface.textureUvs()::get).toList(),indices));
        }
        return List.copyOf(result);
    }

    private static int root(int[] parent,int at) {
        while(parent[at]!=at){parent[at]=parent[parent[at]];at=parent[at];}
        return at;
    }

    private static boolean coplanar(GlbSurfaceReader.Surface source,int a,int b) {
        var pa=triangle(source,a); var pb=triangle(source,b);
        double[] na=normal(pa),nb=normal(pb);
        if(dot(na,nb)<1-1e-12)return false;
        double scale=1;
        for(var p:pa)scale=Math.max(scale,Math.max(Math.abs(p.x()),Math.max(Math.abs(p.y()),Math.abs(p.z()))));
        for(var p:pb)scale=Math.max(scale,Math.max(Math.abs(p.x()),Math.max(Math.abs(p.y()),Math.abs(p.z()))));
        // Only absorb floating-point plane noise, not a screen-space approximation budget.
        double tolerance=64*Math.ulp(scale);
        for(var p:pb)if(Math.abs(na[0]*(p.x()-pa.get(0).x())+na[1]*(p.y()-pa.get(0).y())+na[2]*(p.z()-pa.get(0).z()))>tolerance)return false;
        return true;
    }

    private static List<SurfaceProjection.Vertex> triangle(GlbSurfaceReader.Surface source,int triangle) {
        return List.of(source.positions().get(source.indices().get(3*triangle)),
            source.positions().get(source.indices().get(3*triangle+1)), source.positions().get(source.indices().get(3*triangle+2)));
    }
    private static double[] normal(List<SurfaceProjection.Vertex> p) {
        double ax=p.get(1).x()-p.get(0).x(),ay=p.get(1).y()-p.get(0).y(),az=p.get(1).z()-p.get(0).z();
        double bx=p.get(2).x()-p.get(0).x(),by=p.get(2).y()-p.get(0).y(),bz=p.get(2).z()-p.get(0).z();
        double[] n={ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx}; double length=Math.hypot(Math.hypot(n[0],n[1]),n[2]);
        if(!Double.isFinite(length)||length==0)throw new IllegalArgumentException("invalid source plane");
        for(int i=0;i<3;i++)n[i]/=length;return n;
    }
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
}
