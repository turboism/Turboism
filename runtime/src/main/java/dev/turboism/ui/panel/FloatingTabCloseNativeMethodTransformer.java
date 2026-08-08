package dev.turboism.ui.panel;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/**
 * Exact-selector transformer that intercepts the floating-tab close callback
 * ({@code tab.d#a}) before the host removes the palette. When the bridge returns
 * {@code true}, the native close is cancelled and the palette is docked instead.
 */
public final class FloatingTabCloseNativeMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/turboism/ui/panel/NativeFloatingTabCloseBridge";

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final String paletteFieldName;
    private final String paletteFieldDescriptor;
    private final ClassLoader expectedClassLoader;

    public FloatingTabCloseNativeMethodTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final String paletteFieldName,
        final String paletteFieldDescriptor,
        final ClassLoader expectedClassLoader
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        this.paletteFieldName = requireText(paletteFieldName, "paletteFieldName");
        this.paletteFieldDescriptor = requireText(paletteFieldDescriptor, "paletteFieldDescriptor");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
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
        final int[] exits = {0};
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
                    public void visitCode() {
                        super.visitCode();
                        final Label continueOriginal = new Label();
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
                            "beforeClose",
                            "(Ljava/lang/Object;)Z",
                            false
                        );
                        super.visitJumpInsn(Opcodes.IFEQ, continueOriginal);
                        super.visitInsn(Opcodes.RETURN);
                        super.visitLabel(continueOriginal);
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            exits[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return exits[0] == 1 ? writer.toByteArray() : null;
    }

    private static String requireText(final String value, final String name) {
        final String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
