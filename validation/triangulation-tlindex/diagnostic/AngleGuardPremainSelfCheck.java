import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.bootstrap.TurboismAgent;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.agent.shaded.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;

/** Actual sole-premain live dependency controls, without initializing geometry or Editor. */
public final class AngleGuardPremainSelfCheck {
    private static final String P = "com.live2d.graphics3d.editableMesh.triangulation.";
    private AngleGuardPremainSelfCheck() { }

    // Read only the existing owned gateway; never create a new owner or expose a raw handle.
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
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (TurboismAgent.class.getClassLoader() != null) throw new AssertionError("canonical premain absent");
        String control = args[0];
        RuntimeDiagnostics.install((level, component, message, failure) -> System.out.println(message));
        TriangulationDefinitionLifecycle owner = lifecycle();
        if (!control.equals("unsupported")) {
            if (owner == null || !owner.startupReason().equals("SUPPORTED_OWNED_PREMAIN"))
                throw new AssertionError("actual owned gateway absent");
            if (!control.equals("accepted") && !control.equals("revocation")) {
                String target = switch (control) {
                    case "d" -> P + "h";
                    case "angle" -> P + "r";
                    case "math-getter", "math-init", "math-field" -> "com.live2d.util.L";
                    case "vector-getter", "vector-field" -> "com.live2d.graphics3d.type.GVector2";
                    default -> throw new IllegalArgumentException("unreviewed control " + control);
                };
                // Register before h and its verifier dependencies are loaded.
                owner.instrumentation().addTransformer(new ClassFileTransformer() {
                    @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                            ProtectionDomain domain, byte[] bytes) {
                        if (!target.replace('.', '/').equals(name)) return null;
                        ClassWriter writer = new ClassWriter(0);
                        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override public FieldVisitor visitField(int access, String n, String desc,
                                    String sig, Object value) {
                                boolean change = control.equals("math-field") && n.equals("i")
                                        || control.equals("vector-field") && n.equals("x");
                                return super.visitField(change ? access ^ Opcodes.ACC_FINAL : access, n, desc, sig, value);
                            }
                            @Override public MethodVisitor visitMethod(int access, String n, String desc,
                                    String sig, String[] errors) {
                                MethodVisitor output = super.visitMethod(access, n, desc, sig, errors);
                                boolean change = control.equals("d") && n.equals("d") && desc.equals("()V")
                                        || control.equals("angle") && n.equals("a") && desc.endsWith("GVector2;)F")
                                        || control.equals("math-getter") && n.equals("f") && desc.equals("()F")
                                        || control.equals("vector-getter") && n.equals("getX");
                                return new MethodVisitor(Opcodes.ASM9, output) {
                                    @Override public void visitCode() {
                                        super.visitCode(); if (change) super.visitInsn(Opcodes.NOP);
                                    }
                                    @Override public void visitLdcInsn(Object value) {
                                        super.visitLdcInsn(control.equals("math-init") && n.equals("<clinit>")
                                                && value instanceof Float scalar && scalar == 1.0e-6f ? 1.0e-5f : value);
                                    }
                                };
                            }
                        }, 0);
                        return writer.toByteArray();
                    }
                }, true);
            }
        }
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        Class<?> host = Class.forName(P + "h", false, loader);
        host.getDeclaredFields(); host.getDeclaredMethods();
        Class<?> list = Class.forName(P + "TriangleList", false, loader);
        list.getDeclaredFields(); list.getDeclaredMethods();
        boolean expected = control.equals("accepted") || control.equals("revocation");
        try (AutoCloseable first = LazyTriangulationEdgeBridge.enter(host)) {
            if ((first != null) != expected) throw new AssertionError("unexpected h gate " + control);
            try (AutoCloseable nested = LazyTriangulationEdgeBridge.enterBuilder(list)) {
                if ((nested != null) != expected) throw new AssertionError("unexpected shared builder gate " + control);
            }
        }
        try (AutoCloseable lease = LazyTriangulationEdgeBridge.enter(host)) {
            if ((lease != null) != expected) throw new AssertionError("shared gate changed " + control);
        }
        if (control.equals("revocation")) {
            owner.instrumentation().addTransformer(new ClassFileTransformer() { }, false);
            if (LazyTriangulationEdgeBridge.enter(host) != null || LazyTriangulationEdgeBridge.enterBuilder(list) != null)
                throw new AssertionError("shared gate did not revoke");
        }
        if (!java.awt.GraphicsEnvironment.isHeadless() || java.awt.Frame.getFrames().length != 0)
            throw new AssertionError("Editor must be absent");
        System.out.println("ANGLE_GUARD_PREMAIN_CONTROL_PASS control=" + control);
        System.exit(0);
    }
}
