package dev.turboism.validation.modelimport3d.host;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded, pose-independent source partition with per-view painter traversal.
 * This is an offline candidate plan, not a continuous native-order proof. Coplanar
 * depth ties and the reported floating-point plane tolerance require separate
 * acceptance policy. No geometry/material identities are merged.
 */
final class SurfaceBspPlan {
    private SurfaceBspPlan() { }
    record Budget(int maxFragments,int maxNodes,int maxDepth,long maxCornerEvaluations) {
        Budget {
            require(maxFragments>0 && maxFragments<=400_000 && maxNodes>0 && maxNodes<=400_000
                && maxDepth>0 && maxDepth<=128 && maxCornerEvaluations>0 && maxCornerEvaluations<=200_000_000,
                "BSP allocation budget");
        }
    }
    record Identity(int node,int mesh,int primitive,int triangle) { }
    record Fragment(int id,SurfaceSourceSplit.Polygon polygon) { }
    record Node(SurfaceSourceSplit.Plane plane,List<Integer> coplanar,Node negative,Node positive) {
        Node {coplanar=List.copyOf(coplanar);}
    }
    record Plan(Node root,List<Fragment> fragments,long cornerEvaluations,int nodes,int depth,double tolerance) {
        Plan {fragments=List.copyOf(fragments);}
        /** Direction points from the orthographic camera into the source scene. */
        List<Integer> backToFront(SurfaceProjection.Vertex direction) {
            Objects.requireNonNull(direction,"view direction");
            double length=Math.hypot(Math.hypot(direction.x(),direction.y()),direction.z());
            require(Double.isFinite(length) && length>0,"nonzero finite view direction");
            var normalized=new SurfaceProjection.Vertex(direction.x()/length,direction.y()/length,direction.z()/length);
            var result=new ArrayList<Integer>(fragments.size());traverse(root,normalized,result);return List.copyOf(result);
        }
        private static void traverse(Node node,SurfaceProjection.Vertex direction,List<Integer> result) {
            if(node==null)return;
            var p=node.plane();double facing=p.x()*direction.x()+p.y()*direction.y()+p.z()*direction.z();
            // Positive dot: rays enter negative space first, so positive is farther.
            Node far=facing>=0?node.positive():node.negative();Node near=facing>=0?node.negative():node.positive();
            traverse(far,direction,result);result.addAll(node.coplanar());traverse(near,direction,result);
        }
    }
    static Plan compile(List<SurfaceSourceSplit.Source> triangles,Budget budget) {
        Objects.requireNonNull(budget,"budget");triangles=List.copyOf(triangles);
        require(!triangles.isEmpty() && triangles.size()<=budget.maxFragments(),"source triangle budget");
        var identities=new HashSet<Identity>();var polygons=new ArrayList<SurfaceSourceSplit.Polygon>();
        for(var source:triangles) {
            require(identities.add(new Identity(source.node(),source.mesh(),source.primitive(),source.triangle())),"duplicate source identity");
            support(source); // Reject degenerate sources before building any tree.
            polygons.add(SurfaceSourceSplit.Polygon.triangle(source));
        }
        var builder=new Builder(budget,polygons.size());var root=builder.build(polygons,1);
        return new Plan(root,builder.fragments,builder.evaluations,builder.nodes,builder.depth,builder.tolerance);
    }
    private record Trial(SurfaceSourceSplit.Plane plane,List<SurfaceSourceSplit.Polygon> lower,
            List<SurfaceSourceSplit.Polygon> upper,List<SurfaceSourceSplit.Polygon> on,int splits,double tolerance) {
        long cost() {return 3L*splits+Math.max(lower.size(),upper.size());}
    }
    private static final class Builder {
        final Budget budget;final List<Fragment> fragments=new ArrayList<>();
        long evaluations;int nodes,depth,fragmentCount;double tolerance;
        Builder(Budget budget,int count) {this.budget=budget;this.fragmentCount=count;}
        Node build(List<SurfaceSourceSplit.Polygon> polygons,int level) {
            if(polygons.isEmpty())return null;
            require(level<=budget.maxDepth(),"BSP depth budget: level="+level+", polygons="+polygons.size()+", nodes="+nodes+", fragments="+fragmentCount+", evaluations="+evaluations);
            require(++nodes<=budget.maxNodes(),"BSP node budget");depth=Math.max(depth,level);
            Trial best=null;
            for(var plane:candidates(polygons)) {
                var trial=partition(polygons,plane);
                // A spatial plane with no coplanar geometry must make progress on both sides.
                if(trial.on().isEmpty() && (trial.lower().size()>=polygons.size() || trial.upper().size()>=polygons.size()))continue;
                // Reserve depth headroom by preferring balanced children in deep branches.
                long cost=level>budget.maxDepth()/2 ? 4L*Math.max(trial.lower().size(),trial.upper().size())+trial.splits() : trial.cost();
                long previous=best==null?Long.MAX_VALUE:level>budget.maxDepth()/2
                    ? 4L*Math.max(best.lower().size(),best.upper().size())+best.splits() : best.cost();
                if(cost<previous)best=trial;
                // A sufficiently balanced low-split spatial cut needs no exhaustive search.
                if(polygons.size()>32 && trial.cost()<=.6*polygons.size())break;
            }
            require(best!=null,"BSP numerical partition made no progress");
            fragmentCount+=best.splits();require(fragmentCount<=budget.maxFragments(),"BSP fragment budget");
            tolerance=Math.max(tolerance,best.tolerance());var on=new ArrayList<Integer>();
            for(var polygon:best.on()) {
                int id=fragments.size();fragments.add(new Fragment(id,polygon));on.add(id);
            }
            var negative=build(best.lower(),level+1);var positive=build(best.upper(),level+1);
            return new Node(best.plane(),on,negative,positive);
        }
        Trial partition(List<SurfaceSourceSplit.Polygon> polygons,SurfaceSourceSplit.Plane plane) {
            var lower=new ArrayList<SurfaceSourceSplit.Polygon>();var upper=new ArrayList<SurfaceSourceSplit.Polygon>();
            var on=new ArrayList<SurfaceSourceSplit.Polygon>();int splits=0;double maxTolerance=0;
            for(var polygon:polygons) {
                evaluations+=polygon.corners().size();require(evaluations<=budget.maxCornerEvaluations(),"BSP evaluation budget: nodes="+nodes+", fragments="+fragmentCount+", level="+depth);
                var split=SurfaceSourceSplit.split(polygon,plane);maxTolerance=Math.max(maxTolerance,split.tolerance());
                if(split.negative()!=null)lower.add(split.negative());
                if(split.positive()!=null)upper.add(split.positive());
                if(split.coplanar()!=null)on.add(split.coplanar());
                if(split.negative()!=null && split.positive()!=null)splits++;
            }
            return new Trial(plane,lower,upper,on,splits,maxTolerance);
        }
    }
    private static List<SurfaceSourceSplit.Plane> candidates(List<SurfaceSourceSplit.Polygon> polygons) {
        var result=new ArrayList<SurfaceSourceSplit.Plane>();
        int count=Math.min(5,polygons.size());
        if(polygons.size()>8) {
            double[][] centers=new double[3][polygons.size()];int polygonIndex=0;
            double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
            double[] hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(var polygon:polygons) {
                for(var corner:polygon.corners()) {
                var p=corner.position(polygon.source());double[] v={p.x(),p.y(),p.z()};
                for(int axis=0;axis<3;axis++){lo[axis]=Math.min(lo[axis],v[axis]);hi[axis]=Math.max(hi[axis],v[axis]);centers[axis][polygonIndex]+=v[axis]/polygon.corners().size();}
            }
                polygonIndex++;
            }
            for(int axis=0;axis<3;axis++)if(lo[axis]<hi[axis]) {
                result.add(new SurfaceSourceSplit.Plane(axis==0?1:0,axis==1?1:0,axis==2?1:0,-(lo[axis]/2+hi[axis]/2)));
                java.util.Arrays.sort(centers[axis]);
                result.add(new SurfaceSourceSplit.Plane(axis==0?1:0,axis==1?1:0,axis==2?1:0,-centers[axis][polygons.size()/2]));
            }
        }
        for(int i=0;i<count;i++)result.add(support(polygons.get(i*polygons.size()/count).source()));
        return result;
    }
    private static SurfaceSourceSplit.Plane support(SurfaceSourceSplit.Source source) {
        var p=source.positions();var a=p.get(0);var b=p.get(1);var c=p.get(2);
        double ax=b.x()-a.x(),ay=b.y()-a.y(),az=b.z()-a.z(),bx=c.x()-a.x(),by=c.y()-a.y(),bz=c.z()-a.z();
        double scale=Math.max(Math.max(Math.max(Math.abs(ax),Math.abs(ay)),Math.abs(az)),
            Math.max(Math.max(Math.abs(bx),Math.abs(by)),Math.abs(bz)));
        require(Double.isFinite(scale) && scale>0,"degenerate or overflowing source triangle");
        ax/=scale;ay/=scale;az/=scale;bx/=scale;by/=scale;bz/=scale;
        double nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
        return new SurfaceSourceSplit.Plane(nx,ny,nz,-(nx*a.x()+ny*a.y()+nz*a.z()));
    }
    private static void require(boolean value,String message) {if(!value)throw new IllegalArgumentException(message);}
}
