package dev.turboism.adapter.cubism.mesh;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;

/** Exact-selector transformer for the recovered mesh mirror-axis operations. */
public final class MeshMirrorNativeMethodTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/adapter/cubism/mesh/NativeMeshMirrorBridge";

    private final MeshMirrorHostProfile profile;
    private final ClassLoader expectedClassLoader;

    public MeshMirrorNativeMethodTransformer(
        final MeshMirrorHostProfile profile,
        final ClassLoader expectedClassLoader
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.expectedClassLoader = expectedClassLoader;
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
        if (classfileBuffer == null
            || (expectedClassLoader != null && loader != expectedClassLoader)
            || !isTargetOwner(className)) return null;

        final boolean[] transformed = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                try {
                    final ClassLoader classLoader = expectedClassLoader == null
                        ? MeshMirrorNativeMethodTransformer.class.getClassLoader()
                        : expectedClassLoader;
                    final Class<?> leftType = Class.forName(left.replace('/', '.'), false, classLoader);
                    final Class<?> rightType = Class.forName(right.replace('/', '.'), false, classLoader);
                    if (leftType.isAssignableFrom(rightType)) return left;
                    if (rightType.isAssignableFrom(leftType)) return right;
                    if (leftType.isInterface() || rightType.isInterface()) return "java/lang/Object";
                    Class<?> current = leftType;
                    do current = current.getSuperclass(); while (!current.isAssignableFrom(rightType));
                    return current.getName().replace('.', '/');
                } catch (Throwable ignored) {
                    return "java/lang/Object";
                }
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                final Kind kind = kind(className, name, descriptor);
                if (kind == null) return delegate;
                transformed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if (kind == Kind.DRAW) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.FLOAD, 1);
                            visitVarInsn(Opcodes.ILOAD, 2);
                            visitVarInsn(Opcodes.FLOAD, 3);
                            visitVarInsn(Opcodes.ALOAD, 4);
                            visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "drawAxis", "(Ljava/lang/Object;FZFLjava/lang/Object;)Z", false);
                            final org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
                            visitJumpInsn(Opcodes.IFEQ, continueLabel);
                            visitInsn(Opcodes.RETURN);
                            visitLabel(continueLabel);
                        }
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (kind == Kind.POINT && opcode == Opcodes.ARETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "adjustPoint", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(descriptor).getInternalName());
                        } else if (kind == Kind.AXIS_POINT && opcode == Opcodes.ARETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "adjustAxisPoint", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(descriptor).getInternalName());
                        } else if (kind == Kind.HIT && opcode == Opcodes.IRETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitVarInsn(Opcodes.FLOAD, 2);
                            visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "adjustHit", "(ZLjava/lang/Object;Ljava/lang/Object;F)Z", false);
                        } else if (kind == Kind.WIDGET && opcode == Opcodes.ARETURN) {
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "attachControl", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(descriptor).getInternalName());
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return transformed[0] ? writer.toByteArray() : null;
    }

    private Kind kind(final String owner, final String name, final String descriptor) {
        if (owner.equals(profile.meshEditorOwner())) {
            if (name.equals(profile.mirrorPointMethod()) && descriptor.equals(profile.mirrorPointDescriptor())) return Kind.POINT;
            if (name.equals(profile.mirrorAxisPointMethod()) && descriptor.equals(profile.mirrorPointDescriptor())) return Kind.AXIS_POINT;
            if (name.equals(profile.mirrorHitMethod()) && descriptor.equals(profile.mirrorHitDescriptor())) return Kind.HIT;
        }
        if (owner.equals(profile.mirrorWidgetOwner())
            && name.equals(profile.mirrorWidgetMethod())
            && descriptor.equals(profile.mirrorWidgetDescriptor())) return Kind.WIDGET;
        if (owner.equals(profile.mirrorAxisDrawOwner())
            && name.equals(profile.mirrorAxisDrawMethod())
            && descriptor.equals(profile.mirrorAxisDrawDescriptor())) return Kind.DRAW;
        return null;
    }

    private boolean isTargetOwner(final String owner) {
        return profile.meshEditorOwner().equals(owner)
            || profile.mirrorWidgetOwner().equals(owner)
            || profile.mirrorAxisDrawOwner().equals(owner);
    }

    private enum Kind { POINT, AXIS_POINT, HIT, WIDGET, DRAW }
}
