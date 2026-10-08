package dev.turboism.validation.modelimport3d.host;

import dev.turboism.protocol.json.StrictJson;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelInputNormalizerTest {
    private static final Path CAT=Path.of(System.getProperty("mi3d.repo"),"artifacts/cat-textured/source-r2/cat-textured.glb");
    @SuppressWarnings("unchecked") private static Map<String,Object> document(byte[] glb) {
        var b=ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);b.position(12);int n=b.getInt();b.getInt();byte[] json=new byte[n];b.get(json);return StrictJson.parse(json);
    }
    private static byte[] binary(byte[] glb) {
        var b=ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);b.position(12);int n=b.getInt();b.position(20+n);int size=b.getInt();b.getInt();byte[] bin=new byte[size];b.get(bin);return bin;
    }
    @Test void realCatExternalBufferPackagePreservesEverySurfaceAndIsDeterministic() throws Exception {
        byte[] original=Files.readAllBytes(CAT);var doc=document(original);byte[] bin=binary(original);
        doc.put("buffers",List.of(Map.of("uri","cat.bin","byteLength",bin.length)));
        byte[] zip=zip(Map.of("cat/model.gltf",StrictJson.bytes(doc),"cat/cat.bin",bin));
        byte[] normalized=ModelInputNormalizer.normalize(zip);
        assertEquals(GlbSurfaceReader.read(original),GlbSurfaceReader.read(normalized));
        assertArrayEquals(normalized,ModelInputNormalizer.normalize(zip));
    }
    @Test void selfContainedGltfAndExternalPngHaveIdenticalCatTexels() throws Exception {
        byte[] original=Files.readAllBytes(CAT);var doc=document(original);byte[] bin=binary(original);
        doc.put("buffers",List.of(Map.of("uri","data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(bin),"byteLength",bin.length)));
        assertEquals(GlbSurfaceReader.read(original),GlbSurfaceReader.read(ModelInputNormalizer.normalize(StrictJson.bytes(doc))));
        @SuppressWarnings("unchecked") var images=(List<Map<String,Object>>)doc.get("images");
        @SuppressWarnings("unchecked") var views=(List<Map<String,Object>>)doc.get("bufferViews");
        var resources=new TreeMap<String,byte[]>();
        for(int i=0;i<images.size();i++) {
            var image=images.get(i);var view=views.get(((Number)image.remove("bufferView")).intValue());
            int offset=((Number)view.getOrDefault("byteOffset",0)).intValue(),length=((Number)view.get("byteLength")).intValue();
            String name="texture "+i+".png";resources.put("cat/"+name,Arrays.copyOfRange(bin,offset,offset+length));
            image.put("uri",name.replace(" ","%20"));
        }
        doc.put("buffers",List.of(Map.of("uri","cat.bin","byteLength",bin.length)));
        resources.put("cat/cat.bin",bin);resources.put("cat/model.gltf",StrictJson.bytes(doc));
        assertEquals(GlbSurfaceReader.read(original),GlbSurfaceReader.read(ModelInputNormalizer.normalize(zip(resources))));
    }
    @Test void rejectsMissingResourcesTraversalNetworkAndAmbiguousModels() throws Exception {
        byte[] original=Files.readAllBytes(CAT);var doc=document(original);int size=binary(original).length;
        for(String uri:List.of("missing.bin","../outside.bin","%2e%2e/outside.bin","file:///tmp/a","https://example.com/a","/absolute","C:/a","a?b")) {
            doc.put("buffers",List.of(Map.of("uri",uri,"byteLength",size)));
            assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(StrictJson.bytes(doc)),uri);
        }
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("a.glb",original,"b.glb",original))));
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("../a.glb",original))));
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("a.gltf",new byte[]{'{','}'} ,"bomb",new byte[GlbSurfaceReader.MAX_BYTES]))));
    }
    @Test void rejectsResourceLengthMismatchAndPassesGlbWithoutMutatingInput() throws Exception {
        byte[] original=Files.readAllBytes(CAT),saved=original.clone();
        assertArrayEquals(original,ModelInputNormalizer.normalize(original));assertArrayEquals(saved,original);
        var doc=document(original);byte[] bin=binary(original);
        doc.put("buffers",List.of(Map.of("uri","a.bin","byteLength",bin.length+1)));
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("a.gltf",StrictJson.bytes(doc),"a.bin",bin))));
    }
    @Test void rejectsTruncatedZipAndDisguisedNestedArchive() throws Exception {
        byte[] original=Files.readAllBytes(CAT),packed=zip(Map.of("a.glb",original));
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(Arrays.copyOf(packed,packed.length-22)));
        assertThrows(IllegalArgumentException.class,()->ModelInputNormalizer.normalize(zip(Map.of("a.glb",packed))));
    }
    private static byte[] zip(Map<String,byte[]> entries) throws IOException {
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)) {
            for(var e:new TreeMap<>(entries).entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}
        }return out.toByteArray();
    }
}
