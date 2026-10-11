package dev.turboism.validation.modelimport3d.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ObjModelInputTest {
    private static byte[] text(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static final String QUAD="v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf -4/-4 -3/-3 -2/-2 -1/-1\n";
    @Test void bareObjNegativeIndicesAndUvDirectionArePreserved() throws Exception {
        byte[] input=text(QUAD);var s=GlbSurfaceReader.read(ModelInputNormalizer.normalize(input)).get(0);
        assertEquals(List.of(0,1,2,0,2,3),s.indices());assertEquals(4,s.positions().size());
        assertEquals(1,s.textureUvs().get(0).y());assertEquals(0,s.textureUvs().get(2).y());assertNull(s.texture());
        assertArrayEquals(ModelInputNormalizer.normalize(input),ModelInputNormalizer.normalize(input));
    }
    @Test void normalizationIsByteIdenticalAcrossIndependentJvmProcesses() throws Exception {
        var roots=List.of(NormalizationProcessWitness.class,ModelInputNormalizer.class,dev.turboism.sdk.json.Json.class).stream().map(c->{try{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}catch(Exception e){throw new IllegalStateException(e);}}).distinct().toList();
        String classpath=String.join(File.pathSeparator,roots),javaBinary=Path.of(System.getProperty("java.home"),"bin","java").toString();
        String expected=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ModelInputNormalizer.normalize(text(QUAD))));
        for(int i=0;i<3;i++) {
            var process=new ProcessBuilder(javaBinary,"-cp",classpath,NormalizationProcessWitness.class.getName(),Base64.getEncoder().encodeToString(text(QUAD))).redirectErrorStream(true).start();
            assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();assertEquals(0,process.exitValue(),output);assertEquals(expected,output);
        }
    }
    @Test void concaveFaceHasCorrectAreaAndUntexturedFacesMayOmitUv() throws Exception {
        String obj="v 0 0 0\nv 2 0 0\nv 2 2 0\nv 1 1 0\nv 0 2 0\nf 1 2 3 4 5\n";
        var s=GlbSurfaceReader.read(ModelInputNormalizer.normalize(text(obj))).get(0);assertEquals(9,s.indices().size());double area=0;
        for(int i=0;i<s.indices().size();i+=3){var a=s.positions().get(s.indices().get(i));var b=s.positions().get(s.indices().get(i+1));var c=s.positions().get(s.indices().get(i+2));double cross=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());assertTrue(cross>0);area+=cross/2;}
        assertEquals(3,area,1e-8);
    }
    @Test void packedObjMtlJpegImportsBaseColorAndRetainsUvSeams() throws Exception {
        byte[] jpg=image("jpg");String obj="mtllib ../materials/scene.mtl\nusemtl red\n"+QUAD;
        var files=Map.of("scene/model.obj",text(obj),"materials/scene.mtl",text("newmtl red\nKd .5 1 .25\nmap_Kd ../textures/diffuse.jpg\n"),"textures/diffuse.jpg",jpg);
        var s=GlbSurfaceReader.read(ModelInputNormalizer.normalize(zip(files))).get(0);
        assertEquals(List.of(.5,1d,.25,1d),s.baseColor());assertNotNull(s.texture());assertEquals(2,s.texture().width());assertEquals(2,s.texture().height());
        var decoded=ImageIO.read(new ByteArrayInputStream(jpg));for(int y=0;y<2;y++)for(int x=0;x<2;x++)assertEquals(decoded.getRGB(x,y),s.texture().pixels().get(y*2+x));
        String seam=QUAD+"vt .5 .5\nf 1/5 2/2 3/3\n";
        var split=GlbSurfaceReader.read(ModelInputNormalizer.normalize(text(seam))).get(0);assertEquals(5,split.positions().size());assertEquals(split.positions().get(0),split.positions().get(4));assertNotEquals(split.textureUvs().get(0),split.textureUvs().get(4));
    }
    @Test void multipleMaterialsShareOneDecodedTextureWithoutChangingTheirFactors() throws Exception {
        String obj="mtllib a.mtl\nusemtl red\n"+QUAD+"usemtl green\nf 1/1 2/2 3/3\n";
        var sources=GlbSurfaceReader.read(ModelInputNormalizer.normalize(zip(Map.of("a.obj",text(obj),"a.mtl",text("newmtl red\nKd 1 0 0\nmap_Kd shared.jpg\nnewmtl green\nKd 0 1 0\nmap_Kd shared.jpg\n"),"shared.jpg",image("jpg")))));
        assertEquals(2,sources.size());assertSame(sources.get(0).texture(),sources.get(1).texture());
        assertEquals(List.of(1d,0d,0d,1d),sources.get(0).baseColor());assertEquals(List.of(0d,1d,0d,1d),sources.get(1).baseColor());
    }
    @Test void selfIntersectingPolygonRefusesRatherThanCreatingOverlappingFaces() {
        String obj="v 0 0 0\nv 2 2 0\nv 0 2 0\nv 2 0 0\nf 1 2 3 4\n";
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(text(obj)));
    }
    @Test void undefinedMaterialsMissingUvBadIndicesAndUnsupportedFieldsReject() {
        for(String obj:List.of("usemtl missing\n"+QUAD,QUAD.replace("-4/-4","0/1"),QUAD.replace("-4/-4","99/1"),QUAD+"curv 1 2 3\n",QUAD.replace("v 0 0 0","v NaN 0 0")))
            assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(text(obj)));
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("a.obj",text("mtllib a.mtl\nusemtl a\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"),"a.mtl",text("newmtl a\nmap_Kd a.jpg\n"),"a.jpg",image("jpg")))));
    }
    @Test void transparentMaterialAndOutOfPackageTextureReject() {
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("a.obj",text("mtllib a.mtl\n"+QUAD),"a.mtl",text("newmtl a\nd .5\n")))));
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("a.obj",text("mtllib a.mtl\n"+QUAD),"a.mtl",text("newmtl a\nmap_Kd ../secret.jpg\n")))));
    }
    @Test void authorizedOriginalCatPackageImportsWithoutManualConversion() throws Exception {
        var root=Path.of(System.getProperty("mi3d.repo"));byte[] packed=Files.readAllBytes(root.resolve("artifacts/cat-textured/package-source-r1/cat.zip"));
        var sources=GlbSurfaceReader.read(ModelInputNormalizer.normalize(packed));var prior=GlbSurfaceReader.read(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb")));
        assertEquals(1,sources.size());var s=sources.get(0);var p=prior.get(0);assertEquals(70576,s.indices().size()/3);assertEquals(36530,s.positions().size());
        assertEquals(p.positions(),s.positions());assertEquals(p.indices(),s.indices());
        double uvError=0;for(int i=0;i<p.textureUvs().size();i++){var a=p.textureUvs().get(i);var b=s.textureUvs().get(i);uvError=Math.max(uvError,Math.hypot(a.x()-b.x(),a.y()-b.y()));}assertTrue(uvError<1e-6);
        assertEquals(1024,s.texture().width());int max=0;long error=0;
        for(int i=0;i<s.texture().pixels().size();i++)for(int shift:new int[]{0,8,16}){int difference=Math.abs(((s.texture().pixels().get(i)>>>shift)&255)-((p.texture().pixels().get(i)>>>shift)&255));max=Math.max(max,difference);error+=difference;}
        System.out.println("CAT_OBJ_PACKAGE triangles="+s.indices().size()/3+" slots="+s.positions().size()+" uvMax="+uvError+" jpegVsPriorPngChannelMax="+max+" mean="+error/(3d*s.texture().pixels().size()));
        assertTrue(max<=5,"JPEG decoding must remain close to the independently normalized prior texture");
        Path out=root.resolve("build/model-import-3d-offline/obj-package-input-r1-20261003");Files.createDirectories(out);Files.write(out.resolve("cat-normalized.glb"),ModelInputNormalizer.normalize(packed));
    }
    private static byte[] image(String format) throws IOException {var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,0xffa01020);image.setRGB(1,0,0xff2040c0);image.setRGB(0,1,0xff10c020);image.setRGB(1,1,0xffe0e010);var out=new ByteArrayOutputStream();ImageIO.write(image,format,out);return out.toByteArray();}
    private static byte[] zip(Map<String,byte[]> files) throws IOException {var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){for(var e:new TreeMap<>(files).entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return out.toByteArray();}
}
