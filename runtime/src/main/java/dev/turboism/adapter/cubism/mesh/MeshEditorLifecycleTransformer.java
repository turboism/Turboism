package dev.turboism.adapter.cubism.mesh;

import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Premain-only exact-owner transformer for mesh-editor startMode(List)/endMode lifecycle callbacks. */
public final class MeshEditorLifecycleTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/adapter/cubism/mesh/NativeMeshToolSessionBridge";

    private final MeshToolSessionHostProfile profile;
    private final ClassLoader expectedClassLoader;
    private final Path expectedArtifact;
    private final Consumer<String> diagnostic;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final java.util.concurrent.atomic.AtomicInteger transformedCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public MeshEditorLifecycleTransformer(
            final MeshToolSessionHostProfile profile,
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
        return profile.meshEditorOwnerInternalName();
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
        if (!profile.meshEditorOwnerInternalName().equals(className) || classfileBuffer == null) return null;
        if (loader == null || loader != expectedClassLoader)
            return reject(Outcome.LOADER_MISMATCH, "MESH_TOOL_SESSION_LOADER_MISMATCH");
        if (!expectedArtifact.equals(codeSourcePath(protectionDomain))) {
            return reject(Outcome.ARTIFACT_MISMATCH, "MESH_TOOL_SESSION_ARTIFACT_MISMATCH");
        }
        try {
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            final int[] startCount = {0};
            final int[] endCount = {0};
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
                            if (profile.startMethod().equals(name)
                                    && profile.startDescriptor().equals(descriptor)) {
                                startCount[0]++;
                                return startVisitor(delegate);
                            }
                            if (profile.endMethod().equals(name)
                                    && profile.endDescriptor().equals(descriptor)) {
                                endCount[0]++;
                                return endVisitor(delegate);
                            }
                            return delegate;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (startCount[0] != 1 || endCount[0] != 1) {
                return reject(Outcome.METHOD_SHAPE_MISMATCH, "MESH_TOOL_SESSION_METHOD_SHAPE_MISMATCH");
            }
            transformedCount.incrementAndGet();
            outcome(Outcome.TARGET_TRANSFORMED, "MESH_TOOL_SESSION_TARGET_TRANSFORMED");
            return writer.toByteArray();
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            return reject(Outcome.TRANSFORMATION_FAILED, "MESH_TOOL_SESSION_TRANSFORMATION_FAILED");
        }
    }

    /** Returns the latest fail-closed transformation outcome. */
    public Outcome outcome() {
        return outcome.get();
    }

    private static MethodVisitor startVisitor(final MethodVisitor delegate) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.RETURN) {
                    visitVarInsn(Opcodes.ALOAD, 0);
                    visitVarInsn(Opcodes.ALOAD, 1);
                    visitMethodInsn(
                            Opcodes.INVOKESTATIC, BRIDGE, "afterStart", "(Ljava/lang/Object;Ljava/util/List;)V", false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static MethodVisitor endVisitor(final MethodVisitor delegate) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "beforeEnd", "(Ljava/lang/Object;)V", false);
            }
        };
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
