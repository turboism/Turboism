package dev.turboism.ui.context;

import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Exact-selector transformer for one object context-menu builder. */
public final class ObjectContextMenuNativeMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE = NativeObjectContextMenuBridge.class.getName().replace('.', '/');

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final Location location;

    public ObjectContextMenuNativeMethodTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final Location location
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
        this.location = Objects.requireNonNull(location, "location");
        final Type method = Type.getMethodType(descriptor);
        if (method.getReturnType().getSort() != Type.OBJECT) {
            throw new IllegalArgumentException("object context-menu operation must return a menu object");
        }
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
        final int[] returnPoints = {0};
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
                if ((access & Opcodes.ACC_STATIC) != 0
                    && methodName.equals(name)
                    && descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                if (!methodName.equals(name) || !descriptor.equals(methodDescriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitLdcInsn(location.name());
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "augment",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                                false
                            );
                            super.visitTypeInsn(
                                Opcodes.CHECKCAST,
                                Type.getReturnType(descriptor).getInternalName()
                            );
                            returnPoints[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return returnPoints[0] == 1 ? writer.toByteArray() : null;
    }


    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
