package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.adapter.cubism.optimization.HostClassWriter;
import dev.turboism.internal.Requirements;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact-selector ASM transformer for the canonical Editor parameter-palette operation. */
public final class ParameterNativeMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/turboism/adapter/cubism/lifecycle/NativeParameterLifecycleBridge";

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final String sourceOwner;
    private final String sourceIdMethod;
    private final String sourceIdDescriptor;
    private final String idOwner;
    private final String idValueMethod;
    private final String idValueDescriptor;

    public ParameterNativeMethodTransformer(
            final String ownerInternalName, final String methodName, final String descriptor) {
        this(
                ownerInternalName,
                methodName,
                descriptor,
                null,
                "com/live2d/cubism/doc/model/param/CParameterSource",
                "getId",
                "()Lcom/live2d/cubism/doc/model/id/CParameterId;",
                "com/live2d/core/id/Id",
                "getIdString",
                "()Ljava/lang/String;");
    }

    public ParameterNativeMethodTransformer(
            final String ownerInternalName,
            final String methodName,
            final String descriptor,
            final ClassLoader expectedClassLoader) {
        this(
                ownerInternalName,
                methodName,
                descriptor,
                expectedClassLoader,
                "com/live2d/cubism/doc/model/param/CParameterSource",
                "getId",
                "()Lcom/live2d/cubism/doc/model/id/CParameterId;",
                "com/live2d/core/id/Id",
                "getIdString",
                "()Ljava/lang/String;");
    }

    public ParameterNativeMethodTransformer(
            final String ownerInternalName,
            final String methodName,
            final String descriptor,
            final ClassLoader expectedClassLoader,
            final String sourceOwner,
            final String sourceIdMethod,
            final String sourceIdDescriptor,
            final String idOwner,
            final String idValueMethod,
            final String idValueDescriptor) {
        this.ownerInternalName = Requirements.text(ownerInternalName, "ownerInternalName");
        this.methodName = Requirements.text(methodName, "methodName");
        this.descriptor = Requirements.text(descriptor, "descriptor");
        this.expectedClassLoader = expectedClassLoader;
        this.sourceOwner = Requirements.text(sourceOwner, "sourceOwner");
        this.sourceIdMethod = Requirements.text(sourceIdMethod, "sourceIdMethod");
        this.sourceIdDescriptor = Requirements.text(sourceIdDescriptor, "sourceIdDescriptor");
        this.idOwner = Requirements.text(idOwner, "idOwner");
        this.idValueMethod = Requirements.text(idValueMethod, "idValueMethod");
        this.idValueDescriptor = Requirements.text(idValueDescriptor, "idValueDescriptor");
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (!ownerInternalName.equals(className)
                || classfileBuffer == null
                || (expectedClassLoader != null && loader != expectedClassLoader)) {
            return null;
        }
        final boolean[] transformed = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer =
                new HostClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String methodDescriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor delegate =
                                super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                        if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
                            return delegate;
                        }
                        transformed[0] = true;
                        return instrument(delegate);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }

    private MethodVisitor instrument(final MethodVisitor delegate) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private final Label start = new Label();
            private final Label end = new Label();
            private final Label handler = new Label();

            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, sourceOwner, sourceIdMethod, sourceIdDescriptor, false);
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, idOwner, idValueMethod, idValueDescriptor, false);
                visitVarInsn(Opcodes.FLOAD, 2);
                visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "beforeNative", "(Ljava/lang/String;F)F", false);
                visitVarInsn(Opcodes.FSTORE, 2);
                visitLabel(start);
            }

            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN) {
                    visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "afterNative", "()V", false);
                }
                super.visitInsn(opcode);
            }

            @Override
            public void visitMaxs(final int maxStack, final int maxLocals) {
                visitLabel(end);
                visitTryCatchBlock(start, end, handler, null);
                visitLabel(handler);
                visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "failedNative", "()V", false);
                visitInsn(Opcodes.ATHROW);
                super.visitMaxs(maxStack, maxLocals);
            }
        };
    }
}
