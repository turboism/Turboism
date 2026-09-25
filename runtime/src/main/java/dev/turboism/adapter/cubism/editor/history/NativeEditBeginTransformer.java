package dev.turboism.adapter.cubism.editor.history;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reports the entry of one exact native {@code beginEdit} method to a loader-neutral receiver.
 *
 * <p>The transformation adds a single call at method entry and changes nothing else: the native
 * body, its parameters, its return value and its exception behaviour are untouched. The receiver
 * is read from a system property at call time and invoked only when it is a {@link Consumer}; the
 * whole call sits inside a {@code try}/{@code catch Throwable} whose handler discards the failure,
 * so no injection fault can reach the host's edit entry.</p>
 *
 * <p>Only the exact owner, name and descriptor are matched. A class with the same name from another
 * loader, or a method with the same name and a different descriptor, is left alone.</p>
 */
public final class NativeEditBeginTransformer implements ClassFileTransformer {

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final String callbackKey;

    /**
     * Creates a transformer for one exact method.
     *
     * @param ownerInternalName JVM internal name of the declaring class to transform
     * @param methodName        the exact method name
     * @param descriptor        the exact method descriptor; its first argument must be a String
     * @param expectedClassLoader the only loader whose classes are transformed; null matches any
     * @param callbackKey       system-property key holding the {@link Consumer} receiver
     */
    public NativeEditBeginTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String callbackKey
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        if (!descriptor.startsWith("(Ljava/lang/String;)")) {
            throw new IllegalArgumentException("descriptor must take the edit name as its first argument");
        }
        this.expectedClassLoader = expectedClassLoader;
        this.callbackKey = requireText(callbackKey, "callbackKey");
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
        final boolean[] transformed = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(
            reader,
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        );
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
                    access, name, methodDescriptor, signature, exceptions
                );
                if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                transformed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // The receiver is a static property so the host class needs no reference
                        // to any Turboism type: the transformation links against the JDK only.
                        final Label receiver = new Label();
                        final Label done = new Label();
                        final Label start = new Label();
                        final Label end = new Label();
                        final Label failure = new Label();
                        visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
                        visitLabel(start);
                        visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperties",
                            "()Ljava/util/Properties;",
                            false
                        );
                        visitLdcInsn(callbackKey);
                        visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "java/util/Properties",
                            "get",
                            "(Ljava/lang/Object;)Ljava/lang/Object;",
                            false
                        );
                        visitInsn(Opcodes.DUP);
                        visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/Consumer");
                        visitJumpInsn(Opcodes.IFNE, receiver);
                        visitInsn(Opcodes.POP);
                        visitJumpInsn(Opcodes.GOTO, end);
                        visitLabel(receiver);
                        visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/Consumer");
                        // Slot 1 is the declared edit name; slot 0 is the receiver instance.
                        visitVarInsn(Opcodes.ALOAD, 1);
                        visitMethodInsn(
                            Opcodes.INVOKEINTERFACE,
                            "java/util/function/Consumer",
                            "accept",
                            "(Ljava/lang/Object;)V",
                            true
                        );
                        visitLabel(end);
                        visitJumpInsn(Opcodes.GOTO, done);
                        visitLabel(failure);
                        visitInsn(Opcodes.POP);
                        visitLabel(done);
                    }

                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        super.visitMaxs(maxStack + 3, maxLocals);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
