package dev.turboism.adapter.cubism.temporarydeform;

import dev.turboism.core.reflect.CodeSources;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fail-closed ASM transformer weaving loader-neutral {@link java.util.function.BiConsumer} probes
 * into Cubism's temporary handler manager and Tool Details panel.
 */
public final class TemporaryDeformNativeTransformer implements ClassFileTransformer {

    private static final String UNBIND_METHOD = "i";
    private static final String UNBIND_DESCRIPTOR = "(Lcom/live2d/cubism/view/context/actionManager/aG;)V";
    private static final String SWITCH_METHOD = "a";
    private static final String TOOL_DETAIL_METHOD = "a";
    private static final String TOOL_DETAIL_INIT_DESCRIPTOR = "(Lcom/live2d/cubism/pack/CECompletePack;)V";
    private static final String TOOL_DETAIL_REFRESH_DESCRIPTOR =
            "(Lcom/live2d/cubism/view/context/CEViewContext_ModelingView;)V";

    private static final String BICONSUMER = "java/util/function/BiConsumer";
    private static final String SYSTEM_CLASS = "java/lang/System";
    private static final String PROPERTIES_CLASS = "java/util/Properties";
    private static final String THROWABLE_CLASS = "java/lang/Throwable";
    private static final String GET_PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";
    private static final String PROPERTIES_GET_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BICONSUMER_ACCEPT_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    private static final int EXPECTED_MANAGER_METHODS = 2;
    private static final int EXPECTED_TOOL_DETAIL_METHODS = 2;

    private final TemporaryDeformHostProfile profile;
    private final ClassLoader expectedClassLoader;
    private final Path expectedArtifact;
    private final Consumer<String> diagnostic;
    private final String switchMethodDescriptor;
    private final Set<String> targets;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicInteger transformedCount = new AtomicInteger();

    public TemporaryDeformNativeTransformer(
            final TemporaryDeformHostProfile profile,
            final ClassLoader expectedClassLoader,
            final Path expectedArtifact,
            final Consumer<String> diagnostic) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
        this.expectedArtifact = Objects.requireNonNull(expectedArtifact, "expectedArtifact")
                .toAbsolutePath()
                .normalize();
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.switchMethodDescriptor =
                "(L" + profile.enumInternalName() + ";Lcom/live2d/cubism/view/context/actionManager/aG;)V";
        this.targets = Set.of(profile.managerInternalName(), profile.toolDetailInternalName());
    }

    /** Returns the verified internal class names targeted by this transformer. */
    public Set<String> targetClassNames() {
        return targets;
    }

    /** Returns how many target classes transformed since the last reset. */
    public int transformedCount() {
        return transformedCount.get();
    }

    /** Resets the transformation counter before a new installation pass. */
    public void resetTransformedCount() {
        transformedCount.set(0);
    }

    /** Returns the latest fail-closed transformation outcome. */
    public Outcome outcome() {
        return outcome.get();
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (className == null || !targets.contains(className) || classfileBuffer == null) {
            return null;
        }
        if (loader == null || loader != expectedClassLoader) {
            return reject(Outcome.LOADER_MISMATCH, "TEMPORARY_DEFORM_LOADER_MISMATCH");
        }
        if (!expectedArtifact.equals(CodeSources.pathOf(protectionDomain))) {
            return reject(Outcome.ARTIFACT_MISMATCH, "TEMPORARY_DEFORM_ARTIFACT_MISMATCH");
        }
        try {
            final boolean isManager = profile.managerInternalName().equals(className);
            final int expectedCount = isManager ? EXPECTED_MANAGER_METHODS : EXPECTED_TOOL_DETAIL_METHODS;
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = createHostAwareWriter(reader, loader);
            final int[] matched = {0};
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
                            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                                return delegate;
                            }
                            if (isManager) {
                                if (UNBIND_METHOD.equals(name) && UNBIND_DESCRIPTOR.equals(descriptor)) {
                                    matched[0]++;
                                    return headProbeVisitor(delegate, NativeTemporaryDeformBridge.ON_UNBIND_PROPERTY);
                                }
                                if (SWITCH_METHOD.equals(name) && switchMethodDescriptor.equals(descriptor)) {
                                    matched[0]++;
                                    return headProbeVisitor(
                                            delegate, NativeTemporaryDeformBridge.ON_SWITCH_NATIVE_PROPERTY);
                                }
                                return delegate;
                            }
                            if (TOOL_DETAIL_METHOD.equals(name) && TOOL_DETAIL_INIT_DESCRIPTOR.equals(descriptor)) {
                                matched[0]++;
                                return tailProbeVisitor(
                                        delegate, NativeTemporaryDeformBridge.ON_TOOL_DETAIL_INIT_PROPERTY);
                            }
                            if (TOOL_DETAIL_METHOD.equals(name) && TOOL_DETAIL_REFRESH_DESCRIPTOR.equals(descriptor)) {
                                matched[0]++;
                                return tailProbeVisitor(
                                        delegate, NativeTemporaryDeformBridge.ON_TOOL_DETAIL_REFRESH_PROPERTY);
                            }
                            return delegate;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (matched[0] != expectedCount) {
                return reject(Outcome.METHOD_SHAPE_MISMATCH, "TEMPORARY_DEFORM_METHOD_SHAPE_MISMATCH:" + className);
            }
            transformedCount.incrementAndGet();
            recordOutcome(Outcome.TARGET_TRANSFORMED, "TEMPORARY_DEFORM_TARGET_TRANSFORMED:" + className);
            return writer.toByteArray();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return reject(Outcome.TRANSFORMATION_FAILED, "TEMPORARY_DEFORM_TRANSFORMATION_FAILED:" + className);
        }
    }

    private ClassWriter createHostAwareWriter(final ClassReader reader, final ClassLoader loader) {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader;
            }

            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                try {
                    final Class<?> leftType = Class.forName(left.replace('/', '.'), false, loader);
                    final Class<?> rightType = Class.forName(right.replace('/', '.'), false, loader);
                    if (leftType.isAssignableFrom(rightType)) {
                        return left;
                    }
                    if (rightType.isAssignableFrom(leftType)) {
                        return right;
                    }
                    if (leftType.isInterface() || rightType.isInterface()) {
                        return "java/lang/Object";
                    }
                    Class<?> current = leftType;
                    do {
                        current = current.getSuperclass();
                    } while (!current.isAssignableFrom(rightType));
                    return current.getName().replace('.', '/');
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    return "java/lang/Object";
                }
            }
        };
    }

    private static MethodVisitor headProbeVisitor(final MethodVisitor delegate, final String propertyKey) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                emitBiConsumerProbe(delegate, propertyKey);
            }
        };
    }

    private static MethodVisitor tailProbeVisitor(final MethodVisitor delegate, final String propertyKey) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN) {
                    emitBiConsumerProbe(delegate, propertyKey);
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static void emitBiConsumerProbe(final MethodVisitor out, final String propertyKey) {
        final Label tryStart = new Label();
        final Label tryEnd = new Label();
        final Label catchHandler = new Label();
        final Label skip = new Label();
        final Label done = new Label();

        out.visitTryCatchBlock(tryStart, tryEnd, catchHandler, THROWABLE_CLASS);
        out.visitLabel(tryStart);
        out.visitMethodInsn(Opcodes.INVOKESTATIC, SYSTEM_CLASS, "getProperties", GET_PROPERTIES_DESCRIPTOR, false);
        out.visitLdcInsn(propertyKey);
        out.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROPERTIES_CLASS, "get", PROPERTIES_GET_DESCRIPTOR, false);
        out.visitInsn(Opcodes.DUP);
        out.visitTypeInsn(Opcodes.INSTANCEOF, BICONSUMER);
        out.visitJumpInsn(Opcodes.IFEQ, skip);
        out.visitTypeInsn(Opcodes.CHECKCAST, BICONSUMER);
        out.visitVarInsn(Opcodes.ALOAD, 0);
        out.visitVarInsn(Opcodes.ALOAD, 1);
        out.visitMethodInsn(Opcodes.INVOKEINTERFACE, BICONSUMER, "accept", BICONSUMER_ACCEPT_DESCRIPTOR, true);
        out.visitJumpInsn(Opcodes.GOTO, tryEnd);
        out.visitLabel(skip);
        out.visitInsn(Opcodes.POP);
        out.visitLabel(tryEnd);
        out.visitJumpInsn(Opcodes.GOTO, done);
        out.visitLabel(catchHandler);
        out.visitInsn(Opcodes.POP);
        out.visitLabel(done);
    }

    private byte[] reject(final Outcome next, final String message) {
        recordOutcome(next, message);
        return null;
    }

    private void recordOutcome(final Outcome next, final String message) {
        outcome.set(next);
        FatalErrors.reportSafely(diagnostic, message);
    }

    /** Latest exact-owner admission or bytecode weaving outcome. */
    public enum Outcome {
        NONE,
        TARGET_TRANSFORMED,
        METHOD_SHAPE_MISMATCH,
        LOADER_MISMATCH,
        ARTIFACT_MISMATCH,
        TRANSFORMATION_FAILED
    }
}
