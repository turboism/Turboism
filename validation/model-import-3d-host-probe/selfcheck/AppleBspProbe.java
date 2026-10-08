package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

/** Offline algorithm probe, deliberately separate from GLB admission/native imports. */
public final class AppleBspProbe {
    private AppleBspProbe() { }
    public static void main(String[] args)throws Exception {
        byte[] raw=Files.readAllBytes(Path.of(args[0]));
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if(!sha.equals(args[1]))throw new IllegalArgumentException("frozen input changed");
        var bytes=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if(bytes.getInt()!=0x31505342 || bytes.getInt()!=2)throw new IllegalArgumentException("fixture header");
        var sources=new ArrayList<SurfaceSourceSplit.Source>();
        for(int mesh=0;mesh<2;mesh++) {
            int count=bytes.getInt();if(count!=(mesh==0?3328:147456))throw new IllegalArgumentException("source triangle count");
            for(int triangle=0;triangle<count;triangle++) {
                var p=new ArrayList<SurfaceProjection.Vertex>();var uv=new ArrayList<Point2>();
                for(int corner=0;corner<3;corner++) {
                    p.add(new SurfaceProjection.Vertex(bytes.getFloat(),bytes.getFloat(),bytes.getFloat()));
                    uv.add(new Point2(bytes.getFloat(),bytes.getFloat()));
                }
                sources.add(new SurfaceSourceSplit.Source(mesh,mesh,0,triangle,p,uv));
            }
        }
        if(bytes.hasRemaining())throw new IllegalArgumentException("trailing bytes");
        long evaluations=args.length>2?Long.parseLong(args[2]):40000000;
        int component=args.length>3?Integer.parseInt(args[3]):-1;
        if(component< -1 || component>1)throw new IllegalArgumentException("component scope");
        if(component>=0)sources.removeIf(source->source.mesh()!=component);
        long start=System.nanoTime();
        try {
            var plan=SurfaceBspPlan.compile(sources,new SurfaceBspPlan.Budget(400000,400000,128,evaluations));
            var merged=SurfaceBspMerge.compile(plan,new SurfaceBspMerge.Domain(30,30),65535);
            int testedViews=0;
            for(int yaw=-30;yaw<=30;yaw+=10)for(int pitch=-30;pitch<=30;pitch+=10) {
                var sequence=new ArrayList<Integer>();
                for(int patch:merged.backToFront(yaw,pitch))sequence.addAll(merged.patches().get(patch).fragments());
                double y=Math.toRadians(yaw),p=Math.toRadians(pitch);
                var direction=new SurfaceProjection.Vertex(-Math.sin(y),Math.cos(y)*Math.sin(p),Math.cos(y)*Math.cos(p));
                if(!sequence.equals(plan.backToFront(direction)))throw new IllegalStateException("merged traversal differs");
                testedViews++;
            }
            long corners=merged.patches().stream().mapToLong(SurfaceBspMerge.Patch::corners).sum();
            System.out.println("{\"status\":\"BSP_CANDIDATE_COMPILED_NOT_IMPORTED\",\"sourceTriangles\":"+sources.size()
                +",\"componentScope\":"+component+",\"mergedPatches\":"+merged.patches().size()+",\"mergedCorners\":"+corners+",\"verifiedTraversalViews\":"+testedViews+",\"evaluationBudget\":"+evaluations
                +",\"fragments\":"+plan.fragments().size()+",\"nodes\":"+plan.nodes()+",\"depth\":"+plan.depth()
                +",\"cornerEvaluations\":"+plan.cornerEvaluations()+",\"planeTolerance\":"+plan.tolerance()
                +",\"seconds\":"+(System.nanoTime()-start)/1e9+"}");
        } catch(IllegalArgumentException rejected) {
            System.out.println("{\"status\":\"BSP_REJECTED_NOT_IMPORTED\",\"reason\":\""+rejected.getMessage()
                +"\",\"componentScope\":"+component+",\"evaluationBudget\":"+evaluations+",\"sourceTriangles\":"+sources.size()+",\"seconds\":"+(System.nanoTime()-start)/1e9+"}");
            System.exit(2);
        }
    }
}
