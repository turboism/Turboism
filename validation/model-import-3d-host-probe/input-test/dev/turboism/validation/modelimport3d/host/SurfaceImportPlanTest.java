package dev.turboism.validation.modelimport3d.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/** Actual authorized cube, independent rotation oracle, seams, collapsed faces and budgets. */
public final class SurfaceImportPlanTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            .equals("985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630"), "authorized cube hash");
        var surfaces = GlbSurfaceReader.read(bytes);
        var camera = new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0));
        var plan = SurfaceImportPlan.compile(surfaces,camera,30,30,0.05,2_000_000);
        int ny = plan.grid().yawKeys().size(), np = plan.grid().pitchKeys().size();
        check(plan.patches().size()==6 && plan.keys().size()==ny*np, "six faces with complete joint tuples");
        check(plan.keys().get(plan.neutralKey()).equals(new SurfaceImportPlan.Key(0,0)), "neutral tuple");
        check(plan.projectedVertices()==24L*ny*np, "all seam slots projected for every key");
        check(plan.patches().stream().filter(p -> p.neutralCollapsedTriangles().size()==2).count()==4,
            "four edge-on faces retained at neutral");
        check(plan.patches().stream().mapToInt(p -> p.source().indices().size()).sum()==36, "all source triangles retained");
        var covered = new java.util.HashSet<Integer>();
        for (var patch : plan.patches()) {
            var source = surfaces.get(patch.surfaceIndex());
            for (int i=0;i<patch.source().sourceVertices().size();i++) {
                int original = patch.source().sourceVertices().get(i);
                covered.add(original);
                check(patch.source().positions().get(i).equals(source.positions().get(original))
                    && patch.source().textureUvs().get(i).equals(source.textureUvs().get(original)), "position/UV correspondence");
            }
            boolean hasArea = patch.positionsByKey().stream().anyMatch(points -> {
                var a=points.get(patch.source().indices().get(0));
                var b=points.get(patch.source().indices().get(1));
                var c=points.get(patch.source().indices().get(2));
                return Math.abs(((double)b.x()-a.x())*((double)c.y()-a.y())
                    - ((double)b.y()-a.y())*((double)c.x()-a.x())) > 1;
            });
            check(hasArea, "each retained face becomes nondegenerate in the pose domain");
        }
        check(covered.size()==24, "all original vertex identities covered");
        // Equal 3D seam positions must project identically without merging their UV identities.
        for (var a:plan.patches()) for (var b:plan.patches()) {
            for(int i=0;i<a.source().positions().size();i++)for(int j=0;j<b.source().positions().size();j++) {
                if(!a.source().positions().get(i).equals(b.source().positions().get(j)))continue;
                for(int k=0;k<plan.keys().size();k++)check(a.positionsByKey().get(k).get(i)
                    .equals(b.positionsByKey().get(k).get(j)), "coincident seam trajectories");
            }
        }
        Random random = new Random(4012026);
        double maximum = 0;
        for (int sample=0;sample<1000;sample++) {
            double yaw=-30+60*random.nextDouble(),pitch=-30+60*random.nextDouble();
            int yi=bracket(plan.grid().yawKeys(),yaw),pi=bracket(plan.grid().pitchKeys(),pitch);
            // Interpolate on actual stored float keys, not the ideal planning keys.
            double ya=plan.keys().get(yi*np).yaw(),yb=plan.keys().get((yi+1)*np).yaw();
            double pa=plan.keys().get(pi).pitch(),pb=plan.keys().get(pi+1).pitch();
            double u=(yaw-ya)/(yb-ya),v=(pitch-pa)/(pb-pa);
            for(var patch:plan.patches())for(int vertex=0;vertex<patch.source().positions().size();vertex++) {
                var p00=patch.positionsByKey().get(yi*np+pi).get(vertex);
                var p10=patch.positionsByKey().get((yi+1)*np+pi).get(vertex);
                var p01=patch.positionsByKey().get(yi*np+pi+1).get(vertex);
                var p11=patch.positionsByKey().get((yi+1)*np+pi+1).get(vertex);
                double x=(1-v)*((1-u)*p00.x()+u*p10.x())+v*((1-u)*p01.x()+u*p11.x());
                double y=(1-v)*((1-u)*p00.y()+u*p10.y())+v*((1-u)*p01.y()+u*p11.y());
                var p=patch.source().positions().get(vertex);
                double a=Math.toRadians(yaw),b=Math.toRadians(pitch);
                // Expanded rotation matrix, independently of SurfaceProjection.
                double expectedX=512+200*(Math.cos(a)*p.x()+Math.sin(a)*Math.sin(b)*p.y()+Math.sin(a)*Math.cos(b)*p.z());
                double expectedY=512-200*(Math.cos(b)*p.y()-Math.sin(b)*p.z());
                double error=Math.hypot(x-expectedX,y-expectedY);
                maximum=Math.max(maximum,error);
                check(error<=0.0501,"independent continuous projection oracle");
            }
        }
        reject(()->SurfaceImportPlan.compile(surfaces,camera,30,30,0.05,plan.projectedVertices()-1));
        reject(()->SurfaceImportPlan.compile(surfaces,camera,30,30,0.05,2_000_001));
        reject(()->SurfaceImportPlan.compile(List.of(),camera,30,30,0.05,100));
        reject(()->SurfaceImportPlan.compile(surfaces,camera,1e-9,30,0.05,2_000_000));
        reject(()->SurfaceImportPlan.compile(surfaces,camera,30,30,1e-12,2_000_000));
        System.out.println("SurfaceImportPlanTest: "+checks+" checks; "+ny+"x"+np
            +" keys per face; max ideal interpolation error="+maximum+" px; hostExecuted=false");
    }
    private static int bracket(List<Double> keys,double value) {
        for(int i=0;i<keys.size()-1;i++)if(value<=(float)(double)keys.get(i+1))return i;
        throw new AssertionError("sample outside keys");
    }
    private static void check(boolean condition,String label) {
        checks++;if(!condition)throw new AssertionError(label);
    }
    private static void reject(Runnable operation) {
        try { operation.run(); } catch(IllegalArgumentException expected) {checks++;return;}
        throw new AssertionError("invalid plan accepted");
    }
}
