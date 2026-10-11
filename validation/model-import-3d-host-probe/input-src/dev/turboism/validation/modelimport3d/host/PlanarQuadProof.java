package dev.turboism.validation.modelimport3d.host;

import java.math.BigDecimal;
import java.util.*;

/** Exact visibility precondition for one convex two-triangle plane.
 * Position equivalence is analysis-only: no source slots, UVs or indices are welded.
 * Does not admit multiple surfaces, nonplanar quads or overlapping triangles.
 */
final class PlanarQuadProof {
    private PlanarQuadProof() {}
    record Proof(int sourceSlots,int geometricVertices,int triangles,int boundaryEdges) {}
    private record V(BigDecimal x,BigDecimal y,BigDecimal z) {
        V(SurfaceProjection.Vertex p) {this(new BigDecimal(p.x()),new BigDecimal(p.y()),new BigDecimal(p.z()));}
        V minus(V b) {return new V(x.subtract(b.x),y.subtract(b.y),z.subtract(b.z));}
        V cross(V b) {return new V(y.multiply(b.z).subtract(z.multiply(b.y)),z.multiply(b.x).subtract(x.multiply(b.z)),x.multiply(b.y).subtract(y.multiply(b.x)));}
        BigDecimal dot(V b) {return x.multiply(b.x).add(y.multiply(b.y)).add(z.multiply(b.z));}
        boolean zero() {return x.signum()==0 && y.signum()==0 && z.signum()==0;}
    }
    private record Edge(int low,int high) {}
    private record Use(int from,int to) {}
    static Proof verify(List<GlbSurfaceReader.Surface> surfaces) {
        require(surfaces.size()==1,"one plane primitive required");var surface=surfaces.get(0);
        require(!surface.reflected() && !surface.doubleSided(),"single-sided nonreflected plane required");
        require(surface.baseColor().size()==4 && surface.baseColor().get(3)==1d,"opaque plane required");
        require(surface.positions().size()>=4 && surface.positions().size()<=6 && surface.indices().size()==6,"two-triangle quad budget");
        var positions=new LinkedHashMap<V,Integer>();var local=new ArrayList<Integer>();
        for(var p:surface.positions()) {
            require(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()),"finite plane coordinates");
            // BigDecimal(double) gives exact binary value; strip scales for key equality.
            var v=new V(p);v=new V(v.x.stripTrailingZeros(),v.y.stripTrailingZeros(),v.z.stripTrailingZeros());
            local.add(positions.computeIfAbsent(v,key->positions.size()));
        }
        require(positions.size()==4,"four geometric corners required");
        var vertices=List.copyOf(positions.keySet());var edges=new HashMap<Edge,List<Use>>();
        var used=new HashSet<Integer>();V normal=null;
        for(int triangle=0;triangle<2;triangle++) {
            int[] t=new int[3];
            for(int j=0;j<3;j++) {
                int index=surface.indices().get(3*triangle+j);require(index>=0 && index<local.size(),"plane source index");
                used.add(index);t[j]=local.get(index);
            }
            var a=vertices.get(t[0]);var n=vertices.get(t[1]).minus(a).cross(vertices.get(t[2]).minus(a));
            require(!n.zero(),"degenerate plane triangle");
            if(normal==null)normal=n;
            else require(normal.cross(n).zero() && normal.dot(n).signum()>0,"plane normals differ");
            for(var v:vertices)require(n.dot(v.minus(a)).signum()==0,"nonplanar quad");
            for(int j=0;j<3;j++) {
                int from=t[j],to=t[(j+1)%3];var key=new Edge(Math.min(from,to),Math.max(from,to));
                edges.computeIfAbsent(key,k->new ArrayList<>()).add(new Use(from,to));
            }
        }
        require(used.size()==surface.positions().size(),"unused source slots");
        int shared=0,boundary=0;var outgoing=new HashMap<Integer,Integer>();var incoming=new HashSet<Integer>();
        for(var uses:edges.values()) {
            require(uses.size()<=2,"nonmanifold plane edge");
            if(uses.size()==2) {
                var a=uses.get(0);var b=uses.get(1);
                require(a.from==b.to && a.to==b.from,"shared edge winding");shared++;
            } else {
                var edge=uses.get(0);boundary++;
                require(outgoing.put(edge.from,edge.to)==null && incoming.add(edge.to),"branched boundary");
                var a=vertices.get(edge.from);var direction=vertices.get(edge.to).minus(a);
                for(int i=0;i<4;i++)if(i!=edge.from && i!=edge.to)
                    require(normal.dot(direction.cross(vertices.get(i).minus(a))).signum()>0,"concave or overlapping plane");
            }
        }
        require(shared==1 && boundary==4 && outgoing.size()==4 && incoming.size()==4,"quad topology");
        int at=0;var visited=new HashSet<Integer>();
        for(int i=0;i<4;i++){require(visited.add(at),"disconnected boundary");at=outgoing.get(at);}
        require(at==0,"open boundary");
        return new Proof(surface.positions().size(),4,2,4);
    }
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalArgumentException(message);}
}
