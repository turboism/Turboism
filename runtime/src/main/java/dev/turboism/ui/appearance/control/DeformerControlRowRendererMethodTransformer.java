package dev.turboism.ui.appearance.control;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Hooks the exact deformer table-cell renderer return. */
public final class DeformerControlRowRendererMethodTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/ui/appearance/control/NativeDeformerControlRowAppearanceBridge";
    private final String owner;
    private final String method;
    private final String descriptor;
    private final ClassLoader loader;

    public DeformerControlRowRendererMethodTransformer(
        final String owner, final String method, final String descriptor, final ClassLoader loader
    ) {
        this.owner = requireText(owner, "owner");
        this.method = requireText(method, "method");
        this.descriptor = requireText(descriptor, "descriptor");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public byte[] transform(Module module, ClassLoader candidateLoader, String className,
        Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] bytes) {
        if (!owner.equals(className) || candidateLoader != loader || bytes == null) return null;
        final boolean[] changed = {false};
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!method.equals(name) || !descriptor.equals(desc)) return delegate;
                changed[0] = true;
                final Type returnType = Type.getReturnType(desc);
                if (!returnType.equals(Type.getType(java.awt.Component.class))) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ILOAD, 5);
                            visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "afterRender",
                                "(Ljava/awt/Component;Ljava/lang/Object;I)Ljava/awt/Component;", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return changed[0] ? writer.toByteArray() : null;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
