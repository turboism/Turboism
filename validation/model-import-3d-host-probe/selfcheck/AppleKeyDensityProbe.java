package dev.turboism.validation.modelimport3d.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exact source allocation comparison. No large form allocation or host writes. */
public final class AppleKeyDensityProbe {
    private AppleKeyDensityProbe() { }
    public static void main(String[] args) throws Exception {
        byte[] input=Files.readAllBytes(Path.of(args[0]));
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        if(!sha.equals("54331e29af9a3ca07f11017712ce9156208a8ae8538b0a80b78e8c5eb8a894e0"))
            throw new IllegalArgumentException("source changed");
        var surfaces=GlbSurfaceReader.read(input);
        var packing=SurfaceFixedIndexPlan.compile(surfaces,new SurfaceFixedIndexPlan.Budget(65535,1024,200000,600000));
        var camera=SurfaceAutoCamera.fit(surfaces,512,512,400);
        var vertices=surfaces.stream().flatMap(s->s.positions().stream()).toList();
        System.out.println("{\"status\":\"SOURCE_ALLOCATION_COMPARISON_NOT_HOST_IMPORTED\",\"sourceSha256\":\""+sha+"\",\"results\":[");
        double[] tolerances={.05,.25,.5,1};
        for(int i=0;i<tolerances.length;i++) {
            double tolerance=tolerances[i];var grid=SurfaceKeyPlan.linear(vertices,camera,30,30,tolerance,4096);
            long keys=(long)grid.yawKeys().size()*grid.pitchKeys().size(),samples=keys*packing.expandedSlots();
            System.out.println((i==0?"":",")+"{\"idealTolerancePixels\":"+tolerance+",\"idealBilinearErrorBoundPixels\":"+grid.errorBoundPixels()
                +",\"yawKeys\":"+grid.yawKeys().size()+",\"pitchKeys\":"+grid.pitchKeys().size()+",\"expandedSlots\":"+packing.expandedSlots()
                +",\"projectedVertices\":"+samples+",\"minimumPackedXYBytes\":"+(samples*8)
                +",\"fitsCurrentTotalBudget\":"+(samples<=SurfaceImportPlan.MAX_PROJECTED_VERTICES)
                +",\"largestPatchNonDefaultBatchVertices\":"+((keys-1)*packing.patches().stream().mapToLong(p->p.positions().size()).max().orElseThrow())+"}");
        }
        System.out.println("],\"sourceGeometryUnchanged\":true,\"hostExecuted\":false}");
    }
}
