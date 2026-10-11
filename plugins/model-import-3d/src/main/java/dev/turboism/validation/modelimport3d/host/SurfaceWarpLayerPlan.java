package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.*;

/** Source-preserving chart partitions and joint-key painter ranks. A candidate,
 * requiring source-depth and actual native pixel validation before menu admission. */
final class SurfaceWarpLayerPlan {
    private SurfaceWarpLayerPlan() { }
    record Orders(List<List<Integer>> jointKeys, List<SurfaceProjection.Vertex> centroids) {
        Orders { jointKeys=jointKeys.stream().map(List::copyOf).toList();centroids=List.copyOf(centroids); }
    }
    static SurfaceWarpChartPlan.Plan splitCharts(SurfaceWarpChartPlan.Plan original) {
        var builders=new TreeMap<Integer,Builder>();
        var seen=new HashSet<SurfaceWarpChartPlan.Triangle>();
        for(var mesh:original.meshes())for(int t=0;t<mesh.triangles().size();t++) {
            if(!seen.add(mesh.triangles().get(t)))throw new IllegalArgumentException("duplicate original triangle");
            int chart=mesh.slotIdentities().get(mesh.indices().get(t*3)).chart();
            var builder=builders.computeIfAbsent(chart,ignored->new Builder());
            for(int c=0;c<3;c++) {
                int index=mesh.indices().get(t*3+c);var identity=mesh.slotIdentities().get(index);
                if(identity.chart()!=chart)throw new IllegalArgumentException("triangle spans different charts");
                Integer slot=builder.slots.get(identity);
                if(slot==null) {
                    slot=builder.positions.size();if(slot>=65535)throw new IllegalArgumentException("chart exceeds native mesh slot limit");
                    builder.slots.put(identity,slot);builder.positions.add(mesh.sourcePositions().get(index));builder.uvs.add(mesh.sourceUvs().get(index));
                    builder.local.add(mesh.warpLocal().get(index));builder.identities.add(identity);
                } else if(!builder.positions.get(slot).equals(mesh.sourcePositions().get(index))
                    || !builder.uvs.get(slot).equals(mesh.sourceUvs().get(index)) || !builder.local.get(slot).equals(mesh.warpLocal().get(index)))
                    throw new IllegalArgumentException("same source slot/chart changed data");
                builder.indices.add(slot);
            }
            builder.triangles.add(mesh.triangles().get(t));
        }
        if(builders.isEmpty() || builders.size()>126)throw new IllegalArgumentException("chart layer capacity exceeds spaced 0..1000 orders");
        var meshes=builders.values().stream().map(Builder::finish).toList();
        return new SurfaceWarpChartPlan.Plan(original.sources(),meshes,original.columns(),original.rows(),
            original.sourceControls(),original.maximumSourceResidual(),original.controlSamples());
    }
    static Orders orders(SurfaceWarpChartPlan.Plan plan,SurfaceProjection.Camera camera,List<Float> yaw,List<Float> pitch) {
        if(plan.meshes().size()>126 || yaw.size()<3 || pitch.size()<3 || (long)yaw.size()*pitch.size()>4096)
            throw new IllegalArgumentException("bounded joint layer orders required");
        var centers=new ArrayList<SurfaceProjection.Vertex>();
        var chartIds=new HashSet<Integer>();
        for(var mesh:plan.meshes()) {
            if(mesh.indices().isEmpty() || mesh.indices().size()!=mesh.triangles().size()*3)throw new IllegalArgumentException("complete chart triangles required");
            int chart=mesh.slotIdentities().get(0).chart();
            if(!chartIds.add(chart) || mesh.slotIdentities().stream().anyMatch(s->s.chart()!=chart))throw new IllegalArgumentException("one mesh per chart required");
            double x=0,y=0,z=0;
            for(int triangle=0;triangle<mesh.triangles().size();triangle++) {
                double tx=0,ty=0,tz=0;
                for(int corner=0;corner<3;corner++){var v=mesh.sourcePositions().get(mesh.indices().get(triangle*3+corner));tx+=v.x();ty+=v.y();tz+=v.z();}
                x+=tx/3;y+=ty/3;z+=tz/3;
            }
            int n=mesh.triangles().size();centers.add(new SurfaceProjection.Vertex(x/n,y/n,z/n));
        }
        var bank=new ArrayList<List<Integer>>();
        for(float y:yaw)for(float p:pitch) {
            var depths=SurfaceProjection.project(centers,camera,new SurfaceProjection.Pose(y,p)).depths();
            var order=new ArrayList<Integer>();for(int i=0;i<centers.size();i++)order.add(i);
            order.sort(Comparator.comparingDouble((Integer i)->depths.get(i)).reversed().thenComparingInt(Integer::intValue));
            var ranks=new ArrayList<>(Collections.nCopies(order.size(),0));
            for(int i=0;i<order.size();i++)ranks.set(order.get(i),8*i);
            bank.add(ranks);
        }
        return new Orders(bank,centers);
    }
    private static final class Builder {
        final Map<SurfaceWarpChartPlan.Slot,Integer> slots=new HashMap<>();
        final List<SurfaceProjection.Vertex> positions=new ArrayList<>();
        final List<Point2> uvs=new ArrayList<>(),local=new ArrayList<>();
        final List<SurfaceWarpChartPlan.Slot> identities=new ArrayList<>();
        final List<Integer> indices=new ArrayList<>();
        final List<SurfaceWarpChartPlan.Triangle> triangles=new ArrayList<>();
        SurfaceWarpChartPlan.Mesh finish(){return new SurfaceWarpChartPlan.Mesh(positions,uvs,local,identities,indices,triangles);}
    }
}
