package dev.modelimport.validation;

import com.live2d.sdk.cubism.core.CubismMoc;
import com.live2d.sdk.cubism.core.CubismModel;
import com.live2d.sdk.cubism.core.Live2DCubismCore;
import java.io.*;
import java.util.zip.GZIPOutputStream;
import java.security.DigestOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Standalone official Core recorder. No Editor, Turboism SDK, runtime or plugin dependencies. */
public final class OfficialCoreCapture {
    private static final long MOC_LIMIT=32L*1024*1024;
    private OfficialCoreCapture() { }
    public static void main(String[] args)throws Exception {
        System.out.println("CORE_STAGE MAIN_ENTERED");System.out.flush();
        if(args.length!=4 && args.length!=5 && args.length!=6)throw new IllegalArgumentException("MOC output-directory yaw-parameter-id pitch-parameter-id [capture-profile] required");
        boolean derived=args.length==6 && args[5].equals("apple-derived-xyz-140-v1");
        boolean xyz=args.length==6 && (derived || args[5].equals("apple-xyz-140-v1"));
        if(args.length==6 && !xyz)throw new IllegalArgumentException("unknown capture profile");
        boolean surface=args.length==5 && args[4].equals("surface-dense-49-v1");
        boolean apple=args.length==5 && !surface;
        boolean domain=apple && args[4].equals("apple-domain-353-v1");
        if(apple && !domain && !args[4].equals("apple-11-v1"))throw new IllegalArgumentException("unknown capture profile");
        long mocLimit=xyz?256L*1024*1024:apple?128L*1024*1024:MOC_LIMIT;
        Path input=plain(Path.of(args[0])),out=plain(Path.of(args[1]));
        if(!Files.isRegularFile(input,LinkOption.NOFOLLOW_LINKS) || Files.size(input)<64 || Files.size(input)>mocLimit)
            throw new IllegalArgumentException("MOC must be a bounded regular file");
        if(args[2].isBlank() || args[3].isBlank() || args[2].equals(args[3]))throw new IllegalArgumentException("two distinct parameter IDs required");
        if(xyz && (args[4].isBlank() || args[4].equals(args[2]) || args[4].equals(args[3])))throw new IllegalArgumentException("three distinct parameter IDs required");
        byte[] bytes;
        try(var stream=Files.newInputStream(input)) {bytes=stream.readNBytes((int)mocLimit+1);}
        if(bytes.length>mocLimit || bytes.length<64 || bytes[0]!='M' || bytes[1]!='O' || bytes[2]!='C' || bytes[3]!='3')
            throw new IllegalArgumentException("MOC3 header/budget mismatch");
        if(xyz){System.out.println("CORE_STAGE MOC_READ bytes="+bytes.length);System.out.flush();}
        var loader=OfficialCoreCapture.class.getClassLoader();
        for(String resource:List.of("dev/turboism/sdk/plugin/PluginContext.class","dev/turboism/bootstrap/BootstrapAgent.class",
                "dev/turboism/adapter/cubism/CubismFacadeImpl.class"))
            if(loader.getResource(resource)!=null)throw new IllegalStateException("Turboism is on the recorder classpath");
        var vm=ManagementFactory.getRuntimeMXBean();
        if(vm.getInputArguments().stream().anyMatch(a->a.startsWith("-javaagent:") || a.startsWith("-agentlib:") || a.startsWith("-agentpath:")))
            throw new IllegalStateException("recorder requires a process without agents");
        var coreOrigin=origin(CubismMoc.class);var recorderOrigin=origin(OfficialCoreCapture.class);
        requireClasspath(coreOrigin,recorderOrigin);
        var process=ProcessHandle.current();
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("schemaVersion",derived?"mi3d-apple-derived-xyz-official-core-capture-v1":xyz?"mi3d-apple-xyz-official-core-capture-v1":surface?"mi3d-surface-official-core-capture-v1":domain?"mi3d-apple-official-core-domain-capture-v1":apple?"mi3d-apple-official-core-capture-v1":"mi3d-official-core-capture-v2");evidence.put("status","CAPTURED_NOT_VERIFIED");
        evidence.put("poseProtocol",derived?"mi3d-apple-derived-xyz-140-v1":xyz?"mi3d-apple-xyz-140-v1":surface?"mi3d-surface-dense-9921-v1":domain?"mi3d-apple-layer-domain-353-v1":apple?"mi3d-apple-geometry-11-v1":"mi3d-cube-geometry-377-v1");
        evidence.put("mocSha256",sha(bytes));evidence.put("mocBytes",bytes.length);
        evidence.put("pid",process.pid());evidence.put("processStart",process.info().startInstant().orElseThrow().toString());
        evidence.put("jvmArguments",vm.getInputArguments());evidence.put("classpath",System.getProperty("java.class.path"));
        evidence.put("coreOrigin",coreOrigin);evidence.put("recorderOrigin",recorderOrigin);
        evidence.put("coreVersion",Live2DCubismCore.getVersion().toString());
        if(xyz){System.out.println("CORE_STAGE CORE_LOADED");System.out.flush();}
        if(!Live2DCubismCore.hasMocConsistency(bytes))throw new IllegalArgumentException("official Core rejected MOC consistency");
        if(xyz){System.out.println("CORE_STAGE CONSISTENCY_ACCEPTED");System.out.flush();}
        try(var moc=CubismMoc.instantiate(bytes);var model=moc.instantiateModel()) {
            if(xyz){System.out.println("CORE_STAGE MODEL_INSTANTIATED");System.out.flush();}
            var p=model.getParameters();
            if(p.getCount()>4096 || model.getDrawables().getCount()>4096)throw new IllegalArgumentException("model count budget exceeded");
            int yaw=index(p.getIds(),args[2]),pitch=index(p.getIds(),args[3]),roll=xyz?index(p.getIds(),args[4]):-1;
            for(int axis:(xyz?new int[]{yaw,pitch,roll}:new int[]{yaw,pitch}))
                if(p.getMinimumValues()[axis]>(xyz?-180:-30) || p.getMaximumValues()[axis]<(xyz?180:30))throw new IllegalArgumentException("parameter domain does not cover poses");
            evidence.put("mocVersion",moc.getMocVersion());
            evidence.put("canvas",Map.of("size",model.getCanvasInfo().getSizeInPixels(),"origin",model.getCanvasInfo().getOriginInPixels(),
                "pixelsPerUnit",model.getCanvasInfo().getPixelsPerUnit()));
            evidence.put("parameters",Map.of("ids",p.getIds(),"minimum",p.getMinimumValues(),"maximum",p.getMaximumValues(),
                "default",p.getDefaultValues(),"keyCounts",p.getKeyCounts(),"keys",p.getKeyValues(),"repeats",p.getParameterRepeats()));
            evidence.put("axes",xyz?List.of(args[2],args[3],args[4]):List.of(args[2],args[3]));
            if(xyz){captureXYZ(model,yaw,pitch,roll,out,evidence,derived);System.out.println("OFFICIAL_CORE_XYZ_CAPTURED; independent source verification still required");return;}
            if(surface) {
                var rows=drawables(model);
                boolean plane=rows.size()==1 && (Integer)rows.get(0).get("vertexCount")==4;
                boolean seam=rows.size()==2 && rows.stream().allMatch(r->(Integer)r.get("vertexCount")==3);
                if(!plane && !seam)throw new IllegalArgumentException("surface profile requires complete plane or seam layout");
            }
            if(domain) {
                captureDomain(model,yaw,pitch,out,evidence);
                System.out.println("OFFICIAL_CORE_DOMAIN_CAPTURED; independent source verification still required");
                return;
            }
            var frames=new ArrayList<Map<String,Object>>();
            for(double[] pose:(surface?surfacePoses():apple?applePoses():poses())) {
                System.arraycopy(p.getDefaultValues(),0,p.getValues(),0,p.getCount());
                p.getValues()[yaw]=(float)pose[0];p.getValues()[pitch]=(float)pose[1];
                model.resetDrawableDynamicFlags();model.update();
                // Copy each frame: official arrays are mutable views of the next update.
                frames.add(Map.of("pose",pose,"values",p.getValues().clone(),"drawables",drawables(model)));
            }
            evidence.put("frames",frames);
            String json=json(evidence);
            if(json.getBytes(StandardCharsets.UTF_8).length>(apple?256L:64L)*1024*1024)throw new IllegalArgumentException("report budget exceeded");
            Files.createDirectory(out);
            Files.writeString(out.resolve("core-capture.json"),json+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }
        System.out.println("OFFICIAL_CORE_CAPTURED; independent geometry/raster verification still required");
    }
    /** Frozen layer keys, all cell/edge midpoints and 64 PCG64(4012027) float32 poses. */
    static double[][] appleDomainPoses() {
        var poses=new ArrayList<double[]>();
        for(int y=0;y<17;y++)for(int p=0;p<17;p++)poses.add(new double[]{-30+3.75*y,-30+3.75*p});
        for(double[] pose:new double[][]{{-16.209083557128906,19.058353424072266},{-5.374926567077637,28.428680419921875},{3.7596983909606934,-23.706052780151367},{-28.765705108642578,25.949453353881836},{-18.100818634033203,13.676994323730469},{28.68636703491211,-29.619213104248047},{11.219045639038086,17.997299194335938},{18.213502883911133,26.710651397705078},{18.177001953125,5.008863925933838},{16.911043167114258,-12.841861724853516},{1.0717473030090332,28.730375289916992},{-4.132311820983887,-14.630465507507324},{-29.536205291748047,0.5830563306808472},{-20.286314010620117,9.666141510009766},{-2.911710262298584,7.662674903869629},{-11.906396865844727,-29.484912872314453},{5.856235027313232,13.086173057556152},{-29.432498931884766,-24.419767379760742},{24.909765243530273,16.6470947265625},{-29.60669708251953,23.176424026489258},{-18.219650268554688,26.89920425415039},{7.175976276397705,-15.597223281860352},{19.18378448486328,-5.906917572021484},{8.345706939697266,3.626599073410034},{-4.563911437988281,16.989910125732422},{13.841788291931152,-22.71415901184082},{27.683155059814453,-20.039081573486328},{-29.094614028930664,10.350420951843262},{-19.949087142944336,-24.215845108032227},{8.865918159484863,-4.022687911987305},{1.7995189428329468,7.854432106018066},{-28.91837501525879,15.321013450622559},{-2.123734712600708,9.990045547485352},{-17.472042083740234,13.93738842010498},{-20.602941513061523,8.592161178588867},{-9.257081031799316,-5.3039679527282715},{-6.9716267585754395,-17.970075607299805},{-12.158268928527832,29.74662208557129},{18.28473663330078,-21.327659606933594},{15.436593055725098,2.1214370727539062},{12.995001792907715,14.692383766174316},{22.811857223510742,-14.510303497314453},{2.3587052822113037,18.91655921936035},{-11.413640975952148,-28.122047424316406},{-1.1537632942199707,20.373828887939453},{-27.043989181518555,-23.322582244873047},{28.32240867614746,-27.04542350769043},{-1.0412859916687012,-5.869907855987549},{1.2476592063903809,25.689443588256836},{1.1509754657745361,-14.960091590881348},{3.127352714538574,-16.875614166259766},{-16.312461853027344,22.21343994140625},{-19.630210876464844,-4.305556774139404},{23.626981735229492,-8.080437660217285},{11.123614311218262,0.8783137798309326},{24.480438232421875,6.254356384277344},{20.079622268676758,5.188309669494629},{27.67375946044922,-28.17354393005371},{-21.754568099975586,-15.775323867797852},{21.663663864135742,15.19331169128418},{-3.301018714904785,21.172409057617188},{-6.275078773498535,8.930447578430176},{14.618111610412598,-15.094454765319824},{-14.490534782409668,-25.00251007080078}})poses.add(pose);
        if(poses.size()!=353)throw new IllegalStateException("domain sample inventory differs");
        return poses.toArray(double[][]::new);
    }
    private static void captureDomain(CubismModel model,int yaw,int pitch,Path out,Map<String,Object> evidence)throws Exception {
        var parameters=model.getParameters();var poses=appleDomainPoses();
        System.arraycopy(parameters.getDefaultValues(),0,parameters.getValues(),0,parameters.getCount());
        model.resetDrawableDynamicFlags();model.update();
        var baseline=drawables(model);long vertices=baseline.stream().mapToLong(r->(Integer)r.get("vertexCount")).sum();
        if(vertices!=100258 || baseline.size()!=98)throw new IllegalArgumentException("complete apple domain geometry required");
        long rawBytes=16L+poses.length*vertices*8;
        if(rawBytes>512L*1024*1024)throw new IllegalArgumentException("domain coordinate budget exceeded");
        Files.createDirectory(out);Path positions=out.resolve("positions-f32.bin.gz");
        var digest=MessageDigest.getInstance("SHA-256");var frames=new ArrayList<Map<String,Object>>();
        try(var file=Files.newOutputStream(positions,StandardOpenOption.CREATE_NEW);
            var hashed=new DigestOutputStream(file,digest);
            var gzip=new GZIPOutputStream(new BufferedOutputStream(hashed));
            var data=new DataOutputStream(gzip)) {
            data.writeBytes("APXY0001");data.writeInt(poses.length);data.writeInt((int)vertices);
            int frameIndex=0;
            for(double[] pose:poses) {
                System.arraycopy(parameters.getDefaultValues(),0,parameters.getValues(),0,parameters.getCount());
                parameters.getValues()[yaw]=(float)pose[0];parameters.getValues()[pitch]=(float)pose[1];
                model.resetDrawableDynamicFlags();model.update();
                var rows=drawables(model);var scalar=new ArrayList<Map<String,Object>>();
                for(int i=0;i<rows.size();i++) {
                    var row=rows.get(i);
                    if(!row.get("id").equals(baseline.get(i).get("id")) || !row.get("vertexCount").equals(baseline.get(i).get("vertexCount")))
                        throw new IllegalStateException("drawable layout changed during domain capture");
                    for(float v:(float[])row.get("positions")) {
                        if(!Float.isFinite(v))throw new IllegalStateException("nonfinite Core domain position");
                        data.writeFloat(v);
                    }
                    var fixed=new LinkedHashMap<String,Object>();
                    for(String key:List.of("id","vertexCount","constantFlags","textureIndex","uvs","indices","masks"))fixed.put(key,row.get(key));
                    var state=new LinkedHashMap<String,Object>();
                    for(String key:List.of("id","vertexCount","dynamicFlags","drawOrder","renderOrder","opacity","blendMode","multiplyColor","screenColor"))state.put(key,row.get(key));
                    state.put("invariantSha256",sha(json(fixed).getBytes(StandardCharsets.UTF_8)));scalar.add(state);
                }
                frames.add(Map.of("pose",pose,"values",parameters.getValues().clone(),"drawables",scalar));
                if(++frameIndex%10==0){System.out.println("CORE_STAGE DOMAIN_FRAMES="+frameIndex);System.out.flush();}
            }
        }
        if(Files.size(positions)>512L*1024*1024)throw new IllegalStateException("compressed domain budget exceeded");
        evidence.put("baselineDrawables",baseline);evidence.put("frames",frames);
        evidence.put("coordinates",Map.of("artifact","positions-f32.bin.gz","bytes",Files.size(positions),
            "sha256",HexFormat.of().formatHex(digest.digest()),"uncompressedBytes",rawBytes,"verticesPerFrame",vertices));
        String report=json(evidence);
        if(report.getBytes(StandardCharsets.UTF_8).length>64L*1024*1024)throw new IllegalStateException("bounded domain metadata required");
        Files.writeString(out.resolve("core-capture.json"),report+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    private static void captureXYZ(CubismModel model,int yaw,int pitch,int roll,Path out,Map<String,Object> evidence,boolean derived)throws Exception {
        var parameters=model.getParameters();var poses=appleXYZPoses();
        System.arraycopy(parameters.getDefaultValues(),0,parameters.getValues(),0,parameters.getCount());
        model.resetDrawableDynamicFlags();model.update();
        var baseline=drawables(model);long vertices=baseline.stream().mapToLong(r->(Integer)r.get("vertexCount")).sum();
        if(vertices!=(derived?80514:100258) || baseline.size()!=98)throw new IllegalArgumentException("complete apple XYZ geometry required");
        if(derived) {
            long faces=baseline.stream().mapToLong(r->((int[])r.get("indices")).length/3).sum();
            if(faces!=150784 || baseline.stream().anyMatch(r->(Integer)r.get("textureIndex")<0 || (Integer)r.get("textureIndex")>1))
                throw new IllegalArgumentException("complete derived faces and two-page bindings required");
        }
        long rawBytes=16L+poses.length*vertices*8;
        if(rawBytes>512L*1024*1024)throw new IllegalArgumentException("domain coordinate budget exceeded");
        Files.createDirectory(out);Path positions=out.resolve("positions-f32.bin.gz");
        var digest=MessageDigest.getInstance("SHA-256");var frames=new ArrayList<Map<String,Object>>();
        try(var file=Files.newOutputStream(positions,StandardOpenOption.CREATE_NEW);
            var hashed=new DigestOutputStream(file,digest);
            var gzip=new GZIPOutputStream(new BufferedOutputStream(hashed));
            var data=new DataOutputStream(gzip)) {
            data.writeBytes("APXYZ001");data.writeInt(poses.length);data.writeInt((int)vertices);
            int frameIndex=0;
            for(double[] pose:poses) {
                System.arraycopy(parameters.getDefaultValues(),0,parameters.getValues(),0,parameters.getCount());
                parameters.getValues()[yaw]=(float)pose[0];parameters.getValues()[pitch]=(float)pose[1];parameters.getValues()[roll]=(float)pose[2];
                model.resetDrawableDynamicFlags();model.update();
                var rows=drawables(model);var scalar=new ArrayList<Map<String,Object>>();
                for(int i=0;i<rows.size();i++) {
                    var row=rows.get(i);
                    if(!row.get("id").equals(baseline.get(i).get("id")) || !row.get("vertexCount").equals(baseline.get(i).get("vertexCount")))
                        throw new IllegalStateException("drawable layout changed during domain capture");
                    for(float v:(float[])row.get("positions")) {
                        if(!Float.isFinite(v))throw new IllegalStateException("nonfinite Core domain position");
                        data.writeFloat(v);
                    }
                    var fixed=new LinkedHashMap<String,Object>();
                    for(String key:List.of("id","vertexCount","constantFlags","textureIndex","uvs","indices","masks"))fixed.put(key,row.get(key));
                    var state=new LinkedHashMap<String,Object>();
                    for(String key:List.of("id","vertexCount","dynamicFlags","drawOrder","renderOrder","opacity","blendMode","multiplyColor","screenColor"))state.put(key,row.get(key));
                    state.put("invariantSha256",sha(json(fixed).getBytes(StandardCharsets.UTF_8)));scalar.add(state);
                }
                frames.add(Map.of("pose",pose,"values",parameters.getValues().clone(),"drawables",scalar));
                if(++frameIndex%10==0){System.out.println("CORE_STAGE XYZ_FRAMES="+frameIndex);System.out.flush();}
            }
        }
        if(Files.size(positions)>512L*1024*1024)throw new IllegalStateException("compressed domain budget exceeded");
        evidence.put("baselineDrawables",baseline);evidence.put("frames",frames);
        evidence.put("coordinates",Map.of("artifact","positions-f32.bin.gz","bytes",Files.size(positions),
            "sha256",HexFormat.of().formatHex(digest.digest()),"uncompressedBytes",rawBytes,"verticesPerFrame",vertices));
        String report=json(evidence);
        if(report.getBytes(StandardCharsets.UTF_8).length>64L*1024*1024)throw new IllegalStateException("bounded domain metadata required");
        Files.writeString(out.resolve("core-capture.json"),report+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    static double[][] appleXYZPoses() {
        var poses=new ArrayList<double[]>();
        // Yaw, pitch, roll: complete axis endpoints, quarter turns, joint off-key poses.
        for(double[] p:new double[][]{{0,0,0},{0,-180,0},{0,180,0},{-180,0,0},{180,0,0},{0,0,-180},{0,0,180},{0,90,0},{90,0,0},{0,0,90},{-112.5,37.5,45},{75,-150,-135}})poses.add(p);
        var random=new Random(0x33445859L);
        for(int i=0;i<128;i++)poses.add(new double[]{(float)(-180+360*random.nextDouble()),(float)(-180+360*random.nextDouble()),(float)(-180+360*random.nextDouble())});
        return poses.toArray(double[][]::new);
    }
    static double[][] applePoses() {
        var poses=new ArrayList<double[]>();for(double y:new double[]{-30,0,30})for(double p:new double[]{-30,0,30})poses.add(new double[]{y,p});
        poses.add(new double[]{-13.125,9.375});poses.add(new double[]{16.875,-24.375});return poses.toArray(double[][]::new);
    }
    /** All 49 keys, every cell/edge midpoint, and 512 Java Random(0x3344494d) poses. */
    static double[][] surfacePoses() {
        float[] keys=new float[49];
        for(int i=0;i<keys.length;i++)keys[i]=i==24?0:(float)(30*(2*i/48.0-1));
        float[] split=new float[97];
        for(int i=0;i<keys.length;i++) {
            split[2*i]=keys[i];
            if(i+1<keys.length)split[2*i+1]=(keys[i]+keys[i+1])/2;
        }
        var result=new ArrayList<double[]>();
        for(float yaw:split)for(float pitch:split)result.add(new double[]{yaw,pitch});
        var random=new Random(0x3344494d);
        for(int i=0;i<512;i++)result.add(new double[]{-30+60*random.nextFloat(),-30+60*random.nextFloat()});
        if(result.size()!=9921)throw new IllegalStateException("surface pose inventory differs");
        return result.toArray(double[][]::new);
    }
    /** Frozen critical, axis-key, diagonal-midpoint and seeded random inventory. No evaluator. */
    static double[][] poses() {
        var result=new LinkedHashSet<List<Double>>();
        double[] critical={-30,-15,-1,-.25,0,.25,1,15,30};
        for(double yaw:critical)for(double pitch:critical)result.add(List.of(yaw,pitch));
        float[] keys=new float[45];
        for(int i=0;i<keys.length;i++)keys[i]=i==22?0:(float)(30*(2*i/44.0-1));
        for(float key:keys)result.add(List.of((double)key,0d));
        for(float key:keys)result.add(List.of(0d,(double)key));
        for(int i=1;i<keys.length;i++) {
            float midpoint=(keys[i-1]+keys[i])/2;
            result.add(List.of((double)midpoint,(double)midpoint));
            result.add(List.of((double)midpoint,(double)-midpoint));
        }
        var random=new Random(4012027);
        for(int i=0;i<128;i++)result.add(List.of((double)(float)(-30+60*random.nextDouble()),
            (double)(float)(-30+60*random.nextDouble())));
        if(result.size()!=377)throw new IllegalStateException("frozen pose inventory differs");
        return result.stream().map(p->new double[]{p.get(0),p.get(1)}).toArray(double[][]::new);
    }
    private static List<Map<String,Object>> drawables(CubismModel model) {
        var d=model.getDrawables();var rows=new ArrayList<Map<String,Object>>();long vertices=0;
        for(int i=0;i<d.getCount();i++) {
            vertices+=d.getVertexCounts()[i];if(vertices>2_000_000)throw new IllegalArgumentException("vertex budget exceeded");
            var row=new LinkedHashMap<String,Object>();row.put("id",d.getIds()[i]);
            row.put("constantFlags",Byte.toUnsignedInt(d.getConstantFlags()[i]));row.put("dynamicFlags",Byte.toUnsignedInt(d.getDynamicFlags()[i]));
            row.put("textureIndex",d.getTextureIndices()[i]);row.put("drawOrder",d.getDrawOrders()[i]);row.put("renderOrder",model.getRenderOrders()[i]);
            row.put("opacity",d.getOpacities()[i]);row.put("blendMode",d.getBlendModes()[i]);row.put("vertexCount",d.getVertexCounts()[i]);
            row.put("positions",d.getVertexPositions()[i].clone());row.put("uvs",d.getVertexUvs()[i].clone());
            row.put("indices",unsigned(d.getIndices()[i]));row.put("masks",d.getMasks()[i].clone());
            row.put("multiplyColor",d.getMultiplyColors()[i].clone());row.put("screenColor",d.getScreenColors()[i].clone());rows.add(row);
        }
        return rows;
    }
    private static int[] unsigned(short[] values) {int[] out=new int[values.length];for(int i=0;i<values.length;i++)out[i]=Short.toUnsignedInt(values[i]);return out;}
    private static int index(String[] ids,String id) {int found=-1;for(int i=0;i<ids.length;i++)if(ids[i].equals(id)){if(found>=0)throw new IllegalArgumentException("duplicate parameter");found=i;}if(found<0)throw new IllegalArgumentException("missing parameter: "+id);return found;}
    private static Path plain(Path p) {
        p=p.toAbsolutePath().normalize();for(Path q=p;q!=null;q=q.getParent())if(Files.isSymbolicLink(q))throw new IllegalArgumentException("linked input/output");return p;
    }
    private static Map<String,Object> origin(Class<?> type)throws Exception {
        Path path=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        if(!Files.isRegularFile(path))throw new IllegalStateException("recorder and Core must use explicit JAR artifacts");
        return Map.of("path",path.toString(),"sha256",sha(Files.readAllBytes(path)));
    }
    private static void requireClasspath(Map<String,Object> core,Map<String,Object> recorder)throws Exception {
        var expected=Set.of(plain(Path.of((String)core.get("path"))),plain(Path.of((String)recorder.get("path"))));
        String[] entries=System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator),-1);
        var actual=new HashSet<Path>();
        for(String entry:entries) {
            if(entry.isBlank())throw new IllegalStateException("recorder classpath must contain exactly Core and recorder JARs");
            Path path=plain(Path.of(entry));
            if(!expected.contains(path) || !actual.add(path))
                throw new IllegalStateException("recorder classpath must contain exactly Core and recorder JARs");
            try(var jar=new java.util.jar.JarFile(path.toFile())) {
                var manifest=jar.getManifest();
                if(manifest!=null && manifest.getMainAttributes().getValue("Class-Path")!=null)
                    throw new IllegalStateException("recorder JARs must not extend the classpath through manifests");
            }
        }
        if(!actual.equals(expected))throw new IllegalStateException("recorder classpath must contain exactly Core and recorder JARs");
    }
    private static String sha(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof String s) {var b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number n) {if(!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("nonfinite report number");return n.toString();}
        var parts=new ArrayList<String>();
        if(value instanceof Map<?,?> map){for(var e:map.entrySet()){if(!(e.getKey() instanceof String))throw new IllegalArgumentException("JSON key");parts.add(json(e.getKey())+":"+json(e.getValue()));}return "{"+String.join(",",parts)+"}";}
        if(value instanceof Iterable<?> list){for(var x:list)parts.add(json(x));return "["+String.join(",",parts)+"]";}
        if(value.getClass().isArray()){for(int i=0;i<Array.getLength(value);i++)parts.add(json(Array.get(value,i)));return "["+String.join(",",parts)+"]";}
        throw new IllegalArgumentException("unsupported report type: "+value.getClass());
    }
}
