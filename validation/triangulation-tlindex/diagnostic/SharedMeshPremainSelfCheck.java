import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.bootstrap.TurboismAgent;
import dev.turboism.agent.shaded.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Real Turboism ownership; synthetic classes test shared bridge protocol, not native admission. */
public final class SharedMeshPremainSelfCheck {
    private static final String H = "com.live2d.graphics3d.editableMesh.triangulation.h";
    private static final String TL = "com.live2d.graphics3d.editableMesh.triangulation.TriangleList";
    private static final String MESH = "com.live2d.graphics3d.editableMesh.GEditableMesh2";
    private static Method fingerprint;
    private static Constructor<?> admission;
    private static int checks;
    private SharedMeshPremainSelfCheck() { }
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static TriangulationDefinitionLifecycle lifecycle() throws Exception {
        Class<?> shims = Class.forName("dev.turboism.bootstrap.JvmShims");
        for (String name : new String[] {"STARTUP_SUPPRESSION", "PIPE_IMPL_SHIM"}) {
            var holder = shims.getDeclaredField(name); holder.setAccessible(true);
            Object installation = ((AtomicReference<?>) holder.get(null)).get();
            if (installation == null) continue;
            var field = installation.getClass().getDeclaredField("instrumentation"); field.setAccessible(true);
            Instrumentation gateway = (Instrumentation) field.get(installation);
            if (gateway != null && TriangulationDefinitionLifecycle.ownedBy(gateway) != null)
                return TriangulationDefinitionLifecycle.ownedBy(gateway);
        }
        throw new AssertionError("canonical owned gateway unavailable");
    }
    private static byte[] definition(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd(); writer.visitEnd();
        return writer.toByteArray();
    }
    private static final class Loader extends ClassLoader {
        final Map<String, byte[]> definitions;
        Loader(ClassLoader parent, Map<String, byte[]> definitions) { super(parent); this.definitions = definitions; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    byte[] bytes = definitions.get(name);
                    type = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
    private static String fingerprint(byte[] raw) {
        try { return (String) fingerprint.invoke(null, (Object) raw); }
        catch (ReflectiveOperationException failure) { throw new IllegalArgumentException(failure); }
    }
    // No application Class/loader is captured by registry factory values.
    private record Factory(TriangulationDefinitionLifecycle owner, Map<String, String> expected,
                           String mode, AtomicInteger calls, AtomicInteger captures) implements Function<Class<?>, Object> {
        @Override public Object apply(Class<?> host) {
            calls.incrementAndGet();
            if (mode.equals("failure")) throw new IllegalArgumentException("owned factory failure");
            try {
                Map<String, String> wanted = new LinkedHashMap<>(expected);
                boolean mesh = !mode.equals("legacy");
                if (!mesh || mode.equals("missing-mesh")) wanted.remove(MESH);
                if (mode.equals("fallback")) wanted.put(MESH, "deliberately-wrong-owned-definition");
                Class<?>[] types = new Class<?>[wanted.size()]; int at = 0;
                for (String name : wanted.keySet()) types[at++] = Class.forName(name, false, host.getClassLoader());
                captures.incrementAndGet();
                TriangulationDefinitionLifecycle.Gate gate = owner.capture(types, wanted, SharedMeshPremainSelfCheck::fingerprint);
                if (mode.equals("fallback")) {
                    require(!gate.reason().equals("OWNED_FINAL_DEFINITION_MATCH"), "extended mismatch actually refused");
                    wanted.remove(MESH);
                    captures.incrementAndGet();
                    gate = owner.capture(new Class<?>[] {host, Class.forName(TL, false, host.getClassLoader())},
                            wanted, SharedMeshPremainSelfCheck::fingerprint);
                    mesh = false;
                }
                return admission.newInstance(gate, mesh);
            } catch (ReflectiveOperationException failure) { throw new IllegalArgumentException(failure); }
        }
    }
    private static void checkEntries(Class<?> host, Class<?> list, Class<?> mesh, boolean expectedMesh) throws Exception {
        try (AutoCloseable h = LazyTriangulationEdgeBridge.enter(host)) {
            require(h != null, "h admission preserved");
            try (AutoCloseable builder = LazyTriangulationEdgeBridge.enterBuilder(list);
                    AutoCloseable nativeMesh = LazyTriangulationEdgeBridge.enterMesh(mesh)) {
                require(builder != null, "builder shares h gate");
                require((nativeMesh != null) == expectedMesh, "mesh uses only extended admission");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        require(TurboismAgent.class.getClassLoader() == null, "genuine canonical premain");
        String mode = args[0];
        TriangulationDefinitionLifecycle owner = lifecycle();
        require(owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN"), "actual ownership supported");
        fingerprint = Class.forName("dev.turboism.adapter.cubism.mesh.TriangulationDefinitionFingerprint")
                .getDeclaredMethod("runtimeOf", byte[].class); fingerprint.setAccessible(true);
        Class<?> result = Class.forName(LazyTriangulationEdgeBridge.class.getName() + "$Admission");
        admission = result.getDeclaredConstructor(TriangulationDefinitionLifecycle.Gate.class, boolean.class);
        admission.setAccessible(true);
        Map<String, byte[]> definitions = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String name : new String[] {H, TL, MESH}) {
            byte[] raw = definition(name); definitions.put(name, raw); expected.put(name, fingerprint(raw));
        }
        Loader loader = new Loader(SharedMeshPremainSelfCheck.class.getClassLoader(), definitions);
        AtomicInteger calls = new AtomicInteger(), captures = new AtomicInteger();
        Factory factory = new Factory(owner, Map.copyOf(expected), mode, calls, captures);
        Method register = LazyTriangulationEdgeBridge.class.getDeclaredMethod("registerShared", ClassLoader.class, Function.class);
        register.setAccessible(true);
        require((boolean) register.invoke(null, loader, factory), "shared registration accepted");
        require(!(boolean) register.invoke(null, loader, factory), "duplicate registration refused");
        Class<?> host = loader.loadClass(H), list = loader.loadClass(TL), mesh = loader.loadClass(MESH);
        if (mode.equals("failure")) {
            require(LazyTriangulationEdgeBridge.enterMesh(mesh) == null && LazyTriangulationEdgeBridge.enter(host) == null,
                    "factory failure reaches native fallback");
        } else if (mode.equals("race")) {
            var executor = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                    start.await();
                    try (AutoCloseable lease = LazyTriangulationEdgeBridge.enterMesh(mesh)) { return lease != null; }
                }));
                start.countDown();
                for (var future : futures) require(future.get(30, TimeUnit.SECONDS), "concurrent entry admitted");
            } finally { executor.shutdownNow(); }
            checkEntries(host, list, mesh, true);
        } else {
            boolean extended = !mode.equals("legacy") && !mode.equals("fallback") && !mode.equals("missing-mesh");
            if (mode.equals("mesh-first")) try (AutoCloseable first = LazyTriangulationEdgeBridge.enterMesh(mesh)) {
                require(first != null, "mesh-first shared capture");
            }
            checkEntries(host, list, mesh, extended);
            checkEntries(host, list, mesh, extended);
            if (mode.equals("split")) {
                Loader child = new Loader(loader, Map.of(MESH, definition(MESH)));
                require(LazyTriangulationEdgeBridge.enterMesh(child.loadClass(MESH)) == null, "split h/mesh loader refuses");
                checkEntries(host, list, mesh, true);
            }
            if (mode.equals("revocation")) {
                owner.instrumentation().addTransformer(new ClassFileTransformer() { }, false);
                require(LazyTriangulationEdgeBridge.enter(host) == null && LazyTriangulationEdgeBridge.enterBuilder(list) == null
                        && LazyTriangulationEdgeBridge.enterMesh(mesh) == null, "owned mutation revokes all shared entries");
            }
        }
        require(calls.get() == 1, "factory called once, no duplicate publication or recapture");
        require(captures.get() == (mode.equals("failure") ? 0 : mode.equals("fallback") ? 2 : 1), "exact capture count");
        require(LazyTriangulationEdgeBridge.enterMesh(null) == null && LazyTriangulationEdgeBridge.enterMesh(String.class) == null,
                "unknown owner declines");
        require(java.awt.GraphicsEnvironment.isHeadless() && java.awt.Frame.getFrames().length == 0, "no Editor");
        System.out.printf("SHARED_MESH_PREMAIN_PASS mode=%s checks=%d factories=%d captures=%d scope=OWNED_SYNTHETIC_DEFINITIONS%n",
                mode, checks, calls.get(), captures.get());
        System.exit(0);
    }
}
