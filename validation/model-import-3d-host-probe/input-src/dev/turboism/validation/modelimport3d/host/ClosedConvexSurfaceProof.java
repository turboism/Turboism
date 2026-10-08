package dev.turboism.validation.modelimport3d.host;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Conservative visibility precondition only; does not enable native culling or alter source slots.
 * Exact binary-double coordinates establish seam adjacency for analysis, never stored geometry.
 * Ambiguous/nonmatching seams and reflected instances are refused rather than repaired.
 */
final class ClosedConvexSurfaceProof {
    private ClosedConvexSurfaceProof() { }
    record Proof(int sourceSlots, int geometricVertices, int triangles, int edges, BigDecimal sixVolume) { }
    private record Position(double x, double y, double z) {
        Position(SurfaceProjection.Vertex v) { this(zero(v.x()), zero(v.y()), zero(v.z())); }
        private static double zero(double v) { return v == 0 ? 0 : v; }
    }
    private record Exact(BigDecimal x, BigDecimal y, BigDecimal z) {
        Exact(Position p) { this(new BigDecimal(p.x()), new BigDecimal(p.y()), new BigDecimal(p.z())); }
        Exact minus(Exact p) { return new Exact(x.subtract(p.x), y.subtract(p.y), z.subtract(p.z)); }
        Exact cross(Exact p) { return new Exact(y.multiply(p.z).subtract(z.multiply(p.y)),
            z.multiply(p.x).subtract(x.multiply(p.z)), x.multiply(p.y).subtract(y.multiply(p.x))); }
        BigDecimal dot(Exact p) { return x.multiply(p.x).add(y.multiply(p.y)).add(z.multiply(p.z)); }
        boolean zero() { return x.signum()==0 && y.signum()==0 && z.signum()==0; }
    }
    private record Edge(int low, int high) { }
    private record Use(int triangle, int from, int to) { }

    static Proof verify(List<GlbSurfaceReader.Surface> surfaces) {
        require(!surfaces.isEmpty(), "empty solid");
        long slots=surfaces.stream().mapToLong(s->s.positions().size()).sum();
        long indices=surfaces.stream().mapToLong(s->s.indices().size()).sum();
        require(slots<=1024 && indices<=768, "convex proof budget exceeded");
        var identities=new LinkedHashMap<Position,Integer>();
        var triangles=new ArrayList<int[]>();
        for(var surface:surfaces) {
            require(!surface.reflected(), "reflected winding requires explicit conversion");
            require(surface.baseColor().size()==4 && surface.baseColor().get(3)==1d, "opaque material required");
            require(!surface.indices().isEmpty() && surface.indices().size()%3==0, "triangle indices required");
            var local=new ArrayList<Integer>();
            for(var vertex:surface.positions()) {
                require(Double.isFinite(vertex.x()) && Double.isFinite(vertex.y()) && Double.isFinite(vertex.z()), "nonfinite vertex");
                local.add(identities.computeIfAbsent(new Position(vertex), k->identities.size()));
            }
            for(int i=0;i<surface.indices().size();i+=3) {
                int[] triangle=new int[3];
                for(int j=0;j<3;j++) {
                    int index=surface.indices().get(i+j);
                    require(index>=0 && index<local.size(), "vertex index outside surface");
                    triangle[j]=local.get(index);
                }
                triangles.add(triangle);
            }
        }
        require(identities.size()>=4 && identities.size()<=256, "geometric vertex budget/dimension");
        var vertices=identities.keySet().stream().map(Exact::new).toList();
        var edges=new HashMap<Edge,List<Use>>();
        BigDecimal volume=BigDecimal.ZERO;
        for(int i=0;i<triangles.size();i++) {
            int[] t=triangles.get(i);var a=vertices.get(t[0]);var b=vertices.get(t[1]);var c=vertices.get(t[2]);
            var normal=b.minus(a).cross(c.minus(a));
            require(!normal.zero(), "degenerate triangle");
            boolean strict=false;
            for(var vertex:vertices) {
                int side=normal.dot(vertex.minus(a)).signum();
                require(side<=0, "nonconvex or inward-facing triangle");
                strict|=side<0;
            }
            require(strict, "flat solid");
            volume=volume.add(a.minus(vertices.get(0)).dot(b.minus(vertices.get(0)).cross(c.minus(vertices.get(0)))));
            for(int j=0;j<3;j++) {
                int from=t[j],to=t[(j+1)%3];
                var uses=edges.computeIfAbsent(new Edge(Math.min(from,to),Math.max(from,to)),k->new ArrayList<>());
                uses.add(new Use(i,from,to));
                require(uses.size()<=2, "nonmanifold geometric edge");
            }
        }
        var neighbors=new ArrayList<List<Integer>>();
        for(int i=0;i<triangles.size();i++)neighbors.add(new ArrayList<>());
        for(var uses:edges.values()) {
            require(uses.size()==2, "open or nonmatching geometric seam");
            var a=uses.get(0);var b=uses.get(1);
            require(a.from()==b.to() && a.to()==b.from(), "inconsistent winding");
            neighbors.get(a.triangle()).add(b.triangle());neighbors.get(b.triangle()).add(a.triangle());
        }
        boolean[] seen=new boolean[triangles.size()];var queue=new ArrayDeque<Integer>();queue.add(0);int visited=0;
        while(!queue.isEmpty()) {
            int at=queue.remove();if(seen[at])continue;seen[at]=true;visited++;queue.addAll(neighbors.get(at));
        }
        require(visited==triangles.size() && volume.signum()>0, "disconnected or zero-volume solid");
        return new Proof((int)slots,vertices.size(),triangles.size(),edges.size(),volume.stripTrailingZeros());
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalArgumentException(message);
    }
}
