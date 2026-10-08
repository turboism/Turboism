package dev.turboism.validation.modelimport3d.host;

import dev.turboism.protocol.json.StrictJson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual GLB parse + image preparation; no host or synthetic native-render claim. */
class EmbeddedTextureTest {
    private byte[] cube() throws Exception {
        return Files.readAllBytes(Path.of(System.getProperty("mi3d.repo"),
            "build/model-import-3d-inputs/cube-985496727e50/cube.glb"));
    }
    private byte[] image(boolean transparent) throws Exception {
        var image=new BufferedImage(256,128,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<128;y++)for(int x=0;x<256;x++)image.setRGB(x,y,0xff808080);
        image.setRGB(0,0,0xffff0000);image.setRGB(255,127,0xff0000ff);
        image.setRGB(17,9,transparent ? 0x8032c850 : 0xff32c850);
        var out=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",out));return out.toByteArray();
    }
    @SuppressWarnings("unchecked")
    private byte[] glb(byte[] png,List<Double> factor,java.util.function.Consumer<Map<String,Object>> mutate)throws Exception {
        var input=ByteBuffer.wrap(cube()).order(ByteOrder.LITTLE_ENDIAN);input.position(12);
        int length=input.getInt();input.getInt();byte[] json=new byte[length];input.get(json);
        int binLength=input.getInt();input.getInt();byte[] oldBin=new byte[binLength];input.get(oldBin);
        var doc=(Map<String,Object>)StrictJson.parse(json);
        var views=new ArrayList<>((List<Object>)doc.get("bufferViews"));int view=views.size();
        views.add(Map.of("buffer",0,"byteOffset",oldBin.length,"byteLength",png.length));doc.put("bufferViews",views);
        doc.put("images",List.of(Map.of("mimeType","image/png","bufferView",view)));
        doc.put("textures",List.of(Map.of("source",0,"sampler",0)));
        doc.put("samplers",List.of(Map.of("wrapS",33071,"wrapT",33071,"magFilter",9729,"minFilter",9729)));
        doc.put("materials",List.of(Map.of("pbrMetallicRoughness",Map.of("baseColorFactor",factor,"baseColorTexture",Map.of("index",0)))));
        doc.put("buffers",List.of(Map.of("byteLength",oldBin.length+png.length)));mutate.accept(doc);
        byte[] encoded=StrictJson.bytes(doc);int js=(encoded.length+3)&~3,bs=(oldBin.length+png.length+3)&~3;
        var output=ByteBuffer.allocate(28+js+bs).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546c67).putInt(2).putInt(output.capacity()).putInt(js).putInt(0x4e4f534a).put(encoded);
        while(output.position()<20+js)output.put((byte)' ');
        output.putInt(bs).putInt(0x004e4942).put(oldBin).put(png);return output.array();
    }
    private SurfaceBaseColorPlan.Plan appearance(byte[] glb,SurfaceBaseColorPlan.Appearance mode) throws Exception {
        var surfaces=GlbSurfaceReader.read(glb);
        var geometry=SurfaceImportPlan.compile(surfaces,new SurfaceProjection.Camera(512,512,200,new SurfaceProjection.Vertex(0,0,0)),30,30,.05,2_000_000);
        return SurfaceBaseColorPlan.compile(surfaces,geometry,mode);
    }
    @Test void sourcePixelsAndUvsSurviveWithoutFlippingOrResizing()throws Exception {
        byte[] png=image(false),input=glb(png,List.of(1d,1d,1d,1d),d->{});
        var surfaces=GlbSurfaceReader.read(input);var original=GlbSurfaceReader.read(cube());
        assertEquals(original.get(0).textureUvs(),surfaces.get(0).textureUvs());
        assertEquals(original.get(0).positions(),surfaces.get(0).positions());
        assertEquals(0,surfaces.get(0).texture().textureIndex());assertEquals(0,surfaces.get(0).texture().imageIndex());
        assertThrows(UnsupportedOperationException.class,()->surfaces.get(0).texture().pixels().set(0,0));
        var plan=appearance(input,SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL);
        assertEquals(1,plan.materials().size());assertEquals(6,plan.patches().size());
        var source=ImageIO.read(new ByteArrayInputStream(png));var result=ImageIO.read(new ByteArrayInputStream(plan.materials().get(0).png()));
        assertEquals(512,result.getWidth());assertEquals(256,result.getHeight());
        assertArrayEquals(source.getRGB(0,0,256,128,null,0,256),result.getRGB(1,1,256,128,null,0,256));
        for(int y=0;y<256;y++)for(int x=0;x<512;x++)
            assertEquals(source.getRGB(Math.max(0,Math.min(255,x-1)),Math.max(0,Math.min(127,y-1))),result.getRGB(x,y));
        var grant=plan.materials().get(0).newGrant();assertEquals(512,grant.width());assertEquals(256,grant.height());
        var corners=SurfaceImportGeometry.UvMode.EDGE_PADDED_IMAGE_PIXELS.map(List.of(
            new dev.turboism.sdk.cubism.model.Point2(0,0),new dev.turboism.sdk.cubism.model.Point2(1,1)));
        assertEquals(1d,corners.get(0).x()*512d,.00002);assertEquals(1d,corners.get(0).y()*256d,.00002);
        assertEquals(257d,corners.get(1).x()*512d,.00002);assertEquals(129d,corners.get(1).y()*256d,.00002);

    }
    @Test void paddedNativeBorderSamplingMatchesSourceClampAtEdgesAndInterior() throws Exception {
        byte[] png=image(false);var source=ImageIO.read(new ByteArrayInputStream(png));
        var material=appearance(glb(png,List.of(1d,1d,1d,1d),d->{}),SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL).materials().get(0);
        var padded=ImageIO.read(new ByteArrayInputStream(material.png()));
        for(double u:new double[]{0,.0001,.001,.25,.5,.999,1})
            for(double v:new double[]{0,.001,.5,.997744701,.998509153,1}) {
                var mapped=SurfaceImportGeometry.UvMode.EDGE_PADDED_IMAGE_PIXELS.map(List.of(
                    new dev.turboism.sdk.cubism.model.Point2((float)u,(float)v))).get(0);
                double[] expected=sample(source,u,v,true),actual=sample(padded,mapped.x(),mapped.y(),false);
                for(int c=0;c<4;c++)assertEquals(expected[c],actual[c],.02,"channel "+c+" at "+u+","+v);
            }
        assertTrue(sample(source,.5,.998509153,false)[3]<200,"unfixed native border reproduces transparent alpha");
    }
    private static double[] sample(BufferedImage image,double u,double v,boolean clamp) {
        double x=u*image.getWidth()-.5,y=v*image.getHeight()-.5;int ix=(int)Math.floor(x),iy=(int)Math.floor(y);
        double[] result=new double[4];
        for(int dy=0;dy<2;dy++)for(int dx=0;dx<2;dx++) {
            int px=ix+dx,py=iy+dy;
            int color=0;
            if(clamp || (px>=0 && py>=0 && px<image.getWidth() && py<image.getHeight()))
                color=image.getRGB(Math.max(0,Math.min(image.getWidth()-1,px)),Math.max(0,Math.min(image.getHeight()-1,py)));
            double weight=(dx==0?1-(x-ix):x-ix)*(dy==0?1-(y-iy):y-iy);
            for(int c=0;c<4;c++)result[c]+=((color>>>(c==3?24:16-8*c))&255)*weight;
        }
        return result;
    }

    @Test void materialFactorMultipliesInLinearSpaceAndFaceColorsAreExplicit()throws Exception {
        byte[] input=glb(image(false),List.of(.5d,1d,1d,1d),d->{});
        var material=appearance(input,SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL).materials().get(0);
        var result=ImageIO.read(new ByteArrayInputStream(material.png()));
        assertEquals(0xffbc0000,result.getRGB(0,0)); // half linear red -> sRGB 188, not 128
        assertEquals(0xff0000ff,result.getRGB(256,128));
        var diagnostics=appearance(input,SurfaceBaseColorPlan.Appearance.FACE_COLORS);
        assertEquals(6,diagnostics.materials().size());
        var flat=ImageIO.read(new ByteArrayInputStream(diagnostics.materials().get(0).png()));
        assertEquals(flat.getRGB(0,0),flat.getRGB(17,9));
    }
    @Test void unsupportedSamplerExternalImagesAlphaAndCorruptionAreRejectedBeforeBinding()throws Exception {
        var factor=List.of(1d,1d,1d,1d);byte[] png=image(false);
        for(var sampler:List.of(Map.of("wrapS",10497,"wrapT",33071,"magFilter",9729,"minFilter",9729),
                Map.of("wrapS",33071,"wrapT",33071,"magFilter",9729,"minFilter",9987),Map.<String,Integer>of())) {
            byte[] invalid=glb(png,factor,d->d.put("samplers",List.of(sampler)));
            assertThrows(IllegalArgumentException.class,()->GlbSurfaceReader.read(invalid));
        }
        byte[] external=glb(png,factor,d->d.put("images",List.of(Map.of("uri","file:///not-authorized.png"))));
        assertThrows(IllegalArgumentException.class,()->GlbSurfaceReader.read(external));
        byte[] alpha=glb(image(true),factor,d->{});
        assertThrows(IllegalArgumentException.class,()->GlbSurfaceReader.read(alpha));
        png[29]^=1;byte[] crc=glb(png,factor,d->{});
        assertThrows(IllegalArgumentException.class,()->GlbSurfaceReader.read(crc));
    }
    @Test void originalCatImageReadsAtFullResolutionAndPreservesEveryTexel() throws Exception {
        var root=Path.of(System.getProperty("mi3d.repo"));
        var sources=GlbSurfaceReader.read(Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb")));
        assertEquals(1,sources.size());assertEquals(36530,sources.get(0).positions().size());
        assertEquals(70576,sources.get(0).indices().size()/3);
        var texture=sources.get(0).texture();assertEquals(1024,texture.width());assertEquals(1024,texture.height());
        var source=ImageIO.read(root.resolve("artifacts/cat-textured/source-r2/Cat_diffuse.png").toFile());
        var material=SurfaceBaseColorPlan.sourceMaterial(texture,List.of(1d,1d,1d,1d));
        var result=ImageIO.read(new ByteArrayInputStream(material.png()));
        assertEquals(1024,material.newGrant().width());assertEquals(1024,material.newGrant().height());
        assertArrayEquals(source.getRGB(0,0,1024,1024,null,0,1024),result.getRGB(0,0,1024,1024,null,0,1024));
        for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)assertEquals(source.getRGB(x,y),texture.pixels().get(y*1024+x));
    }
    @Test void expansionAndMalformedDimensionsAreRejectedBeforeImageDecode() throws Exception {
        for(int[] size:List.of(new int[]{2048,2048},new int[]{1000,128},new int[]{0,128})) {
            byte[] png=image(false);ByteBuffer.wrap(png).putInt(16,size[0]).putInt(20,size[1]);
            assertThrows(IllegalArgumentException.class,()->EmbeddedBaseColorTexture.decode(0,0,png));
        }
    }
    @SuppressWarnings("unchecked")
    @Test void aggregateTextureExpansionIsRejectedBeforeDecodingAnotherFullImage() throws Exception {
        var root=Path.of(System.getProperty("mi3d.repo"));
        byte[] png=Files.readAllBytes(root.resolve("artifacts/cat-textured/source-r2/Cat_diffuse.png"));
        byte[] input=glb(png,List.of(1d,1d,1d,1d),d->{
            var image=((List<Object>)d.get("images")).get(0);d.put("images",List.of(image,image));
            d.put("textures",List.of(Map.of("source",0,"sampler",0),Map.of("source",1,"sampler",0)));
            d.put("materials",List.of(Map.of("pbrMetallicRoughness",Map.of("baseColorTexture",Map.of("index",0))),
                Map.of("pbrMetallicRoughness",Map.of("baseColorTexture",Map.of("index",1)))));
            var meshes=(List<Map<String,Object>>)d.get("meshes");
            var primitives=new ArrayList<>((List<Map<String,Object>>)meshes.get(0).get("primitives"));
            primitives.get(0).put("material",0);
            var second=new LinkedHashMap<>(primitives.get(0));second.put("material",1);primitives.add(second);
            meshes.get(0).put("primitives",primitives);
        });
        var failure=assertThrows(IllegalArgumentException.class,()->GlbSurfaceReader.read(input));
        assertEquals("aggregate source texture RGBA budget",failure.getMessage());
    }
}
