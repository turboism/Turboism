package dev.turboism.validation;

import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Test-only validation at actual native texture creation calls; no production GL calls are replaced. */
public final class NativeTextureUploadHostAgent {
    private static final String ENABLE = "turboism.optimization.textureUploadPreparation";
    private static final String CALLBACK = "turboism.texture-upload-preparation.callback";
    private static final String STATS = "turboism.texture-upload-preparation.stats";
    private static final Properties RESULT = new Properties();

    /** Starts one task-scoped observer, not a Cubism application or a synthetic performance loop. */
    public static void premain(String args, Instrumentation instrumentation) {
        long start = System.nanoTime(), cpuStart = NativeFloatArrayHostAgent.processCpu();
        var allocations = new NativeFloatArrayHostAgent.Allocations();
        boolean enabled = Boolean.getBoolean(ENABLE), shadow = Boolean.getBoolean("turboism.validation.textureUpload.shadow");
        Capture capture = new Capture(shadow);
        if (enabled) capture.start();
        Thread worker = new Thread(() -> run(instrumentation, start, cpuStart, allocations, capture, enabled, shadow), "turboism-texture-upload-validation");
        worker.setDaemon(true); worker.start();
    }

    private static void run(Instrumentation instrumentation, long start, long cpuStart,
                            NativeFloatArrayHostAgent.Allocations allocations, Capture capture, boolean enabled, boolean shadow) {
        Path home = Path.of(System.getProperty("turboism.validation.textureUpload.home"));
        Path output = home.resolve("state/texture-upload/result.properties");
        try {
            RESULT.setProperty("schemaVersion","1");
            RESULT.setProperty("runId",System.getProperty("turboism.validation.runId",""));
            RESULT.setProperty("fixtureName",System.getProperty("turboism.validation.fixtureName",""));
            RESULT.setProperty("hostRuntime",System.getProperty("java.runtime.version"));
            RESULT.setProperty("optimizationEnabled",Boolean.toString(enabled));
            RESULT.setProperty("shadowComparison",Boolean.toString(shadow));
            long deadline = System.nanoTime()+600_000_000_000L;
            while (!Files.isRegularFile(home.resolve("state/texture-upload/start.flag")) && System.nanoTime()<deadline) Thread.sleep(100);
            require(Files.isRegularFile(home.resolve("state/texture-upload/start.flag")),"runtime-ready trigger absent");
            Class<?> target = null;
            while (target == null && System.nanoTime()<deadline) {
                for (Class<?> type:instrumentation.getAllLoadedClasses()) if (type.getName().equals("com.live2d.graphics3d.shader.A")) target=type;
                if(target==null)Thread.sleep(100);
            }
            require(target!=null,"native texture factory absent");
            NativeAtlasWorkflow.awaitTaskDocument(target.getClassLoader());
            RESULT.setProperty("agentToDocumentReadyNs",Long.toString(System.nanoTime()-start));
            long cpuEnd=NativeFloatArrayHostAgent.processCpu();
            RESULT.setProperty("processCpuToDocumentReadyNs",Long.toString(cpuStart<0||cpuEnd<0?-1:cpuEnd-cpuStart));
            RESULT.setProperty("observedThreadAllocatedBytes",Long.toString(allocations.stopAndTotal()));
            RESULT.setProperty("allocationMetric","sum-of-observed-thread-allocation-maxima;lower-bound-not-retained-or-peak-memory");
            RESULT.setProperty("heapUsedAtReadyBytes",Long.toString(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
            Object stats=System.getProperties().get(STATS);
            if(enabled) {
                require(stats instanceof Supplier<?>,"requested texture preparation not installed");
                for(var entry:((Map<?,?>)((Supplier<?>)stats).get()).entrySet()) RESULT.setProperty("preparation."+entry.getKey(),entry.getValue().toString());
                require(number(stats,"prepared")>0,"no real texture creation used preparation");
                require(number(stats,"failures")==0,"production texture preparation reported failures");
                capture.detach();
                require(capture.failure.get()==null,"texture observer failed: "+capture.failure.get());
                RESULT.setProperty("observer.callsBeforeAttach",Long.toString(capture.callsBeforeAttach));
                RESULT.setProperty("shadow.comparedTextures",Long.toString(capture.compared.get()));
                RESULT.setProperty("shadow.comparedBytes",Long.toString(capture.bytes.get()));
                RESULT.setProperty("shadow.mismatches",Long.toString(capture.mismatches.get()));
                RESULT.setProperty("shadow.rejectedCalls",Long.toString(capture.rejected.get()));
                RESULT.setProperty("shadow.rejectedMaxPixels",Long.toString(capture.rejectedPixels.get()));
                RESULT.setProperty("shadow.rejectedMaxWidth",Long.toString(capture.rejectedWidth.get()));
                RESULT.setProperty("shadow.rejectedMaxHeight",Long.toString(capture.rejectedHeight.get()));
                if(shadow) {
                    require(capture.callsBeforeAttach==0,"shadow missed initial texture calls");
                    require(capture.compared.get()>0&&capture.mismatches.get()==0,"native JOGL representation differs");
                    require(capture.compared.get()==number(stats,"prepared"),"shadow did not compare every preparation");
                }
            } else {
                require(stats==null&&System.getProperties().get(CALLBACK)==null,"off baseline installed a texture hook");
            }
            Class<?> factory=target;
            NativeAtlasWorkflow.onEdt(()->{
                Object manager=NativeAtlasWorkflow.manager(factory.getClassLoader());
                RESULT.setProperty("modelImageCount",Integer.toString(((List<?>)manager.getClass().getMethod("getAllModelImages").invoke(manager)).size()));
                RESULT.setProperty("atlasCount",Integer.toString(((List<?>)manager.getClass().getMethod("getTextureAtlases").invoke(manager)).size()));
                return null;
            });
            store(home.resolve("state/texture-upload/load.properties"));
            // All contract/disable/restoration checks are outside the load timing window.
            if(enabled) {
                @SuppressWarnings("unchecked") BiFunction<Object,Object,Object> callback=(BiFunction<Object,Object,Object>)System.getProperties().get(CALLBACK);
                Object profile=capture.profile.get();require(profile!=null,"actual texture profile was not observed");
                BufferedImage source=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);
                source.setRGB(0,0,0x80ff8040);
                BufferedImage prepared=(BufferedImage)callback.apply(source,profile);
                require(prepared!=null&&prepared!=source&&prepared.isAlphaPremultiplied(),"fresh prepared image absent");
                require(source.getRGB(0,0)==0x80ff8040,"preparation modified source pixels");
                System.setProperty(ENABLE,"false");require(callback.apply(source,profile)==null,"live disable failed");
                System.setProperty(ENABLE,"true");require(callback.apply(source,profile) instanceof BufferedImage,"live re-enable failed");
                Class<?> agent=null;
                for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("dev.turboism.bootstrap.TurboismAgent"))agent=type;
                require(agent!=null,"production agent absent");
                var field=agent.getDeclaredField("TEXTURE_UPLOAD_PREPARATION");field.setAccessible(true);
                Object installation=((AtomicReference<?>)field.get(null)).get();require(installation!=null,"texture installer absent");
                var close=installation.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(installation);
                var restored=installation.getClass().getDeclaredMethod("restored");restored.setAccessible(true);
                require(Boolean.TRUE.equals(restored.invoke(installation)),"native texture factory bytecode restoration not proven");
                require(System.getProperties().get(CALLBACK)==null&&System.getProperties().get(STATS)==null,"texture callback slots remained");
                require(callback.apply(source,profile)==null,"closed in-flight callback remained active");
                RESULT.setProperty("liveDisableAndRestoration","PASS");
            }
            RESULT.setProperty("fixtureWritten","false");RESULT.setProperty("status","PASS");store(output);
            String fixture=System.getProperty("turboism.validation.fixtureName","");
            SwingUtilities.invokeLater(()->{for(Frame frame:Frame.getFrames())if(frame.isVisible()&&frame.getTitle().contains(fixture)){
                frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));break;
            }});
        } catch(Throwable failure) {
            Throwable root=failure;while(root.getCause()!=null)root=root.getCause();
            RESULT.setProperty("status","FAIL");RESULT.setProperty("failure",root.toString());root.printStackTrace(System.err);
            try{store(output);}catch(Exception ignored){ }
        } finally {
            allocations.stopAndTotal();try{capture.detach();}catch(Exception ignored){ }capture.profile.set(null);
        }
    }

    private static long number(Object stats,String name){return ((Number)((Map<?,?>)((Supplier<?>)stats).get()).get(name)).longValue();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void store(Path path)throws Exception{
        Files.createDirectories(path.getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        try(var out=Files.newOutputStream(temporary)){RESULT.store(out,"Native texture upload validation");}
        Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class Capture {
        final boolean shadow;
        final AtomicBoolean stopped=new AtomicBoolean();
        final AtomicReference<Object> profile=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicLong compared=new AtomicLong(), bytes=new AtomicLong(), mismatches=new AtomicLong();
        final AtomicLong rejected=new AtomicLong(), rejectedPixels=new AtomicLong();
        final AtomicLong rejectedWidth=new AtomicLong(), rejectedHeight=new AtomicLong();
        volatile long callsBeforeAttach=-1;
        volatile Object original;
        volatile BiFunction<Object,Object,Object> wrapper;
        Thread watcher;
        Capture(boolean shadow){this.shadow=shadow;}
        void start(){
            watcher=new Thread(()->{
                try{
                    long deadline=System.nanoTime()+120_000_000_000L;
                    while(!stopped.get()&&System.nanoTime()<deadline){
                        Properties properties=System.getProperties();
                        synchronized(properties){
                            Object found=properties.get(CALLBACK),stats=properties.get(STATS);
                            if(found instanceof BiFunction<?,?,?>&&stats instanceof Supplier<?>){
                                original=found;callsBeforeAttach=number(stats,"calls");
                                @SuppressWarnings("unchecked") BiFunction<Object,Object,Object> delegate=(BiFunction<Object,Object,Object>)found;
                                wrapper=(image,glProfile)->{
                                    try{
                                        Object prepared=delegate.apply(image,glProfile);
                                        if(shadow&&prepared==null&&image instanceof BufferedImage rejectedSource) {
                                            rejected.incrementAndGet();
                                            rejectedPixels.accumulateAndGet((long)rejectedSource.getWidth()*rejectedSource.getHeight(),Math::max);
                                            rejectedWidth.accumulateAndGet(rejectedSource.getWidth(),Math::max);
                                            rejectedHeight.accumulateAndGet(rejectedSource.getHeight(),Math::max);
                                        }
                                        if(prepared instanceof BufferedImage target&&image instanceof BufferedImage source){
                                            profile.compareAndSet(null,glProfile);
                                            if(shadow) compare(source,target,glProfile);
                                        }
                                        return prepared;
                                    }catch(Throwable rejected){failure.compareAndSet(null,rejected);mismatches.incrementAndGet();return null;}
                                };
                                properties.put(CALLBACK,wrapper);return;
                            }
                        }
                        Thread.sleep(1);
                    }
                    if(!stopped.get())throw new IllegalStateException("texture observer attachment timed out");
                }catch(Throwable rejected){failure.set(rejected);}
            },"turboism-texture-upload-observer");watcher.setDaemon(true);watcher.start();
        }
        private void compare(BufferedImage source,BufferedImage prepared,Object profile)throws Exception{
            ClassLoader loader=profile.getClass().getClassLoader();
            Class<?> dataType=Class.forName("com.jogamp.opengl.util.texture.awt.AWTTextureData",false,loader);
            var constructor=dataType.getConstructor(profile.getClass(),int.class,int.class,boolean.class,BufferedImage.class);
            Object expected=constructor.newInstance(profile,0,0,true,source),actual=constructor.newInstance(profile,0,0,true,prepared);
            ByteBuffer a=(ByteBuffer)dataType.getMethod("getBuffer").invoke(expected);
            ByteBuffer b=(ByteBuffer)dataType.getMethod("getBuffer").invoke(actual);
            for(String getter:List.of("getWidth","getHeight","getBorder","getPixelFormat","getPixelType","getInternalFormat","getMipmap","getMustFlipVertically","getColorSpace")){
                Object before=dataType.getMethod(getter).invoke(expected),after=dataType.getMethod(getter).invoke(actual);
                require(Objects.equals(before,after),"JOGL "+getter+" differs: "+before+" / "+after);
            }
            require(((Number)dataType.getMethod("getPixelFormat").invoke(actual)).intValue()==6408,"expected RGBA upload");
            require(((Number)dataType.getMethod("getPixelType").invoke(actual)).intValue()==5121,"expected unsigned-byte upload");
            require(stride(dataType,expected)==stride(dataType,actual),"effective GL unpack row stride differs");
            require(a.asReadOnlyBuffer().equals(b.asReadOnlyBuffer()),"native JOGL upload bytes differ");
            compared.incrementAndGet();bytes.addAndGet(a.remaining());
        }
        private long stride(Class<?> type,Object data)throws Exception{
            int width=((Number)type.getMethod("getWidth").invoke(data)).intValue();
            int row=((Number)type.getMethod("getRowLength").invoke(data)).intValue();
            int alignment=((Number)type.getMethod("getAlignment").invoke(data)).intValue();
            require(alignment==1||alignment==2||alignment==4||alignment==8,"invalid native unpack alignment");
            long bytes=(long)(row==0?width:row)*4;
            return ((bytes+alignment-1)/alignment)*alignment;
        }
        synchronized void detach()throws Exception{
            stopped.set(true);if(watcher!=null){watcher.interrupt();watcher.join(1000);}
            if(wrapper!=null){require(System.getProperties().replace(CALLBACK,wrapper,original),"texture observer ownership changed");wrapper=null;}
        }
    }
}
