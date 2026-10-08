package dev.turboism.validation.modelimport3d.nativeprobe;

import com.live2d.cubism.CEAppCtrl;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Dedicated task carrier. Native document save and backup-manager entry, no OS input or geometry writes. */
public final class CatNativeSerializationAgent {
    static final String HOST_SHA="988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    static void require(boolean pass,String message) { if(!pass)throw new IllegalStateException(message); }
    static String sha(Path file)throws Exception { try(var in=Files.newInputStream(file)){return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));} }
    static <T>T edt(Callable<T> call)throws Exception { var task=new FutureTask<T>(call);SwingUtilities.invokeAndWait(task);return task.get(); }
    static CModelingDocument document() {
        Object view;
        try {
            Object app=CEAppCtrl.class.getMethod("access$get_instance$cp").invoke(null);
            view=app.getClass().getMethod("getCurrentViewContext").invoke(app);
        } catch(Exception failure){throw new IllegalStateException(failure);}
        require(view!=null && view.getClass().getName().equals("com.live2d.cubism.view.context.CEViewContext_ModelingView"),"dedicated modeling view");
        try { return (CModelingDocument)view.getClass().getMethod("getDoc").invoke(view); }
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    static final class Sink {
        final MessageDigest digest;final java.nio.ByteBuffer bytes=java.nio.ByteBuffer.allocate(65536);
        Sink()throws Exception { digest=MessageDigest.getInstance("SHA-256"); }
        void integer(int value){if(bytes.remaining()<4)flush();bytes.putInt(value);}
        void flush(){digest.update(bytes.array(),0,bytes.position());bytes.clear();}
        String finish(){flush();return HexFormat.of().formatHex(digest.digest());}
    }
    static String bank(CModelSource source)throws Exception {
        var sink=new Sink();int count=0;
        for(var mesh:source.getAllArtMeshes()) {
            var forms=new ArrayList<>(mesh.getKeyforms());
            var extension=mesh.getExtensions().stream().filter(CExtendedInterpolationExtension.class::isInstance).map(CExtendedInterpolationExtension.class::cast).findFirst().orElseThrow();
            for(var form:extension.getNormalExtendedKeyforms())forms.add((CArtMeshForm)form);
            for(var form:forms) {
                sink.integer(form.getPositions().length);
                for(float value:form.getPositions())sink.integer(Float.floatToRawIntBits(value));
                sink.integer(form.getDrawOrder());sink.integer(Float.floatToRawIntBits(form.getOpacity()));count++;
            }
        }
        require(count==557568,"full Cat source and derived inventory");return sink.finish();
    }
    static Class<?> bridge(String name)throws Exception {
        Class<?> type=Class.forName("dev.turboism.adapter.cubism.interpolation."+name);
        var url=type.getResource("/"+type.getName().replace('.','/')+".class");
        require(url!=null && url.getProtocol().equals("jar"),"runtime jar resource");
        var connection=(java.net.JarURLConnection)url.openConnection();connection.setUseCaches(false);
        require(sha(Path.of(connection.getJarFileURL().toURI())).equals(System.getProperty("turboism.validation.mi3d.serializationAgentSha")),"admitted runtime agent identity");
        return type;
    }
    static void execute(Path state,Properties request,Properties result)throws Exception {
        require(sha(Path.of(CEAppCtrl.class.getProtectionDomain().getCodeSource().getLocation().toURI())).equals(HOST_SHA),"official artifact identity");
        if("cat-default-serialization".equals(System.getProperty("turboism.validation.mi3d.mode"))) {
            for(String key:List.of("turboism.meshInterpolation.enabled","turboism.meshInterpolation.coefficients",
                    "turboism.meshInterpolation.serializationProjection","turboism.meshInterpolation.serializationGridProjection",
                    "turboism.meshInterpolation.serializationStreaming","turboism.modelSerialization.enabled"))
                require(System.getProperty(key)==null,"default path cannot use explicit optimization flag: "+key);
            result.setProperty("defaultFlagsAbsent","true");
        }
        var streaming=bridge("NativeXmlStreamingBridge");var projection=bridge("DerivedCacheSerializationBridge");
        var doc=edt(CatNativeSerializationAgent::document);var source=edt(doc::getModelSource);
        require(source.getAllArtMeshes().size()==512 && source.getAllArtMeshes().get(0).getGuid().getUuidString().equals(request.getProperty("sourceGuid")),"exact task source identity");
        require(edt(()->doc.getCompletePack().getAllFileContents().size())==1,"only the Cat task document may be backed up");
        require(sha(edt(()->doc.getFile().toPath())).equals(request.getProperty("seedSha256")),"exact admitted seed bytes");
        String before=edt(()->bank(source));result.setProperty("beforeBank",before);
        Path saved=state.resolve("cat-native-document-save.cmo3");require(!Files.exists(saved),"new output only");
        long count=((Number)streaming.getMethod("streamedArchives").invoke(null)).longValue(),start=System.nanoTime();
        require(edt(()->doc.saveDocument(saved.toFile(),false)),"official document save rejected");
        require(Files.isRegularFile(saved) && Files.size(saved)>0,"native save output missing");
        require(((Number)streaming.getMethod("streamedArchives").invoke(null)).longValue()==count+1,"native document save did not use streaming");
        result.setProperty("documentSaveNs",Long.toString(System.nanoTime()-start));result.setProperty("savePath",saved.toString());result.setProperty("saveSha256",sha(saved));
        String afterSave=edt(()->bank(source));require(before.equals(afterSave),"native save changed source or cache bank");result.setProperty("afterSaveBank",afterSave);
        var managerClass=Class.forName("com.live2d.cubism.util.a");Object manager=managerClass.getField("a").get(null);
        Path backup=((java.io.File)managerClass.getMethod("i").invoke(manager)).toPath();
        Set<Path> existing;try(var paths=Files.walk(backup)){existing=new HashSet<>(paths.filter(Files::isRegularFile).toList());}
        long lastSaved=edt(doc::getLastSavedTime),lastBackup=edt(doc::getLastAutoBackupTime);
        result.setProperty("backupEligibilityModifiedTime",Long.toString(edt(doc::getLastModifiedTime)));
        count=((Number)streaming.getMethod("streamedArchives").invoke(null)).longValue();start=System.nanoTime();
        try {
            // Reversible document timestamps exercise native dirty-file eligibility, without editing geometry/history.
            edt(()->{doc.setLastSavedTime(-1L);doc.setLastAutoBackupTime(-1L);require(doc.isModifiedAfterSaving(),"native backup dirty gate not entered");return null;});
            managerClass.getMethod("h").invoke(manager);
            require(((Number)streaming.getMethod("streamedArchives").invoke(null)).longValue()==count+1,"native backup did not serialize the task model");
            List<Path> created;try(var paths=Files.walk(backup)){created=paths.filter(Files::isRegularFile).filter(p->!existing.contains(p)).filter(p->p.getFileName().toString().endsWith(".cmo3")).toList();}
            require(created.size()==1,"exactly one native backup model required");
            Path copy=state.resolve("cat-native-auto-backup.cmo3");Files.copy(created.get(0),copy);
            result.setProperty("backupPath",copy.toString());result.setProperty("backupSha256",sha(copy));
            result.setProperty("backupNs",Long.toString(System.nanoTime()-start));
        } finally { edt(()->{doc.setLastSavedTime(lastSaved);doc.setLastAutoBackupTime(lastBackup);return null;}); }
        String afterBackup=edt(()->bank(source));require(before.equals(afterBackup),"native backup changed source or cache bank");
        result.setProperty("afterBackupBank",afterBackup);result.setProperty("projection",projection.getMethod("diagnostic").invoke(null).toString());
        require(!((Boolean)projection.getMethod("projectCache").invoke(null)),"serializer context leaked");
        result.setProperty("scope","NATIVE_DOCUMENT_SAVE_AND_MANUAL_NATIVE_BACKUP_MANAGER_ENTRY_WITH_REVERSIBLE_DIRTY_TIMESTAMPS_NOT_TIMER_OR_CONCURRENT_EDIT_TEST");
    }
    public static void premain(String ignored) throws Exception {
        require(Set.of("cat-native-serialization","cat-default-serialization").contains(System.getProperty("turboism.validation.mi3d.mode","")),"explicit dedicated profile required");
        Path jar=Path.of(CatNativeSerializationAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        require(jar.getParent().getFileName().toString().equals("agents"),"task agent directory required");
        Path state=jar.getParent().getParent().resolve("turboism-home/state/dev.turboism.validation.model-import-3d-user-entry");
        Thread worker=new Thread(()->{
            try {
                Path requestFile=state.resolve("serialization-request.properties");long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(15);
                while(!Files.isRegularFile(requestFile) && System.nanoTime()<deadline)Thread.sleep(100);
                if(!Files.isRegularFile(requestFile))return;
                var request=new Properties();try(var in=Files.newInputStream(requestFile)){request.load(in);}
                var result=new Properties();result.setProperty("schemaVersion","mi3d-cat-native-serialization-v1");
                try { execute(state,request,result);result.setProperty("status","PASS"); }
                catch(Throwable failure){result.setProperty("status","FAIL");result.setProperty("error",failure.toString());failure.printStackTrace();}
                Path temporary=state.resolve("serialization-response.tmp");try(var out=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW)){result.store(out,"dedicated native serialization");}
                Files.move(temporary,state.resolve("serialization-response.properties"));
            } catch(Throwable failure){failure.printStackTrace();}
        },"mi3d-native-serialization");worker.setDaemon(true);worker.start();
        System.err.println("CAT_NATIVE_SERIALIZATION_READY");
    }
}
