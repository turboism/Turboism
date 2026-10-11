package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.json.Json;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelTextureNormalizerTest {
    @Test void jpegEmbeddedInGlbAndGltfHasTheSameDecodedPixels() throws Exception {
        byte[] jpg=image(2,2,false,"jpeg");byte[] bin=ByteBuffer.allocate(60+jpg.length).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(1).put(jpg).array();
        var doc=new LinkedHashMap<String,Object>();doc.put("asset",Map.of("version","2.0"));doc.put("scene",0);doc.put("scenes",List.of(Map.of("nodes",List.of(0))));doc.put("nodes",List.of(Map.of("mesh",0)));doc.put("meshes",List.of(Map.of("primitives",List.of(Map.of("attributes",Map.of("POSITION",0,"TEXCOORD_0",1),"indices",2,"material",0)))));
        int indexOffset=(bin.length+3)&~3;byte[] all=Arrays.copyOf(bin,indexOffset+12);ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN).position(indexOffset).putInt(0).putInt(1).putInt(2);
        doc.put("buffers",List.of(Map.of("byteLength",all.length)));doc.put("bufferViews",List.of(Map.of("buffer",0,"byteLength",36),Map.of("buffer",0,"byteOffset",36,"byteLength",24),Map.of("buffer",0,"byteOffset",60,"byteLength",jpg.length),Map.of("buffer",0,"byteOffset",indexOffset,"byteLength",12)));
        doc.put("accessors",List.of(Map.of("bufferView",0,"componentType",5126,"count",3,"type","VEC3"),Map.of("bufferView",1,"componentType",5126,"count",3,"type","VEC2"),Map.of("bufferView",3,"componentType",5125,"count",3,"type","SCALAR")));
        doc.put("materials",List.of(Map.of("pbrMetallicRoughness",Map.of("baseColorTexture",Map.of("index",0)))));doc.put("textures",List.of(Map.of("source",0,"sampler",0)));doc.put("samplers",List.of(Map.of("wrapS",33071,"wrapT",33071,"magFilter",9729,"minFilter",9729)));doc.put("images",List.of(new LinkedHashMap<>(Map.of("bufferView",2,"mimeType","image/jpeg"))));
        var source=GlbSurfaceReader.read(ModelInputNormalizer.normalize(glb(doc,all))).get(0);
        doc.put("buffers",List.of(Map.of("byteLength",all.length,"uri","data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(all))));doc.put("images",List.of(Map.of("uri","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(jpg))));
        var external=GlbSurfaceReader.read(ModelInputNormalizer.normalize(Json.bytes(doc))).get(0);assertEquals(source,external);
        var decoded=ImageIO.read(new ByteArrayInputStream(jpg));for(int y=0;y<2;y++)for(int x=0;x<2;x++)assertEquals(decoded.getRGB(x,y),source.texture().pixels().get(2*y+x));
    }
    @Test void pngDimensionsAndDecodedColorsStayExactAndTransparencyRejects() throws Exception {
        byte[] input=image(4,2,false,"png"),normalized=ModelTextureNormalizer.png(input);var pixels=EmbeddedBaseColorTexture.decode(0,0,normalized);assertEquals(4,pixels.width());assertEquals(2,pixels.height());var decoded=ImageIO.read(new ByteArrayInputStream(input));for(int y=0;y<2;y++)for(int x=0;x<4;x++)assertEquals(decoded.getRGB(x,y),pixels.pixels().get(4*y+x));
        assertThrows(IllegalArgumentException.class,()->ModelTextureNormalizer.png(image(2,2,true,"png")));
        assertThrows(IllegalArgumentException.class,()->ModelTextureNormalizer.png(image(3,2,false,"png")));
        assertThrows(IllegalArgumentException.class,()->ModelTextureNormalizer.png(new byte[8]));
    }
    private static byte[] image(int w,int h,boolean alpha,String format)throws IOException {var image=new BufferedImage(w,h,alpha?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,(alpha?0x7f000000:0xff000000)|((30+x*40)<<16)|((50+y*40)<<8)|80);var out=new ByteArrayOutputStream();ImageIO.write(image,format,out);return out.toByteArray();}
    private static byte[] glb(Map<String,Object> doc,byte[] bin){byte[] json=Json.bytes(doc);int padded=(json.length+3)&~3,binary=(bin.length+3)&~3;var out=ByteBuffer.allocate(28+padded+binary).order(ByteOrder.LITTLE_ENDIAN);out.putInt(0x46546c67).putInt(2).putInt(out.capacity()).putInt(padded).putInt(0x4e4f534a).put(json);while(out.position()<20+padded)out.put((byte)' ');out.putInt(binary).putInt(0x004e4942).put(bin);return out.array();}
}
