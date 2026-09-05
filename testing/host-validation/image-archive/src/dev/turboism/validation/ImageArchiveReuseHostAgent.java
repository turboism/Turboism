package dev.turboism.validation;

import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Test-only auxiliary agent. Exercises the production optimization inside the official Editor. */
public final class ImageArchiveReuseHostAgent {
    private static final String ENABLE = "turboism.optimization.imageArchiveReuse";
    private static final String STATS = "turboism.image-archive-reuse.stats";
    private static final Properties RESULT = new Properties();
    private static Class<?> resourceType;
    private static Class<?> imageType;
    private static Method getImage, archive, getPixels;
    private static Field decodedField, pngField;
    private static Supplier<?> stats;
    private static com.sun.management.ThreadMXBean threadMetrics;

    /** Starts one bounded task-local validator; no Editor main class is launched here. */
    public static void premain(String arguments, Instrumentation instrumentation) {
        Thread thread = new Thread(() -> run(instrumentation), "turboism-image-archive-validation");
        thread.setDaemon(true); thread.start();
    }

    private static void run(Instrumentation instrumentation) {
        Path home = Path.of(System.getProperty("turboism.validation.imageArchive.home"));
        Path trigger = home.resolve("state/image-archive/start.flag");
        Path result = home.resolve("state/image-archive/result.properties");
        try {
            long deadline = System.nanoTime() + 240_000_000_000L;
            while (!Files.isRegularFile(trigger) && System.nanoTime() < deadline) Thread.sleep(200);
            require(Files.isRegularFile(trigger), "validation trigger timed out");
            RESULT.setProperty("hostRuntime",System.getProperty("java.runtime.version"));
            if (java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() && bean.isCurrentThreadCpuTimeSupported()) {
                bean.setThreadAllocatedMemoryEnabled(true);bean.setThreadCpuTimeEnabled(true);threadMetrics=bean;
            }
            RESULT.setProperty("threadAllocationMeasured",Boolean.toString(threadMetrics!=null));
            RESULT.setProperty("fixtureName",System.getProperty("turboism.validation.imageArchive.fixtureName", ""));
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (type.getName().equals("com.live2d.graphics.CImageResource")) resourceType = type;
            }
            require(resourceType != null,"native image resource was not loaded");
            imageType = Class.forName("com.live2d.graphics.CWritableImage",false,resourceType.getClassLoader());
            getImage=resourceType.getMethod("getImage"); archive=resourceType.getMethod("archive");
            getPixels=imageType.getMethod("getIntBuffer"); decodedField=field(resourceType,"image");
            pngField=field(resourceType,"imageFileBuf");
            Object diagnostics=System.getProperties().get(STATS);
            require(diagnostics instanceof Supplier<?>,"production optimization not installed"); stats=(Supplier<?>)diagnostics;
            long beforeReuse=count("reused");
            phase(home,"synthetic-roundtrip-and-timing");
            ownedImageChecks(1024,home);
            phase(home,"current-document-native-images");
            onEdt(() -> liveDocumentChecks(home));
            require(count("reused")>beforeReuse,"no actual native archive selected reuse");
            require(count("failures")==0,"production callback reported failures");
            RESULT.setProperty("nativeReuseSelections",Long.toString(count("reused")-beforeReuse));
            RESULT.setProperty("callbackFailures",Long.toString(count("failures")));
            phase(home,"disable-and-bytecode-restoration");
            Class<?> agent=null;
            for(Class<?> type:instrumentation.getAllLoadedClasses()) if(type.getName().equals("dev.turboism.bootstrap.TurboismAgent")) agent=type;
            require(agent!=null,"production agent missing");
            Object installation=((AtomicReference<?>)field(agent,"IMAGE_ARCHIVE_REUSE").get(null)).get();
            require(installation!=null,"production installation missing");
            Method close=installation.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(installation);
            Method restored=installation.getClass().getDeclaredMethod("restored");restored.setAccessible(true);
            require(Boolean.TRUE.equals(restored.invoke(installation)),"original native bytecode restoration not proven");
            require(System.getProperties().get(STATS)==null,"diagnostic callback was not removed");
            require(System.getProperties().get("turboism.image-archive-reuse.callback")==null,"native callback was not removed");
            RESULT.setProperty("bytecodeRestored","true");
            RESULT.setProperty("fixtureWritten","false");
            RESULT.setProperty("status","PASS");
            store(result,RESULT);
            closeTaskWindow();
        } catch(Throwable failure) {
            Throwable root=failure;
            while(root.getCause()!=null)root=root.getCause();
            RESULT.setProperty("status","FAIL");RESULT.setProperty("failure",root.getClass().getName()+": "+root.getMessage());
            root.printStackTrace(System.err);
            try{store(result,RESULT);}catch(Exception ignored){ /* runner treats a missing result as failure */ }
        }
    }

    private static void ownedImageChecks(int size,Path home) throws Exception {
        BufferedImage original=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        int[] input=((DataBufferInt)original.getRaster().getDataBuffer()).getData();
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
            int a=64+(x+y)%192;
            input[y*size+x]=(a<<24)|(((x*17+y*3)&255)<<16)|(((x+y*11)&255)<<8)|((x*3+y*7)&255);
        }
        Object resource=resourceType.getConstructor(BufferedImage.class,boolean.class).newInstance(original,true);
        resourceType.getMethod("setUnmanagedResource",boolean.class).invoke(resource,true);
        try {
            System.setProperty(ENABLE,"false");archive.invoke(resource);
            int[] expected=pixels(resource).clone();archive.invoke(resource);
            System.setProperty(ENABLE,"true");
            benchmark(resource,"synthetic",12,home);
            require(Arrays.equals(expected,pixels(resource)),"unchanged synthetic image round-trip mismatch");
            archive.invoke(resource);
            Object decoded=getImage.invoke(resource);int[] raw=(int[])getPixels.invoke(decoded);
            int changedIndex=raw.length/2;raw[changedIndex]^=0x00010101;int changed=raw[changedIndex];
            long reused=count("reused"),fallback=count("fallback");
            archive.invoke(resource);
            require(count("reused")==reused,"raw pixel mutation reused stale PNG");
            require(count("fallback")>fallback,"raw pixel mutation did not select native encoding");
            require(pixels(resource)[changedIndex]==changed,"raw pixel mutation was lost during native archive");
            RESULT.setProperty("rawMutationPreserved","true");archive.invoke(resource);
            getImage.invoke(resource);
            resourceType.getMethod("setUpdated").invoke(resource);
            reused=count("reused");archive.invoke(resource);
            require(count("reused")==reused,"setUpdated reused invalidated PNG");
            RESULT.setProperty("normalInvalidationPreserved","true");
            System.setProperty(ENABLE,"false");getImage.invoke(resource);reused=count("reused");archive.invoke(resource);
            require(count("reused")==reused,"live disable did not take effect");
            RESULT.setProperty("liveDisablePreserved","true");
        } finally {
            System.setProperty(ENABLE,"true");
            Method dispose=resourceType.getDeclaredMethod("dispose");dispose.setAccessible(true);dispose.invoke(resource);
            original.flush();
        }
    }

    private static void liveDocumentChecks(Path home) throws Exception {
        ClassLoader loader=resourceType.getClassLoader();
        Class<?> appClass=Class.forName("com.live2d.cubism.CEAppCtrl",false,loader);
        Object app=appClass.getMethod("access$get_instance$cp").invoke(null);
        Object doc=appClass.getMethod("getCurrentDoc").invoke(app);
        require(doc!=null && doc.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingDocument"),"active fixture is not a modeling document");
        Object source=doc.getClass().getMethod("getModelSource").invoke(doc);
        Object manager=source.getClass().getMethod("getTextureManager").invoke(source);
        List<?> images=(List<?>)manager.getClass().getMethod("getAllModelImages").invoke(manager);
        RESULT.setProperty("modelImageCount",Integer.toString(images.size()));
        int checked=0;
        for(Object modelImage:images) {
            Object resource=modelImage.getClass().getMethod("getFilteredImage").invoke(modelImage);
            int width=(int)resourceType.getMethod("getWidth").invoke(resource),height=(int)resourceType.getMethod("getHeight").invoke(resource);
            if(width<128||height<128||(long)width*height>4_194_304L)continue;
            System.setProperty(ENABLE,"false");archive.invoke(resource);
            int[] expected=pixels(resource).clone();archive.invoke(resource);
            long reused=count("reused");
            benchmark(resource,"live"+checked,8,home);
            require(Arrays.equals(expected,pixels(resource)),"live model image round-trip mismatch");
            require(count("reused")>reused,"live model image did not exercise PNG reuse");
            RESULT.setProperty("live"+checked+".width",Integer.toString(width));
            RESULT.setProperty("live"+checked+".height",Integer.toString(height));
            checked++;if(checked==2)break;
        }
        require(checked>0,"fixture has no eligible native images for live validation");
        RESULT.setProperty("liveImagesValidated",Integer.toString(checked));
        RESULT.setProperty("liveImagePixelsPreserved","true");
    }

    private static void benchmark(Object resource,String prefix,int pairs,Path home) throws Exception {
        for(int i=0;i<4;i++) {System.setProperty(ENABLE,Boolean.toString((i&1)!=0));getImage.invoke(resource);archive.invoke(resource);}
        List<Long> baseline=new ArrayList<>(),optimized=new ArrayList<>();
        List<Long> baselineCpu=new ArrayList<>(),optimizedCpu=new ArrayList<>();
        List<Long> baselineBytes=new ArrayList<>(),optimizedBytes=new ArrayList<>();
        for(int i=0;i<pairs;i++) for(int phase=0;phase<2;phase++) {
            boolean enabled=((i+phase)&1)!=0;System.setProperty(ENABLE,Boolean.toString(enabled));
            long allocatedBefore=threadMetrics==null?0:threadMetrics.getThreadAllocatedBytes(Thread.currentThread().getId());
            long cpuBefore=threadMetrics==null?0:threadMetrics.getCurrentThreadCpuTime();
            long start=System.nanoTime();getImage.invoke(resource);archive.invoke(resource);long elapsed=System.nanoTime()-start;
            long cpu=threadMetrics==null?0:threadMetrics.getCurrentThreadCpuTime()-cpuBefore;
            long allocated=threadMetrics==null?0:threadMetrics.getThreadAllocatedBytes(Thread.currentThread().getId())-allocatedBefore;
            (enabled?optimizedCpu:baselineCpu).add(cpu);(enabled?optimizedBytes:baselineBytes).add(allocated);
            require(decodedField.get(resource)==null,"native archive no longer releases decoded image");
            require(pngField.get(resource) instanceof byte[],"native archive did not preserve encoded representation");
            (enabled?optimized:baseline).add(elapsed);
        }
        baseline.sort(Long::compare);optimized.sort(Long::compare);
        baselineCpu.sort(Long::compare);optimizedCpu.sort(Long::compare);
        baselineBytes.sort(Long::compare);optimizedBytes.sort(Long::compare);
        if(threadMetrics!=null) {
            RESULT.setProperty(prefix+".baselineCpuMedianNs",Long.toString(baselineCpu.get(pairs/2)));
            RESULT.setProperty(prefix+".optimizedCpuMedianNs",Long.toString(optimizedCpu.get(pairs/2)));
            RESULT.setProperty(prefix+".baselineAllocatedMedianBytes",Long.toString(baselineBytes.get(pairs/2)));
            RESULT.setProperty(prefix+".optimizedAllocatedMedianBytes",Long.toString(optimizedBytes.get(pairs/2)));
        }
        RESULT.setProperty(prefix+".pairs",Integer.toString(pairs));
        RESULT.setProperty(prefix+".baselineMedianNs",Long.toString(baseline.get(pairs/2)));
        RESULT.setProperty(prefix+".optimizedMedianNs",Long.toString(optimized.get(pairs/2)));
        RESULT.setProperty(prefix+".baselineP95Ns",Long.toString(baseline.get(Math.min(pairs-1,(int)Math.ceil(.95*pairs)-1))));
        RESULT.setProperty(prefix+".optimizedP95Ns",Long.toString(optimized.get(Math.min(pairs-1,(int)Math.ceil(.95*pairs)-1))));
        System.setProperty(ENABLE,"true");phase(home,prefix+"-complete");
    }

    private static int[] pixels(Object resource) throws Exception {return (int[])getPixels.invoke(getImage.invoke(resource));}
    private static long count(String name) {return ((Number)((Map<?,?>)stats.get()).get(name)).longValue();}
    private static Field field(Class<?> owner,String name) throws Exception {Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void phase(Path home,String phase) throws Exception {RESULT.setProperty("phase",phase);store(home.resolve("state/image-archive/progress.properties"),RESULT);}
    private static void store(Path path,Properties properties) throws Exception {
        Files.createDirectories(path.getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        try(OutputStream out=Files.newOutputStream(temporary)){properties.store(out,"Native image archive validation");}
        Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING);
    }
    private static void onEdt(Checked action) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{action.run();}catch(Throwable thrown){failure.set(thrown);}});
        if(failure.get()!=null)throw new Exception("EDT validation failed",failure.get());
    }
    private static void closeTaskWindow() {
        String fixture=System.getProperty("turboism.validation.imageArchive.fixtureName","");
        if(fixture.isBlank())return;
        SwingUtilities.invokeLater(()->{for(Frame frame:Frame.getFrames()) if(frame.isVisible()&&frame.getTitle().contains(fixture)) {
            frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));break;
        }});
    }
    @FunctionalInterface private interface Checked {void run() throws Exception;}
}
