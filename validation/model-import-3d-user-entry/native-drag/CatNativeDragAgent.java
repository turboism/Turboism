package dev.turboism.validation.modelimport3d.nativeprobe;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/** Task-local diagnostic carrier. No Robot, model writes, or installed-file edits.
 * Uses official camera mapping and the view input wrapper for task-local AWT events.
 * The SDK driver owns selection, model-change assertions and Undo restoration. */
public final class CatNativeDragAgent {
    static final boolean SHADER_PRODUCT = Boolean.getBoolean("turboism.validation.mi3d.shaderUniformProduct");
    static final String HOST_VERSION = System.getProperty("turboism.validation.hostVersion","5302");
    static final String HOST_SHA = "5303".equals(HOST_VERSION)
        ? "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166"
        : "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    static final String RUNTIME_SHA = SHADER_PRODUCT
        ? "07361ac42b9057f8b98f20a390d548a9d2c2ddc4473a12bb7af0f6cfa69dfc76"
        : Boolean.getBoolean("turboism.validation.mi3d.editableDrag")
        ? "f13e67d56dffc59ffabbe8c6fffe70ace98fb91b7299b0edd332209f532645a9"
        : "cat-cost-drag".equals(System.getProperty("turboism.validation.mi3d.mode"))
        ? "7a59b95c14fd8b4a4bfaabd387b4d11fbf3d30f5d865d6099f24b7a590d9f192"
        : "1e9f9dc49f3db472dc3068bc08e3993cd7a10583a9d9bafae5e85f47073cf5aa";
    static final String BRIDGE = "dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge";
    static final String SHADER_BRIDGE = "dev.turboism.adapter.cubism.shader.NativeShaderUniformBridge";
    static final String BRIDGE_CLASS_SHA = SHADER_PRODUCT
        ? "c674ae4e26836e699a4b5d63b393e8b06756df8493ae78401a4ef2fda5a3016b"
        : Boolean.getBoolean("turboism.validation.mi3d.editableDrag")
        ? "e0f69025860934bbe3194252d488ea69b4170ee2bddd9de46a43e8baab92773f"
        : "55229bb8e445134313fbe953af099952a5a762f74cdec8b0a009fdf1a32631c9";
    static final String SHADER_BRIDGE_CLASS_SHA = "7cd54404e066428ca2fc9e563bc8621075589ca3aa42407cad818f4e6c3c5438";
    private CatNativeDragAgent() { }

    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        Path jar = Path.of(CatNativeDragAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!jar.getParent().getFileName().toString().equals("agents")) throw new IllegalStateException("task agents directory required");
        Path state = jar.getParent().getParent().resolve("turboism-home/state/dev.turboism.validation.model-import-3d-user-entry");
        if(SHADER_PRODUCT) {
            if(!Boolean.getBoolean("turboism.validation.mi3d.dragTrace") || !Boolean.getBoolean("turboism.validation.mi3d.editableDrag") || !"cat-cost-drag".equals(System.getProperty("turboism.validation.mi3d.mode")))throw new IllegalStateException("production shader validation only");
        }
        if(Boolean.getBoolean("turboism.validation.mi3d.shaderUniformCandidate")) {
            if(!Boolean.getBoolean("turboism.validation.mi3d.dragTrace") || !Boolean.getBoolean("turboism.validation.mi3d.editableDrag") || !"cat-cost-drag".equals(System.getProperty("turboism.validation.mi3d.mode")))throw new IllegalStateException("isolated shader candidate only");
            instrumentation.addTransformer(new UniformCandidate.Transformer(),false);
        }
        Thread worker = new Thread(() -> {
            try {
                for (int sequence=0; sequence<3; sequence++) {
                    Path request = state.resolve("drag-request-"+sequence+".properties");
                    long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(15);
                    while (!Files.isRegularFile(request) && System.nanoTime()<deadline) Thread.sleep(100);
                    if (!Files.isRegularFile(request)) return;
                    Properties input = new Properties();
                    try (var stream=Files.newInputStream(request)) { input.load(stream); }
                    Properties output = new Properties();
                    output.setProperty("schemaVersion",Boolean.getBoolean("turboism.validation.mi3d.dragTrace")?"mi3d-cat-native-drag-v4":"mi3d-cat-native-drag-v3");
                    if(Boolean.getBoolean("turboism.validation.mi3d.dragTrace"))input.setProperty("traceOutput",state.resolve("drag-trace-"+sequence+".txt").toString());
                    output.setProperty("eventPath","OFFICIAL_VIEW_STATEFUL_DRAG_WRAPPER_FROM_TASK_COMPONENT_AWT_MOUSE_EVENT_NOT_OS_INPUT");
                    try { execute(input, output); output.setProperty("status","PASS"); }
                    catch (Throwable failure) { output.setProperty("status","FAIL"); output.setProperty("error",failure.toString()); failure.printStackTrace(); }
                    Path temporary=state.resolve("drag-response-"+sequence+".tmp");
                    try (var stream=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW)) { output.store(stream,"native drag diagnostic"); }
                    Files.move(temporary,state.resolve("drag-response-"+sequence+".properties"));
                    if (!output.getProperty("status").equals("PASS")) return;
                }
            } catch (Throwable failure) { failure.printStackTrace(); }
        },"mi3d-task-native-drag");
        worker.setDaemon(true); worker.start();
        System.err.println("CAT_NATIVE_DRAG_READY");
    }

    static String sha(Path path) throws Exception {
        try (var input=Files.newInputStream(path)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
    static Class<?> pinned(String name, String digest) throws Exception {
        Class<?> type=Class.forName(name,false,ClassLoader.getSystemClassLoader());
        var origin=type.getProtectionDomain().getCodeSource();
        Path artifact;
        if (origin==null) {
            if ((!name.equals(BRIDGE) && !name.equals(SHADER_BRIDGE)) || !digest.equals(RUNTIME_SHA) || type.getClassLoader()!=null)
                throw new IllegalStateException("unreviewed bootstrap class "+name);
            var resource=type.getResource("/"+name.replace('.','/')+".class");
            if (resource==null || !resource.getProtocol().equals("jar"))
                throw new IllegalStateException("bootstrap class resource unavailable");
            var connection=(java.net.JarURLConnection)resource.openConnection();connection.setUseCaches(false);
            artifact=Path.of(connection.getJarFileURL().toURI());
            String expected=name.equals(SHADER_BRIDGE)?SHADER_BRIDGE_CLASS_SHA:BRIDGE_CLASS_SHA;
            try(var input=connection.getInputStream()) {
                String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                if (!actual.equals(expected)) throw new IllegalStateException("unreviewed bootstrap class bytes");
            }
        } else artifact=Path.of(origin.getLocation().toURI());
        if (!sha(artifact).equals(digest))
            throw new IllegalStateException("unreviewed class origin "+name);
        return type;
    }
    static Object call(Object receiver,String method) throws Exception { return receiver.getClass().getMethod(method).invoke(receiver); }
    static String sourceGuid(Object mesh) throws Exception {
        return call(call(mesh,"getGuid"),"getUuidString").toString();
    }
    static <T>T edt(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    static Component canvas() {
        List<Component> found=new ArrayList<>();
        for (Window window:Window.getWindows()) if (window.isShowing()) walk(window,found);
        if (found.size()!=1) throw new IllegalStateException("unique task GLJPanel required: "+found.size());
        return found.get(0);
    }
    static void walk(Component component,List<Component> found) {
        if (component.isShowing() && component.getClass().getName().equals("com.jogamp.opengl.awt.GLJPanel")) found.add(component);
        if (component instanceof Container parent) for (Component child:parent.getComponents()) walk(child,found);
    }
    record Target(Object app,Object view,Object source,Object camera,Component canvas,int x,int y,
                  java.lang.reflect.Constructor<?> mouseWrapper,java.lang.reflect.Constructor<?> dragWrapper,
                  java.lang.reflect.Method dragUpdate,java.lang.reflect.Method dragRelease,
                  java.lang.reflect.Method inputHandler,Object[] gesture) { }
    static void requireSourceDomain(int count) {
        boolean editable=Boolean.getBoolean("turboism.validation.mi3d.editableDrag");
        if(editable && !"cat-cost-drag".equals(System.getProperty("turboism.validation.mi3d.mode")))
            throw new IllegalStateException("editable Cat drag requires cost profile");
        if(count!=(editable?615:512))throw new IllegalStateException("selected Cat source domain mismatch");
    }
    static Target target(Properties input,Properties output) throws Exception {
        Class<?> controller=pinned("com.live2d.cubism.CEAppCtrl",HOST_SHA);
        Object app=controller.getMethod("access$get_instance$cp").invoke(null);
        Object view=call(app,"getCurrentViewContext");
        if (!view.getClass().getName().equals("com.live2d.cubism.view.context.CEViewContext_ModelingView")) throw new IllegalStateException("modeling view required");
        Object source=call(call(view,"getDoc"),"getModelSource");
        List<?> meshes=(List<?>)call(source,"getAllArtMeshes");
        requireSourceDomain(meshes.size());
        Object selected=meshes.get(0);
        if ("cat-cost-drag".equals(System.getProperty("turboism.validation.mi3d.mode"))) {
            List<Object> matches=new ArrayList<>();
            for(Object mesh:meshes) if(sourceGuid(mesh).equals(input.getProperty("sourceGuid"))) matches.add(mesh);
            if(matches.size()!=1) throw new IllegalStateException("unique selected Cat source required");
            selected=matches.get(0);
        }
        output.setProperty("nativeSourceGuid",sourceGuid(selected));
        if (!output.getProperty("nativeSourceGuid").equals(input.getProperty("sourceGuid")))
            throw new IllegalStateException("selected Cat source identity mismatch");
        Object camera=call(view,"getCameraManager");
        Class<?> vector=pinned("com.live2d.graphics3d.type.GVector2",HOST_SHA);
        float dx=Float.parseFloat(input.getProperty("documentX")), dy=Float.parseFloat(input.getProperty("documentY"));
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) throw new IllegalStateException("finite document point required");
        Object point=vector.getConstructor(float.class,float.class).newInstance(dx,dy);
        Object mapped=camera.getClass().getMethod("documentToComponent",vector).invoke(camera,point);
        float mx=((Number)call(mapped,"getX")).floatValue(), my=((Number)call(mapped,"getY")).floatValue();
        Component canvas=canvas();
        if (!Float.isFinite(mx) || !Float.isFinite(my) || mx<24 || my<24 || mx>canvas.getWidth()-24 || my>canvas.getHeight()-24)
            throw new IllegalStateException("mapped drag point outside bounded task canvas: "+mx+","+my);
        int x=Math.round(mx), y=Math.round(my);
        Class<?> nativePoint=pinned("com.live2d.type.CPoint",HOST_SHA);
        Object back=camera.getClass().getMethod("componentToDocument",nativePoint).invoke(camera,nativePoint.getConstructor(int.class,int.class).newInstance(x,y));
        output.setProperty("documentX",Float.toString(dx));output.setProperty("documentY",Float.toString(dy));
        output.setProperty("componentX",Float.toString(mx));output.setProperty("componentY",Float.toString(my));
        output.setProperty("roundedDocumentX",call(back,"getX").toString());output.setProperty("roundedDocumentY",call(back,"getY").toString());
        Object end=camera.getClass().getMethod("componentToDocument",nativePoint).invoke(camera,nativePoint.getConstructor(int.class,int.class).newInstance(x+20,y));
        output.setProperty("expectedDeltaCanvasX",Float.toString(((Number)call(end,"getX")).floatValue()-((Number)call(back,"getX")).floatValue()));
        output.setProperty("expectedDeltaCanvasY",Float.toString(((Number)call(end,"getY")).floatValue()-((Number)call(back,"getY")).floatValue()));
        output.setProperty("canvasWidth",Integer.toString(canvas.getWidth()));output.setProperty("canvasHeight",Integer.toString(canvas.getHeight()));
        output.setProperty("documentUid",call(call(view,"getDoc"),"getDocumentUID").toString());
        output.setProperty("viewClass",view.getClass().getName());
        Class<?> wrapper=pinned("com.live2d.ui.event.m",HOST_SHA);
        Class<?> drag=pinned("com.live2d.ui.event.l",HOST_SHA);
        Class<?> inputType=pinned("com.live2d.ui.event.h",HOST_SHA);
        return new Target(app,view,source,camera,canvas,x,y,wrapper.getConstructor(MouseEvent.class),
            drag.getConstructor(MouseEvent.class),drag.getMethod("a",MouseEvent.class),drag.getMethod("b",MouseEvent.class),
            view.getClass().getMethod("onInputEvent",inputType),new Object[1]);
    }
    static void requireCurrent(Target target) throws Exception {
        if (call(target.app,"getCurrentViewContext")!=target.view || call(call(target.view,"getDoc"),"getModelSource")!=target.source)
            throw new IllegalStateException("native view changed");
    }
    static long counter(Class<?> bridge,String method) throws Exception { return ((Number)bridge.getMethod(method).invoke(null)).longValue(); }
    interface ShaderCtl {
        boolean patched() throws Exception;
        boolean enabled() throws Exception;
        void enabled(boolean value) throws Exception;
        long hits() throws Exception; long queries() throws Exception; long invalidations() throws Exception;
    }
    static ShaderCtl shaderControl() throws Exception {
        if (SHADER_PRODUCT) {
            Class<?> bridge=pinned(SHADER_BRIDGE,RUNTIME_SHA);
            var patched=bridge.getMethod("patched");var getEnabled=bridge.getMethod("enabled");
            var setEnabled=bridge.getMethod("enabled",boolean.class);var hits=bridge.getMethod("hits");
            var queries=bridge.getMethod("queries");var invalidations=bridge.getMethod("invalidations");
            return new ShaderCtl() {
                public boolean patched() throws Exception {return (boolean)patched.invoke(null);}
                public boolean enabled() throws Exception {return (boolean)getEnabled.invoke(null);}
                public void enabled(boolean value) throws Exception {setEnabled.invoke(null,value);}
                public long hits() throws Exception {return ((Number)hits.invoke(null)).longValue();}
                public long queries() throws Exception {return ((Number)queries.invoke(null)).longValue();}
                public long invalidations() throws Exception {return ((Number)invalidations.invoke(null)).longValue();}
            };
        }
        if (Boolean.getBoolean("turboism.validation.mi3d.shaderUniformCandidate")) return new ShaderCtl() {
            public boolean patched() {return UniformCandidate.patched;}
            public boolean enabled() {return UniformCandidate.enabled;}
            public void enabled(boolean value) {UniformCandidate.enabled=value;}
            public long hits() {return UniformCandidate.hits.get();}
            public long queries() {return UniformCandidate.queries.get();}
            public long invalidations() {return UniformCandidate.invalidations.get();}
        };
        return null;
    }
    static void event(Target target,int id,int x,int y,int button,int modifiers) throws Exception {
        requireCurrent(target);
        MouseEvent event=new MouseEvent(target.canvas,id,System.currentTimeMillis(),modifiers,x,y,1,false,button);
        Object nativeEvent;
        if(id==MouseEvent.MOUSE_PRESSED) {
            if(target.gesture[0]!=null)throw new IllegalStateException("gesture already pressed");
            nativeEvent=target.dragWrapper.newInstance(event);target.gesture[0]=nativeEvent;
        } else if(id==MouseEvent.MOUSE_DRAGGED || id==MouseEvent.MOUSE_RELEASED) {
            nativeEvent=target.gesture[0];
            if(nativeEvent==null)throw new IllegalStateException("drag without press");
            (id==MouseEvent.MOUSE_DRAGGED?target.dragUpdate:target.dragRelease).invoke(nativeEvent,event);
        } else nativeEvent=target.mouseWrapper.newInstance(event);
        target.inputHandler.invoke(target.view,nativeEvent);
        Object consumed=call(target.view,"getLastMouseEvent");
        if (consumed!=nativeEvent) throw new IllegalStateException("official view did not consume task mouse event");
        if(id==MouseEvent.MOUSE_RELEASED)target.gesture[0]=null;
    }
    static long[] measureOnEdt(java.util.concurrent.Callable<Void> action,com.sun.management.ThreadMXBean threads) throws Exception {
        long enqueue=System.nanoTime();
        long[] result=edt(()->{
            long entry=System.nanoTime(),a=threads.getThreadAllocatedBytes(Thread.currentThread().getId());
            action.call();
            return new long[]{threads.getThreadAllocatedBytes(Thread.currentThread().getId())-a,System.nanoTime()-entry,entry-enqueue,0};
        });
        result[3]=System.nanoTime()-enqueue;return result;
    }
    static long[] measuredEvent(Target target,int id,int x,int y,int button,int modifiers,com.sun.management.ThreadMXBean threads) throws Exception {
        return measureOnEdt(()->{event(target,id,x,y,button,modifiers);return null;},threads);
    }
    static long gcTime() {return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    static void timing(Properties output,String prefix,long[] values,boolean trace) {
        output.setProperty(prefix+".allocatedBytes",Long.toString(values[0]));output.setProperty(prefix+".ns",Long.toString(values[1]));
        if(trace){output.setProperty(prefix+".queueWaitNs",Long.toString(values[2]));output.setProperty(prefix+".callerNs",Long.toString(values[3]));}
    }
    static void execute(Properties input,Properties output) throws Exception {
        String action=input.getProperty("action");
        if (!Set.of("map","baseline","candidate").contains(action)) throw new IllegalStateException("unsupported action");
        output.setProperty("action",action);
        boolean trace=Boolean.getBoolean("turboism.validation.mi3d.dragTrace");
        if(trace && (!Boolean.getBoolean("turboism.validation.mi3d.editableDrag") || !"cat-cost-drag".equals(System.getProperty("turboism.validation.mi3d.mode")) || !Set.of("map","candidate").contains(action)))
            throw new IllegalStateException("trace requires isolated editable candidate only");
        int steps=Integer.parseInt(input.getProperty("steps","20"));
        if(steps!=3 && steps!=20)throw new IllegalStateException("reviewed drag step count required");
        Target target=edt(()->target(input,output));
        ShaderCtl shader=shaderControl();
        if(shader!=null && !shader.patched())throw new IllegalStateException("exact shader transform absent");
        if (action.equals("map")) {if(shader!=null)captureUniformPair(target,input,output,"neutral",shader);return;}
        boolean priorUniform=shader!=null && shader.enabled();
        long uniformHits=shader!=null?shader.hits():0,uniformQueries=shader!=null?shader.queries():0,uniformInvalidations=shader!=null?shader.invalidations():0;
        edt(()->{event(target,MouseEvent.MOUSE_ENTERED,target.x,target.y,MouseEvent.NOBUTTON,0);
            event(target,MouseEvent.MOUSE_MOVED,target.x,target.y,MouseEvent.NOBUTTON,0);return null;});
        Thread.sleep(100);
        Class<?> bridge=pinned(BRIDGE,RUNTIME_SHA);
        String previousCoefficients=System.getProperty("turboism.meshInterpolation.coefficients");
        System.setProperty("turboism.meshInterpolation.coefficients",Boolean.toString(action.equals("candidate")));
        output.setProperty("ellipseCoefficientCandidate",Boolean.toString(action.equals("candidate")));
        bridge.getMethod("enabled",boolean.class).invoke(null,action.equals("candidate"));
        try {
        if(shader!=null)shader.enabled(true);
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation measurement unavailable");
        boolean allocationEnabled=threads.isThreadAllocatedMemoryEnabled();
        boolean cpuEnabled=threads.isThreadCpuTimeEnabled();
        if(trace && threads.isThreadCpuTimeSupported() && !cpuEnabled)threads.setThreadCpuTimeEnabled(true);
        threads.setThreadAllocatedMemoryEnabled(true);
        long accepted=counter(bridge,"acceptedCalls"), refused=counter(bridge,"refusedCalls");
        long edtId=edt(()->Thread.currentThread().getId());
        long phaseAllocation=threads.getThreadAllocatedBytes(edtId),phaseCpu=threads.getThreadCpuTime(edtId),phaseGc=gcTime();
        var stacks=new StringBuilder();AtomicLong stackSamples=new AtomicLong();
        AtomicLong peak=new AtomicLong(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        AtomicLong samples=new AtomicLong(); AtomicBoolean observe=new AtomicBoolean(true);
        Thread sampler=new Thread(()->{
            while(observe.get()) { peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);samples.incrementAndGet();
                if(trace && stackSamples.get()<2000) {
                    var info=threads.getThreadInfo(edtId,64);
                    if(info!=null){stacks.append("SAMPLE ").append(System.nanoTime()).append(" ").append(info.getThreadState()).append('\n');
                        for(var frame:info.getStackTrace())stacks.append(frame).append('\n');stackSamples.incrementAndGet();}
                }
                try { Thread.sleep(20); } catch(InterruptedException ended) { return; }
            }
        },"mi3d-drag-heap-sampler");sampler.setDaemon(true);sampler.start();
        long start=System.nanoTime();long allocation=0;long maximum=0;long[] phaseEnd=new long[3];
        boolean pressed=false;
        try {
            long[] press=measuredEvent(target,MouseEvent.MOUSE_PRESSED,target.x,target.y,MouseEvent.BUTTON1,MouseEvent.BUTTON1_DOWN_MASK,threads);pressed=true;
            timing(output,"press",press,trace);
            for (int step=1; step<=steps; step++) {
                int offset=(int)Math.round(20.0*step/steps);
                long[] measured=measuredEvent(target,MouseEvent.MOUSE_DRAGGED,target.x+offset,target.y,MouseEvent.NOBUTTON,MouseEvent.BUTTON1_DOWN_MASK,threads);
                allocation+=measured[0];maximum=Math.max(maximum,measured[1]);
                timing(output,"step."+step,measured,trace);
                Thread.sleep(25);
            }
        } finally {
            try {
                if (pressed) {
                    long[] release=measuredEvent(target,MouseEvent.MOUSE_RELEASED,target.x+20,target.y,MouseEvent.BUTTON1,0,threads);
                    timing(output,"release",release,trace);
                }
            } finally {
                observe.set(false);sampler.interrupt();sampler.join(2000);
                phaseEnd[0]=threads.getThreadAllocatedBytes(edtId);phaseEnd[1]=threads.getThreadCpuTime(edtId);phaseEnd[2]=gcTime();
                threads.setThreadAllocatedMemoryEnabled(allocationEnabled);
                if(trace && !cpuEnabled && threads.isThreadCpuTimeSupported())threads.setThreadCpuTimeEnabled(false);
            }
        }
        long phaseWall=System.nanoTime()-start;
        if(trace) {
            if(sampler.isAlive())throw new IllegalStateException("bounded trace sampler did not end");
            output.setProperty("phaseEdtAllocatedBytes",Long.toString(phaseEnd[0]-phaseAllocation));
            output.setProperty("phaseEdtCpuNs",Long.toString(phaseEnd[1]-phaseCpu));
            output.setProperty("phaseGcMs",Long.toString(phaseEnd[2]-phaseGc));
            Path tracePath=Path.of(input.getProperty("traceOutput"));
            Files.writeString(tracePath,stacks,StandardOpenOption.CREATE_NEW);output.setProperty("traceArtifact",tracePath.getFileName().toString());
            output.setProperty("traceSamples",Long.toString(stackSamples.get()));
        }
        output.setProperty("mouseDragEvents",Integer.toString(steps));
        output.setProperty("wallNs",Long.toString(phaseWall));
        output.setProperty("dragEventEdtAllocatedBytes",Long.toString(allocation));
        output.setProperty("maxDragEventDispatchNs",Long.toString(maximum));
        output.setProperty("sampledHeapPeakBytes",Long.toString(peak.get()));output.setProperty("heapSamples",Long.toString(samples.get()));
        output.setProperty("acceptedDelta",Long.toString(counter(bridge,"acceptedCalls")-accepted));
        output.setProperty("refusedDelta",Long.toString(counter(bridge,"refusedCalls")-refused));
        if(shader!=null) {
            output.setProperty("uniformHitDelta",Long.toString(shader.hits()-uniformHits));
            output.setProperty("uniformQueryDelta",Long.toString(shader.queries()-uniformQueries));
            output.setProperty("uniformInvalidationDelta",Long.toString(shader.invalidations()-uniformInvalidations));
            captureUniformPair(target,input,output,"moved",shader);
        }
        output.setProperty("scope","Official view input wrapper with task AWT events; includes probe overhead; not OS input or resident-memory measurement");
        } finally {
            if(previousCoefficients==null)System.clearProperty("turboism.meshInterpolation.coefficients");
            else System.setProperty("turboism.meshInterpolation.coefficients",previousCoefficients);
            bridge.getMethod("enabled",boolean.class).invoke(null,true);
            if(shader!=null)shader.enabled(priorUniform);
        }
    }
    /** Isolated opt-in renderer experiment. No persistent model edits. */
    public static final class UniformCandidate {
        public static volatile boolean enabled;
        static volatile boolean patched;
        static final java.util.WeakHashMap<Object,State> CACHE=new java.util.WeakHashMap<>();
        static final AtomicLong hits=new AtomicLong(),queries=new AtomicLong(),invalidations=new AtomicLong();
        static final int MAX_SHADERS=1024,MAX_NAMES=4096;
        static final String OWNER="com/live2d/graphics3d/shader/GShader";
        static final String CLASS_SHA="efed635454ed364f26f7aa1673c602cba35b00b4396ba03dee631dfc6450b6e0";
        static final String HELPER="dev/turboism/validation/modelimport3d/nativeprobe/CatNativeDragAgent$UniformCandidate";
        static final class State {
            final java.lang.ref.WeakReference<Object> context;final long handle;final int program;
            final Map<String,Integer> locations=new HashMap<>();
            State(Object c,long h,int p){context=new java.lang.ref.WeakReference<>(c);handle=h;program=p;}
            boolean same(Object c,long h,int p){return context.get()==c && handle==h && program==p;}
        }
        static java.lang.reflect.Method method(Class<?> type,String name,Class<?>...args){
            try{return type.getMethod(name,args);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        }
        static final ClassValue<java.lang.reflect.Method[]> GL=new ClassValue<>() {
            protected java.lang.reflect.Method[] computeValue(Class<?> type){return new java.lang.reflect.Method[]{method(type,"glGetUniformLocation",int.class,String.class),method(type,"getContext")};}
        };
        static final ClassValue<java.lang.reflect.Method[]> CONTEXT=new ClassValue<>() {
            protected java.lang.reflect.Method[] computeValue(Class<?> type){return new java.lang.reflect.Method[]{method(type,"getHandle"),method(type,"isCreated"),method(type,"getCurrent")};}
        };
        static final ClassValue<java.lang.reflect.Method[]> SHADER=new ClassValue<>() {
            protected java.lang.reflect.Method[] computeValue(Class<?> type){return new java.lang.reflect.Method[]{method(type,"getGlProgramID"),method(type,"getUniformMap")};}
        };
        static Object invoke(java.lang.reflect.Method method,Object receiver,Object...args){
            try{return method.invoke(receiver,args);}catch(java.lang.reflect.InvocationTargetException e){
                if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw new IllegalStateException(e.getCause());
            }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        }
        static int original(Object gl,int program,String name){queries.incrementAndGet();return (int)invoke(GL.get(gl.getClass())[0],gl,program,name);}
        public static int lookup(Object gl,int program,String name,Object shader) {
            if(!enabled || program<=0 || name==null)return original(gl,program,name);
            Object context=null;long handle=0;boolean eligible=false;
            try {
                context=invoke(GL.get(gl.getClass())[1],gl);
                if(context!=null){var api=CONTEXT.get(context.getClass());handle=((Number)invoke(api[0],context)).longValue();
                    var shaderApi=SHADER.get(shader.getClass());
                    eligible=handle!=0 && Boolean.TRUE.equals(invoke(api[1],context)) && invoke(api[2],null)==context
                        && ((Number)invoke(shaderApi[0],shader)).intValue()==program
                        && invoke(shaderApi[1],shader) instanceof Map<?,?> uniforms && !uniforms.isEmpty();}
            }catch(IllegalStateException e){eligible=false;}
            if(!eligible)return original(gl,program,name);
            State state;
            synchronized(CACHE) {
                state=CACHE.get(shader);
                if(state==null || !state.same(context,handle,program)) {
                    if(CACHE.size()>=MAX_SHADERS)CACHE.clear();state=new State(context,handle,program);CACHE.put(shader,state);
                }
                Integer location=state.locations.get(name);if(location!=null){hits.incrementAndGet();return location;}
            }
            int location=original(gl,program,name);
            synchronized(CACHE){if(CACHE.get(shader)==state && state.locations.size()<MAX_NAMES)state.locations.put(name,location);}
            return location;
        }
        public static void invalidate(Object shader){synchronized(CACHE){CACHE.remove(shader);}invalidations.incrementAndGet();}
        static byte[] patch(byte[] bytes) {
            var reader=new org.objectweb.asm.ClassReader(bytes);
            if(!reader.getClassName().equals(OWNER))throw new IllegalArgumentException("shader owner");
            var writer=new org.objectweb.asm.ClassWriter(reader,org.objectweb.asm.ClassWriter.COMPUTE_MAXS);int[] count={0,0};
            reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9,writer) {
                public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions) {
                    var base=super.visitMethod(access,name,desc,sig,exceptions);
                    boolean drawing=name.equals("preDraw_exe") && desc.equals("(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics3d/material/GMaterial;Lcom/live2d/graphics3d/type/GMatrix44;)V");
                    boolean invalidate=name.equals("init_exe") && desc.equals("(Lcom/live2d/graphics3d/a;)V") || name.equals("setGlProgramID") && desc.equals("(I)V");
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9,base) {
                        public void visitCode(){super.visitCode();if(invalidate){count[1]++;super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD,0);super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,HELPER,"invalidate","(Ljava/lang/Object;)V",false);}}
                        public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf) {
                            if(drawing && opcode==org.objectweb.asm.Opcodes.INVOKEINTERFACE && owner.equals("com/jogamp/opengl/GL3") && method.equals("glGetUniformLocation") && descriptor.equals("(ILjava/lang/String;)I")) {
                                count[0]++;super.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD,0);super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,HELPER,"lookup","(Ljava/lang/Object;ILjava/lang/String;Ljava/lang/Object;)I",false);
                            }else super.visitMethodInsn(opcode,owner,method,descriptor,itf);
                        }
                    };
                }
            },0);
            if(count[0]!=1 || count[1]!=2)throw new IllegalArgumentException("shader hook cardinality "+Arrays.toString(count));return writer.toByteArray();
        }
        static final class Transformer implements java.lang.instrument.ClassFileTransformer {
            public byte[] transform(ClassLoader loader,String name,Class<?> prior,java.security.ProtectionDomain domain,byte[] bytes) {
                if(!OWNER.equals(name))return null;
                try {
                    if(prior!=null || domain==null || domain.getCodeSource()==null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(CLASS_SHA)
                            || !sha(Path.of(domain.getCodeSource().getLocation().toURI())).equals(HOST_SHA))throw new IllegalStateException("shader identity");
                    byte[] result=patch(bytes);patched=true;System.err.println("CAT_SHADER_UNIFORM_PATCHED_5302");return result;
                }catch(Exception | LinkageError e){System.err.println("CAT_SHADER_UNIFORM_DECLINED "+e);return null;}
            }
        }
    }
    static void captureUniformPair(Target target,Properties input,Properties output,String stage,ShaderCtl shader)throws Exception {
        Path directory=Path.of(input.getProperty("traceOutput")).getParent();
        java.awt.image.BufferedImage[] pair=edt(()->{
            requireCurrent(target);int width=target.canvas.getWidth(),height=target.canvas.getHeight();
            if(width<=0 || height<=0 || (long)width*height>8_000_000)throw new IllegalStateException("bounded task canvas");
            var result=new java.awt.image.BufferedImage[2];boolean previous=shader.enabled();
            try {for(int i=0;i<2;i++){
                long calls=shader.hits()+shader.queries();
                shader.enabled(i==1);result[i]=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var graphics=result[i].createGraphics();try{target.canvas.paint(graphics);}finally{graphics.dispose();}
                long observed=shader.hits()+shader.queries()-calls;output.setProperty(stage+".lookupCalls."+i,Long.toString(observed));
                if(observed<=0)throw new IllegalStateException("canvas capture did not execute actual shader path");
            }}finally{shader.enabled(previous);}return result;
        });
        for(int i=0;i<2;i++){
            Path path=directory.resolve("shader-"+stage+"-"+(i==0?"official":"candidate")+".png");
            if(Files.exists(path) || !javax.imageio.ImageIO.write(pair[i],"PNG",path.toFile()))throw new IllegalStateException("fresh native canvas image");
            output.setProperty(stage+".image."+i,path.getFileName().toString());
        }
        int width=pair[0].getWidth(),height=pair[0].getHeight();int[] a=pair[0].getRGB(0,0,width,height,null,0,width),b=pair[1].getRGB(0,0,width,height,null,0,width);
        long colors=java.util.Arrays.stream(a).distinct().count();output.setProperty(stage+".uniqueColors",Long.toString(colors));
        if(colors<32)throw new IllegalStateException("blank/non-model native canvas capture");
        output.setProperty(stage+".pixelsExact",Boolean.toString(Arrays.equals(a,b)));output.setProperty(stage+".pixelCount",Integer.toString(a.length));
        if(!Arrays.equals(a,b))throw new IllegalStateException("native canvas pixels differ with shader uniform reuse "+stage);
    }

}
