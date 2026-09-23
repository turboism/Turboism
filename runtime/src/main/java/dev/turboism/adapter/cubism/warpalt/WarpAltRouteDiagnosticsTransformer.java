package dev.turboism.adapter.cubism.warpalt;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Route-diagnostics transformer for the reviewed 5.3.03 modeling selection action.
 *
 * <p>Instruments the bbox/selection action heads (press hit-test, drag dispatch,
 * selected-point move) and the doc-level warp point write, so a host-validation
 * window shows exactly where an Alt+drag disappears. Diagnostics only: the injected
 * calls never alter arguments or control flow.</p>
 */
public final class WarpAltRouteDiagnosticsTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/adapter/cubism/warpalt/NativeWarpAltMirrorBridge";

    /** Exact selectors recovered by disassembly of the reviewed 5.3.03 artifact. */
    public record RouteTarget(String owner, String method, String descriptor, String stage) { }

    private static final Set<RouteTarget> TARGETS = Set.of(
        new RouteTarget(
            "com/live2d/cubism/view/context/action/h$a", "a",
            "(Lcom/live2d/cubism/view/context/actionManager/N;)Lcom/live2d/b/a/a;",
            "PRESS_HIT"),
        new RouteTarget(
            "com/live2d/cubism/view/context/action/h$a", "b",
            "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
            "DRAG_DISPATCH"),
        new RouteTarget(
            "com/live2d/cubism/view/context/action/h$a", "a",
            "(Lcom/live2d/cubism/view/context/actionManager/aG;)V",
            "MOVE_SELECTED"),
        new RouteTarget(
            "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef", "moveToOnLocal",
            "(Lcom/live2d/graphics3d/type/GVector2;F)V",
            "POINT_MOVE"),
        new RouteTarget(
            "com/live2d/cubism/view/context/action/U$a", "a",
            "(Lcom/live2d/cubism/view/context/actionManager/N;"
                + "Lcom/live2d/cubism/view/context/action/Z;Z)"
                + "Lcom/live2d/cubism/view/context/actionManager/a;",
            "UB_FACTORY"),
        new RouteTarget(
            "com/live2d/cubism/view/context/action/U$b", "b",
            "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
            "UB_PRESS"),
        new RouteTarget(
            "com/live2d/cubism/view/context/action/U$b", "c",
            "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
            "UB_RELEASE"),
        new RouteTarget(
            "com/live2d/cubism/view/context/action/U$b", "a",
            "(Lcom/live2d/cubism/view/context/actionManager/aG;"
                + "Lcom/live2d/doc/selection/m;)V",
            "UB_MOVE"),
        new RouteTarget(
            "com/live2d/cubism/view/context/action/U$b", "a",
            "(Lcom/live2d/cubism/view/context/actionManager/aG;)V",
            "UB_MOVE_LOOP")
    );

    private final AtomicReference<String> admittedOwner = new AtomicReference<>();

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final java.security.ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) {
        if (className == null || classfileBuffer == null || classBeingRedefined != null) {
            return null;
        }
        final boolean isInstrumentedClass = className.equals(
            "com/live2d/cubism/view/context/action/h$a")
            || className.equals("com/live2d/cubism/view/context/action/U$a")
            || className.equals("com/live2d/cubism/view/context/action/U$b")
            || className.equals("com/live2d/cubism/doc/model/deformer/warp/WarpPointRef");
        if (!isInstrumentedClass) return null;

        try {
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(
                    final int access,
                    final String name,
                    final String descriptor,
                    final String signature,
                    final String[] exceptions
                ) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    for (final RouteTarget target : TARGETS) {
                        if (!target.owner().equals(className)
                            || !target.method().equals(name)
                            || !target.descriptor().equals(descriptor)) {
                            continue;
                        }
                        final boolean passStageAndEvent = !target.stage().equals("POINT_MOVE");
                        final MethodVisitor previous = delegate;
                        delegate = new MethodVisitor(Opcodes.ASM9, previous) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                visitLdcInsn(target.stage());
                                visitVarInsn(Opcodes.ALOAD, 0);
                                if (passStageAndEvent) {
                                    visitVarInsn(Opcodes.ALOAD, 1);
                                }
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    passStageAndEvent ? "diagRoute" : "diagPointMove",
                                    passStageAndEvent
                                        ? "(Ljava/lang/Object;Ljava/lang/Object;)V"
                                        : "(Ljava/lang/Object;)V",
                                    false
                                );
                            }
                        };
                        admittedOwner.compareAndSet(null, className);
                    }
                    return delegate;
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable failure) {
            failure.printStackTrace();
            return null;
        }
    }

    /** @return the owner that this transformer actually instrumented, or null. */
    public String admittedOwner() {
        return admittedOwner.get();
    }

}
