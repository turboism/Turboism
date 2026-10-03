import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.bootstrap.TurboismAgent;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.agent.shaded.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;

/** Offline genuine single-premain gates; no Editor or official class initialization. */
public final class LocalBuilderPremainSelfCheck {
    private static final String P = "com.live2d.graphics3d.editableMesh.triangulation.";
    private LocalBuilderPremainSelfCheck() {}
    private static TriangulationDefinitionLifecycle lifecycle() throws Exception {
        Class<?> shims = Class.forName("dev.turboism.bootstrap.JvmShims");
        for (String name : new String[] {"STARTUP_SUPPRESSION", "PIPE_IMPL_SHIM"}) {
            var holder = shims.getDeclaredField(name); holder.setAccessible(true);
            Object installation = ((AtomicReference<?>) holder.get(null)).get();
            if (installation == null) continue;
            var field = installation.getClass().getDeclaredField("instrumentation"); field.setAccessible(true);
            Instrumentation gateway = (Instrumentation) field.get(installation);
            if (gateway == null) continue;
            TriangulationDefinitionLifecycle owner = TriangulationDefinitionLifecycle.ownedBy(gateway);
            if (owner != null) return owner;
        }
        throw new AssertionError("owned preparation absent");
    }
    public static void main(String[] args) throws Exception {
        String control = args[0];
        if (TurboismAgent.class.getClassLoader() != null) throw new AssertionError("canonical premain absent");
        RuntimeDiagnostics.install((level, component, message, failure) -> System.out.println(message));
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> h = Class.forName(P + "h", false, loader);
        TriangulationDefinitionLifecycle owner = lifecycle();
        if (!owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN")) throw new AssertionError("ownership absent");
        if (!control.equals("accepted") && !control.equals("revocation")) {
            String target = (P + (control.equals("k") ? "k" : "TriangleList")).replace('.', '/');
            owner.instrumentation().addTransformer(new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader ignored, String name, Class<?> type,
                        ProtectionDomain domain, byte[] bytes) {
                    if (!target.equals(name)) return null;
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public FieldVisitor visitField(int access, String n, String desc,
                                String sig, Object value) {
                            return super.visitField(control.equals("field") && n.equals("b")
                                    ? access ^ Opcodes.ACC_FINAL : access, n, desc, sig, value);
                        }
                        @Override public MethodVisitor visitMethod(int access, String n, String desc,
                                String sig, String[] exceptions) {
                            MethodVisitor visitor = super.visitMethod(access, n, desc, sig, exceptions);
                            boolean change = control.equals("b") ? n.equals("b") && desc.startsWith("()L")
                                    : control.equals("k") && n.equals("a") && desc.endsWith(";Z)Z");
                            if (!change) return visitor;
                            return new MethodVisitor(Opcodes.ASM9, visitor) {
                                @Override public void visitCode() { super.visitCode(); super.visitInsn(Opcodes.NOP); }
                            };
                        }
                    }, 0);
                    return writer.toByteArray();
                }
            }, true);
        }
        h.getDeclaredMethods(); h.getDeclaredFields();
        Class<?> list = Class.forName(P + "TriangleList", false, loader);
        list.getDeclaredMethods(); list.getDeclaredFields();
        boolean expected = control.equals("accepted") || control.equals("revocation");
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(h)) {
            if ((lease != null) != expected) throw new AssertionError("unexpected h admission " + control);
        }
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enterBuilder(list)) {
            if ((lease != null) != expected) throw new AssertionError("unexpected builder admission " + control);
        }
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(h)) {
            if ((lease != null) != expected) throw new AssertionError("builder revoked gate " + control);
        }
        if (control.equals("revocation")) {
            owner.instrumentation().addTransformer(new ClassFileTransformer() {}, false);
            if (LazyTriangulationEdgeBridge.enter(h) != null || LazyTriangulationEdgeBridge.enterBuilder(list) != null)
                throw new AssertionError("owned mutation did not revoke both entries");
        }
        if (!java.awt.GraphicsEnvironment.isHeadless() || java.awt.Frame.getFrames().length != 0)
            throw new AssertionError("Editor must be absent");
        System.out.println("LOCAL_BUILDER_PREMAIN_CONTROL_PASS control=" + control);
        System.exit(0);
    }
}
