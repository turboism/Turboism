package dev.turboism.adapter.cubism.optimization.physics;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
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
 * Rewrites the admitted Cubism physics evaluator to eliminate per-tick heap allocations while
 * preserving exact IEEE-754 floating-point results.
 */
public final class PhysicsAccelerationTransformer implements ClassFileTransformer {

    private static final String FAILURE_SOURCE_MISMATCH = "physics acceleration source is not the reviewed artifact";
    private static final String FAILURE_METHOD_ABSENT = "reviewed physics evaluator method absent";
    private static final String FAILURE_SHAPE_MISMATCH = "physics evaluator method shape mismatch";
    private static final String FAILURE_NOT_APPLIED = "physics acceleration fast path was not applied";
    private static final String FAILURE_LOCATOR_SEPARATOR = " at ";
    private static final String NO_FRAMES_MARKER = "<no frames>";

    private final ClassLoader loader;
    private final Path artifact;
    private final PhysicsAccelerationTarget target;
    private final List<String> evaluateShape;
    private final List<String> stepShape;
    private final List<String> resetShape;
    private final List<String> findShape;
    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Constructs a fail-closed transformer validated against reference evaluator bytes.
     *
     * @param loader permitted host class loader
     * @param artifact normalized path to the admitted host JAR
     * @param reference unmodified bytecode of the physics evaluator class
     * @param target reviewed physics target metadata
     * @throws IllegalArgumentException when any reviewed evaluator overload is absent
     */
    public PhysicsAccelerationTransformer(
            final ClassLoader loader,
            final Path artifact,
            final byte[] reference,
            final PhysicsAccelerationTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        Objects.requireNonNull(reference, "reference");
        this.target = Objects.requireNonNull(target, "target");
        final String owner = target.evaluatorOwner();
        evaluateShape = requireShape(
                reference,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.EVALUATE_SOURCE_DESCRIPTOR);
        stepShape = requireShape(
                reference,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.STEP_VERTICES_DESCRIPTOR);
        resetShape = requireShape(
                reference,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.RESET_VERTICES_DESCRIPTOR);
        findShape = requireShape(
                reference,
                owner,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.FIND_PARAMETER_DESCRIPTOR);
    }

    /**
     * Returns the most recent refusal reason recorded during transformation.
     *
     * @return failure detail, or {@code null} if all transforms succeeded
     */
    public String failure() {
        return failure;
    }

    /**
     * Returns the number of evaluator class definitions rewritten by this transformer.
     *
     * @return rewritten class count
     */
    public int matches() {
        return matches;
    }

    /**
     * Returns the total number of rewritten methods inside the transformed evaluator class.
     *
     * @return rewritten method site count
     */
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
        if (actualLoader != loader || bytes == null || !target.evaluatorOwner().equals(name)) {
            return null;
        }
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = FAILURE_SOURCE_MISMATCH;
                return null;
            }
            if (!matchesReviewedShapes(bytes)) {
                return null;
            }
            final ClassReader reader = HostClassWriters.newClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final int[] appliedSites = {0};
            final ClassVisitor visitor =
                    HostClassWriters.preservingClassVersion(bytes, new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String method,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor delegate =
                                    super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!PhysicsAccelerationTarget.TARGET_METHOD_NAME.equals(method)) {
                                return delegate;
                            }
                            final MethodKind kind = MethodKind.ofDescriptor(descriptor);
                            if (kind == null) {
                                return delegate;
                            }
                            appliedSites[0]++;
                            return new FastPathMethodVisitor(delegate, target, kind);
                        }
                    });
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            if (appliedSites[0] != PhysicsAccelerationTarget.EXPECTED_TOTAL_SITES) {
                failure = FAILURE_NOT_APPLIED + " (" + appliedSites[0] + ")";
                return null;
            }
            sites = appliedSites[0];
            matches++;
            return writer.toByteArray();
        } catch (final Exception | LinkageError rejected) {
            final StackTraceElement[] trace = rejected.getStackTrace();
            failure =
                    rejected + FAILURE_LOCATOR_SEPARATOR + (trace.length == 0 ? NO_FRAMES_MARKER : trace[0].toString());
            return null;
        }
    }

    private boolean matchesReviewedShapes(final byte[] bytes) {
        final String owner = target.evaluatorOwner();
        return matchesShape(bytes, owner, PhysicsAccelerationTarget.EVALUATE_SOURCE_DESCRIPTOR, evaluateShape)
                && matchesShape(bytes, owner, PhysicsAccelerationTarget.STEP_VERTICES_DESCRIPTOR, stepShape)
                && matchesShape(bytes, owner, PhysicsAccelerationTarget.RESET_VERTICES_DESCRIPTOR, resetShape)
                && matchesShape(bytes, owner, PhysicsAccelerationTarget.FIND_PARAMETER_DESCRIPTOR, findShape);
    }

    private boolean matchesShape(
            final byte[] bytes, final String owner, final String descriptor, final List<String> expected) {
        final List<String> actual =
                ReviewedMethodShape.read(bytes, owner, PhysicsAccelerationTarget.TARGET_METHOD_NAME, descriptor);
        if (actual == null) {
            failure = FAILURE_METHOD_ABSENT;
            return false;
        }
        if (!expected.equals(actual)) {
            failure = FAILURE_SHAPE_MISMATCH;
            return false;
        }
        return true;
    }

    private static List<String> requireShape(
            final byte[] bytes, final String owner, final String method, final String descriptor) {
        final List<String> shape = ReviewedMethodShape.read(bytes, owner, method, descriptor);
        if (shape == null) {
            throw new IllegalArgumentException(FAILURE_METHOD_ABSENT + ": " + owner + "." + method + descriptor);
        }
        return shape;
    }

    private enum MethodKind {
        EVALUATE_SOURCE(PhysicsAccelerationTarget.EVALUATE_SOURCE_DESCRIPTOR),
        STEP_VERTICES(PhysicsAccelerationTarget.STEP_VERTICES_DESCRIPTOR),
        RESET_VERTICES(PhysicsAccelerationTarget.RESET_VERTICES_DESCRIPTOR),
        FIND_PARAMETER(PhysicsAccelerationTarget.FIND_PARAMETER_DESCRIPTOR);

        private final String descriptor;

        MethodKind(final String descriptor) {
            this.descriptor = descriptor;
        }

        private static MethodKind ofDescriptor(final String descriptor) {
            for (final MethodKind kind : values()) {
                if (kind.descriptor.equals(descriptor)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private static final class FastPathMethodVisitor extends MethodVisitor {
        private final PhysicsAccelerationTarget target;
        private final MethodKind kind;

        private FastPathMethodVisitor(
                final MethodVisitor delegate, final PhysicsAccelerationTarget target, final MethodKind kind) {
            super(Opcodes.ASM9, delegate);
            this.target = target;
            this.kind = kind;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            switch (kind) {
                case FIND_PARAMETER -> PhysicsMethodEmitters.emitFindParameterFastPath(mv, fallback);
                case RESET_VERTICES -> PhysicsMethodEmitters.emitResetVerticesFastPath(mv, fallback);
                case STEP_VERTICES -> PhysicsMethodEmitters.emitStepVerticesFastPath(mv, target, fallback);
                case EVALUATE_SOURCE -> PhysicsMethodEmitters.emitEvaluateSourceFastPath(mv, target, fallback);
            }
            mv.visitLabel(fallback);
        }

        @Override
        public void visitFrame(
                final int type, final int numLocal, final Object[] local, final int numStack, final Object[] stack) {
            // ClassWriter recomputes all frames cleanly across the inserted fast path and original fallback.
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(0, 0);
        }
    }
}
