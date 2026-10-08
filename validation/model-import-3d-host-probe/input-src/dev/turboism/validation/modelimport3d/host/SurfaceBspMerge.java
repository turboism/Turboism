package dev.turboism.validation.modelimport3d.host;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Groups consecutive fragments whose painter order is invariant over a symmetric
 * Ry*Rx domain. Plane-sign bounds use exact decimal arithmetic with conservative
 * rational angle bounds. Geometry, UV corners and source identities are untouched.
 * This reduces draw groups only, not keyform vertices; native index draw order and
 * interpolated order transitions still require separate verification.
 */
final class SurfaceBspMerge {
    private SurfaceBspMerge() { }
    record Domain(double yawLimit,double pitchLimit) {
        Domain {
            require(Double.isFinite(yawLimit) && Double.isFinite(pitchLimit)
                && yawLimit>=0 && yawLimit<=30 && pitchLimit>=0 && pitchLimit<=30,"bounded rotation domain");
        }
    }
    record Patch(int id,List<Integer> fragments,int corners) {
        Patch {fragments=List.copyOf(fragments);}
    }
    private sealed interface Op permits Fixed,Sequence,Branch { }
    private record Fixed(List<Integer> ids) implements Op {
        Fixed {ids=List.copyOf(ids);}
    }
    private record Sequence(List<Op> parts) implements Op {
        Sequence {parts=List.copyOf(parts);}
    }
    private record Branch(SurfaceSourceSplit.Plane plane,Op lower,Op on,Op upper) implements Op { }
    static final class Plan {
        private final Domain domain;private final Op root;private final List<Patch> patches;
        Plan(Domain domain,Op root,List<Patch> patches) {this.domain=domain;this.root=root;this.patches=List.copyOf(patches);}
        List<Patch> patches() {return patches;}
        List<Integer> backToFront(double yaw,double pitch) {
            require(Double.isFinite(yaw) && Double.isFinite(pitch) && Math.abs(yaw)<=domain.yawLimit()
                && Math.abs(pitch)<=domain.pitchLimit(),"view outside merge domain");
            double y=Math.toRadians(yaw),p=Math.toRadians(pitch);
            var direction=new SurfaceProjection.Vertex(-Math.sin(y),Math.cos(y)*Math.sin(p),Math.cos(y)*Math.cos(p));
            var result=new ArrayList<Integer>();append(root,direction,result);return List.copyOf(result);
        }
        private static void append(Op op,SurfaceProjection.Vertex d,List<Integer> result) {
            if(op instanceof Fixed f)result.addAll(f.ids());
            else if(op instanceof Sequence s)for(var child:s.parts())append(child,d,result);
            else if(op instanceof Branch b) {
                var n=b.plane();boolean positive=n.x()*d.x()+n.y()*d.y()+n.z()*d.z()>=0;
                append(positive?b.upper():b.lower(),d,result);append(b.on(),d,result);append(positive?b.lower():b.upper(),d,result);
            }
        }
    }
    static Plan compile(SurfaceBspPlan.Plan bsp,Domain domain,int maxCornersPerPatch) {
        require(maxCornersPerPatch>=64 && maxCornersPerPatch<=65535,"merged patch corner budget");
        var expression=expression(bsp.root(),domain);var patches=new ArrayList<Patch>();
        return new Plan(domain,pack(expression,bsp,patches,maxCornersPerPatch),patches);
    }
    /** 0 means unproved sign, never permission to merge a changing order. */
    static int invariantSign(SurfaceSourceSplit.Plane plane,Domain domain) {
        var y=upperRadians(domain.yawLimit());var p=upperRadians(domain.pitchLimit());
        var cy=BigDecimal.ONE.subtract(y.multiply(y).divide(BigDecimal.valueOf(2)));
        var cp=BigDecimal.ONE.subtract(p.multiply(p).divide(BigDecimal.valueOf(2)));
        var lower=new BigDecimal(Math.abs(plane.z())).multiply(cy).multiply(cp)
            .subtract(new BigDecimal(Math.abs(plane.x())).multiply(y))
            .subtract(new BigDecimal(Math.abs(plane.y())).multiply(p));
        // Reserve roundoff for the normalized double-precision traversal direction.
        return lower.compareTo(new BigDecimal(64*Math.ulp(1d)))>0?(plane.z()>0?1:-1):0;
    }
    private static BigDecimal upperRadians(double degrees) {
        // 355/113 > pi; rounding upward preserves the conservative bound.
        return new BigDecimal(degrees).multiply(BigDecimal.valueOf(355))
            .divide(BigDecimal.valueOf(113*180),new MathContext(50,RoundingMode.CEILING));
    }
    private static Op expression(SurfaceBspPlan.Node node,Domain domain) {
        if(node==null)return new Fixed(List.of());
        Op lower=expression(node.negative(),domain),upper=expression(node.positive(),domain),on=new Fixed(node.coplanar());
        int sign=invariantSign(node.plane(),domain);
        if(sign>0)return sequence(List.of(upper,on,lower));
        if(sign<0)return sequence(List.of(lower,on,upper));
        // A leaf's coplanar geometry has the same sequence in both traversals.
        if(node.negative()==null && node.positive()==null)return on;
        return new Branch(node.plane(),lower,on,upper);
    }
    private static Op sequence(List<Op> parts) {
        var flattened=new ArrayList<Op>();for(var part:parts)flatten(part,flattened);
        var result=new ArrayList<Op>();var run=new ArrayList<Integer>();
        for(var part:flattened) {
            if(part instanceof Fixed f)run.addAll(f.ids());
            else {if(!run.isEmpty()){result.add(new Fixed(run));run.clear();}result.add(part);}
        }
        if(!run.isEmpty())result.add(new Fixed(run));
        return result.size()==1?result.get(0):result.isEmpty()?new Fixed(List.of()):new Sequence(result);
    }
    private static void flatten(Op op,List<Op> result) {
        if(op instanceof Sequence s)for(var child:s.parts())flatten(child,result);else result.add(op);
    }
    private static Op pack(Op op,SurfaceBspPlan.Plan bsp,List<Patch> patches,int limit) {
        if(op instanceof Fixed f) {
            var ids=new ArrayList<Integer>();var run=new ArrayList<Integer>();int count=0;SurfaceSourceSplit.Source previous=null;
            for(int id:f.ids()) {
                var polygon=bsp.fragments().get(id).polygon();var source=polygon.source();int corners=polygon.corners().size();
                boolean same=previous==null || previous.node()==source.node() && previous.mesh()==source.mesh() && previous.primitive()==source.primitive();
                if(!run.isEmpty() && (!same || count+corners>limit)) {
                    ids.add(patches.size());patches.add(new Patch(patches.size(),run,count));run.clear();count=0;
                }
                run.add(id);count+=corners;previous=source;
            }
            if(!run.isEmpty()){ids.add(patches.size());patches.add(new Patch(patches.size(),run,count));}
            return new Fixed(ids);
        }
        if(op instanceof Sequence s) {
            var parts=new ArrayList<Op>();for(var part:s.parts())parts.add(pack(part,bsp,patches,limit));return new Sequence(parts);
        }
        var b=(Branch)op;
        return new Branch(b.plane(),pack(b.lower(),bsp,patches,limit),pack(b.on(),bsp,patches,limit),pack(b.upper(),bsp,patches,limit));
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalArgumentException(message);}
}
