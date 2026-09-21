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
 * Injects a guard at the entry of the host message dispatcher
 * ({@code com.live2d.cubism.doc.webSocket.l.a(String, WebSocket)}).
 *
 * <p>The prologue reads a {@link java.util.function.BiFunction} receiver from
 * {@code System.getProperties()} under a fixed key, calls it with the raw message and the
 * socket, and returns from the host method when the receiver answers {@link Boolean#TRUE}.
 * Every other outcome — absent receiver, non-{@code BiFunction}, non-{@code Boolean} result,
 * {@code false}, or any {@code Throwable} — falls through to the untouched native body. The
 * injected code links against JDK types only, so the transformed host class never references a
 * Turboism type.</p>
 *
 * <p>Matching is exact: one owner internal name, one method name, one descriptor, optional
 * single class loader, and the reviewed access-flag mask (the dispatch entry is
 * {@code private final}; a static or differently-shaped member is refused). Anything else is
 * returned untransformed.</p>
 */
public final class EditApiDispatchTransformer implements ClassFileTransformer {

    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** The target class has not been defined yet. */
        NONE,
        /** The exact method was found with the required shape and was patched. */
        PATCHED,
        /**
         * The owner matched but the method was absent, duplicated, or carried flags outside the
         * reviewed mask; the class was returned untouched.
         */
        SHAPE_REJECTED
    }

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final int requiredAccess;
    private final int forbiddenAccess;
    private final ClassLoader expectedClassLoader;
    private final String callbackKey;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    /**
     * Creates a transformer for the exact dispatcher entry.
     *
     * @param ownerInternalName JVM internal name of the declaring class
     * @param methodName        the exact method name
     * @param descriptor        the exact method descriptor; must be
     *                          {@code (Ljava/lang/String;L<ref>;)V}
     * @param expectedClassLoader the only loader whose classes are transformed; null matches any
     * @param callbackKey       system-property key holding the {@code BiFunction} receiver
     */
    public EditApiDispatchTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String callbackKey
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        if (!descriptor.startsWith("(Ljava/lang/String;L") || !descriptor.endsWith(";)V")) {
            throw new IllegalArgumentException(
                "descriptor must be (Ljava/lang/String;L<ref>;)V: " + descriptor);
        }
        // The dispatch entry is private final (0x0012); a static or public replacement is not
        // the reviewed shape and must be refused rather than patched.
        this.requiredAccess = Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
        this.forbiddenAccess = Opcodes.ACC_STATIC;
        this.expectedClassLoader = expectedClassLoader;
        this.callbackKey = requireText(callbackKey, "callbackKey");
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
                    public void visitCode() {
                        super.visitCode();
                        injectGuard(mv);
                    }

                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        super.visitMaxs(maxStack + 4, maxLocals);
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
     * Emits the guard prologue into the matched method:
     *
     * <pre>
     * try {
     *   Object r = System.getProperties().get(key);
     *   if (r instanceof BiFunction) {
     *     Object res = ((BiFunction) r).apply(arg1, arg2);
     *     if (res instanceof Boolean &amp;&amp; ((Boolean) res).booleanValue()) return;
     *   }
     * } catch (Throwable ignored) { }
     * </pre>
     */
    private void injectGuard(final MethodVisitor mv) {
        final Label start = new Label();
        final Label end = new Label();
        final Label handler = new Label();
        final Label receiver = new Label();
        final Label notBoolean = new Label();
        final Label done = new Label();

        mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        mv.visitLabel(start);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System",
            "getProperties", "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(callbackKey);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties",
            "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BiFunction");
        mv.visitJumpInsn(Opcodes.IFNE, receiver);
        mv.visitInsn(Opcodes.POP);
        mv.visitJumpInsn(Opcodes.GOTO, end);
        mv.visitLabel(receiver);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BiFunction");
        // Instance method slots: 0=this, 1=raw message, 2=socket.
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction",
            "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Boolean");
        mv.visitJumpInsn(Opcodes.IFEQ, notBoolean);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean",
            "booleanValue", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, end);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(notBoolean);
        mv.visitInsn(Opcodes.POP);
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
