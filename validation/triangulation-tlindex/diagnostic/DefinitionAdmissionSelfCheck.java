package dev.turboism.validation.tlindex.diagnostic;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.*;

/** Real Instrumentation over owned generated definitions; never defines an official class. */
public final class DefinitionAdmissionSelfCheck implements Opcodes {
    private static final String OWNED = "dev/turboism/validation/tlindex/diagnostic/owned/";
    private static final List<String> observations = new ArrayList<>();
    private static int checks;
    private DefinitionAdmissionSelfCheck() {}
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message + " check=" + checks);
    }

    private static byte[] fixture(String name, int value, boolean layout) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        if (layout) for (int i = 0; i < 300; i++) w.newConst("unused-" + i);
        w.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
        w.visitSource(layout ? "OtherOwned.java" : "Owned.java", null);
        AnnotationVisitor annotation = w.visitAnnotation("L" + OWNED + "Note;", true);
        annotation.visit("name", "reviewed"); annotation.visit("number", 7); annotation.visitEnd();
        w.visitField(ACC_PRIVATE | ACC_FINAL, "index", "I", null, null).visitEnd();
        String[] methods = layout ? new String[] {"marker", "branch", "value", "<init>"}
                : new String[] {"<init>", "value", "branch", "marker"};
        for (String method : methods) {
            String desc = method.equals("marker") ? "()Ljava/lang/String;" : method.equals("branch") ? "(I)I"
                    : method.equals("value") ? "()I" : "()V";
            MethodVisitor m = w.visitMethod(ACC_PUBLIC, method, desc, null, null); m.visitCode();
            Label start = new Label(); m.visitLabel(start); m.visitLineNumber(layout ? 20 : 10, start);
            switch (method) {
                case "<init>" -> {
                    m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    m.visitVarInsn(ALOAD, 0); m.visitIntInsn(BIPUSH, 7); m.visitFieldInsn(PUTFIELD, name, "index", "I"); m.visitInsn(RETURN);
                }
                case "value" -> { m.visitIntInsn(BIPUSH, value); m.visitInsn(IRETURN); }
                case "marker" -> { m.visitLdcInsn("stable-marker"); m.visitInsn(ARETURN); }
                default -> {
                    Label negative = new Label(), done = new Label();
                    m.visitVarInsn(ILOAD, 1); m.visitJumpInsn(IFLT, negative); m.visitIntInsn(BIPUSH, 6);
                    m.visitJumpInsn(GOTO, done); m.visitLabel(negative); m.visitIntInsn(BIPUSH, 4);
                    m.visitLabel(done); m.visitInsn(IRETURN);
                }
            }
            m.visitMaxs(0, 0); m.visitEnd();
        }
        w.visitEnd(); return w.toByteArray();
    }
    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> resources = new LinkedHashMap<>();
        Loader() { super(DefinitionAdmissionSelfCheck.class.getClassLoader()); }
        Class<?> define(String name, byte[] live, byte[] reviewedResource) {
            resources.put(name + ".class", reviewedResource);
            return defineClass(name.replace('/', '.'), live, 0, live.length);
        }
        @Override public InputStream getResourceAsStream(String name) {
            byte[] bytes = resources.get(name); return bytes == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
        }
    }
    private static Map<String, String> expected(String name, byte[] bytes) {
        return Map.of(name.replace('/', '.'), DefinitionFingerprint.of(bytes));
    }
    private static int value(Class<?> type) throws Exception {
        return (int) type.getMethod("value").invoke(type.getConstructor().newInstance());
    }
    private static ClassFileTransformer change(Class<?> target) {
        return new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                if (redefined != target) return null;
                ClassReader reader = new ClassReader(bytes); ClassWriter writer = new ClassWriter(reader, 0);
                reader.accept(new ClassVisitor(ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                        MethodVisitor output = super.visitMethod(access, method, desc, signature, exceptions);
                        if (!method.equals("value")) return output;
                        return new MethodVisitor(ASM9, output) {
                            @Override public void visitIntInsn(int opcode, int operand) { super.visitIntInsn(opcode, operand == 3 ? 9 : operand); }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        };
    }
    private record Released(WeakReference<ClassLoader> loader, LiveDefinitionAdmission.Gate gate) {}
    private static Released releasable(LiveDefinitionAdmission engine) {
        String name = OWNED + "Releasable"; byte[] bytes = fixture(name, 3, false); Loader loader = new Loader();
        Class<?> type = loader.define(name, bytes, bytes);
        LiveDefinitionAdmission.Gate gate = engine.capture(new Class<?>[] {type}, expected(name, bytes));
        require(gate.getAsBoolean(), "releasable class admission");
        return new Released(new WeakReference<>(loader), gate);
    }
    private static void parallel(LiveDefinitionAdmission engine) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String name = OWNED + "Parallel" + i; byte[] bytes = fixture(name, 3, false);
            Class<?> type = new Loader().define(name, bytes, bytes);
            Thread thread = new Thread(() -> {
                try {
                    start.await(); LiveDefinitionAdmission.Gate gate = engine.capture(new Class<?>[] {type}, expected(name, bytes));
                    if (!gate.getAsBoolean()) throw new AssertionError(gate.reason());
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "owned-admission-" + i);
            threads.add(thread); thread.start();
        }
        start.countDown(); for (Thread thread : threads) thread.join(10_000);
        require(threads.stream().noneMatch(Thread::isAlive), "parallel capture completion");
        require(failure.get() == null, "parallel capture rejected: " + failure.get());
    }
    private static void fingerprintBoundaries() {
        String name = OWNED + "StringBoundary";
        byte[][] strings = new byte[2][];
        for (int i = 0; i < strings.length; i++) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
            writer.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "text", "Ljava/lang/String;", null,
                    String.valueOf((char) (0xd800 + i))).visitEnd();
            writer.visitEnd(); strings[i] = writer.toByteArray();
        }
        require(!DefinitionFingerprint.of(strings[0]).equals(DefinitionFingerprint.of(strings[1])),
                "distinct unpaired UTF-16 constants must not collapse");
        ClassWriter module = new ClassWriter(0);
        module.visit(V17, ACC_MODULE, "module-info", null, null, null);
        module.visitModule("owned.module", 0, null).visitEnd(); module.visitEnd();
        boolean refused = false;
        try { DefinitionFingerprint.of(module.toByteArray()); }
        catch (IllegalArgumentException expectedFailure) { refused = true; }
        require(refused, "module metadata cannot be silently ignored");
    }
    private static void lateTransformerBoundary(Instrumentation instrumentation) throws Exception {
        String name = OWNED + "LateTransformer"; byte[] bytes = fixture(name, 3, false);
        Class<?> type = new Loader().define(name, bytes, bytes);
        ClassFileTransformer late = change(type);
        AtomicInteger registrations = new AtomicInteger();
        // Reproduce an external registration just after the temporary collector.
        // All definition callbacks and changed method execution use the real JVM.
        Instrumentation interleaved = (Instrumentation) Proxy.newProxyInstance(
                DefinitionAdmissionSelfCheck.class.getClassLoader(), new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> {
                    Object result;
                    try { result = method.invoke(instrumentation, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                    if (method.getName().equals("addTransformer") && registrations.incrementAndGet() == 2)
                        instrumentation.addTransformer(late, true);
                    return result;
                });
        try (LiveDefinitionAdmission engine = new LiveDefinitionAdmission(interleaved)) {
            LiveDefinitionAdmission.Gate gate = engine.capture(new Class<?>[] {type}, expected(name, bytes));
            require(gate.getAsBoolean() && value(type) == 9, "known downstream-transformer limitation reproduced");
            observations.add("lateTransformerLimit=collectorMatchDoesNotProveFinalDefinition");
        } finally { instrumentation.removeTransformer(late); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("new output directory required");
        Path out = Path.of(args[0]); Files.createDirectory(out);
        Instrumentation instrumentation = DefinitionAdmissionSelfCheckAgent.instrumentation();
        require(instrumentation != null && instrumentation.isRetransformClassesSupported(), "owned agent instrumentation");
        String name = OWNED + "Witness";
        byte[] reviewed = fixture(name, 3, false), layout = fixture(name, 3, true), tampered = fixture(name, 8, false);
        Files.write(out.resolve("reviewed-owned.class"), reviewed); Files.write(out.resolve("layout-owned.class"), layout);
        Files.write(out.resolve("tampered-owned.class"), tampered);
        require(!Arrays.equals(reviewed, layout), "layout control bytes differ");
        require(DefinitionFingerprint.of(reviewed).equals(DefinitionFingerprint.of(layout)), "pool/debug/member-order normalization");
        require(!DefinitionFingerprint.of(reviewed).equals(DefinitionFingerprint.of(tampered)), "instruction change rejected");
        fingerprintBoundaries();
        Class<?> good = new Loader().define(name, layout, reviewed);
        Class<?> bad = new Loader().define(name, tampered, reviewed);
        try (InputStream resource = bad.getClassLoader().getResourceAsStream(name + ".class")) {
            require(resource != null && DefinitionFingerprint.of(resource.readAllBytes()).equals(DefinitionFingerprint.of(reviewed)), "resource control matches review");
        }
        require(value(good) == 3 && value(bad) == 8, "actual definitions differ under same binary name");
        LiveDefinitionAdmission engine = new LiveDefinitionAdmission(instrumentation);
        try {
            LiveDefinitionAdmission.Gate goodGate = engine.capture(new Class<?>[] {good}, expected(name, reviewed));
            require(goodGate.getAsBoolean(), "actual reviewed definition admitted: " + goodGate.reason());
            observations.add("reviewed=" + goodGate.reason());
            LiveDefinitionAdmission.Gate badGate = engine.capture(new Class<?>[] {bad}, expected(name, reviewed));
            require(!badGate.getAsBoolean(), "changed live definition with reviewed resource admitted");
            observations.add("resourceLies=" + badGate.reason());
            require(!engine.capture(new Class<?>[] {good, bad}, expected(name, reviewed)).getAsBoolean(), "duplicate binary names rejected");
            require(!engine.capture(new Class<?>[] {good}, Map.of(name.replace('/', '.'), DefinitionFingerprint.of(reviewed), "missing.Type", "0".repeat(64))).getAsBoolean(), "missing dependency rejected");
            Map<String, String> invalidExpected = new LinkedHashMap<>();
            invalidExpected.put(name.replace('/', '.'), null);
            require(!engine.capture(new Class<?>[] {good}, invalidExpected).getAsBoolean(), "invalid expected map fallback");
            ClassFileTransformer modifier = change(good); instrumentation.addTransformer(modifier, true);
            try {
                instrumentation.retransformClasses(good);
                require(!goodGate.getAsBoolean(), "definition change revoked admission");
                require(value(good) == 9, "owned transformer actually changed definition");
                require(!engine.capture(new Class<?>[] {good}, expected(name, reviewed)).getAsBoolean(), "earlier transformer output observed by capture");
            } finally { instrumentation.removeTransformer(modifier); }
            instrumentation.retransformClasses(good);
            require(value(good) == 3 && !goodGate.getAsBoolean(), "old admission stays revoked after restoration");
            LiveDefinitionAdmission.Gate restored = engine.capture(new Class<?>[] {good}, expected(name, reviewed));
            require(restored.getAsBoolean(), "fresh capture admits restored definition");
            parallel(engine);
            Released released = releasable(engine);
            // GC is confined to this isolated owned JVM; never part of host validation.
            for (int i = 0; i < 60 && released.loader.get() != null; i++) { System.gc(); Thread.sleep(20); }
            require(released.loader.get() == null, "live gate/observer retained application loader");
            require(released.gate.getAsBoolean(), "gate lifetime independent of weak observer keys");
            observations.add("loaderReleased=true");
            engine.close();
            require(!restored.getAsBoolean() && engine.watchedClassCount() == 0, "close revokes and unregisters state");
            engine.close();
            require(engine.watchedClassCount() == 0, "close is idempotent");
            require(!engine.capture(new Class<?>[] {good}, expected(name, reviewed)).getAsBoolean(), "closed engine refused");
        } finally { engine.close(); }
        try (LiveDefinitionAdmission unavailable = new LiveDefinitionAdmission(null)) {
            require(!unavailable.capture(new Class<?>[] {good}, expected(name, reviewed)).getAsBoolean(), "unavailable instrumentation fallback");
        }
        lateTransformerBoundary(instrumentation);
        Files.writeString(out.resolve("receipts.txt"), String.join("\n", observations) + "\n");
        System.out.println("LIVE_DEFINITION_OWN_SELFCHECK PASS checks=" + checks
                + " officialClassesExecuted=false hostTasksSubmitted=0 productionAdmissionReady=false");
    }
}
