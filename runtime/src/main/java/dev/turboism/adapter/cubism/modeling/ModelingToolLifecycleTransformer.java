package dev.turboism.adapter.cubism.modeling;

import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Observes every normal return of the exact native tool setter, including same-tool requests. */
public final class ModelingToolLifecycleTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/adapter/cubism/modeling/NativeModelingToolBridge";

    private final ModelingToolHostProfile profile;
    private final ClassLoader expectedClassLoader;
    private final Path expectedArtifact;
    private final Consumer<String> diagnostic;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final java.util.concurrent.atomic.AtomicInteger transformedCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public ModelingToolLifecycleTransformer(
            final ModelingToolHostProfile profile,
            final ClassLoader expectedClassLoader,
            final Path expectedArtifact,
            final Consumer<String> diagnostic) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.expectedClassLoader = Objects.requireNonNull(expectedClassLoader, "expectedClassLoader");
        this.expectedArtifact = Objects.requireNonNull(expectedArtifact, "expectedArtifact")
                .toAbsolutePath()
                .normalize();
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Returns the exact reviewed owner internal name this transformer targets. */
    public String targetClassName() {
        return profile.ownerInternalName();
    }

    /** Returns the single reviewed native app owner. */
    public Set<String> targetClassNames() {
        return Set.of(profile.ownerInternalName());
    }

    /** Returns how many target classes were transformed since the last reset. */
    public int transformedCount() {
        return transformedCount.get();
    }

    /** Resets the transformation counter before a new installation pass. */
    public void resetTransformedCount() {
        transformedCount.set(0);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            // `classBeingRedefined` is intentionally not a rejection: the installer retransforms the
            // exact owner when the host loaded it before this hook was installed.
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (className == null || !targetClassNames().contains(className) || classfileBuffer == null) return null;
        if (loader == null || loader != expectedClassLoader)
            return reject(Outcome.LOADER_MISMATCH, "MODELING_TOOL_LOADER_MISMATCH");
        if (!expectedArtifact.equals(codeSourcePath(protectionDomain))) {
            return reject(Outcome.ARTIFACT_MISMATCH, "MODELING_TOOL_ARTIFACT_MISMATCH");
        }
        try {
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final int[] methods = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                int access, String name, String descriptor, String signature, String[] exceptions) {
                            final MethodVisitor delegate =
                                    super.visitMethod(access, name, descriptor, signature, exceptions);
                            if (!profile.method().equals(name)
                                    || !profile.descriptor().equals(descriptor)) return delegate;
                            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                                return delegate;
                            methods[0]++;
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override
                                public void visitInsn(int opcode) {
                                    if (opcode == Opcodes.RETURN) {
                                        visitVarInsn(Opcodes.ALOAD, 0);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "afterToolChanged",
                                                "(Ljava/lang/Object;)V",
                                                false);
                                    }
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (methods[0] != 1) return reject(Outcome.METHOD_SHAPE_MISMATCH, "MODELING_TOOL_METHOD_SHAPE_MISMATCH");
            transformedCount.incrementAndGet();
            outcome(Outcome.TARGET_TRANSFORMED, "MODELING_TOOL_TARGET_TRANSFORMED");
            return writer.toByteArray();
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            return reject(Outcome.TRANSFORMATION_FAILED, "MODELING_TOOL_TRANSFORMATION_FAILED");
        }
    }

    /** Returns the latest fail-closed transformation outcome. */
    public Outcome outcome() {
        return outcome.get();
    }

    private byte[] reject(final Outcome next, final String message) {
        outcome(next, message);
        return null;
    }

    private void outcome(final Outcome next, final String message) {
        outcome.set(next);
        try {
            diagnostic.accept(message);
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics cannot block host class definition.
        }
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

    /** Latest exact-owner admission or weaving outcome. */
    public enum Outcome {
        NONE,
        TARGET_TRANSFORMED,
        METHOD_SHAPE_MISMATCH,
        LOADER_MISMATCH,
        ARTIFACT_MISMATCH,
        TRANSFORMATION_FAILED
    }
}
