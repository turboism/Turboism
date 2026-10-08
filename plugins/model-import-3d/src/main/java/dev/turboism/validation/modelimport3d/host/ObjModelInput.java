package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.json.Json;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Static OBJ/MTL to GLB. Polygon/corner identities, materials and UV seams are explicit. */
final class ObjModelInput {
    private record Corner(int position,int uv,int normal,int synthetic) { }
    private record Face(List<Corner> corners,String material) { }
    private static final class Material {
        final String name;List<Double> color=List.of(1d,1d,1d,1d);String image;
        Material(String name){this.name=name;}
    }
    static byte[] normalize(byte[] input,Map<String,byte[]> files,String base) throws IOException {
        var positions=new ArrayList<double[]>();var uvs=new ArrayList<double[]>();int normals=0,triangles=0;
        var faces=new ArrayList<Face>();var materials=new LinkedHashMap<String,Material>();String current="";
        materials.put("",new Material("default"));
        for(String line:lines(input)) {
            String[] words=line.split("\\s+");String kind=words[0];
            switch(kind) {
                case "v" -> {require(words.length==4 || words.length==5,"OBJ v 字段不支持顶点颜色。");double w=words.length==5?number(words[4]):1;require(w==1,"OBJ 齐次坐标要求 w=1。");positions.add(new double[]{number(words[1]),number(words[2]),number(words[3])});require(positions.size()<=100000,"OBJ 顶点超限。");}
                case "vt" -> {require(words.length==3 || words.length==4,"OBJ vt 字段无效。");double u=number(words[1]),v=number(words[2]);require(u>=0 && u<=1 && v>=0 && v<=1,"OBJ 重复 UV 暂不支持。");if(words.length==4)require(number(words[3])==0,"OBJ 3D UV 暂不支持。");uvs.add(new double[]{u,1-v});require(uvs.size()<=100000,"OBJ UV 超限。");}
                case "vn" -> {require(words.length==4,"OBJ vn 字段无效。");double length=Math.hypot(Math.hypot(number(words[1]),number(words[2])),number(words[3]));require(length>0,"OBJ 法线退化。");require(++normals<=100000,"OBJ 法线超限。");}
                case "mtllib" -> {
                    String name=line.substring(kind.length()).trim();require(!name.isEmpty(),"OBJ mtllib 缺少文件名。");
                    byte[] mtl=ModelInputNormalizer.packageResource(files,base,name);
                    String resolved=ModelInputNormalizer.packagePath(base,name);int slash=resolved.lastIndexOf('/');
                    parseMaterials(mtl,files,slash<0?"":resolved.substring(0,slash+1),materials);
                }
                case "usemtl" -> {current=line.substring(kind.length()).trim();require(!current.isEmpty(),"OBJ 材质名为空。");}
                case "f" -> {
                    require(words.length>=4 && words.length<=65,"OBJ 多边形需要 3–64 个角点。");var corners=new ArrayList<Corner>();
                    for(int i=1;i<words.length;i++) {
                        String[] corner=words[i].split("/",-1);require(corner.length<=3 && !corner[0].isEmpty(),"OBJ 角点字段无效。");
                        int p=index(corner[0],positions.size()),uv=corner.length>1 && !corner[1].isEmpty()?index(corner[1],uvs.size()):-1,n=corner.length>2 && !corner[2].isEmpty()?index(corner[2],normals):-1;
                        corners.add(new Corner(p,uv,n,-1));
                    }
                    require((triangles+=corners.size()-2)<=200000,"OBJ 三角形超限。");faces.add(new Face(List.copyOf(corners),current));
                }
                case "o","g","s" -> { /* Object/group names and smoothing do not affect static base-color rendering. */ }
                default -> throw new IllegalArgumentException("不支持的 OBJ 字段："+kind);
            }
        }
        require(!faces.isEmpty(),"OBJ 没有面。");
        var groups=new LinkedHashMap<String,List<Face>>();for(var face:faces){require(materials.containsKey(face.material()),"OBJ 使用了未定义材质："+face.material());groups.computeIfAbsent(face.material(),ignored->new ArrayList<>()).add(face);}
        require(groups.size()<=32,"OBJ 材质数量超限。");
        var binary=new ByteArrayOutputStream();var views=new ArrayList<Object>();var accessors=new ArrayList<Object>();var primitives=new ArrayList<Object>();var gltfMaterials=new ArrayList<Object>();var images=new ArrayList<Object>();var textures=new ArrayList<Object>();
        var textureIds=new LinkedHashMap<String,Integer>();
        int totalSlots=0;
        for(var group:groups.entrySet()) {
            var material=materials.get(group.getKey());var slots=new LinkedHashMap<Corner,Integer>();var xyz=new ArrayList<double[]>();var uv=new ArrayList<double[]>();var indices=new ArrayList<Integer>();int synthetic=0;
            for(var face:group.getValue())for(int[] triangle:triangulate(face,positions)) {
                for(int c=0;c<3;c++) {
                    var original=face.corners().get(triangle[c]);require(material.image==null || original.uv()>=0,"带贴图的 OBJ 面缺少 UV。");
                    var corner=original.uv()>=0?original:new Corner(original.position(),-1,original.normal(),synthetic++);
                    Integer id=slots.get(corner);if(id==null){id=slots.size();slots.put(corner,id);xyz.add(positions.get(corner.position()));uv.add(corner.uv()>=0?uvs.get(corner.uv()):new double[]{c==1?1:0,c==2?0:1});}indices.add(id);
                }
            }
            totalSlots+=slots.size();require(totalSlots<=100000,"OBJ 角点展开后超过 100,000 顶点。");
            int positionAccessor=vectors(binary,views,accessors,xyz,3),uvAccessor=vectors(binary,views,accessors,uv,2),indexAccessor=indices(binary,views,accessors,indices);
            var pbr=new LinkedHashMap<String,Object>();pbr.put("baseColorFactor",material.color);
            if(material.image!=null) {
                Integer texture=textureIds.get(material.image);
                if(texture==null) {
                byte[] png=ModelTextureNormalizer.png(ModelInputNormalizer.packageResource(files,"",material.image));
                align(binary);int offset=binary.size();require((long)binary.size()+png.length<=GlbSurfaceReader.MAX_BYTES,"OBJ 贴图资源展开超限。");binary.writeBytes(png);int view=views.size();views.add(Map.of("buffer",0,"byteOffset",offset,"byteLength",png.length));
                int image=images.size();images.add(Map.of("bufferView",view,"mimeType","image/png"));texture=textures.size();textures.add(Map.of("source",image,"sampler",0));textureIds.put(material.image,texture);
                }
                pbr.put("baseColorTexture",Map.of("index",texture));
            }
            int materialIndex=gltfMaterials.size();gltfMaterials.add(Map.of("name",material.name,"pbrMetallicRoughness",pbr));
            primitives.add(Map.of("attributes",Map.of("POSITION",positionAccessor,"TEXCOORD_0",uvAccessor),"indices",indexAccessor,"material",materialIndex));
        }
        require(binary.size()<=GlbSurfaceReader.MAX_BYTES,"OBJ 二进制展开超限。");
        var doc=new LinkedHashMap<String,Object>();doc.put("asset",Map.of("version","2.0"));doc.put("scene",0);doc.put("scenes",List.of(Map.of("nodes",List.of(0))));doc.put("nodes",List.of(Map.of("mesh",0)));doc.put("meshes",List.of(Map.of("primitives",primitives)));
        doc.put("buffers",List.of(Map.of("uri","data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(binary.toByteArray()),"byteLength",binary.size())));doc.put("bufferViews",views);doc.put("accessors",accessors);doc.put("materials",gltfMaterials);
        if(!images.isEmpty()){doc.put("images",images);doc.put("textures",textures);doc.put("samplers",List.of(Map.of("wrapS",33071,"wrapT",33071,"magFilter",9729,"minFilter",9729)));}
        return ModelInputNormalizer.gltf(Json.bytes(doc),Map.of(),"");
    }
    private static void parseMaterials(byte[] input,Map<String,byte[]> files,String base,Map<String,Material> materials) {
        Material current=null;
        for(String line:lines(input)) {
            String[] words=line.split("\\s+");String kind=words[0];
            if(kind.equals("newmtl")){String name=line.substring(kind.length()).trim();require(!name.isEmpty() && !materials.containsKey(name),"MTL 材质名重复或为空。");require(materials.size()<33,"MTL 材质数量超限。");current=new Material(name);materials.put(name,current);continue;}
            require(current!=null,"MTL 属性出现在 newmtl 之前。");
            switch(kind) {
                case "Kd" -> {require(words.length==4,"MTL Kd 无效。");var color=new ArrayList<Double>();for(int i=1;i<4;i++){double v=number(words[i]);require(v>=0 && v<=1,"MTL Kd 超出基础色范围。");color.add(v);}color.add(1d);current.color=List.copyOf(color);}
                case "d","Tr" -> {require(words.length==2,"MTL 透明度字段无效。");require(number(words[1])==(kind.equals("d")?1:0),"暂不支持透明 MTL 材质。");}
                case "map_Kd" -> {String name=line.substring(kind.length()).trim();require(!name.startsWith("-"),"MTL map_Kd 变换选项暂不支持。");ModelInputNormalizer.packageResource(files,base,name);current.image=ModelInputNormalizer.packagePath(base,name);}
                case "Ka","Ks","Ke","Ns","Ni","Tf","illum","map_Ka","map_bump","bump" -> { /* Cubism imports base color only; UI states lighting/bump are not reproduced. */ }
                default -> throw new IllegalArgumentException("不支持的 MTL 字段："+kind);
            }
        }
    }
    private static int vectors(ByteArrayOutputStream binary,List<Object> views,List<Object> accessors,List<double[]> values,int components) {
        align(binary);int offset=binary.size();var b=ByteBuffer.allocate(values.size()*components*4).order(ByteOrder.LITTLE_ENDIAN);for(double[] v:values)for(double x:v){float f=(float)x;require(Float.isFinite(f),"OBJ 坐标不能转换为有限 float。");b.putFloat(f);}binary.writeBytes(b.array());
        int view=views.size();views.add(Map.of("buffer",0,"byteOffset",offset,"byteLength",b.capacity()));int accessor=accessors.size();accessors.add(Map.of("bufferView",view,"componentType",5126,"count",values.size(),"type",components==3?"VEC3":"VEC2"));return accessor;
    }
    private static int indices(ByteArrayOutputStream binary,List<Object> views,List<Object> accessors,List<Integer> values) {
        align(binary);int offset=binary.size();var b=ByteBuffer.allocate(values.size()*4).order(ByteOrder.LITTLE_ENDIAN);for(int i:values)b.putInt(i);binary.writeBytes(b.array());int view=views.size();views.add(Map.of("buffer",0,"byteOffset",offset,"byteLength",b.capacity()));int accessor=accessors.size();accessors.add(Map.of("bufferView",view,"componentType",5125,"count",values.size(),"type","SCALAR"));return accessor;
    }
    private static List<int[]> triangulate(Face face,List<double[]> positions) {
        int count=face.corners().size();var polygon=new ArrayList<double[]>();for(var corner:face.corners())polygon.add(positions.get(corner.position()));
        double[] normal=new double[3];for(int i=0;i<count;i++){var a=polygon.get(i);var b=polygon.get((i+1)%count);normal[0]+=(a[1]-b[1])*(a[2]+b[2]);normal[1]+=(a[2]-b[2])*(a[0]+b[0]);normal[2]+=(a[0]-b[0])*(a[1]+b[1]);}
        int axis=0;for(int i=1;i<3;i++)if(Math.abs(normal[i])>Math.abs(normal[axis]))axis=i;require(Math.abs(normal[axis])>1e-12,"OBJ 多边形退化。");int x=(axis+1)%3,y=(axis+2)%3;double sign=Math.signum(normal[axis]);
        for(int i=0;i<count;i++)for(int j=i+1;j<count;j++) {
            if(j==i+1 || i==0 && j==count-1)continue;
            var a=polygon.get(i);var b=polygon.get((i+1)%count);var c=polygon.get(j);var d=polygon.get((j+1)%count);
            double abC=cross(a,b,c,x,y),abD=cross(a,b,d,x,y),cdA=cross(c,d,a,x,y),cdB=cross(c,d,b,x,y);
            require(!(abC*abD<=0 && cdA*cdB<=0 && Math.max(Math.min(a[x],b[x]),Math.min(c[x],d[x]))<=Math.min(Math.max(a[x],b[x]),Math.max(c[x],d[x])) && Math.max(Math.min(a[y],b[y]),Math.min(c[y],d[y]))<=Math.min(Math.max(a[y],b[y]),Math.max(c[y],d[y]))),"OBJ 多边形自交或重叠。");
        }
        boolean convex=true;for(int i=0;i<count;i++)if(cross(polygon.get(i),polygon.get((i+1)%count),polygon.get((i+2)%count),x,y)*sign<=0)convex=false;
        var result=new ArrayList<int[]>();if(convex){for(int i=1;i<count-1;i++)result.add(new int[]{0,i,i+1});return result;}
        var remaining=new ArrayList<Integer>();for(int i=0;i<count;i++)remaining.add(i);
        while(remaining.size()>3) {
            boolean found=false;
            for(int at=0;at<remaining.size();at++) {
                int a=remaining.get((at+remaining.size()-1)%remaining.size()),b=remaining.get(at),c=remaining.get((at+1)%remaining.size());
                if(cross(polygon.get(a),polygon.get(b),polygon.get(c),x,y)*sign<=1e-12)continue;boolean inside=false;
                for(int p:remaining)if(p!=a && p!=b && p!=c && cross(polygon.get(a),polygon.get(b),polygon.get(p),x,y)*sign>=0 && cross(polygon.get(b),polygon.get(c),polygon.get(p),x,y)*sign>=0 && cross(polygon.get(c),polygon.get(a),polygon.get(p),x,y)*sign>=0){inside=true;break;}
                if(inside)continue;result.add(new int[]{a,b,c});remaining.remove(at);found=true;break;
            }
            require(found,"OBJ 多边形无法可靠三角化，请检查自交或重复角点。");
        }
        result.add(new int[]{remaining.get(0),remaining.get(1),remaining.get(2)});return result;
    }
    private static double cross(double[] a,double[] b,double[] c,int x,int y){return (b[x]-a[x])*(c[y]-a[y])-(b[y]-a[y])*(c[x]-a[x]);}
    private static int index(String text,int count){int i=Integer.parseInt(text);require(i!=0,"OBJ 索引不能为 0。");int result=i>0?i-1:count+i;require(result>=0 && result<count,"OBJ 索引越界。");return result;}
    private static double number(String text){double n=Double.parseDouble(text);require(Double.isFinite(n),"OBJ/MTL 数值必须有限。");return n;}
    private static List<String> lines(byte[] input) {
        require(input.length<=GlbSurfaceReader.MAX_BYTES,"OBJ/MTL 文件超限。");String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();}catch(CharacterCodingException e){throw new IllegalArgumentException("OBJ/MTL 需要 UTF-8 编码。",e);}
        var result=new ArrayList<String>();for(String raw:text.split("\\R")){int comment=raw.indexOf('#');String line=(comment<0?raw:raw.substring(0,comment)).trim();require(line.length()<=16384,"OBJ/MTL 单行超限。");if(!line.isEmpty())result.add(line);}return result;
    }
    private static void align(ByteArrayOutputStream b){while(b.size()%4!=0)b.write(0);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
}
