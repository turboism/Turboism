package dev.turboism.adapter.cubism.textureatlas;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Routes one exact native automatic-layout entry through a loader-neutral BooleanSupplier. */
public final class TextureAtlasAutoLayoutTransformer implements ClassFileTransformer {

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final String callbackKey;

    public TextureAtlasAutoLayoutTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String callbackKey
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
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
                        final Label callback = new Label();
                        final Label nativePath = new Label();
                        final Label callbackStart = new Label();
                        final Label callbackEnd = new Label();
                        final Label callbackFailure = new Label();
                        visitTryCatchBlock(
                            callbackStart,
                            callbackEnd,
                            callbackFailure,
                            "java/lang/Throwable"
                        );
                        visitLabel(callbackStart);
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
                        visitTypeInsn(Opcodes.INSTANCEOF, "java/util/function/BooleanSupplier");
                        visitJumpInsn(Opcodes.IFNE, callback);
                        visitInsn(Opcodes.POP);
                        visitJumpInsn(Opcodes.GOTO, callbackEnd);
                        visitLabel(callback);
                        visitTypeInsn(Opcodes.CHECKCAST, "java/util/function/BooleanSupplier");
                        visitMethodInsn(
                            Opcodes.INVOKEINTERFACE,
                            "java/util/function/BooleanSupplier",
                            "getAsBoolean",
                            "()Z",
                            true
                        );
                        visitJumpInsn(Opcodes.IFEQ, callbackEnd);
                        visitInsn(Opcodes.RETURN);
                        visitLabel(callbackEnd);
                        visitJumpInsn(Opcodes.GOTO, nativePath);
                        visitLabel(callbackFailure);
                        visitInsn(Opcodes.POP);
                        visitLabel(nativePath);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
