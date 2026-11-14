package dev.turboism.ui.appearance.control;

import dev.turboism.internal.Requirements;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact-selector transformer for native Part-tree renderer returns. */
public final class PartTreeRendererMethodTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/ui/appearance/control/NativePartTreeAppearanceBridge";
    private final String owner;
    private final String method;
    private final String descriptor;
    private final ClassLoader loader;

    public PartTreeRendererMethodTransformer(
            final String owner, final String method, final String descriptor, final ClassLoader loader) {
        this.owner = Requirements.text(owner, "owner");
        this.method = Requirements.text(method, "method");
        this.descriptor = Requirements.text(descriptor, "descriptor");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader candidateLoader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] bytes) {
        if (!owner.equals(className) || candidateLoader != loader || bytes == null) return null;
        final boolean[] changed = {false};
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader == null ? super.getClassLoader() : loader;
            }
        };
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
                        if (!method.equals(name) || !descriptor.equals(methodDescriptor)) return delegate;
                        changed[0] = true;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitInsn(final int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    visitVarInsn(Opcodes.ALOAD, 2);
                                    visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            BRIDGE,
                                            "afterRender",
                                            "(Ljava/awt/Component;Ljava/lang/Object;)Ljava/awt/Component;",
                                            false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return changed[0] ? writer.toByteArray() : null;
    }
}
