package dev.turboism.validation;

import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Test-only actual-call differential checks. Retains no model/form/vector across callback invocations. */
public final class NativeWarpPositionHostAgent {
    private static final String ENABLE="turboism.optimization.warpPositionProjection";
    private static final String CALLBACK="turboism.warp-position-projection.callback";
    private static final String STATS="turboism.warp-position-projection.stats";
    private static final String TARGET="com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension";
    private static final Properties RESULT=new Properties();

    /** Runs inside the official Editor only; this is not an application launcher. */
    public static void premain(String args,Instrumentation instrumentation) {
        long start=System.nanoTime(), cpu=NativeFloatArrayHostAgent.processCpu();
        var allocations=new NativeFloatArrayHostAgent.Allocations();
        Path home=Path.of(System.getProperty("turboism.validation.warpProjection.home"));
        NativeMemoryObservation.startLoading(home);
        boolean enabled=Boolean.getBoolean(ENABLE), shadow=Boolean.getBoolean("turboism.validation.warpProjection.shadow");
        Capture capture=new Capture(shadow);if(enabled&&shadow)capture.start();
        Thread worker=new Thread(()->run(home,instrumentation,start,cpu,allocations,capture,enabled,shadow),"turboism-warp-projection-validation");
        worker.setDaemon(true);worker.start();
    }

    private static void run(Path home,Instrumentation instrumentation,long start,long cpu,
                            NativeFloatArrayHostAgent.Allocations allocations,Capture capture,boolean enabled,boolean shadow) {
        Path output=home.resolve("state/warp-projection/result.properties");
        try {
            RESULT.setProperty("schemaVersion","1");RESULT.setProperty("runId",System.getProperty("turboism.validation.runId",""));
            RESULT.setProperty("fixtureName",System.getProperty("turboism.validation.fixtureName",""));
            RESULT.setProperty("hostRuntime",System.getProperty("java.runtime.version"));
            RESULT.setProperty("optimizationEnabled",Boolean.toString(enabled));RESULT.setProperty("shadowComparison",Boolean.toString(shadow));
            long deadline=System.nanoTime()+600_000_000_000L;
            while(!Files.isRegularFile(home.resolve("state/warp-projection/start.flag"))&&System.nanoTime()<deadline)Thread.sleep(100);
            require(Files.isRegularFile(home.resolve("state/warp-projection/start.flag")),"runtime-ready trigger absent");
            Class<?> target=null;
            while(target==null&&System.nanoTime()<deadline) {
                for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals(TARGET))target=type;
                if(target==null)Thread.sleep(100);
            }
            require(target!=null,"native interpolation consumer absent");
            NativeAtlasWorkflow.awaitTaskDocument(target.getClassLoader());
            RESULT.setProperty("agentToDocumentReadyNs",Long.toString(System.nanoTime()-start));
            long cpuEnd=NativeFloatArrayHostAgent.processCpu();
            RESULT.setProperty("processCpuToDocumentReadyNs",Long.toString(cpu<0||cpuEnd<0?-1:cpuEnd-cpu));
            RESULT.setProperty("observedThreadAllocatedBytes",Long.toString(allocations.stopAndTotal()));
            RESULT.setProperty("allocationMetric","observed-thread-maxima-lower-bound-not-RSS-or-peak");
            Object stats=System.getProperties().get(STATS);
            if(enabled) {
                require(stats instanceof Supplier<?>,"projection not installed");
                capture.detach();
                require(capture.failure.get()==null,"projection observer failed: "+capture.failure.get());
                var snapshot=(Map<?,?>)((Supplier<?>)stats).get();
                for(var entry:snapshot.entrySet())RESULT.setProperty("projection."+entry.getKey(),entry.getValue().toString());
                require(number(stats,"projected")>0,"no real projection consumer");
                require(number(stats,"failures")==0,"production projection failed");
                RESULT.setProperty("shadow.callsBeforeAttach",Long.toString(capture.before));
                RESULT.setProperty("shadow.comparedForms",Long.toString(capture.forms.get()));
                RESULT.setProperty("shadow.comparedPoints",Long.toString(capture.points.get()));
                RESULT.setProperty("shadow.mismatches",Long.toString(capture.mismatches.get()));
                if(shadow) {
                    require(capture.before==0,"shadow missed initial calls");
                    require(capture.forms.get()==number(stats,"projected"),"shadow missed projected forms");
                    require(capture.points.get()==number(stats,"points")&&capture.mismatches.get()==0,"native projection bits differ");
                }
            } else require(stats==null&&System.getProperties().get(CALLBACK)==null,"off installed a projection hook");
            Class<?> host=target;
            NativeAtlasWorkflow.onEdt(()->{
                Object manager=NativeAtlasWorkflow.manager(host.getClassLoader());
                RESULT.setProperty("modelImageCount",Integer.toString(((List<?>)manager.getClass().getMethod("getAllModelImages").invoke(manager)).size()));
                RESULT.setProperty("atlasCount",Integer.toString(((List<?>)manager.getClass().getMethod("getTextureAtlases").invoke(manager)).size()));
                return null;
            });
            store(home.resolve("state/warp-projection/load.properties"));
            NativeMemoryObservation.observe(home);
            if(enabled) {
                @SuppressWarnings("unchecked") Function<Object,Object> callback=(Function<Object,Object>)System.getProperties().get(CALLBACK);
                long before=number(stats,"calls");System.setProperty(ENABLE,"false");
                require(callback.apply(null)==null&&number(stats,"calls")==before,"live disable failed");
                System.setProperty(ENABLE,"true");callback.apply(null);
                require(number(stats,"calls")==before+1,"live re-enable failed");
                Class<?> agent=null;for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("dev.turboism.bootstrap.TurboismAgent"))agent=type;
                require(agent!=null,"product agent absent");
                var field=agent.getDeclaredField("WARP_POSITION_PROJECTION");field.setAccessible(true);
                Object installer=((AtomicReference<?>)field.get(null)).get();require(installer!=null,"projection installer absent");
                var close=installer.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(installer);
                var restored=installer.getClass().getDeclaredMethod("restored");restored.setAccessible(true);
                require(Boolean.TRUE.equals(restored.invoke(installer)),"native consumer restoration not proven");
                require(System.getProperties().get(CALLBACK)==null&&System.getProperties().get(STATS)==null,"projection slots retained");
                long closed=number(stats,"calls");require(callback.apply(null)==null&&number(stats,"calls")==closed,"closed callback active");
                RESULT.setProperty("liveDisableAndRestoration","PASS");
            }
            RESULT.setProperty("fixtureWritten","false");RESULT.setProperty("status","PASS");store(output);
            String fixture=System.getProperty("turboism.validation.fixtureName","");
            SwingUtilities.invokeLater(()->{for(Frame frame:Frame.getFrames())if(frame.isVisible()&&frame.getTitle().contains(fixture)) {
                frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));break;
            }});
        } catch(Throwable failure) {
            Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
            RESULT.setProperty("status","FAIL");RESULT.setProperty("failure",cause.toString());cause.printStackTrace(System.err);
            try{store(output);}catch(Exception ignored){ }
        } finally {allocations.stopAndTotal();try{capture.detach();}catch(Exception ignored){ }}
    }

    private static long number(Object stats,String key){return ((Number)((Map<?,?>)((Supplier<?>)stats).get()).get(key)).longValue();}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void store(Path path)throws Exception {
        Files.createDirectories(path.getParent());Path temp=path.resolveSibling(path.getFileName()+".tmp");
        try(var out=Files.newOutputStream(temp)){RESULT.store(out,"Native warp position projection validation");}
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class Capture {
        final boolean shadow;
        final AtomicBoolean stopped=new AtomicBoolean();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicLong forms=new AtomicLong(),points=new AtomicLong(),mismatches=new AtomicLong();
        volatile long before=-1;
        volatile Object original;
        volatile Function<Object,Object> wrapper;
        Thread watcher;
        Capture(boolean shadow){this.shadow=shadow;}
        void start() {
            watcher=new Thread(()->{
                try {
                    long deadline=System.nanoTime()+180_000_000_000L;
                    while(!stopped.get()&&System.nanoTime()<deadline) {
                        Properties properties=System.getProperties();
                        synchronized(properties) {
                            Object found=properties.get(CALLBACK),stats=properties.get(STATS);
                            if(found instanceof Function<?,?>&&stats instanceof Supplier<?>) {
                                original=found;before=number(stats,"calls");
                                @SuppressWarnings("unchecked") Function<Object,Object> delegate=(Function<Object,Object>)found;
                                wrapper=form->{
                                    synchronized(this) {
                                        Object result=delegate.apply(form);
                                        if(!stopped.get()&&shadow&&result instanceof List<?> actual) {
                                            try{compare(form,actual);}catch(Throwable rejected){failure.compareAndSet(null,rejected);mismatches.incrementAndGet();}
                                        }
                                        return result;
                                    }
                                };
                                properties.put(CALLBACK,wrapper);return;
                            }
                        }
                        Thread.sleep(1);
                    }
                    if(!stopped.get())throw new IllegalStateException("projection observer attachment timed out");
                } catch(Throwable rejected){failure.set(rejected);}
            },"turboism-warp-projection-observer");watcher.setDaemon(true);watcher.start();
        }
        private void compare(Object form,List<?> actual)throws Exception {
            List<?> refs=(List<?>)form.getClass().getMethod("getAllPointRef").invoke(form);
            require(refs.size()==actual.size(),"native point count differs");
            java.lang.reflect.Method getPos=null,getX=null,getY=null;
            for(int i=0;i<refs.size();i++) {
                Object ref=refs.get(i),value=actual.get(i);
                if(getPos==null)getPos=ref.getClass().getMethod("getPos");
                Object expected=getPos.invoke(ref);require(expected!=value,"projection reused native reference output");
                require(expected.getClass()==value.getClass(),"native vector class differs");
                if(getX==null){getX=expected.getClass().getMethod("getX");getY=expected.getClass().getMethod("getY");}
                require(Float.floatToRawIntBits((Float)getX.invoke(expected))==Float.floatToRawIntBits((Float)getX.invoke(value))
                    &&Float.floatToRawIntBits((Float)getY.invoke(expected))==Float.floatToRawIntBits((Float)getY.invoke(value)),"native point bits differ");
            }
            forms.incrementAndGet();points.addAndGet(actual.size());
        }
        synchronized void detach()throws Exception {
            stopped.set(true);if(watcher!=null){watcher.interrupt();watcher.join(1000);}
            if(wrapper!=null){require(System.getProperties().replace(CALLBACK,wrapper,original),"projection observer ownership changed");wrapper=null;}
        }
    }
}
