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

/** Exact-selector transformer for one object context-menu append point. */
public final class ObjectContextMenuAppendNativeMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE = NativeObjectContextMenuBridge.class.getName().replace('.', '/');

    private final String ownerInternalName;
    private final String methodName;
    private final String descriptor;
    private final ClassLoader expectedClassLoader;
    private final String appendOwnerInternalName;
    private final String appendMethodName;
    private final String appendDescriptor;
    private final Location location;
    private final int appendArgumentCount;
    private final int expectedAppendPoints;
    private final int injectionPoint;

    public ObjectContextMenuAppendNativeMethodTransformer(
        final String ownerInternalName,
        final String methodName,
        final String descriptor,
        final ClassLoader expectedClassLoader,
        final String appendOwnerInternalName,
        final String appendMethodName,
        final String appendDescriptor,
        final Location location,
        final int expectedAppendPoints,
        final int injectionPoint
    ) {
        this.ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
        this.methodName = requireText(methodName, "methodName");
        this.descriptor = requireText(descriptor, "descriptor");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
        this.appendOwnerInternalName = requireText(appendOwnerInternalName, "appendOwnerInternalName");
        this.appendMethodName = requireText(appendMethodName, "appendMethodName");
        this.appendDescriptor = requireText(appendDescriptor, "appendDescriptor");
        this.location = Objects.requireNonNull(location, "location");
        if (expectedAppendPoints <= 0 || injectionPoint <= 0 || injectionPoint > expectedAppendPoints) {
            throw new IllegalArgumentException("append cardinality and injection point must be positive and bounded");
        }
        this.expectedAppendPoints = expectedAppendPoints;
        this.injectionPoint = injectionPoint;
        final Type method = Type.getMethodType(descriptor);
        if (method.getReturnType().getSort() != Type.VOID) {
            throw new IllegalArgumentException("object context-menu append operation must return void");
        }
        final Type append = Type.getMethodType(appendDescriptor);
        this.appendArgumentCount = append.getArgumentTypes().length;
        if ((appendArgumentCount != 1 && appendArgumentCount != 2)
            || append.getReturnType().getSort() != Type.VOID
            || java.util.Arrays.stream(append.getArgumentTypes())
                .anyMatch(argument -> argument.getSort() != Type.OBJECT && argument.getSort() != Type.ARRAY)) {
            throw new IllegalArgumentException(
                "object context-menu append selector must accept one or two reference arguments"
            );
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
                    public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String name,
                        final String invokedDescriptor,
                        final boolean isInterface
                    ) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                            && appendOwnerInternalName.equals(owner)
                            && appendMethodName.equals(name)
                            && appendDescriptor.equals(invokedDescriptor)) {
                            appendPoints[0]++;
                            if (appendPoints[0] == injectionPoint) {
                                if (appendArgumentCount == 1) {
                                    // Stack is menu, item. Duplicate the menu below the argument.
                                    super.visitInsn(Opcodes.DUP2);
                                    super.visitInsn(Opcodes.POP);
                                } else {
                                    // Stack is menu, item, constraints. Duplicate the menu below both arguments.
                                    super.visitInsn(Opcodes.DUP2_X1);
                                    super.visitInsn(Opcodes.POP2);
                                    super.visitInsn(Opcodes.DUP_X2);
                                }
                                super.visitLdcInsn(location.name());
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "augment",
                                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                                    false
                                );
                                super.visitInsn(Opcodes.POP);
                            }
                        }
                        super.visitMethodInsn(opcode, owner, name, invokedDescriptor, isInterface);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return appendPoints[0] == expectedAppendPoints ? writer.toByteArray() : null;
    }


    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
