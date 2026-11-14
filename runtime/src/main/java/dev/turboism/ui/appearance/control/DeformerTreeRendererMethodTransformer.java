package dev.turboism.ui.appearance.control;

import dev.turboism.adapter.cubism.optimization.HostClassWriter;
import dev.turboism.internal.Requirements;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact-selector transformer for the native deformer tree renderer return path. */
public final class DeformerTreeRendererMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/turboism/ui/appearance/control/NativeDeformerTreeAppearanceBridge";

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;

    public DeformerTreeRendererMethodTransformer(
            final String ownerInternalName,
            final String methodName,
            final String descriptor,
            final ClassLoader expectedClassLoader) {
        this.ownerInternalName = Requirements.text(ownerInternalName, "ownerInternalName");
        this.methodName = Requirements.text(methodName, "methodName");
        this.descriptor = Requirements.text(descriptor, "descriptor");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (!ownerInternalName.equals(className) || loader != expectedClassLoader || classfileBuffer == null) {
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
                        if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) return delegate;
                        transformed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitInsn(final int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    visitVarInsn(Opcodes.ALOAD, 2);
                                    visitVarInsn(Opcodes.ILOAD, 3);
                                    visitVarInsn(Opcodes.ILOAD, 7);
                                    visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            BRIDGE,
                                            "afterRender",
                                            "(Ljava/awt/Component;Ljava/lang/Object;ZZ)Ljava/awt/Component;",
                                            false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }
}
