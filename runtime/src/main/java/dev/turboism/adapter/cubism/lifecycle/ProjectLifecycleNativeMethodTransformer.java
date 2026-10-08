package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact-selector transformer for model, animation, and editor lifecycle methods. */
public final class ProjectLifecycleNativeMethodTransformer implements ClassFileTransformer {

    private static final String BRIDGE = "dev/turboism/adapter/cubism/lifecycle/NativeProjectLifecycleBridge";

    private final List<Binding> bindings;
    private final ClassLoader expectedClassLoader;

    public ProjectLifecycleNativeMethodTransformer(
            final List<Binding> bindings, final ClassLoader expectedClassLoader) {
        this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (this.bindings.isEmpty()) throw new IllegalArgumentException("bindings must not be empty");
        this.expectedClassLoader = expectedClassLoader;
        dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                "lifecycle", "Prepared verified lifecycle transformer with " + this.bindings.size() + " bindings");
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null) {
            return null;
        }
        if (bindings.stream().noneMatch(binding -> binding.ownerInternalName().equals(className))) {
            return null;
        }
        if (expectedClassLoader != null && loader != expectedClassLoader) {
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                    "lifecycle",
                    "Lifecycle transform skipped for " + className + " under loader " + loader + " (expected "
                            + expectedClassLoader + ")");
            return null;
        }
        try {
            return transformMatched(className, classfileBuffer);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A throwing transformer must fail the weave, not the host class load.
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "lifecycle",
                    "Lifecycle transform failed for " + className + " ("
                            + failure.getClass().getName() + ": " + failure.getMessage() + ")",
                    null);
            return null;
        }
    }

    private byte[] transformMatched(final String className, final byte[] classfileBuffer) {
        final boolean[] transformed = {false};
        final ClassReader reader = new ClassReader(classfileBuffer);
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                try {
                    final ClassLoader classLoader = expectedClassLoader == null
                            ? ProjectLifecycleNativeMethodTransformer.class.getClassLoader()
                            : expectedClassLoader;
                    final Class<?> leftType = Class.forName(left.replace('/', '.'), false, classLoader);
                    final Class<?> rightType = Class.forName(right.replace('/', '.'), false, classLoader);
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
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor delegate =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        final Binding binding = bindings.stream()
                                .filter(candidate ->
                                        candidate.ownerInternalName().equals(className))
                                .filter(candidate -> candidate.methodName().equals(name))
                                .filter(candidate -> candidate.descriptor().equals(descriptor))
                                .findFirst()
                                .orElse(null);
                        if (binding == null) return delegate;
                        transformed[0] = true;
                        dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                                "lifecycle", "Installed one verified lifecycle transform");
                        return instrument(delegate, binding);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (transformed[0]) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "lifecycle", "Lifecycle transform applied to " + className);
            return writer.toByteArray();
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                "lifecycle", "Lifecycle binding produced no method transform for " + className);
        return null;
    }

    private static MethodVisitor instrument(final MethodVisitor delegate, final Binding binding) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            private final Label start = new Label();
            private final Label end = new Label();
            private final Label handler = new Label();

            @Override
            public void visitCode() {
                super.visitCode();
                emitBegin(this, binding);
                visitLabel(start);
            }

            @Override
            public void visitInsn(final int opcode) {
                if (binding.shape() == HookShape.MODEL_OPEN && opcode == Opcodes.ARETURN
                        || binding.shape() == HookShape.ANIMATION_OPEN && opcode == Opcodes.ARETURN) {
                    visitInsn(Opcodes.DUP);
                    visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "completeObject", "(Ljava/lang/Object;)V", false);
                } else if (binding.shape() == HookShape.CONTENT_BOOLEAN && opcode == Opcodes.IRETURN) {
                    visitInsn(Opcodes.DUP);
                    visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "completeBoolean", "(Z)V", false);
                } else if (binding.shape() == HookShape.EDITOR_EXIT && opcode == Opcodes.IRETURN) {
                    visitInsn(Opcodes.DUP);
                    visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "completeEditorExit", "(Z)V", false);
                }
                super.visitInsn(opcode);
            }

            @Override
            public void visitMaxs(final int maxStack, final int maxLocals) {
                visitLabel(end);
                visitTryCatchBlock(start, end, handler, null);
                visitLabel(handler);
                visitInsn(Opcodes.DUP);
                visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        BRIDGE,
                        binding.shape() == HookShape.EDITOR_EXIT ? "failedEditorExit" : "failedFile",
                        "(Ljava/lang/Throwable;)V",
                        false);
                visitInsn(Opcodes.ATHROW);
                super.visitMaxs(maxStack, maxLocals);
            }
        };
    }

    private static void emitBegin(final MethodVisitor visitor, final Binding binding) {
        switch (binding.shape()) {
            case MODEL_OPEN -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 1);
                visitor.visitVarInsn(Opcodes.ALOAD, 3);
                visitor.visitMethodInsn(
                        Opcodes.INVOKESTATIC, BRIDGE, "beginModelOpen", "(Ljava/lang/String;Ljava/io/File;)V", false);
            }
            case ANIMATION_OPEN -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 1);
                visitor.visitVarInsn(Opcodes.ALOAD, 2);
                visitor.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        BRIDGE,
                        "beginAnimationOpen",
                        "(Ljava/lang/Object;Ljava/io/File;)V",
                        false);
            }
            case CONTENT_BOOLEAN -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, binding.kind().ordinal());
                pushInt(visitor, binding.operation().ordinal());
                visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "beginContent", "(Ljava/lang/Object;II)V", false);
            }
            case EDITOR_EXIT -> visitor.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "beforeEditorExit", "()V", false);
        }
    }

    private static void pushInt(final MethodVisitor visitor, final int value) {
        if (value >= -1 && value <= 5) {
            visitor.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            visitor.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            visitor.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            visitor.visitLdcInsn(value);
        }
    }

    /**
     * Which bridge entry/completion pair a {@link Binding} weaves into its host method.
     */
    public enum HookShape {
        /** Host model open; reported to the bridge as {@code MODEL}/{@code OPEN}. */
        MODEL_OPEN,
        /** Host animation open; reported to the bridge as {@code ANIMATION}/{@code OPEN}. */
        ANIMATION_OPEN,
        /** Boolean-returning operation on open content; the returned boolean is the success signal. */
        CONTENT_BOOLEAN,
        /** Host editor exit; carries no content kind or operation. */
        EDITOR_EXIT
    }

    /**
     * One reviewed host method selector and the ingress shape to weave into it. Only methods named here
     * are transformed, so an unreviewed host build simply produces no bindings.
     *
     * @param ownerInternalName JVM internal name of the declaring host class
     * @param methodName exact host method name
     * @param descriptor exact JVM method descriptor, which pins the overload
     * @param shape which bridge entry and completion pair is woven in
     * @param kind content kind reported to the bridge; required for {@code CONTENT_BOOLEAN}
     * @param operation operation type reported to the bridge; required for {@code CONTENT_BOOLEAN}
     * @throws NullPointerException when {@code shape} is null, or when {@code kind} or
     *     {@code operation} is null for a {@code CONTENT_BOOLEAN} shape
     * @throws IllegalArgumentException when any of the selector strings is blank
     */
    public record Binding(
            String ownerInternalName,
            String methodName,
            String descriptor,
            HookShape shape,
            ProjectContentKind kind,
            ProjectFileOperationType operation) {
        public Binding {
            ownerInternalName = requireText(ownerInternalName, "ownerInternalName");
            methodName = requireText(methodName, "methodName");
            descriptor = requireText(descriptor, "descriptor");
            shape = Objects.requireNonNull(shape, "shape");
            if (shape == HookShape.CONTENT_BOOLEAN) {
                kind = Objects.requireNonNull(kind, "kind");
                operation = Objects.requireNonNull(operation, "operation");
            }
        }

        /**
         * Binding for the host's model open method, reported as {@code MODEL}/{@code OPEN}.
         *
         * @param owner JVM internal name of the declaring host class
         * @param name host method name
         * @param descriptor JVM method descriptor pinning the overload
         * @return the binding
         */
        public static Binding modelOpen(final String owner, final String name, final String descriptor) {
            return new Binding(
                    owner,
                    name,
                    descriptor,
                    HookShape.MODEL_OPEN,
                    ProjectContentKind.MODEL,
                    ProjectFileOperationType.OPEN);
        }

        /**
         * Binding for the host's animation open method, reported as {@code ANIMATION}/{@code OPEN}.
         *
         * @param owner JVM internal name of the declaring host class
         * @param name host method name
         * @param descriptor JVM method descriptor pinning the overload
         * @return the binding
         */
        public static Binding animationOpen(final String owner, final String name, final String descriptor) {
            return new Binding(
                    owner,
                    name,
                    descriptor,
                    HookShape.ANIMATION_OPEN,
                    ProjectContentKind.ANIMATION,
                    ProjectFileOperationType.OPEN);
        }

        /**
         * Binding for a boolean-returning operation on already-open content, such as save or close, where
         * the returned boolean is the success signal.
         *
         * @param owner JVM internal name of the declaring host class
         * @param name host method name
         * @param descriptor JVM method descriptor pinning the overload
         * @param kind content kind reported to the bridge
         * @param operation operation type reported to the bridge
         * @return the binding
         * @throws NullPointerException when {@code kind} or {@code operation} is null
         */
        public static Binding content(
                final String owner,
                final String name,
                final String descriptor,
                final ProjectContentKind kind,
                final ProjectFileOperationType operation) {
            return new Binding(owner, name, descriptor, HookShape.CONTENT_BOOLEAN, kind, operation);
        }

        /**
         * Binding for the host's exit command. Carries no content kind or operation, because editor exit is
         * not a project-file operation.
         *
         * @param owner JVM internal name of the declaring host class
         * @param name host method name
         * @param descriptor JVM method descriptor pinning the overload
         * @return the binding
         */
        public static Binding editorExit(final String owner, final String name, final String descriptor) {
            return new Binding(owner, name, descriptor, HookShape.EDITOR_EXIT, null, null);
        }

        private static String requireText(final String value, final String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }
}
