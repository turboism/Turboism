package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.json.Json;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Format/identity tests plus an explicit user-file acceptance input; no native import claim. */
public final class GlbSurfaceReaderTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var surfaces = GlbSurfaceReader.read(container(document(), binary()));
        check(surfaces.size()==1 && surfaces.get(0).positions().size()==3, "triangle decoded");
        check(surfaces.get(0).textureUvs().get(1).x()==1 && surfaces.get(0).indices().equals(List.of(0,1,2)), "UV and index order");
        var nodes = document();
        nodes.put("nodes", List.of(Map.of("children",List.of(1),"translation",List.of(1,2,3)),
            Map.of("mesh",0,"translation",List.of(5,0,0),"rotation",List.of(0,0,Math.sqrt(.5),Math.sqrt(.5)),"scale",List.of(2,1,1))));
        var point=GlbSurfaceReader.read(container(nodes,binary())).get(0).positions().get(0);
        check(Math.abs(point.x()-6)<1e-12 && Math.abs(point.y()-4)<1e-12 && Math.abs(point.z()-3)<1e-12,
            "hierarchical T*R*S with column-major matrices");
        nodes=document(); nodes.put("nodes",List.of(Map.of("mesh",0),Map.of("mesh",0,"translation",List.of(3,0,0))));
        nodes.put("scenes",List.of(Map.of("nodes",List.of(0,1))));
        var instances=GlbSurfaceReader.read(container(nodes,binary()));
        check(instances.size()==2 && instances.get(0).node()==0 && instances.get(1).node()==1
            && instances.get(1).positions().get(0).x()==4, "mesh instances retain node identity/transform");
        nodes=document(); nodes.put("nodes",List.of(Map.of("mesh",0,"scale",List.of(-1,1,1))));
        var mirrored=GlbSurfaceReader.read(container(nodes,binary())).get(0);
        check(mirrored.reflected() && mirrored.indices().equals(List.of(0,1,2)), "reflected winding flagged, source indices retained");
        var strideDoc=document();
        strideDoc.put("bufferViews",List.of(Map.of("buffer",0,"byteOffset",0,"byteLength",48,"byteStride",16),
            Map.of("buffer",0,"byteOffset",48,"byteLength",24),Map.of("buffer",0,"byteOffset",72,"byteLength",6)));
        strideDoc.put("buffers",List.of(Map.of("byteLength",78)));
        ByteBuffer strided=ByteBuffer.allocate(78).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<3;i++){strided.put(binary(),12*i,12);strided.putInt(0x12345678);}
        strided.put(binary(),36,30);
        check(GlbSurfaceReader.read(container(strideDoc,strided.array())).get(0).positions().equals(surfaces.get(0).positions()),
            "interleaved attribute stride");
        bad(d->d.put("buffers",List.of(Map.of("byteLength",66,"uri","file:///not-authorized"))));
        bad(d->d.put("extensionsRequired",List.of("KHR_draco_mesh_compression")));
        bad(d->d.put("animations",List.of(Map.of())));
        bad(d->d.put("skins",List.of(Map.of())));
        bad(d->d.put("nodes",List.of(Map.of("mesh",0,"children",List.of(0)))));
        bad(d->d.put("nodes",List.of(Map.of("mesh",0,"scale",List.of(0,1,1)))));
        bad(d->d.put("nodes",List.of(Map.of("mesh",0,"translation",List.of(0,0,0),"matrix",List.of(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1)))));
        bad(d->d.put("nodes",List.of(Map.of("mesh",0,"rotation",List.of(0,0,0,2)))));
        bad(d->d.put("bufferViews",List.of(Map.of("buffer",0,"byteOffset",0,"byteLength",35),
            Map.of("buffer",0,"byteOffset",36,"byteLength",24),Map.of("buffer",0,"byteOffset",60,"byteLength",6))));
        bad(d->((Map<String,Object>)((List<?>)d.get("accessors")).get(0)).put("sparse",Map.of()));
        bad(d->((Map<String,Object>)((List<?>)d.get("accessors")).get(0)).put("count",100001));
        bad(d->((Map<String,Object>)((List<?>)d.get("accessors")).get(0)).put("componentType",5123));
        bad(d->((Map<String,Object>)((List<?>)d.get("materials")).get(0)).put("alphaMode","BLEND"));
        bad(d->((Map<String,Object>)((Map<?,?>)((List<?>)d.get("materials")).get(0)).get("pbrMetallicRoughness"))
            .put("baseColorTexture",Map.of("index",0)));
        byte[] malformed=container(document(),binary()); malformed[0]=0; reject(()->GlbSurfaceReader.read(malformed));
        reject(()->GlbSurfaceReader.read(Arrays.copyOf(malformed,18)));
        byte[] bin=binary(); ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).putFloat(0,Float.NaN); reject(()->GlbSurfaceReader.read(container(document(),bin)));
        byte[] badIndex=binary(); ByteBuffer.wrap(badIndex).order(ByteOrder.LITTLE_ENDIAN).putShort(60,(short)3); reject(()->GlbSurfaceReader.read(container(document(),badIndex)));
        byte[] repeat=binary(); ByteBuffer.wrap(repeat).order(ByteOrder.LITTLE_ENDIAN).putFloat(36,2); reject(()->GlbSurfaceReader.read(container(document(),repeat)));
        byte[] padded=container(document(),binary()); padded[padded.length-1]=1; reject(()->GlbSurfaceReader.read(padded));
        patchChecks();
        if(args.length==1) {
            byte[] cube=Files.readAllBytes(Path.of(args[0]));
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cube))
                .equals("985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630"),"authorized cube identity");
            var actual=GlbSurfaceReader.read(cube);
            check(actual.size()==1 && actual.get(0).positions().size()==24 && actual.get(0).indices().size()==36,"real cube counts");
            check(actual.get(0).positions().stream().distinct().count()==8 && actual.get(0).textureUvs().size()==24
                && actual.get(0).sourceNormals().size()==24,"coincident seam slots and source normals retained");
            check(actual.get(0).doubleSided() && actual.get(0).baseColor().get(3)==1,"opaque two-sided base color");
            var patches=SurfacePatchPlan.plan(actual.get(0));
            check(patches.size()==6 && patches.stream().allMatch(p->p.positions().size()==4 && p.indices().size()==6),
                "cube yields six coplanar four-vertex patches, not twelve triangle controllers");
            check(patches.stream().flatMap(p->p.sourceTriangles().stream()).distinct().count()==12
                && patches.stream().flatMap(p->p.sourceVertices().stream()).distinct().count()==24,"complete source identity coverage");
            for(var patch:patches)for(int i=0;i<patch.sourceVertices().size();i++) {
                int source=patch.sourceVertices().get(i);
                check(patch.positions().get(i).equals(actual.get(0).positions().get(source))
                    && patch.textureUvs().get(i).equals(actual.get(0).textureUvs().get(source)),"patch source position/UV correspondence");
            }
            System.out.println("AUTHORIZED_GLB decode PASS: 24 seam vertices, 12 triangles; hostExecuted=false");
        }
        System.out.println("GlbSurfaceReader checks="+checks+" PASS; hostExecuted=false");
    }

    private static Map<String,Object> document(){
        return Json.parseObject(Json.bytes(Map.of(
            "asset",Map.of("version","2.0"),"scene",0,"scenes",List.of(Map.of("nodes",List.of(0))),
            "nodes",List.of(Map.of("mesh",0)),"meshes",List.of(Map.of("primitives",List.of(Map.of(
                "attributes",Map.of("POSITION",0,"TEXCOORD_0",1),"indices",2,"material",0)))),
            "materials",List.of(Map.of("doubleSided",true,"pbrMetallicRoughness",Map.of("baseColorFactor",List.of(.8,.8,.8,1)))),
            "buffers",List.of(Map.of("byteLength",66)),"bufferViews",List.of(Map.of("buffer",0,"byteLength",36),
                Map.of("buffer",0,"byteOffset",36,"byteLength",24),Map.of("buffer",0,"byteOffset",60,"byteLength",6)),
            "accessors",List.of(Map.of("bufferView",0,"componentType",5126,"count",3,"type","VEC3"),
                Map.of("bufferView",1,"componentType",5126,"count",3,"type","VEC2"),Map.of("bufferView",2,"componentType",5123,"count",3,"type","SCALAR")))));
    }
    private static byte[] binary(){
        ByteBuffer b=ByteBuffer.allocate(66).order(ByteOrder.LITTLE_ENDIAN);
        for(float v:new float[]{1,0,0,0,1,0,0,0,1,0,0,1,0,0,1})b.putFloat(v);
        b.putShort((short)0).putShort((short)1).putShort((short)2);return b.array();
    }
    private static void patchChecks(){
        var a=new SurfaceProjection.Vertex(0,0,0);var b=new SurfaceProjection.Vertex(1,0,0);
        var c=new SurfaceProjection.Vertex(1,1,0);var d=new SurfaceProjection.Vertex(0,1,0);
        var quad=patchSurface(List.of(a,b,c,d),List.of(0,1,2,0,2,3));
        check(SurfacePatchPlan.plan(quad).size()==1,"coplanar source-connected triangles share a draw patch");
        var crease=patchSurface(List.of(a,b,c,new SurfaceProjection.Vertex(0,1,.1)),List.of(0,1,2,0,2,3));
        check(SurfacePatchPlan.plan(crease).size()==2,"crease separates candidate draw patches");
        var seam=patchSurface(List.of(a,b,c,a,c,d),List.of(0,1,2,3,4,5));
        check(SurfacePatchPlan.plan(seam).size()==2,"coincident positions and UVs never establish source adjacency");
        var bad=patchSurface(List.of(a,b,c,d),List.of(0,1,2,1,0,3,0,1,3));
        reject(()->SurfacePatchPlan.plan(bad));
    }
    private static GlbSurfaceReader.Surface patchSurface(List<SurfaceProjection.Vertex> p,List<Integer> indices){
        return new GlbSurfaceReader.Surface(7,3,2,0,p,List.of(),p.stream().map(v->new dev.turboism.sdk.cubism.model.Point2(0,0)).toList(),
            indices,List.of(1d,1d,1d,1d),true,false);
    }
    private static byte[] container(Map<String,Object> doc,byte[] bin){
        byte[] json=Json.bytes(doc); int jsonSize=(json.length+3)&~3,binSize=(bin.length+3)&~3;
        ByteBuffer b=ByteBuffer.allocate(28+jsonSize+binSize).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(jsonSize).putInt(0x4e4f534a).put(json);
        while(b.position()<20+jsonSize)b.put((byte)' ');
        b.putInt(binSize).putInt(0x004e4942).put(bin);return b.array();
    }
    private static void bad(java.util.function.Consumer<Map<String,Object>> change){var d=document();change.accept(d);reject(()->GlbSurfaceReader.read(container(d,binary())));}
    private static void reject(Runnable action){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("invalid GLB accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
