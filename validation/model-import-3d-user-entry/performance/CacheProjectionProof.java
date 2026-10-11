package dev.turboism.diagnostic;

import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.serialize.UUSerialize;
import dev.turboism.adapter.cubism.interpolation.DerivedCacheSerializationBridge;
import java.util.*;
import org.jdom.output.XMLOutputter;

/** Compare serializer-only projection to the established clear/save/restore oracle. */
public final class CacheProjectionProof {
    static String xml(Object value) { return new XMLOutputter().outputString(UUSerialize.INSTANCE.writeXml(value,new HashMap<>())); }
    public static void main(String[] args) throws Exception {
        try { run(args); System.exit(0); } catch(Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        var mesh=new CArtMeshSource();
        var extension=new CExtendedInterpolationExtension(mesh);
        var cache=extension.getNormalExtendedKeyforms();
        for(int i=0;i<33;i++)cache.add(new CArtMeshForm(mesh,500+i,1f,new float[]{i,0f,i+1f,1f,i+2f,0f},new com.live2d.doc.CoordType("DeformerLocal")));
        var originals=new ArrayList<>(cache);
        var originalGrid=extension.getExtendedKeyformGridSource();
        boolean projectGrid=Boolean.getBoolean("turboism.meshInterpolation.serializationGridProjection");
        DerivedCacheSerializationBridge.enabled(false);
        String withCache=xml(extension);
        cache.clear();if(projectGrid)extension.setExtendedKeyformGridSource(null);
        String oracle=xml(extension);
        cache.addAll(originals);extension.setExtendedKeyformGridSource(originalGrid);
        DerivedCacheSerializationBridge.enabled(true);
        String projection=xml(extension);
        if(args.length>0) {
            java.nio.file.Path out=java.nio.file.Path.of(args[0]);
            java.nio.file.Files.writeString(out.resolve("with-cache.xml"),withCache);
            java.nio.file.Files.writeString(out.resolve("oracle.xml"),oracle);
            java.nio.file.Files.writeString(out.resolve("projected.xml"),projection);
        }
        if(!oracle.equals(projection)||withCache.equals(projection))throw new AssertionError("projection differs from official empty-cache serialization");
        if(extension.getExtendedKeyformGridSource()!=originalGrid || extension.getNormalExtendedKeyforms()!=cache || !cache.equals(originals))throw new AssertionError("projection mutated live cache");
        if(!CatRuntimeInterpolationAgent.installation.serializationPatched())throw new AssertionError("serialization hook missing");
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread observer=new Thread(()->{
            try { if(extension.getNormalExtendedKeyforms()!=cache)throw new AssertionError("projection leaked onto another thread"); }
            catch(Throwable failure) { error.set(failure); }
        });observer.start();observer.join();
        if(error.get()!=null)throw new AssertionError("observer failed",error.get());
        var boundary=UUSerialize.class.getDeclaredMethod("serializeToDoc_exe",Object.class,com.live2d.serialize.XmlWriter.class);
        boundary.setAccessible(true);
        try { boundary.invoke(UUSerialize.INSTANCE,extension,null);throw new AssertionError("failed serialization returned"); }
        catch(java.lang.reflect.InvocationTargetException expected) {
            if(!(expected.getCause() instanceof NullPointerException))throw expected;
        }
        if(DerivedCacheSerializationBridge.projectCache())throw new AssertionError("serializer context leaked");
        DerivedCacheSerializationBridge.enter();
        try { xml(extension);if(!DerivedCacheSerializationBridge.projectCache())throw new AssertionError("nested serializer lost outer context"); }
        finally { DerivedCacheSerializationBridge.exit(); }
        if(args.length>0) {
            var out=java.nio.file.Path.of(args[0]);
            var legacy=out.resolve("small-native-string.cmo3").toFile();
            var streamed=out.resolve("small-native-stream.cmo3").toFile();
            dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.enabled(false);
            if(!UUSerialize.INSTANCE.serializeToFile(legacy,extension,true,com.live2d.serialize.CompressOption.FAST,null,new HashMap<>(),false,-1))throw new AssertionError("small official save");
            long streamedBefore=dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.streamedArchives();
            dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.enabled(true);
            if(!UUSerialize.INSTANCE.serializeToFile(streamed,extension,true,com.live2d.serialize.CompressOption.FAST,null,new HashMap<>(),false,-1))throw new AssertionError("small streaming save");
            if(dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.streamedArchives()!=streamedBefore+1)throw new AssertionError("streaming path not used");
            var a=new com.live2d.serialize.archiver.ArchiveReader(legacy);var b=new com.live2d.serialize.archiver.ArchiveReader(streamed);
            try {
                if(!Arrays.equals(a.getFileByFilePath("main.xml"),b.getFileByFilePath("main.xml")))throw new AssertionError("streamed XML differs from official bytes");
            } finally { a.close();b.close(); }
            System.out.println("PASS_STREAMING_XML_EXACT_OFFICIAL_BYTES");
            dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.enabled(false);
        }
        DerivedCacheSerializationBridge.enabled(false);
        if(!withCache.equals(xml(extension)))throw new AssertionError("disabled hook failed to restore official serialization");
        System.out.println("PASS projected XML exactly equals clear/save/restore oracle; live cache identity and order retained; withCacheBytes="+withCache.length()+" projectedBytes="+projection.length());
    }
}
