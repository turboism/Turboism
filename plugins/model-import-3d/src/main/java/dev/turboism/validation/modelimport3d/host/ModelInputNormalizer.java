package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.json.Json;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;
import java.util.zip.*;

/** Deterministic, bounded input normalization. Never accesses paths or the network. */
final class ModelInputNormalizer {
    private ModelInputNormalizer() { }
    private static final int LIMIT=GlbSurfaceReader.MAX_BYTES;
    static final class MissingResource extends IllegalArgumentException {
        final String key, reference;
        MissingResource(String key,String reference) {
            super("模型缺少资源："+reference+"。请选择对应文件，或将模型与资源一起打包为 ZIP。");
            this.key=key;this.reference=reference;
        }
    }
    /** Resources are separately granted bytes, not paths to read from the filesystem. */
    static byte[] normalizeSelected(byte[] input,Map<String,byte[]> resources) throws IOException {
        require(input!=null && input.length>0 && input.length<=LIMIT,"模型输入超过 16 MiB 限制。");
        long total=input.length;
        require(resources.size()<=64,"外部资源最多允许 64 个文件。");
        for(var entry:resources.entrySet()) {
            require(packagePath("",entry.getKey()).equals(entry.getKey()),"外部资源标识无效。");
            require(entry.getValue()!=null,"外部资源内容为空。");total+=entry.getValue().length;
        }
        require(total<=LIMIT,"模型及外部资源合计超过 16 MiB 限制。");
        if(input.length>=4 && ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).getInt()==0x46546c67)
            return glb(input,resources,"model/");
        if(input.length>=4 && input[0]=='P' && input[1]=='K')return archive(input);
        int at=0;while(at<input.length && Character.isWhitespace((char)(input[at]&255)))at++;
        return at<input.length && input[at]=='{'?gltf(input,resources,"model/"):ObjModelInput.normalize(input,resources,"model/");
    }
    static byte[] normalize(byte[] input) throws IOException {
        require(input!=null && input.length>0 && input.length<=LIMIT,"模型输入超过 16 MiB 限制。");
        if(input.length>=4 && ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).getInt()==0x46546c67)
            return glb(input);
        if(input.length>=4 && input[0]=='P' && input[1]=='K')return archive(input);
        int at=0;while(at<input.length && Character.isWhitespace((char)(input[at]&255)))at++;
        return at<input.length && input[at]=='{'?gltf(input,Map.of(),""):ObjModelInput.normalize(input,Map.of(),"");
    }
    @SuppressWarnings("unchecked")
    private static byte[] glb(byte[] input) throws IOException {
        return glb(input,Map.of(),"");
    }
    @SuppressWarnings("unchecked")
    private static byte[] glb(byte[] input,Map<String,byte[]> resources,String base) throws IOException {
        require(input.length>=28,"GLB 头不完整。");var bytes=ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        require(bytes.getInt()==0x46546c67 && bytes.getInt()==2 && bytes.getInt()==input.length,"GLB 头或长度无效。");
        int length=bytes.getInt();require(bytes.getInt()==0x4e4f534a && length>0 && length<=2*1024*1024 && length%4==0 && length<=bytes.remaining()-8,"GLB JSON 范围无效。");
        byte[] text=new byte[length];bytes.get(text);Object value=Json.parseObject(text);require(value instanceof Map<?,?>,"GLB JSON 必须是对象。");var doc=(Map<String,Object>)value;
        int binLength=bytes.getInt();require(bytes.getInt()==0x004e4942 && binLength==bytes.remaining(),"GLB BIN 范围无效。");
        var images=objects(doc.getOrDefault("images",List.of()),"GLB images");
        boolean convert=images.stream().anyMatch(image->"image/jpeg".equals(image.get("mimeType")) || image.containsKey("uri"));if(!convert)return input.clone();
        byte[] binary=new byte[binLength];bytes.get(binary);
        var views=objects(doc.get("bufferViews"),"GLB bufferViews");
        for(var image:images)if("image/jpeg".equals(image.get("mimeType"))) {
            if(image.containsKey("uri"))continue;
            int view=number(image.get("bufferView"));require(view<views.size(),"GLB image bufferView 越界。");var entry=views.get(view);
            require(number(entry.get("buffer"))==0,"GLB 贴图需要嵌入 buffer。");int offset=number(entry.getOrDefault("byteOffset",0)),size=number(entry.get("byteLength"));require((long)offset+size<=binary.length,"GLB 贴图范围越界。");
            image.remove("bufferView");image.put("uri","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(Arrays.copyOfRange(binary,offset,offset+size)));
        }
        doc.put("buffers",List.of(Map.of("byteLength",binary.length,"uri","data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(binary))));
        return gltf(Json.bytes(doc),resources,base);
    }
    private static byte[] archive(byte[] input) throws IOException {
        int expected=archiveEntryCount(input);
        var files=new TreeMap<String,byte[]>();int total=0,entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(input),StandardCharsets.UTF_8)) {
            for(ZipEntry e;(e=zip.getNextEntry())!=null;) {
                require(++entries<=128,"模型包最多允许 128 个条目。");
                String path=path(e.getName());
                if(e.isDirectory())continue;
                require(!files.containsKey(path),"模型包包含重复路径。");
                byte[] bytes=zip.readNBytes(LIMIT-total+1);total+=bytes.length;
                require(total<=LIMIT,"模型包解压后超过 16 MiB 限制。");
                files.put(path,bytes);zip.closeEntry();
            }
        }
        require(entries==expected,"ZIP 条目数量不完整。");
        var models=files.keySet().stream().filter(p->p.toLowerCase(Locale.ROOT).endsWith(".gltf") || p.toLowerCase(Locale.ROOT).endsWith(".glb") || p.toLowerCase(Locale.ROOT).endsWith(".obj")).toList();
        require(models.size()==1,"模型包必须包含且仅包含一个 GLB、glTF 或 OBJ 模型。");
        String model=models.get(0);
        if(model.toLowerCase(Locale.ROOT).endsWith(".glb")) {
            byte[] glb=files.get(model);require(glb.length>=4 && ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt()==0x46546c67,"包内 GLB 格式无效。");return glb(glb);
        }
        int slash=model.lastIndexOf('/');
        String base=slash<0?"":model.substring(0,slash+1);
        return model.toLowerCase(Locale.ROOT).endsWith(".obj")?ObjModelInput.normalize(files.get(model),files,base):gltf(files.get(model),files,base);
    }
    private static int archiveEntryCount(byte[] input) {
        var b=ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        for(int end=input.length-22;end>=Math.max(0,input.length-65557);end--)if(b.getInt(end)==0x06054b50) {
            int comment=Short.toUnsignedInt(b.getShort(end+20));if(end+22+comment!=input.length)continue;
            int entries=Short.toUnsignedInt(b.getShort(end+10));
            require(b.getShort(end+4)==0 && b.getShort(end+6)==0 && Short.toUnsignedInt(b.getShort(end+8))==entries,"不支持多卷 ZIP。");
            long length=Integer.toUnsignedLong(b.getInt(end+12)),offset=Integer.toUnsignedLong(b.getInt(end+16));
            require(entries>0 && entries<=128 && offset+length==end,"ZIP 目录不完整或超出数量限制。");return entries;
        }throw new IllegalArgumentException("ZIP 文件不完整。");
    }
    @SuppressWarnings("unchecked")
    static byte[] gltf(byte[] json,Map<String,byte[]> files,String base) throws IOException {
        require(json.length<=LIMIT,"glTF JSON 超过 16 MiB 限制。");
        Object parsed=Json.parseObject(json);require(parsed instanceof Map<?,?>,"glTF 必须是 JSON 对象。");
        var doc=(Map<String,Object>)parsed;
        var buffers=objects(doc.get("buffers"),"glTF buffers");
        require(!buffers.isEmpty() && buffers.size()<=32,"glTF buffer 数量超限。");
        var binary=new ByteArrayOutputStream();var offsets=new ArrayList<Integer>();var lengths=new ArrayList<Integer>();
        for(var buffer:buffers) {
            byte[] data=resource(buffer.get("uri"),files,base);
            int declared=number(buffer.get("byteLength"));require(declared==data.length,"glTF buffer 长度不符。");
            align(binary);offsets.add(binary.size());lengths.add(declared);append(binary,data);
        }
        var views=objects(doc.getOrDefault("bufferViews",new ArrayList<>()),"glTF bufferViews");
        require(views.size()<=4096,"glTF bufferView 数量超限。");
        for(var view:views) {
            int buffer=number(view.get("buffer"));require(buffer<offsets.size(),"glTF bufferView buffer 越界。");
            int offset=number(view.getOrDefault("byteOffset",0)),length=number(view.get("byteLength"));
            require((long)offset+length<=lengths.get(buffer),"glTF bufferView 范围越界。");
            view.put("buffer",0);view.put("byteOffset",offsets.get(buffer)+offset);
        }
        var images=objects(doc.getOrDefault("images",new ArrayList<>()),"glTF images");
        require(images.size()<=32,"glTF image 数量超限。");
        for(var image:images)if(image.containsKey("uri")) {
            require(!image.containsKey("bufferView"),"glTF image 同时含 URI 和 bufferView。");
            byte[] encoded=resource(image.remove("uri"),files,base);
            if(image.containsKey("mimeType"))require(Set.of("image/png","image/jpeg").contains(image.get("mimeType")),"贴图 MIME 不支持。");
            byte[] png=ModelTextureNormalizer.png(encoded);
            align(binary);int offset=binary.size();append(binary,png);
            image.put("bufferView",views.size());image.put("mimeType","image/png");
            views.add(new LinkedHashMap<>(Map.of("buffer",0,"byteOffset",offset,"byteLength",png.length)));
        }
        doc.put("bufferViews",views);doc.put("buffers",List.of(Map.of("byteLength",binary.size())));
        byte[] text=Json.bytes((Map<String,Object>) canonical(doc));require(text.length<=2*1024*1024,"规范化 glTF JSON 超限。");
        int padded=(text.length+3)&~3;align(binary);byte[] bin=binary.toByteArray();
        require(28L+padded+bin.length<=LIMIT,"规范化 GLB 超过 16 MiB 限制。");
        var out=ByteBuffer.allocate(28+padded+bin.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546c67).putInt(2).putInt(out.capacity()).putInt(padded).putInt(0x4e4f534a).put(text);
        while(out.position()<20+padded)out.put((byte)' ');
        out.putInt(bin.length).putInt(0x004e4942).put(bin);return out.array();
    }
    private static byte[] resource(Object value,Map<String,byte[]> files,String base) {
        require(value instanceof String,"glTF 资源缺少 URI。");String uri=(String)value;
        if(uri.startsWith("data:")) {
            int comma=uri.indexOf(',');require(comma>0 && uri.substring(0,comma).endsWith(";base64"),"仅支持 base64 data URI。");
            String mime=uri.substring(5,comma-7);
            require(Set.of("application/octet-stream","application/gltf-buffer","image/png","image/jpeg").contains(mime),"不支持的 data URI MIME。");
            byte[] data=Base64.getDecoder().decode(uri.substring(comma+1));require(data.length<=LIMIT,"data URI 超限。");return data;
        }
        URI parsed=URI.create(uri);
        require(!parsed.isAbsolute() && parsed.getRawAuthority()==null && parsed.getRawQuery()==null && parsed.getRawFragment()==null,"模型资源必须是包内相对路径。");
        return packageResource(files,base,parsed.getPath());
    }
    static byte[] packageResource(Map<String,byte[]> files,String base,String name) {
        String resolved=packagePath(base,name);byte[] bytes=files.get(resolved);
        if(bytes==null)throw new MissingResource(resolved,name);return bytes;
    }
    static String packagePath(String base,String name) {
        require(name!=null && !name.isEmpty() && name.length()<=1024 && !name.startsWith("/") && !name.startsWith("\\") && !name.contains(":"),"模型资源路径无效。");
        var parts=new ArrayDeque<String>();
        for(String part:(base+name.replace('\\','/')).split("/",-1)) {
            require(part.chars().noneMatch(c->c<32),"模型资源路径无效。");
            if(part.isEmpty() || part.equals("."))continue;
            if(part.equals("..")){require(!parts.isEmpty(),"模型资源越出所选 ZIP。");parts.removeLast();}else parts.addLast(part);
        }
        require(!parts.isEmpty(),"模型资源路径为空。");return String.join("/",parts);
    }
    private static String path(String value) {
        require(value!=null && !value.isEmpty() && value.length()<=1024 && !value.startsWith("/") && !value.contains("\\") && !value.contains(":"),"模型包路径无效。");
        for(String part:value.split("/",-1))require(!part.equals("..") && !part.equals(".") && part.chars().noneMatch(c->c<32),"模型包路径无效。");
        return value;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> objects(Object value,String name) {
        require(value instanceof List<?>,name+" 必须是数组。");var result=new ArrayList<Map<String,Object>>();
        for(Object item:(List<?>)value){require(item instanceof Map<?,?>,name+" 条目必须是对象。");result.add((Map<String,Object>)item);}return result;
    }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();for(var entry:map.entrySet()){require(entry.getKey() instanceof String,"JSON 对象键必须是字符串。");sorted.put((String)entry.getKey(),canonical(entry.getValue()));}return sorted;}
        if(value instanceof List<?> list)return list.stream().map(ModelInputNormalizer::canonical).toList();return value;
    }
    private static int number(Object value) {
        require(value instanceof Number,"glTF 整数缺失。");
        double n=((Number)value).doubleValue();require(Double.isFinite(n) && n>=0 && n<=LIMIT && n==Math.rint(n),"glTF 整数越界。");return (int)n;
    }
    private static void align(ByteArrayOutputStream b){while(b.size()%4!=0)b.write(0);}
    private static void append(ByteArrayOutputStream b,byte[] bytes){require((long)b.size()+bytes.length<=LIMIT,"模型资源总量超限。");b.writeBytes(bytes);}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
}
