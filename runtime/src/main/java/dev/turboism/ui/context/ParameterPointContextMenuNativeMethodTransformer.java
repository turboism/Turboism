package dev.turboism.ui.context;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
            final ClassLoader loader) {
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
            final byte[] bytes) {
        if (!owner.equals(className) || bytes == null) return null;
        if (actualLoader != loader) {
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                    "context-menu",
                    "Parameter-point menu transform skipped for " + className + " under loader " + actualLoader
                            + " (expected " + loader + ")");
            return null;
        }
        try {
            return transformMatched(className, bytes);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A throwing transformer must fail the weave, not the host class load.
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "context-menu",
                    "Parameter-point menu transform failed for " + className + " ("
                            + failure.getClass().getName() + ": " + failure.getMessage() + ")",
                    null);
            return null;
        }
    }

    private byte[] transformMatched(final String className, final byte[] bytes) {
        final int[] matches = {0};
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                try {
                    final Class<?> leftType = Class.forName(left.replace('/', '.'), false, loader);
                    final Class<?> rightType = Class.forName(right.replace('/', '.'), false, loader);
                    if (leftType.isAssignableFrom(rightType)) return left;
                    if (rightType.isAssignableFrom(leftType)) return right;
                    if (leftType.isInterface() || rightType.isInterface()) {
                        return "java/lang/Object";
                    }
                    Class<?> current = leftType;
                    do {
                        current = current.getSuperclass();
                    } while (!current.isAssignableFrom(rightType));
                    return current.getName().replace('.', '/');
                } catch (Throwable ignored) {
                    FatalErrors.rethrowIfFatal(ignored);
                    return "java/lang/Object";
                }
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
                        if ((access & Opcodes.ACC_STATIC) != 0
                                || !method.equals(name)
                                || !descriptor.equals(methodDescriptor)) {
                            return delegate;
                        }
                        matches[0]++;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitInsn(final int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC, owner, menuGetter, "()Lcom/live2d/ui/menu/k;", false);
                                    super.visitInsn(Opcodes.ACONST_NULL);
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            "dev/turboism/ui/context/NativeParameterPointContextMenuBridge",
                                            "shown",
                                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                            false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (matches[0] == 1) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "context-menu", "Parameter-point menu transform applied to " + className);
            return writer.toByteArray();
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                "context-menu", "Parameter-point menu binding found " + matches[0] + " matches in " + className);
        return null;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
