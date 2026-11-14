package dev.turboism.adapter.cubism.optimization.camera;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code GCamera.getWorldToCameraMatrix()} to consult and populate the root-camera
 * world-to-camera matrix cache.
 */
public final class CameraMatrixCacheTransformer implements ClassFileTransformer {

    private static final String FUNCTION = "java/util/function/Function";
    private static final String BIFUNCTION = "java/util/function/BiFunction";
    private static final int EXPECTED_RETURN_SITES = 1;

    private final ClassLoader loader;
    private final Path artifact;
    private final List<String> methodShape;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures the reference method shape of {@code GCamera.getWorldToCameraMatrix()}.
     *
     * @param loader admitted host class loader
     * @param artifact official host JAR path
     * @param reference reference bytes of {@code GCamera}
     */
    public CameraMatrixCacheTransformer(
            final ClassLoader loader, final Path artifact, final byte[] reference) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        methodShape = ReviewedMethodShape.read(
                reference,
                CameraMatrixCacheTarget.CAMERA_OWNER,
                CameraMatrixCacheTarget.WORLD_TO_CAMERA_METHOD,
                CameraMatrixCacheTarget.WORLD_TO_CAMERA_DESCRIPTOR);
        if (methodShape == null) {
            throw new IllegalArgumentException("GCamera.getWorldToCameraMatrix absent from reference");
        }
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns the number of rewritten methods. */
    public int sites() {
        return sites;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null || !CameraMatrixCacheTarget.CAMERA_OWNER.equals(name)) {
            return null;
        }
        if (actualLoader != loader || !matchesArtifactSource(domain)) {
            failure = "unexpected loader or code source for " + name;
            return null;
        }
        if (!methodShape.equals(ReviewedMethodShape.read(
                bytes,
                CameraMatrixCacheTarget.CAMERA_OWNER,
                CameraMatrixCacheTarget.WORLD_TO_CAMERA_METHOD,
                CameraMatrixCacheTarget.WORLD_TO_CAMERA_DESCRIPTOR))) {
            failure = "GCamera.getWorldToCameraMatrix method shape mismatch";
            return null;
        }
        try {
            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.computeFrames(reader, loader);
            final int[] returnSites = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String methodName,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor mv =
                                    super.visitMethod(access, methodName, descriptor, signature, exceptions);
                            if (!CameraMatrixCacheTarget.WORLD_TO_CAMERA_METHOD.equals(methodName)
                                    || !CameraMatrixCacheTarget.WORLD_TO_CAMERA_DESCRIPTOR.equals(descriptor)) {
                                return mv;
                            }
                            return wrapWorldToCameraMatrix(mv, returnSites);
                        }
                    },
                    ClassReader.EXPAND_FRAMES);
            if (returnSites[0] != EXPECTED_RETURN_SITES) {
                failure = "GCamera return site count mismatch: " + returnSites[0];
                return null;
            }
            final byte[] output = writer.toByteArray();
            matches++;
            sites++;
            return output;
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private boolean matchesArtifactSource(final ProtectionDomain domain) {
        if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
            return false;
        }
        try {
            return Path.of(domain.getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize()
                    .equals(artifact);
        } catch (final Exception ignored) {
            return false;
        }
    }

    private static MethodVisitor wrapWorldToCameraMatrix(
            final MethodVisitor downstream, final int[] returnSites) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            @Override
            public void visitCode() {
                super.visitCode();
                emitPropertyLookup(mv, CameraMatrixCacheBridge.GET_PROPERTY);
                super.visitInsn(Opcodes.DUP);
                super.visitTypeInsn(Opcodes.INSTANCEOF, FUNCTION);
                final Label noFunction = new Label();
                final Label cacheMiss = new Label();
                final Label computeOriginal = new Label();
                super.visitJumpInsn(Opcodes.IFEQ, noFunction);
                super.visitTypeInsn(Opcodes.CHECKCAST, FUNCTION);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        FUNCTION,
                        "apply",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        true);
                super.visitInsn(Opcodes.DUP);
                super.visitTypeInsn(Opcodes.INSTANCEOF, CameraMatrixCacheTarget.MATRIX_OWNER);
                super.visitJumpInsn(Opcodes.IFEQ, cacheMiss);
                super.visitTypeInsn(Opcodes.CHECKCAST, CameraMatrixCacheTarget.MATRIX_OWNER);
                super.visitInsn(Opcodes.ARETURN);
                super.visitLabel(cacheMiss);
                super.visitInsn(Opcodes.POP);
                super.visitJumpInsn(Opcodes.GOTO, computeOriginal);
                super.visitLabel(noFunction);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(computeOriginal);
            }

            @Override
            public void visitInsn(final int opcode) {
                if (opcode == Opcodes.ARETURN) {
                    returnSites[0]++;
                    emitPropertyLookup(mv, CameraMatrixCacheBridge.PUT_PROPERTY);
                    super.visitInsn(Opcodes.DUP);
                    super.visitTypeInsn(Opcodes.INSTANCEOF, BIFUNCTION);
                    final Label skipPut = new Label();
                    final Label finishReturn = new Label();
                    super.visitJumpInsn(Opcodes.IFEQ, skipPut);
                    super.visitTypeInsn(Opcodes.CHECKCAST, BIFUNCTION);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitMethodInsn(
                            Opcodes.INVOKEINTERFACE,
                            BIFUNCTION,
                            "apply",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            true);
                    super.visitTypeInsn(Opcodes.CHECKCAST, CameraMatrixCacheTarget.MATRIX_OWNER);
                    super.visitJumpInsn(Opcodes.GOTO, finishReturn);
                    super.visitLabel(skipPut);
                    super.visitInsn(Opcodes.POP);
                    super.visitLabel(finishReturn);
                }
                super.visitInsn(opcode);
            }
        };
    }

    private static void emitPropertyLookup(final MethodVisitor mv, final String key) {
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
        mv.visitLdcInsn(key);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/util/Properties",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false);
    }
}
