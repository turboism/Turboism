package dev.turboism.validation.texture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.objectweb.asm.*;
import java.awt.geom.*;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Predicate;

/** Validation-only, exact Cubism 5.3.03 native entry boundary timer.
 * Registered AFTER production ingress instrumentation; never shipped with the product.
 * Input/output reflection, geometry checks and file IO are OUTSIDE the measured interval.
 */
public final class HostTimingProbe {
    private static final String OWNER = "com/live2d/cubism/doc/modeling/ui/atlasEditor/a/c";
    private static final String DESC = "(Lcom/live2d/cubism/doc/modeling/ui/atlasEditor/a/c$c;)Z";
    private static final String SELF = "dev/turboism/validation/texture/HostTimingProbe";
    private static final String PLANNER = "dev/turboism/plugin/atlasmaxrectsbssf/layout/CurrentPageTextureAtlasPlanner";
    private static final String PARALLEL_OBSERVER = "dev.turboism.validation.texture.plan-parallel";
    private static final String PACK_OBSERVER = "dev.turboism.validation.texture.pack-worker";
    private static volatile State current;
    private static final String KEY = "dev.turboism.texture-atlas.auto-layout.runtime-ingress";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();
    private static Path directory;
    private static int sequence;
    private static volatile Runnable pendingOutput;
    private static final class State {
        long start;
        long inputProbeNanos;
        String branch = "unobserved";
        Map<String,Object> input;
        volatile Boolean plannerParallel;
        final Set<String> packingThreads = new java.util.concurrent.ConcurrentSkipListSet<>();
        final java.util.concurrent.atomic.AtomicInteger packingCalls = new java.util.concurrent.atomic.AtomicInteger();
    }
    public static void premain(String argument, Instrumentation instrumentation) {
        directory = Path.of(argument);
        Thread worker = new Thread(() -> {
            try {
                for (int i=0; i<900 && !(System.getProperties().get(KEY) instanceof Predicate); i++) Thread.sleep(200);
                if (!(System.getProperties().get(KEY) instanceof Predicate<?>)) throw new IllegalStateException("No production ingress");
                Thread.sleep(3000); // Allow the production installer to finish registering its transformer.
                @SuppressWarnings("unchecked") Predicate<Object> original = (Predicate<Object>) System.getProperties().get(KEY);
                System.getProperties().put(KEY, (Predicate<Object>) receiver -> {
                    State state = ACTIVE.get();
                    try {
                        boolean handled = original.test(receiver);
                        if (state != null) state.branch = handled ? "handled" : "native";
                        return handled;
                    } catch (RuntimeException | Error failure) {
                        if (state != null) state.branch = "ingress-error";
                        throw failure;
                    }
                });
                instrumentation.addTransformer(new ClassFileTransformer() {
                    @Override public byte[] transform(Module module, ClassLoader loader, String name,
                            Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                        if (PLANNER.equals(name)) return instrumentPlanner(bytes);
                        if (!OWNER.equals(name)) return null;
                        ClassReader reader = new ClassReader(bytes);
                        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                        boolean[] found = {false};
                        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                    String signature, String[] exceptions) {
                                MethodVisitor base = super.visitMethod(access,name,descriptor,signature,exceptions);
                                if (!name.equals("a") || !descriptor.equals(DESC)) return base;
                                found[0] = true;
                                return new MethodVisitor(Opcodes.ASM9,base) {
                                    @Override public void visitCode() {
                                        super.visitCode();
                                        super.visitVarInsn(Opcodes.ALOAD,0);
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"enter","(Ljava/lang/Object;)V",false);
                                    }
                                    @Override public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.IRETURN) {
                                            super.visitInsn(Opcodes.DUP);
                                            super.visitVarInsn(Opcodes.ALOAD,0);
                                            super.visitInsn(Opcodes.SWAP);
                                            super.visitMethodInsn(Opcodes.INVOKESTATIC,SELF,"exit","(Ljava/lang/Object;Z)V",false);
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }
                        },0);
                        if (!found[0]) throw new IllegalStateException("Exact method not found");
                        return writer.toByteArray();
                    }
                },true);
                for (Class<?> type : instrumentation.getAllLoadedClasses())
                    if (type.getName().equals(OWNER.replace('/','.')) || type.getName().equals(PLANNER.replace('/','.')))
                        instrumentation.retransformClasses(type);
                Files.writeString(directory.resolve("timer-ready.txt"),"READY exact=5.3.03 owner="+OWNER+" descriptor="+DESC);
            } catch (Throwable failure) {
                try { Files.writeString(directory.resolve("timer-error.txt"),failure.toString()); }
                catch (Exception ignored) { failure.printStackTrace(); }
            }
        },"texture-validation-timer-installer");
        worker.setDaemon(true); worker.start();
    }
    public static void enter(Object receiver) {
        if (ACTIVE.get()!=null) throw new IllegalStateException("Nested layout invocation");
        State state = new State();
        long probeStart = System.nanoTime();
        try { state.input = snapshot(receiver, false); }
        catch (Exception failure) { throw new IllegalStateException("Input snapshot failed",failure); }
        ACTIVE.set(state);
        current = state;
        System.getProperties().put(PARALLEL_OBSERVER, (java.util.function.IntConsumer) value -> {
            State active = current;
            if (active != null) active.plannerParallel = value != 0;
        });
        System.getProperties().put(PACK_OBSERVER, (Runnable) () -> {
            State active = current;
            if (active != null) {
                active.packingThreads.add(Thread.currentThread().getName());
                active.packingCalls.incrementAndGet();
            }
        });
        state.start = System.nanoTime();
        state.inputProbeNanos = state.start - probeStart;
    }
    public static void exit(Object receiver, boolean returned) {
        long end = System.nanoTime();
        State state = ACTIVE.get(); ACTIVE.remove();
        if (state == null) throw new IllegalStateException("Missing timer entry");
        if (HostUiProbe.UI_TIMING) {
            if (pendingOutput != null) throw new IllegalStateException("Repeated pending output");
            pendingOutput = () -> writeOutput(receiver, returned, state, end);
        } else writeOutput(receiver, returned, state, end);
    }
    static void finishAfterProgress() {
        if (!HostUiProbe.UI_TIMING || HostUiProbe.progressClosed == 0) return;
        Runnable output = pendingOutput;
        if (output == null) throw new IllegalStateException("Progress closed without method result");
        pendingOutput = null;
        output.run();
    }
    private static void writeOutput(Object receiver, boolean returned, State state, long end) {
        try {
            Map<String,Object> output = snapshot(receiver,true);
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("sequence",++sequence); result.put("branch",state.branch);
            result.put("plannerParallel",state.plannerParallel);
            result.put("packingThreads",state.packingThreads);
            result.put("packingCalls",state.packingCalls.get());
            result.put("returned",returned); result.put("methodMs",(end-state.start)/1e6);
            if (HostUiProbe.UI_TIMING) {
                long start = HostUiProbe.actionStart, shown = HostUiProbe.progressShown, closed = HostUiProbe.progressClosed;
                if (!(start > 0 && shown >= start && state.start >= shown && end >= state.start && closed >= end))
                    throw new IllegalStateException("Invalid UI lifecycle ordering");
                result.put("uiActionToProgressClosedMs", (closed-start)/1e6);
                result.put("uiActionToProgressShownMs", (shown-start)/1e6);
                result.put("uiMethodReturnToProgressClosedMs", (closed-end)/1e6);
                result.put("uiInputProbeMs", state.inputProbeNanos/1e6);
                result.put("uiOutputValidationDeferred", true);
                result.put("uiProgressClass", "jp.noids.framework.e.a.f");
                result.put("uiTimingBoundary", "OK ActionEvent dispatch to exact progress window SHOWING_CHANGED=false; raw instrumented wall time");
            }
            result.put("inputHash",hash(state.input)); result.put("input",state.input);
            result.put("outputHash",hash(output)); result.put("output",output);
            Path path = directory.resolve(String.format(Locale.ROOT,"timing-%03d.json",sequence));
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),result);
        } catch (Exception failure) { throw new IllegalStateException("Output snapshot failed",failure); }
    }
    private static byte[] instrumentPlanner(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access,name,descriptor,signature,exceptions);
                boolean plan = name.equals("plan") && descriptor.equals("(Ljava/util/List;Ldev/turboism/sdk/cubism/textureatlas/TextureAtlasLayoutConstraints;Z)Ldev/turboism/sdk/cubism/textureatlas/TextureAtlasLayoutPlan;");
                boolean packing = name.equals("bestPacking") && descriptor.equals("(Ljava/util/List;Ldev/turboism/sdk/cubism/textureatlas/TextureAtlasLayoutConstraints;DZ)Ldev/turboism/plugin/atlasmaxrectsbssf/layout/CurrentPageTextureAtlasPlanner$Packed;");
                if (!plan && !packing) return base;
                return new MethodVisitor(Opcodes.ASM9,base) {
                    @Override public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,"java/lang/System","getProperties","()Ljava/util/Properties;",false);
                        super.visitLdcInsn(plan ? PARALLEL_OBSERVER : PACK_OBSERVER);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/util/Properties","get","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                        if (plan) {
                            super.visitTypeInsn(Opcodes.CHECKCAST,"java/util/function/IntConsumer");
                            super.visitVarInsn(Opcodes.ILOAD,3);
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE,"java/util/function/IntConsumer","accept","(I)V",true);
                        } else {
                            super.visitTypeInsn(Opcodes.CHECKCAST,"java/lang/Runnable");
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE,"java/lang/Runnable","run","()V",true);
                        }
                    }
                };
            }
        },0);
        return writer.toByteArray();
    }
    private static String hash(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
    }
    private static Map<String,Object> snapshot(Object receiver, boolean output) throws Exception {
        Object data = field(receiver,"c"), settings = field(receiver,"b");
        List<?> items = (List<?>) call(data,"b");
        List<?> overflow = (List<?>) field(receiver,"i");
        int width=((Number)call(data,"c")).intValue(), height=((Number)call(data,"d")).intValue();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("width",width); result.put("height",height);
        result.put("margin",call(settings,"a")); result.put("rotate",call(settings,"b"));
        result.put("modelImage",call(settings,"c")); result.put("requestedScale",call(settings,"d"));
        result.put("dataScale",field(data,"b")); result.put("count",items.size());
        Object container=call(call(data,"a"),"b");
        Object[] children=(Object[])call(container,"getChildren");
        IdentityHashMap<Object,Object> layerByImage=new IdentityHashMap<>();
        for(Object child:children) layerByImage.put(call(child,"getLayer"),child);
        List<Object> rows=new ArrayList<>(); List<Integer> omitted=new ArrayList<>();
        List<Rectangle2D> bounds=new ArrayList<>(); boolean finite=true,inside=true,layerMatch=true;
        for(int index=0;index<items.size();index++) {
            Object item=items.get(index), rect=call(item,"h");
            double x=num(call(rect,"getX")), y=num(call(rect,"getY"));
            double w=num(call(rect,"getWidth")), h=num(call(rect,"getHeight"));
            AffineTransform transform=(AffineTransform)field(item,"f");
            Object layer=layerByImage.get(call(item,"a"));
            if(layer==null) throw new IllegalStateException("Missing layer");
            AffineTransform visual=(AffineTransform)call(layer,"getTransformToParent");
            double[] matrix=transform == null ? null : new double[6], visualMatrix=new double[6];
            if (transform != null) transform.getMatrix(matrix);
            visual.getMatrix(visualMatrix);
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",index);
            row.put("rect",new double[]{x,y,w,h});row.put("matrix",matrix);row.put("visual",visualMatrix);
            rows.add(row);
            if(overflow.contains(item)) { omitted.add(index); continue; }
            if (!output) continue;
            if (transform == null) throw new IllegalStateException("Placed output has no transform");
            Rectangle2D box=transform.createTransformedShape(new Rectangle2D.Double(x,y,w,h)).getBounds2D();
            bounds.add(box);
            for(int i=0;i<6;i++) {finite &= Double.isFinite(matrix[i]);layerMatch &= Math.abs(matrix[i]-visualMatrix[i])<1e-6;}
            inside &= box.getMinX()>=-1e-4 && box.getMinY()>=-1e-4 && box.getMaxX()<=width+1e-4 && box.getMaxY()<=height+1e-4;
        }
        result.put("items",rows);result.put("overflow",omitted);
        if(output) {
            int overlaps=0;
            for(int i=0;i<bounds.size();i++) for(int j=i+1;j<bounds.size();j++) {
                Rectangle2D a=bounds.get(i),b=bounds.get(j);
                if(Math.min(a.getMaxX(),b.getMaxX())-Math.max(a.getMinX(),b.getMinX())>1e-4
                    && Math.min(a.getMaxY(),b.getMaxY())-Math.max(a.getMinY(),b.getMinY())>1e-4) overlaps++;
            }
            result.put("finite",finite);result.put("inside",inside);result.put("layerMatch",layerMatch);result.put("overlaps",overlaps);
        }
        return result;
    }
    private static double num(Object x) {return ((Number)x).doubleValue();}
    private static Object field(Object target,String name) throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static Object call(Object target,String name) throws Exception {
        Method m=target.getClass().getMethod(name);m.setAccessible(true);return m.invoke(target);
    }
}
