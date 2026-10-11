package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.cubism.model.Point2;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded GLB 2 input stage: static indexed opaque triangles with UV0 and base color.
 * No document access. Node-instance/primitive/vertex identities and UV seams survive.
 * Materials are imported as flat base color, not a PBR lighting simulation.
 * Embedded opaque PNG base-color textures are bounded; other material maps, skins and extensions are refused.
 */
final class GlbSurfaceReader {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_VERTICES = 100_000, MAX_INDICES = 600_000;
    private final Map<?, ?> document;
    private final ByteBuffer binary;
    private final int bufferLength;
    private int vertexCount, indexCount;
    private final Map<Integer,EmbeddedBaseColorTexture> textureCache=new java.util.HashMap<>();

    record Surface(int node, int mesh, int primitive, int material, List<SurfaceProjection.Vertex> positions,
            List<SurfaceProjection.Vertex> sourceNormals, List<Point2> textureUvs, List<Integer> indices, List<Double> baseColor, boolean doubleSided,
            boolean reflected, EmbeddedBaseColorTexture texture) {
        Surface(int node,int mesh,int primitive,int material,List<SurfaceProjection.Vertex> positions,
                List<SurfaceProjection.Vertex> sourceNormals,List<Point2> textureUvs,List<Integer> indices,
                List<Double> baseColor,boolean doubleSided,boolean reflected) {
            this(node,mesh,primitive,material,positions,sourceNormals,textureUvs,indices,baseColor,doubleSided,reflected,null);
        }
        Surface {
            positions = List.copyOf(positions); sourceNormals = List.copyOf(sourceNormals);
            textureUvs = List.copyOf(textureUvs); indices = List.copyOf(indices);
            baseColor = List.copyOf(baseColor);
        }
    }

    static List<Surface> read(byte[] input) {
        require(input != null && input.length >= 28 && input.length <= MAX_BYTES, "GLB container byte budget");
        ByteBuffer bytes = ByteBuffer.wrap(input.clone()).order(ByteOrder.LITTLE_ENDIAN);
        require(bytes.getInt() == 0x46546c67 && bytes.getInt() == 2 && bytes.getInt() == bytes.capacity(), "GLB2 header/length");
        int jsonLength = bytes.getInt();
        require(bytes.getInt() == 0x4e4f534a && jsonLength > 0 && jsonLength <= 2 * 1024 * 1024
            && jsonLength % 4 == 0 && jsonLength <= bytes.remaining() - 8, "GLB JSON chunk");
        byte[] json = new byte[jsonLength]; bytes.get(json);
        Map<?, ?> document = object(Json.parseObject(json), "glTF document");
        int binLength = bytes.getInt();
        require(bytes.getInt() == 0x004e4942 && binLength >= 0 && binLength % 4 == 0 && binLength == bytes.remaining(),
            "one embedded BIN chunk required; extra chunks unsupported");
        return new GlbSurfaceReader(document, bytes.slice().order(ByteOrder.LITTLE_ENDIAN)).scene();
    }

    private GlbSurfaceReader(Map<?, ?> document, ByteBuffer binary) {
        this.document = document; this.binary = binary;
        var asset = object(document.get("asset"), "asset");
        require("2.0".equals(asset.get("version")) && (!asset.containsKey("minVersion")
            || "2.0".equals(asset.get("minVersion"))), "unsupported glTF asset version/minVersion");
        require(optionalList(document, "extensionsRequired").isEmpty()
            && optionalList(document, "extensionsUsed").isEmpty(), "glTF extensions unsupported");
        rejectExtensions(document);
        for (String field : List.of("skins", "animations")) {
            require(optionalList(document, field).isEmpty(), field + " unsupported in static base-color input");
        }
        for(String field:List.of("textures","images","samplers"))
            require(optionalList(document,field).size()<=32,"texture/image/sampler inventory budget");
        var buffers = list(document.get("buffers"), false);
        require(buffers.size() == 1, "one embedded buffer required");
        var buffer = object(buffers.get(0), "buffer");
        require(!buffer.containsKey("uri"), "external/data URI buffers unsupported");
        bufferLength = integer(buffer.get("byteLength"), "buffer length");
        require(bufferLength > 0 && bufferLength <= binary.remaining() && binary.remaining() - bufferLength <= 3, "BIN length/padding");
        for (int i = bufferLength; i < binary.remaining(); i++) require(binary.get(i) == 0, "nonzero BIN padding");
        require(list(document.get("nodes"), false).size() <= 1024, "node budget");
    }

    private List<Surface> scene() {
        var scenes = list(document.get("scenes"), false);
        int selected = optionalInt(document, "scene", 0);
        var scene = object(at(scenes, selected, "scene"), "scene");
        var result = new ArrayList<Surface>();
        var visited = new HashSet<Integer>();
        for (Object root : list(scene.get("nodes"), false)) visit(integer(root, "scene root"), identity(), visited, result, 0);
        require(!result.isEmpty(), "selected scene contains no supported surfaces");
        return List.copyOf(result);
    }

    private void visit(int index, double[] parent, Set<Integer> visited, List<Surface> result, int depth) {
        require(depth <= 64 && visited.add(index), "node cycle/shared child/depth budget");
        var node = object(at(list(document.get("nodes"), false), index, "node"), "node");
        require(!node.containsKey("skin") && !node.containsKey("weights"), "skinned/morph instance unsupported");
        double[] world = multiply(parent, transform(node));
        double determinant = determinant(world);
        require(Double.isFinite(determinant) && Math.abs(determinant) > 1e-12, "singular node transform");
        if (node.containsKey("mesh")) {
            int meshIndex = integer(node.get("mesh"), "mesh");
            var mesh = object(at(list(document.get("meshes"), false), meshIndex, "mesh"), "mesh");
            require(!mesh.containsKey("weights"), "mesh morph weights unsupported");
            var primitives = list(mesh.get("primitives"), false);
            for (int i = 0; i < primitives.size(); i++) result.add(surface(index, meshIndex, i,
                object(primitives.get(i), "primitive"), world, determinant < 0));
        }
        for (Object child : optionalList(node, "children")) visit(integer(child, "child"), world, visited, result, depth + 1);
    }

    private Surface surface(int node, int mesh, int primitiveIndex, Map<?, ?> primitive, double[] world, boolean reflected) {
        require(optionalInt(primitive, "mode", 4) == 4 && !primitive.containsKey("targets"), "only static TRIANGLES supported");
        var attributes = object(primitive.get("attributes"), "attributes");
        require(attributes.keySet().stream().allMatch(Set.of("POSITION", "NORMAL", "TEXCOORD_0")::contains),
            "unsupported vertex attribute; color/extra UV/skin data must not be discarded");
        var positions = floats(integer(attributes.get("POSITION"), "POSITION"), "VEC3", 3);
        var uvs = floats(integer(attributes.get("TEXCOORD_0"), "TEXCOORD_0"), "VEC2", 2);
        require(positions.size() == uvs.size(), "position/UV count mismatch");
        var sourceNormals = new ArrayList<SurfaceProjection.Vertex>();
        if (attributes.containsKey("NORMAL")) {
            var normals = floats(integer(attributes.get("NORMAL"), "NORMAL"), "VEC3", 3);
            require(normals.size() == positions.size(), "normal count mismatch");
            for (double[] n : normals) require(Math.abs(Math.hypot(Math.hypot(n[0], n[1]), n[2])-1) <= 1e-4,
                "non-unit source normal");
            for (double[] n : normals) sourceNormals.add(new SurfaceProjection.Vertex(n[0], n[1], n[2]));
        }
        vertexCount += positions.size();
        require(vertexCount <= MAX_VERTICES, "expanded instance vertex budget");
        var points = new ArrayList<SurfaceProjection.Vertex>();
        var textureUvs = new ArrayList<Point2>();
        for (int i = 0; i < positions.size(); i++) {
            double[] v = positions.get(i);
            points.add(new SurfaceProjection.Vertex(world[0]*v[0]+world[4]*v[1]+world[8]*v[2]+world[12],
                world[1]*v[0]+world[5]*v[1]+world[9]*v[2]+world[13], world[2]*v[0]+world[6]*v[1]+world[10]*v[2]+world[14]));
            var uv = uvs.get(i);
            require(uv[0] >= 0 && uv[0] <= 1 && uv[1] >= 0 && uv[1] <= 1, "UV repetition/out-of-range unsupported");
            textureUvs.add(new Point2((float) uv[0], (float) uv[1]));
        }
        List<Integer> indices = indices(integer(primitive.get("indices"), "indices"), points.size());
        indexCount += indices.size(); require(indexCount <= MAX_INDICES, "expanded instance index budget");
        for (int i = 0; i < indices.size(); i += 3) {
            int a = indices.get(i), b = indices.get(i+1), c = indices.get(i+2);
            require(a != b && b != c && a != c, "repeated triangle index");
            var p = points.get(a); var q = points.get(b); var r = points.get(c);
            double x1=q.x()-p.x(), y1=q.y()-p.y(), z1=q.z()-p.z();
            double x2=r.x()-p.x(), y2=r.y()-p.y(), z2=r.z()-p.z();
            require(Math.hypot(Math.hypot(y1*z2-z1*y2, z1*x2-x1*z2), x1*y2-y1*x2) > 1e-12,
                "degenerate source triangle");
        }
        var material = primitive.containsKey("material")
            ? object(at(list(document.get("materials"), false), integer(primitive.get("material"), "material"), "material"), "material") : Map.of();
        require(!material.containsKey("alphaMode") || "OPAQUE".equals(material.get("alphaMode")), "transparent/masked material unsupported");
        require(!material.containsKey("normalTexture") && !material.containsKey("occlusionTexture")
            && !material.containsKey("emissiveTexture"), "material texture unsupported");
        double[] emissive = vector(material, "emissiveFactor", new double[3]);
        require(Arrays.stream(emissive).allMatch(v -> v == 0), "emissive material unsupported");
        var pbr = material.containsKey("pbrMetallicRoughness") ? object(material.get("pbrMetallicRoughness"), "PBR") : Map.of();
        require(!pbr.containsKey("metallicRoughnessTexture"), "metallic/roughness texture unsupported");
        var texture=pbr.containsKey("baseColorTexture") ? texture(object(pbr.get("baseColorTexture"),"base-color texture")) : null;
        double[] color = vector(pbr, "baseColorFactor", new double[]{1,1,1,1});
        require(Arrays.stream(color).allMatch(v -> v >= 0 && v <= 1) && color[3] == 1, "base-color/alpha range");
        Object sided = material.get("doubleSided");
        require(!material.containsKey("doubleSided") || sided instanceof Boolean, "doubleSided must be boolean");
        return new Surface(node, mesh, primitiveIndex, primitive.containsKey("material") ? integer(primitive.get("material"), "material") : -1,
            points, sourceNormals, textureUvs, indices,
            Arrays.stream(color).boxed().toList(), Boolean.TRUE.equals(sided), reflected, texture);
    }

    private EmbeddedBaseColorTexture texture(Map<?,?> info) {
        require(optionalInt(info,"texCoord",0)==0,"only TEXCOORD_0 supported");
        int index=integer(info.get("index"),"texture index");
        if(textureCache.containsKey(index))return textureCache.get(index);
        var texture=object(at(optionalList(document,"textures"),index,"texture"),"texture");
        // glTF's implicit REPEAT sampler is not equivalent to the native image edge behavior.
        var sampler=object(at(optionalList(document,"samplers"),integer(texture.get("sampler"),"explicit sampler"),"sampler"),"sampler");
        require(optionalInt(sampler,"wrapS",10497)==33071 && optionalInt(sampler,"wrapT",10497)==33071
                && optionalInt(sampler,"magFilter",-1)==9729 && optionalInt(sampler,"minFilter",-1)==9729,
                "texture needs explicit CLAMP_TO_EDGE and LINEAR filters; pre-bake repeat/mipmap inputs");
        int imageIndex=integer(texture.get("source"),"texture source");
        var image=object(at(optionalList(document,"images"),imageIndex,"image"),"image");
        require(!image.containsKey("uri") && "image/png".equals(image.get("mimeType")),"embedded PNG only; external/data URI images unsupported");
        var view=object(at(list(document.get("bufferViews"),false),integer(image.get("bufferView"),"image view"),"image view"),"image view");
        require(integer(view.get("buffer"),"image buffer")==0 && !view.containsKey("byteStride") && !view.containsKey("target"),"image view layout");
        int offset=optionalInt(view,"byteOffset",0),length=integer(view.get("byteLength"),"image length");
        require(offset>=0 && length>0 && length<=dev.turboism.sdk.cubism.model.AuthorizedImageContent.MAX_PNG_BYTES
                && (long)offset+length<=bufferLength,"embedded PNG view bounds");
        byte[] png=new byte[length];binary.duplicate().position(offset).get(png);
        require(png.length>=33,"embedded PNG header bounds");
        var header=java.nio.ByteBuffer.wrap(png);int declaredWidth=header.getInt(16),declaredHeight=header.getInt(20);
        require(declaredWidth>0 && declaredHeight>0,"positive source texture dimensions");
        long pixels=(long)declaredWidth*declaredHeight;
        for(var existing:textureCache.values())pixels+=(long)existing.width()*existing.height();
        require(pixels<=dev.turboism.sdk.cubism.model.AuthorizedImageContent.MAX_RGBA_BYTES/4L,
            "aggregate source texture RGBA budget");
        var decoded=EmbeddedBaseColorTexture.decode(index,imageIndex,png);
        textureCache.put(index,decoded);return decoded;
    }

    private record Accessor(int offset, int stride, int count, int componentType) { }
    private Accessor accessor(int index, String type, int components, boolean indices) {
        var accessor = object(at(list(document.get("accessors"), false), index, "accessor"), "accessor");
        require(type.equals(accessor.get("type")) && !accessor.containsKey("sparse")
            && (!accessor.containsKey("normalized") || Boolean.FALSE.equals(accessor.get("normalized"))), "accessor type/sparse/normalization unsupported");
        int component = integer(accessor.get("componentType"), "component type");
        require(indices ? Set.of(5121,5123,5125).contains(component) : component == 5126, "accessor component type unsupported");
        int width = component == 5121 ? 1 : component == 5123 ? 2 : 4;
        int count = integer(accessor.get("count"), "accessor count");
        require(count > 0 && count <= (indices ? MAX_INDICES : MAX_VERTICES), "accessor count budget");
        var view = object(at(list(document.get("bufferViews"), false), integer(accessor.get("bufferView"), "bufferView"), "bufferView"), "bufferView");
        require(integer(view.get("buffer"), "view buffer") == 0, "non-embedded buffer reference");
        int viewOffset = optionalInt(view, "byteOffset", 0), viewLength = integer(view.get("byteLength"), "view length");
        int offset = optionalInt(accessor, "byteOffset", 0), stride = optionalInt(view, "byteStride", components*width);
        require(!indices || !view.containsKey("byteStride"), "index interleaving unsupported");
        require(!view.containsKey("byteStride") || (stride >= 4 && stride <= 252 && stride % 4 == 0), "invalid attribute stride");
        require(stride >= components*width && offset % width == 0 && ((long)viewOffset+offset) % width == 0,
            "accessor alignment/stride");
        require((long)viewOffset+viewLength <= bufferLength && (long)offset+(long)(count-1)*stride+components*width <= viewLength,
            "accessor out of buffer bounds");
        return new Accessor(viewOffset+offset, stride, count, component);
    }

    private List<double[]> floats(int index, String type, int components) {
        var a = accessor(index,type,components,false); var result = new ArrayList<double[]>(a.count());
        for (int i=0;i<a.count();i++) {
            double[] row=new double[components];
            for (int k=0;k<components;k++) { row[k]=binary.getFloat(a.offset()+i*a.stride()+4*k); require(Double.isFinite(row[k]), "non-finite vertex data"); }
            result.add(row);
        }
        return result;
    }

    private List<Integer> indices(int index, int vertices) {
        var a=accessor(index,"SCALAR",1,true); require(a.count()%3==0,"triangle index count");
        var result=new ArrayList<Integer>(a.count());
        for(int i=0;i<a.count();i++) {
            int at=a.offset()+i*a.stride(); long value=switch(a.componentType()) {
                case 5121 -> Byte.toUnsignedInt(binary.get(at)); case 5123 -> Short.toUnsignedInt(binary.getShort(at));
                default -> Integer.toUnsignedLong(binary.getInt(at));
            };
            require(value<vertices,"triangle index outside source vertex domain"); result.add((int)value);
        }
        return result;
    }

    private static double[] transform(Map<?, ?> node) {
        if(node.containsKey("matrix")) {
            require(!node.containsKey("translation")&&!node.containsKey("rotation")&&!node.containsKey("scale"),"matrix and TRS both present");
            double[] m=vector(node,"matrix",new double[16]);
            require(m[3]==0&&m[7]==0&&m[11]==0&&m[15]==1,"non-affine node matrix"); return m;
        }
        double[] t=vector(node,"translation",new double[3]), s=vector(node,"scale",new double[]{1,1,1});
        double[] q=vector(node,"rotation",new double[]{0,0,0,1});
        double norm=Math.sqrt(Arrays.stream(q).map(v->v*v).sum()); require(Math.abs(norm-1)<=1e-6,"non-unit node quaternion");
        double x=q[0]/norm,y=q[1]/norm,z=q[2]/norm,w=q[3]/norm;
        return new double[]{(1-2*(y*y+z*z))*s[0],2*(x*y+z*w)*s[0],2*(x*z-y*w)*s[0],0,
            2*(x*y-z*w)*s[1],(1-2*(x*x+z*z))*s[1],2*(y*z+x*w)*s[1],0,
            2*(x*z+y*w)*s[2],2*(y*z-x*w)*s[2],(1-2*(x*x+y*y))*s[2],0,t[0],t[1],t[2],1};
    }
    private static double[] identity(){return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static double[] multiply(double[] a,double[] b){
        double[] out=new double[16]; for(int c=0;c<4;c++)for(int r=0;r<4;r++)for(int k=0;k<4;k++)out[4*c+r]+=a[4*k+r]*b[4*c+k];
        require(Arrays.stream(out).allMatch(Double::isFinite),"node transform overflow"); return out;
    }
    private static double determinant(double[] m){return m[0]*(m[5]*m[10]-m[9]*m[6])-m[4]*(m[1]*m[10]-m[9]*m[2])+m[8]*(m[1]*m[6]-m[5]*m[2]);}
    private static double[] vector(Map<?,?> map,String name,double[] fallback){
        if(!map.containsKey(name))return fallback; var values=list(map.get(name),false); require(values.size()==fallback.length,name+" vector length");
        double[] result=new double[values.size()]; for(int i=0;i<result.length;i++) {
            require(values.get(i) instanceof Number,name+" number"); result[i]=((Number)values.get(i)).doubleValue(); require(Double.isFinite(result[i]),name+" finite");
        } return result;
    }
    private static int optionalInt(Map<?,?> map,String key,int fallback){return map.containsKey(key)?integer(map.get(key),key):fallback;}
    private static int integer(Object value,String name){
        require(value instanceof Number,name+" integer required");
        try{int result=new BigDecimal(value.toString()).intValueExact();require(result>=0,name+" negative");return result;}
        catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException(name+" integer range",e);}
    }
    private static Object at(List<?> list,int index,String name){require(index<list.size(),name+" index out of bounds");return list.get(index);}
    private static Map<?,?> object(Object value,String name){require(value instanceof Map<?,?>,name+" object required");return (Map<?,?>)value;}
    private static List<?> list(Object value,boolean optional){if(value==null&&optional)return List.of();require(value instanceof List<?>,"array required");return (List<?>)value;}
    private static List<?> optionalList(Map<?,?> map,String key){return map.containsKey(key)?list(map.get(key),false):List.of();}
    private static void rejectExtensions(Object value){
        if(value instanceof Map<?,?> m){require(!m.containsKey("extensions")||object(m.get("extensions"),"extensions").isEmpty(),"extension data unsupported");m.values().forEach(GlbSurfaceReader::rejectExtensions);}
        else if(value instanceof List<?> l)l.forEach(GlbSurfaceReader::rejectExtensions);
    }
    private static void require(boolean value,String reason){if(!value)throw new IllegalArgumentException(reason);}
}
