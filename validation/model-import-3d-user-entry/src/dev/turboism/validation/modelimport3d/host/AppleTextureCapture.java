package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Read-only render capture after pose writes; no texture/geometry substitution. */
final class AppleTextureCapture {
    private AppleTextureCapture() {}
    static void run(Supplier<CubismModel> current,Supplier<Map<String,Object>> state,
            Supplier<HistorySnapshot> history,IntConsumer undo,Path directory,Map<String,Object> report)throws Exception {
        require(!SwingUtilities.isEventDispatchThread(),"worker required");
        var section=new LinkedHashMap<String,Object>();report.put("sourceTextureRender",section);
        section.put("status","RUNNING");section.put("schemaVersion","mi3d-apple-texture-render-v1");
        var frames=new ArrayList<Map<String,Object>>();section.put("frames",frames);
        var initial=edt(state::get);var root=edt(history::get);
        require(root.availability()==HistorySnapshot.Availability.AVAILABLE,"history unavailable");
        String protectedJson=protectedJson(initial),protectedHash=hash(protectedJson.getBytes(StandardCharsets.UTF_8));
        section.put("protectedStateJson",protectedJson);section.put("protectedStateSha256",protectedHash);
        section.put("entryHistory",history(root));
        var canvas=edt(()->canvas(current.get()));section.put("canvas",canvas);
        require(canvas.get(0)==1024f && canvas.get(1)==1024f,"1024 canvas required");
        var axes=edt(()->current.get().drawables().all().get(0).authoringSnapshot().axes());
        String yaw=axes.stream().map(a->a.parameterId().value()).filter(id->id.matches("Param3DYaw[0-9a-f]{12}")).findFirst().orElseThrow();
        String pitch=axes.stream().map(a->a.parameterId().value()).filter(id->id.matches("Param3DPitch[0-9a-f]{12}")).findFirst().orElseThrow();
        section.put("axes",List.of(yaw,pitch));
        Files.createDirectory(directory.resolve("source-texture-render"));
        Throwable primary=null;
        try {
            int index=0;
            var poses=new ArrayList<List<Float>>();for(float y:new float[]{-30,0,30})for(float p:new float[]{-30,0,30})poses.add(List.of(y,p));
            poses.add(List.of(-13.125f,9.375f));poses.add(List.of(16.875f,-24.375f));
            for(var pose:poses) {float y=pose.get(0),p=pose.get(1);
                var row=new LinkedHashMap<String,Object>();frames.add(row);row.put("index",index);row.put("pose",List.of(y,p));
                var requested=edt(()->{
                    current.get().parameters().find(new ParameterId(yaw)).setValue(y);
                    current.get().parameters().find(new ParameterId(pitch)).setValue(p);
                    return observations(current.get());
                });
                row.put("requested",requested.stream().map(AppleTextureCapture::evaluation).toList());
                awaitConsumed(requested,()->edt(()->observations(current.get())),row,System::nanoTime,Thread::sleep);
                edt(()->{
                    var before=observations(current.get());require(readiness(requested,before).equals("CONSUMED"),"pose changed before render");
                    var h0=history.get();String beforeHash=hash(protectedJson(state.get()).getBytes(StandardCharsets.UTF_8));
                    require(beforeHash.equals(protectedHash),"protected state changed before render");
                    var beforeCanvas=canvas(current.get());require(beforeCanvas.equals(canvas),"canvas changed");
                    var pixels=current.get().renderPixels(1024,1024);
                    var afterCanvas=canvas(current.get());require(afterCanvas.equals(canvas),"render changed canvas");
                    row.put("canvasBefore",beforeCanvas);row.put("canvasAfter",afterCanvas);
                    var after=observations(current.get());var h1=history.get();
                    String afterHash=hash(protectedJson(state.get()).getBytes(StandardCharsets.UTF_8));
                    require(before.equals(after) && h0.equals(h1) && afterHash.equals(protectedHash),"render changed state/evaluation/history");
                    row.put("evaluationBefore",before.stream().map(AppleTextureCapture::evaluation).toList());
                    row.put("evaluationAfter",after.stream().map(AppleTextureCapture::evaluation).toList());
                    row.put("historyBefore",history(h0));row.put("historyAfter",history(h1));
                    row.put("stateBeforeSha256",beforeHash);row.put("stateAfterSha256",afterHash);
                    require(pixels.width()==1024 && pixels.height()==1024,"render size");
                    var image=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,1024,1024,pixels.argb(),0,1024);
                    var bytes=new ByteArrayOutputStream();require(ImageIO.write(image,"PNG",bytes),"PNG writer");byte[] png=bytes.toByteArray();
                    require(png.length<=8*1024*1024,"PNG budget");String name="source-texture-render/"+row.get("index")+".png";
                    Files.write(directory.resolve(name),png,StandardOpenOption.CREATE_NEW);
                    row.put("artifact",name);row.put("artifactBytes",png.length);row.put("artifactSha256",hash(png));return null;
                });index++;
            }
            section.put("status","CAPTURED");
        } catch(Exception|Error failure) {primary=failure;section.put("status","FAIL");section.put("failure",failure.toString());throw failure;}
        finally {
            try {
                edt(()->{int count=history.get().position()-root.position();require(count>=0 && count<=32,"history restore budget");
                    if(count>0)undo.accept(count);require(state.get().equals(initial),"capture failed to restore state");
                    var restored=history.get();require(restored.position()==root.position() && restored.generation()==root.generation()
                        && restored.documentBindingId().equals(root.documentBindingId()) && restored.managerBindingId().equals(root.managerBindingId()),"history target changed");
                    section.put("restoredHistory",history(restored));section.put("restored",true);return null;});
            } catch(Exception|Error failure) {section.put("restored",false);section.put("status","FAIL");if(primary!=null)primary.addSuppressed(failure);else throw failure;}
        }
    }
    @FunctionalInterface interface Sleeper {void sleep(long millis)throws InterruptedException;}
    static void awaitConsumed(List<ArtMeshEvaluationSnapshot> requested,
            Callable<List<ArtMeshEvaluationSnapshot>> sample,Map<String,Object> progress,
            LongSupplier clock,Sleeper sleeper)throws Exception {
        long start=clock.getAsLong();int attempts=0;
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            var actual=sample.call();long elapsed=clock.getAsLong()-start;
            String reason=elapsed>=15_000_000_000L ? "TIMEOUT" : readiness(requested,actual);
            progress.put("attempts",++attempts);progress.put("elapsedMillis",elapsed/1_000_000);
            progress.put("waitReason",reason);progress.put("lastObservation",actual.stream().map(AppleTextureCapture::evaluation).toList());
            if(reason.equals("CONSUMED"))return;
            require(reason.equals("WAITING"),"source texture consumption: "+reason);
            sleeper.sleep(25);
        }
    }
    static String readiness(List<ArtMeshEvaluationSnapshot> requested,List<ArtMeshEvaluationSnapshot> actual) {
        if(requested.size()!=98 || actual.size()!=98)return "IDENTITY_CHANGED";
        boolean waiting=false;
        for(int i=0;i<98;i++) {
            var a=requested.get(i);var b=actual.get(i);
            if(!a.artMeshId().equals(b.artMeshId()) || !a.sourceGuid().equals(b.sourceGuid()))return "IDENTITY_CHANGED";
            if(!values(a.liveParameters()).equals(values(b.liveParameters())))return "LIVE_CHANGED";
            if(b.passEntryTimeMillis()<Math.max(0,a.passEntryTimeMillis()) || b.consumedParameters().updateVersion()==a.consumedParameters().updateVersion()
                    || !values(b.liveParameters()).equals(values(b.consumedParameters())))waiting=true;
        }
        return waiting?"WAITING":"CONSUMED";
    }
    private static List<Float> canvas(CubismModel model) {var c=model.canvas();return List.of(c.widthPixels(),c.heightPixels(),c.originXPixels(),c.originYPixels(),c.pixelsPerUnit());}
    private static List<ArtMeshEvaluationSnapshot> observations(CubismModel model) {return model.drawables().all().stream().map(Drawable::evaluationSnapshot).toList();}
    private static List<?> values(ArtMeshEvaluationSnapshot.ParameterState state) {return state.parameters().stream().sorted(Comparator.comparing(ArtMeshEvaluationSnapshot.ParameterValue::parameterGuid)).map(p->List.of(p.parameterGuid(),p.parameterId().value(),p.value())).toList();}
    private static Map<String,Object> evaluation(ArtMeshEvaluationSnapshot e) {
        var r=new LinkedHashMap<String,Object>();r.put("artMeshId",e.artMeshId().value());r.put("sourceGuid",e.sourceGuid());r.put("passEntryTimeMillis",e.passEntryTimeMillis());
        r.put("live",Map.of("version",e.liveParameters().updateVersion(),"values",values(e.liveParameters())));
        r.put("consumed",Map.of("version",e.consumedParameters().updateVersion(),"values",values(e.consumedParameters())));
        r.put("calculated",e.calculatedForm().map(f->Map.of("vertexCount",f.positions().size(),"drawOrder",f.drawOrder(),"coordinateType",f.coordinateType(),"interpolated",f.interpolated())).orElse(null));return r;
    }
    static Map<String,Object> history(HistorySnapshot h) {return Map.of("availability",h.availability().name(),"position",h.position(),"revision",h.revision(),"generation",h.generation(),"document",h.documentBindingId(),"manager",h.managerBindingId(),"entries",h.entries().stream().map(e->e.entryId().orElseThrow(()->new IllegalStateException("stable history entry identity unavailable")).value()).toList());}
    private static String protectedJson(Map<String,Object> state) {var copy=new LinkedHashMap<>(state);copy.put("parameters",((List<?>)state.get("parameters")).stream().map(p->((List<?>)p).subList(0,4)).toList());return NativeReport.json(copy);}
    private static String hash(byte[] b)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static <T>T edt(Callable<T> action)throws Exception {var task=new FutureTask<T>(action);SwingUtilities.invokeAndWait(task);return task.get();}
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
}
