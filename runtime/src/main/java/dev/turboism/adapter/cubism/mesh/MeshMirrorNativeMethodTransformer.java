package dev.turboism.adapter.cubism.mesh;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Exact-selector transformer for the recovered mesh mirror-axis operations. */
public final class MeshMirrorNativeMethodTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/adapter/cubism/mesh/NativeMeshMirrorBridge";

    private final MeshMirrorHostProfile profile;
    private final ClassLoader expectedClassLoader;
    private final Path expectedArtifact;
    private final String hostClassName;
    private final Instrumentation helperInstrumentation;
    private final Consumer<String> diagnostic;
    private final AtomicReference<ClassLoader> admittedClassLoader = new AtomicReference<>();
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);

    public MeshMirrorNativeMethodTransformer(
        final MeshMirrorHostProfile profile,
        final ClassLoader expectedClassLoader
    ) {
        this(profile, expectedClassLoader, null, null, null, ignored -> { });
    }

    /** PREMAIN transformer: admission is fixed to the exact artifact; loader is captured from first definition. */
    public MeshMirrorNativeMethodTransformer(
        final MeshMirrorHostProfile profile,
        final ClassLoader expectedClassLoader,
        final Path expectedArtifact,
        final String hostClassName,
        final Instrumentation helperInstrumentation
    ) {
        this(profile, expectedClassLoader, expectedArtifact, hostClassName, helperInstrumentation, ignored -> { });
    }

    public MeshMirrorNativeMethodTransformer(
        final MeshMirrorHostProfile profile,
        final ClassLoader expectedClassLoader,
        final Path expectedArtifact,
        final String hostClassName,
        final Instrumentation helperInstrumentation,
        final Consumer<String> diagnostic
    ) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.expectedClassLoader = expectedClassLoader;
        this.expectedArtifact = expectedArtifact == null
            ? null
            : expectedArtifact.toAbsolutePath().normalize();
        this.hostClassName = hostClassName == null ? null : hostClassName.replace('.', '/');
        this.helperInstrumentation = helperInstrumentation;
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
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
        if (className == null || classfileBuffer == null) return null;
        if (!isTargetOwner(className)) return null;
        if (classBeingRedefined != null) {
            reject(Outcome.RETRANSFORM_REJECTED, "MESH_MIRROR_RETRANSFORM_REJECTED owner=" + className);
            return null;
        }
        if (hostClassName != null && !hostClassName.equals(className)) return null;
        if (!admit(loader, protectionDomain)) return null;

        final boolean[] transformed = {false};
        try {
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = new ClassWriter(
                reader,
                ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES
            ) {
                @Override
                protected String getCommonSuperClass(final String left, final String right) {
                    try {
                        final ClassLoader classLoader = admittedClassLoader.get() == null
                            ? MeshMirrorNativeMethodTransformer.class.getClassLoader()
                            : admittedClassLoader.get();
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
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "drawAxis",
                                    "(Ljava/lang/Object;FZFLjava/lang/Object;)Z",
                                    false
                                );
                                final org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
                                visitJumpInsn(Opcodes.IFEQ, continueLabel);
                                visitInsn(Opcodes.RETURN);
                                visitLabel(continueLabel);
                            }
                        }

                        /**
                         * Linked deletion is injected by intercepting the host's own deletion
                         * call rather than at a fixed offset, so the mirror step always lands
                         * immediately before the host deletes. Operands are duplicated on the
                         * stack; no local variable slots are allocated, so the host method's
                         * own frame layout is untouched.
                         */
                        @Override
                        public void visitMethodInsn(
                            final int callOpcode,
                            final String callOwner,
                            final String callName,
                            final String callDescriptor,
                            final boolean isInterface
                        ) {
                            final MeshMirrorHostProfile.LinkedDeletion linked = profile.linkedDeletion();
                            if (linked == null) {
                                super.visitMethodInsn(callOpcode, callOwner, callName, callDescriptor, isInterface);
                                return;
                            }
                            if (kind == Kind.POINT_DELETE
                                && callOwner.equals(linked.pointDeleteOwner())
                                && callName.equals(linked.pointDeleteMethod())
                                && callDescriptor.equals(linked.pointDeleteDescriptor())) {
                                // stack: editMode, sources, groupUndo
                                visitInsn(Opcodes.DUP2);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "mirrorDeletePoints",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false
                                );
                            } else if (kind == Kind.EDGE_DELETE
                                && callOwner.equals(linked.edgeRemoveOwner())
                                && callName.equals(linked.edgeRemoveMethod())
                                && callDescriptor.equals(linked.edgeRemoveDescriptor())) {
                                // stack: mesh, edge
                                visitInsn(Opcodes.DUP);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "mirrorDeleteEdge",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false
                                );
                            }
                            super.visitMethodInsn(callOpcode, callOwner, callName, callDescriptor, isInterface);
                            if (kind == Kind.EDGE_DELETE
                                && callOwner.equals(linked.edgeUndoOwner())
                                && callName.equals(linked.edgeUndoMethod())
                                && callDescriptor.equals(linked.edgeUndoDescriptor())) {
                                // The edge action keeps its undo group in a local, so capture it
                                // as it is produced. It is consumed at the removeEdge site above,
                                // which runs after the host's own snapshot undo is registered.
                                visitInsn(Opcodes.DUP);
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "rememberEdgeUndoGroup",
                                    "(Ljava/lang/Object;)V",
                                    false
                                );
                            }
                        }

                        @Override
                        public void visitInsn(final int opcode) {
                            if (kind == Kind.POINT && opcode == Opcodes.ARETURN) {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "adjustPoint",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                    false
                                );
                                visitTypeInsn(
                                    Opcodes.CHECKCAST,
                                    Type.getReturnType(descriptor).getInternalName()
                                );
                            } else if (kind == Kind.AXIS_POINT && opcode == Opcodes.ARETURN) {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "adjustAxisPoint",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                    false
                                );
                                visitTypeInsn(
                                    Opcodes.CHECKCAST,
                                    Type.getReturnType(descriptor).getInternalName()
                                );
                            } else if (kind == Kind.HIT && opcode == Opcodes.IRETURN) {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitVarInsn(Opcodes.ALOAD, 1);
                                visitVarInsn(Opcodes.FLOAD, 2);
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "adjustHit",
                                    "(ZLjava/lang/Object;Ljava/lang/Object;F)Z",
                                    false
                                );
                            } else if (kind == Kind.WIDGET && opcode == Opcodes.ARETURN) {
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BRIDGE,
                                    "attachControl",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                    false
                                );
                                visitTypeInsn(
                                    Opcodes.CHECKCAST,
                                    Type.getReturnType(descriptor).getInternalName()
                                );
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            if (!transformed[0]) {
                outcome(Outcome.TARGET_UNCHANGED, "MESH_MIRROR_TARGET_UNCHANGED owner=" + className);
                return null;
            }
            final byte[] transformedBytes = writer.toByteArray();
            outcome(Outcome.TARGET_TRANSFORMED, "MESH_MIRROR_TARGET_TRANSFORMED owner=" + className);
            return transformedBytes;
        } catch (RuntimeException failure) {
            reject(Outcome.TRANSFORMATION_FAILED, "MESH_MIRROR_TRANSFORMATION_FAILED owner=" + className);
            return null;
        }
    }

    public ClassLoader admittedClassLoader() {
        return admittedClassLoader.get();
    }

    public Outcome outcome() {
        return outcome.get();
    }

    public boolean targetTransformed() {
        return outcome.get() == Outcome.TARGET_TRANSFORMED;
    }

    /**
     * Each expectation is enforced on its own: gating one behind another would let a target
     * pass a check its owner declared, which this fail-closed boundary must never allow.
     */
    private boolean admit(final ClassLoader loader, final ProtectionDomain protectionDomain) {
        if (loader == null && (expectedClassLoader != null || expectedArtifact != null)) {
            reject(Outcome.BOOTSTRAP_LOADER_REJECTED, "MESH_MIRROR_BOOTSTRAP_LOADER_REJECTED");
            return false;
        }
        if (expectedClassLoader != null && loader != expectedClassLoader) {
            reject(Outcome.LOADER_MISMATCH, "MESH_MIRROR_LOADER_MISMATCH");
            return false;
        }
        if (expectedArtifact != null && !expectedArtifact.equals(codeSourcePath(protectionDomain))) {
            reject(Outcome.ARTIFACT_MISMATCH, "MESH_MIRROR_ARTIFACT_MISMATCH");
            return false;
        }
        final ClassLoader existing = admittedClassLoader.get();
        if (existing != null && loader != existing) {
            reject(Outcome.LOADER_MISMATCH, "MESH_MIRROR_LOADER_MISMATCH");
            return false;
        }
        if (helperInstrumentation != null) {
            try {
                MeshMirrorHelperBootstrap.ensureAvailable(helperInstrumentation, loader);
            } catch (RuntimeException failure) {
                reject(Outcome.HELPER_UNAVAILABLE, "MESH_MIRROR_HELPER_UNAVAILABLE");
                return false;
            }
        }
        if (admittedClassLoader.compareAndSet(null, loader) || admittedClassLoader.get() == loader) return true;
        reject(Outcome.LOADER_MISMATCH, "MESH_MIRROR_LOADER_MISMATCH");
        return false;
    }

    private void outcome(final Outcome next, final String message) {
        outcome.set(next);
        report(message);
    }

    private void reject(final Outcome next, final String message) {
        outcome(next, message);
    }

    private void report(final String message) {
        try {
            diagnostic.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not block host class definition.
        }
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
        final MeshMirrorHostProfile.LinkedDeletion linked = profile.linkedDeletion();
        if (linked != null) {
            if (owner.equals(linked.pointActionOwner())
                && name.equals(linked.pointActionMethod())
                && descriptor.equals(linked.pointActionDescriptor())) return Kind.POINT_DELETE;
            if (owner.equals(linked.edgeActionOwner())
                && name.equals(linked.edgeActionMethod())
                && descriptor.equals(linked.edgeActionDescriptor())) return Kind.EDGE_DELETE;
        }
        return null;
    }

    private boolean isTargetOwner(final String owner) {
        if (profile.meshEditorOwner().equals(owner)
            || profile.mirrorWidgetOwner().equals(owner)
            || profile.mirrorAxisDrawOwner().equals(owner)) return true;
        final MeshMirrorHostProfile.LinkedDeletion linked = profile.linkedDeletion();
        return linked != null
            && (linked.pointActionOwner().equals(owner) || linked.edgeActionOwner().equals(owner));
    }

    private static Path codeSourcePath(final ProtectionDomain protectionDomain) {
        if (protectionDomain == null || protectionDomain.getCodeSource() == null) return null;
        final CodeSource source = protectionDomain.getCodeSource();
        if (source.getLocation() == null) return null;
        try {
            final URI location = source.getLocation().toURI();
            return "file".equalsIgnoreCase(location.getScheme())
                ? Path.of(location).toAbsolutePath().normalize()
                : null;
        } catch (URISyntaxException | RuntimeException failure) {
            return null;
        }
    }

    public enum Outcome {
        NONE,
        TARGET_TRANSFORMED,
        TARGET_UNCHANGED,
        LOADER_MISMATCH,
        ARTIFACT_MISMATCH,
        BOOTSTRAP_LOADER_REJECTED,
        RETRANSFORM_REJECTED,
        HELPER_UNAVAILABLE,
        TRANSFORMATION_FAILED
    }

    private enum Kind { POINT, AXIS_POINT, HIT, WIDGET, DRAW, POINT_DELETE, EDGE_DELETE }
}
