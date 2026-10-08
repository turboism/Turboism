package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Compares the generic production plane planner with all98 proven frozen charts. */
public class SurfaceDerivedPlaneFixtureTest {
    static void check(boolean ok,String reason) {if(!ok)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception {
        byte[] packed=Files.readAllBytes(Path.of(args[0])),fixture=Files.readAllBytes(Path.of(args[1]));
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packed))
            .equals("24fa49be32fd2c6a753ad80d7f52f933c523ae047971f3b894c3312690296541"),"original packed provenance");
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture))
            .equals(AppleDerivedCapture.ALIGNED_SHA),"cropped fixture provenance");
        int totalSlots=0,totalFaces=0,maximumChannelDifference=0;long differingPixels=0;
        var sourceImage=args.length>2?javax.imageio.ImageIO.read(Path.of(args[2]).toFile()):null;
        byte[] canonicalBytes=args.length>3?Files.readAllBytes(Path.of(args[3])):null;
        if(canonicalBytes!=null)check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes))
            .equals("8266ba81170067a613bdb3d6601bb4f2f91c11d1c1251c459b0c65fb93987126"),"actual official restored UV witness");
        try(var input=new DataInputStream(new ByteArrayInputStream(packed));
                var expected=new DataInputStream(new ByteArrayInputStream(fixture));
                var canonical=canonicalBytes==null?null:new DataInputStream(new ByteArrayInputStream(canonicalBytes))) {
            if(canonical!=null)check(canonical.readInt()==0x43555631&&canonical.readInt()==98,"canonical witness header");
            int columns=input.readInt(),rows=input.readInt();check(input.readInt()==98,"98 original charts");
            check(expected.readInt()==0x4d493345&&expected.readInt()==2&&expected.readInt()==98,"frozen header");
            for(int chart=0;chart<98;chart++) {
                int n=input.readInt(),faces=input.readInt();
                var xyz=new ArrayList<SurfaceProjection.Vertex>();var uv=new ArrayList<Point2>();var local=new ArrayList<Point2>();
                for(int i=0;i<n;i++)xyz.add(new SurfaceProjection.Vertex(input.readFloat(),input.readFloat(),input.readFloat()));
                for(int i=0;i<n;i++)uv.add(new Point2(input.readFloat(),input.readFloat()));
                for(int i=0;i<n;i++)local.add(new Point2(input.readFloat(),input.readFloat()));
                input.skipNBytes(n*12L);var indices=new ArrayList<Integer>();
                for(int i=0;i<faces*3;i++)indices.add(input.readInt());
                var triangles=new ArrayList<SurfaceWarpChartPlan.Triangle>();
                for(int i=0;i<faces;i++)triangles.add(new SurfaceWarpChartPlan.Triangle(input.readInt(),input.readInt()));
                var identities=new ArrayList<SurfaceWarpChartPlan.Slot>();
                for(int i=0;i<n;i++)identities.add(new SurfaceWarpChartPlan.Slot(0,i,chart));
                var mesh=new SurfaceWarpChartPlan.Mesh(xyz,uv,local,identities,indices,triangles);
                var actual=SurfaceDerivedPlanePlan.compile(mesh);
                check(expected.readInt()==chart&&expected.readInt()==n,"chart original inventory");
                int slots=expected.readInt(),corners=expected.readInt();
                check(corners==faces*3,"original face count");
                var originalImagePoints=new ArrayList<Point2>();
                for(int i=0;i<n;i++)originalImagePoints.add(new Point2(expected.readFloat(),expected.readFloat()));
                expected.skipNBytes(32);
                var mapping=new ArrayList<Integer>();for(int i=0;i<n;i++)mapping.add(expected.readInt());
                var positions=new ArrayList<Point2>();for(int i=0;i<slots;i++)positions.add(new Point2(expected.readFloat(),expected.readFloat()));
                var expectedIndices=new ArrayList<Integer>();for(int i=0;i<corners;i++)expectedIndices.add(expected.readInt());
                int png=expected.readInt();byte[] expectedPng=expected.readNBytes(png);check(expectedPng.length==png,"complete material");
                var nativeUvs=new ArrayList<Point2>();
                if(canonical!=null) {
                    check(canonical.readInt()==chart&&canonical.readInt()==slots&&canonical.readInt()==corners,"actual restored chart dimensions");
                    for(int i=0;i<slots;i++)check(new Point2(canonical.readFloat(),canonical.readFloat()).equals(positions.get(i)),"native source plane exact");
                    for(int i=0;i<slots;i++)nativeUvs.add(new Point2(canonical.readFloat(),canonical.readFloat()));
                    for(int i=0;i<corners;i++)check(canonical.readInt()==expectedIndices.get(i),"native original corner exact");
                }
                check(actual.geometry().positions().size()==slots,"derived slot count chart "+chart);
                // Slot numbering may differ; every original corner must map to the same
                // derived plane position and exact original Warp-local/world attributes.
                for(int i=0;i<n;i++) {
                    int derived=actual.oldToNew().get(i),representative=actual.representatives().get(derived);
                    check(actual.geometry().positions().get(derived).equals(positions.get(mapping.get(i))),"derived plane chart "+chart+" slot "+i);
                    check(actual.defaultLocal().get(derived).equals(local.get(i))&&xyz.get(i).equals(xyz.get(representative)),"exact weld attributes");
                    if(canonical!=null)check(actual.geometry().uvs().get(derived).equals(nativeUvs.get(mapping.get(i))),"shader UV differs from official cached affine chart "+chart);
                }
                for(int i=0;i<corners;i++)check(actual.geometry().positions().get(actual.geometry().triangleIndices().get(i))
                    .equals(positions.get(expectedIndices.get(i))),"original face/corner sequence");
                if(sourceImage!=null) {
                    var imageGeometry=new ArtMeshGeometry(originalImagePoints,originalImagePoints.stream()
                        .map(p->new Point2(p.x()/1024,p.y()/256)).toList(),indices);
                    var baked=SurfaceDerivedTexturePlan.bake(actual,imageGeometry,sourceImage);
                    var a=javax.imageio.ImageIO.read(new ByteArrayInputStream(baked.png()));
                    var b=javax.imageio.ImageIO.read(new ByteArrayInputStream(expectedPng));
                    check(a.getWidth()==576&&a.getHeight()==576&&b.getWidth()==576&&b.getHeight()==576,"all cropped dimensions");
                    for(int y=0;y<576;y++)for(int x=0;x<576;x++) {
                        int av=a.getRGB(x,y),bv=b.getRGB(x,y);if(av!=bv)differingPixels++;
                        for(int shift=0;shift<32;shift+=8)maximumChannelDifference=Math.max(maximumChannelDifference,
                            Math.abs(((av>>>shift)&255)-((bv>>>shift)&255)));
                    }
                    a.flush();b.flush();
                }
                totalSlots+=slots;totalFaces+=faces;
            }
            // The authoritative packed plan ends with double-precision XYZ controls
            // and its double source-fit residual; those are not part of the plane.
            for(int i=0;i<(columns+1)*(rows+1)*3+1;i++)
                check(Double.isFinite(input.readDouble()),"finite unchanged original control bank");
            check(input.read()==-1&&expected.read()==-1,"no trailing fixture bytes");
            if(canonical!=null)check(canonical.read()==-1,"no trailing native witness bytes");
        }
        check(totalSlots==80514&&totalFaces==150784,"all original faces and optimized slots");
        check(maximumChannelDifference<=1,"generic texture mapping differs beyond integer rounding");
        System.out.println("PASS_GENERIC_ALL98_DERIVED_PLANE_MATCHES_FROZEN_NATIVE_CANDIDATE slots="+totalSlots+" faces="+totalFaces+" NOT_PRODUCTION_MENU_ACCEPTANCE");
        if(sourceImage!=null)System.out.println("ALL98_GENERIC_TEXTURE_DIFFERENCE maximumChannel="+maximumChannelDifference+" differingPixels="+differingPixels);
        if(canonicalBytes!=null)System.out.println("ALL98_GENERIC_SHADER_UVS_EXACTLY_MATCH_OFFICIAL_RESTORATION");
    }
}
