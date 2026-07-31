package dev.turboism.ui.context;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Injects one exact Q-menu show callback before normal return. */
public final class ParameterPointContextMenuNativeMethodTransformer implements ClassFileTransformer {

    private final String owner;
    private final String method;
    private final String descriptor;
    private final String menuGetter;
    private final ClassLoader loader;

    public ParameterPointContextMenuNativeMethodTransformer(
        final String owner,
        final String method,
        final String descriptor,
        final String menuGetter,
        final ClassLoader loader
    ) {
        this.owner = requireText(owner, "owner");
        this.method = requireText(method, "method");
        this.descriptor = requireText(descriptor, "descriptor");
        this.menuGetter = requireText(menuGetter, "menuGetter");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader actualLoader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] bytes
    ) {
        if (!owner.equals(className) || actualLoader != loader || bytes == null) return null;
        final int[] matches = {0};
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access, final String name, final String methodDescriptor,
                final String signature, final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                if ((access & Opcodes.ACC_STATIC) != 0 || !method.equals(name) || !descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, menuGetter,
                                "()Lcom/live2d/ui/menu/k;", false);
                            super.visitInsn(Opcodes.ACONST_NULL);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/turboism/ui/context/NativeParameterPointContextMenuBridge",
                                "shown",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                false
                            );
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return matches[0] == 1 ? writer.toByteArray() : null;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
