package dev.turboism.ui.panel;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Exact-selector transformer that augments Cubism's native dock-tab popup. */
public final class DockTabPopupNativeMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/turboism/ui/panel/NativeDockTabPopupBridge";

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final String menuOwner;
    private final String menuAppendMethod;
    private final String menuAppendDescriptor;
    private final String paletteFieldName;
    private final String paletteFieldDescriptor;

    public DockTabPopupNativeMethodTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String menuOwner,
        final String menuAppendMethod,
        final String menuAppendDescriptor,
        final String paletteFieldName,
        final String paletteFieldDescriptor
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
        this.menuOwner = requireText(menuOwner, "menuOwner");
        this.menuAppendMethod = requireText(menuAppendMethod, "menuAppendMethod");
        this.menuAppendDescriptor = requireText(menuAppendDescriptor, "menuAppendDescriptor");
        this.paletteFieldName = requireText(paletteFieldName, "paletteFieldName");
        this.paletteFieldDescriptor = requireText(paletteFieldDescriptor, "paletteFieldDescriptor");
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
            || loader != expectedClassLoader
            || classfileBuffer == null) {
            return null;
        }
        final int[] appendPoints = {0};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
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
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(
                        final int opcode,
                        final String invokedOwner,
                        final String invokedMethod,
                        final String invokedDescriptor,
                        final boolean isInterface
                    ) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                            && menuOwner.equals(invokedOwner)
                            && menuAppendMethod.equals(invokedMethod)
                            && menuAppendDescriptor.equals(invokedDescriptor)) {
                            super.visitInsn(Opcodes.DUP2);
                            super.visitMethodInsn(
                                opcode, invokedOwner, invokedMethod, invokedDescriptor, isInterface
                            );
                            super.visitInsn(Opcodes.POP);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(
                                Opcodes.GETFIELD,
                                ownerInternalName,
                                paletteFieldName,
                                paletteFieldDescriptor
                            );
                            super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "afterNativeItemAppended",
                                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                false
                            );
                            appendPoints[0]++;
                            return;
                        }
                        super.visitMethodInsn(
                            opcode, invokedOwner, invokedMethod, invokedDescriptor, isInterface
                        );
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (appendPoints[0] != 1) {
            return null;
        }
        return writer.toByteArray();
    }

    private static String requireText(final String value, final String name) {
        final String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
