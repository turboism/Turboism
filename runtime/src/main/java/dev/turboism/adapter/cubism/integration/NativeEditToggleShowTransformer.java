package dev.turboism.adapter.cubism.integration;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Injects a {@link Runnable} ingress call at every {@code RETURN} of the host dialog's
 * private build/reuse method {@code com.live2d.cubism.doc.webSocket.y.b(owner)} (spec 051,
 * Phase-2 seam).
 *
 * <p>The seam is the return of {@code y.b}, not the {@code y.a} show entry: javap evidence
 * shows {@code y.a} ends in {@code aa.a(...)} → {@code JDialog.setVisible(true)} on a
 * non-MODELESS dialog, which blocks the calling thread until the dialog closes. {@code y.b}
 * runs on the EDT immediately before that blocking show on every open path — first build,
 * same-owner reuse, and owner-switch rebuild — so the epilogue is where
 * {@link NativeEditToggleInjector#ensureInjected()} can (re)install the edit checkbox in time
 * for it to be visible when the dialog appears.</p>
 *
 * <p>The epilogue reads a {@link Runnable} from {@code System.getProperties()} under a fixed
 * key and runs it, wrapped in a {@code Throwable} catch: a failing or absent ingress can
 * never disturb the native dialog build. The injected code links against JDK types only, so
 * the transformed host class never references a Turboism type.</p>
 *
 * <p>Matching is exact: one owner internal name, one method name, one descriptor
 * ({@code (Lcom/live2d/ui/window/X;)V} on 5.2.03, {@code (Lcom/live2d/ui/window/V;)V} on
 * 5.3.x), optional single class loader, and the reviewed access-flag mask (private final;
 * a static or public replacement is refused). Anything else is returned untransformed.</p>
 */
public final class NativeEditToggleShowTransformer implements ClassFileTransformer {

    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** The target class has not been defined yet. */
        NONE,
        /** The exact method was found with the required shape and was patched. */
        PATCHED,
        /**
         * The owner matched but the method was absent, duplicated, or carried flags outside
         * the reviewed mask; the class was returned untouched.
         */
        SHAPE_REJECTED
    }

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final int requiredAccess;
    private final int forbiddenAccess;
    private final ClassLoader expectedClassLoader;
    private final String ingressKey;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    /**
     * Creates a transformer for the exact dialog build method.
     *
     * @param ownerInternalName JVM internal name of the declaring class
     * @param methodName        the exact method name
     * @param descriptor        the exact method descriptor; must be {@code (L<ref>;)V}
     * @param expectedClassLoader the only loader whose classes are transformed; null matches any
     * @param ingressKey        system-property key holding the {@link Runnable} ingress
     */
    public NativeEditToggleShowTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String ingressKey
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        if (!descriptor.startsWith("(L") || !descriptor.endsWith(";)V")) {
            throw new IllegalArgumentException(
                "descriptor must be (L<ref>;)V: " + descriptor);
        }
        // The build method is private final (0x0012); a static or public replacement is not
        // the reviewed shape and must be refused rather than patched.
        this.requiredAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
        this.forbiddenAccess = Opcodes.ACC_STATIC;
        this.expectedClassLoader = expectedClassLoader;
        this.ingressKey = requireText(ingressKey, "ingressKey");
    }

    /** Latest observed outcome; {@code NONE} until the owner class has been offered. */
    public Outcome outcome() {
        return outcome.get();
    }

    /** Human-readable detail for a non-clean outcome, or the empty string. */
    public String diagnostic() {
        return diagnostic.get();
    }

    @Override
    public byte[] transform(
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain,
            classfileBuffer);
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) {
        if (!ownerInternalName.equals(className)
            || classfileBuffer == null
            || (expectedClassLoader != null && loader != expectedClassLoader)) {
            return null;
        }
        try {
            return rewrite(classfileBuffer);
        } catch (Throwable failure) {
            outcome.set(Outcome.SHAPE_REJECTED);
            diagnostic.compareAndSet("", "transform failed: " + failure);
            return null;
        }
    }

    private byte[] rewrite(final byte[] classfileBuffer) {
        final int[] matches = {0};
        final boolean[] patched = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(
            reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String methodDescriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(
                    access, name, methodDescriptor, signature, exceptions);
                if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                matches[0]++;
                if ((access & requiredAccess) != requiredAccess
                    || (access & forbiddenAccess) != 0) {
                    return delegate;
                }
                patched[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            injectIngress(mv);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (matches[0] != 1 || !patched[0]) {
            outcome.set(Outcome.SHAPE_REJECTED);
            diagnostic.compareAndSet("",
                ownerInternalName + "." + methodName + descriptor
                    + " matches=" + matches[0] + " patched=" + patched[0]);
            return null;
        }
        outcome.set(Outcome.PATCHED);
        return writer.toByteArray();
    }

    /**
     * Emits the guarded ingress call before a {@code RETURN}:
     *
     * <pre>
     * try {
     *   Object r = System.getProperties().get(key);
     *   if (r instanceof Runnable) ((Runnable) r).run();
     * } catch (Throwable ignored) { }
     * </pre>
     */
    private void injectIngress(final MethodVisitor mv) {
        final Label start = new Label();
        final Label end = new Label();
        final Label handler = new Label();
        final Label ingress = new Label();
        final Label done = new Label();

        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        mv.visitLabel(start);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System",
            "getProperties", "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(ingressKey);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties",
            "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Runnable");
        mv.visitJumpInsn(Opcodes.IFNE, ingress);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(ingress);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Runnable");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable",
            "run", "()V", true);
        mv.visitLabel(end);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(handler);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(done);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
