package dev.turboism.adapter.cubism.mesh;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact-selector transformer for the host triangulator's constant hash.
 *
 * <p>It rewrites one class only, and only when {@link MeshTriangulationHashPatcher} recognises the
 * reviewed field layout and {@code hashCode} body. Every other class, and every unrecognised
 * shape, is returned untouched. The transformer is non-retransforming, so it can only act on the
 * first definition — a class the host has already loaded is never patched behind its back.</p>
 */
public final class MeshTriangulationHashTransformer implements ClassFileTransformer {

    /** Internal name of the reviewed host class; nothing else is eligible. */
    static final String TARGET_INTERNAL_NAME = "com/live2d/graphics3d/editableMesh/triangulation/l";
    /** Descriptor of the corner point type referenced by the target's three fields. */
    static final String POINT_DESCRIPTOR = "Lcom/live2d/graphics3d/editableMesh/triangulation/TriPoint;";
    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** The target has not been defined yet. */
        NONE,
        /** The target was defined with the reviewed body and was patched. */
        PATCHED,
        /** The target was defined but its body differs from the reviewed shape. */
        SHAPE_REJECTED
    }

    private final MeshTriangulationHashPatcher patcher =
            new MeshTriangulationHashPatcher(TARGET_INTERNAL_NAME, POINT_DESCRIPTOR);
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public MeshTriangulationHashTransformer() {}

    /** Latest observed outcome; {@code NONE} until the target class has been defined. */
    public Outcome outcome() {
        return outcome.get();
    }

    /** Human-readable detail for a non-clean outcome, or the empty string. */
    public String diagnostic() {
        return diagnostic.get();
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain domain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || !TARGET_INTERNAL_NAME.equals(className)) return null;
        try {
            final byte[] patched = patcher.patch(classfileBuffer);
            outcome.set(Outcome.PATCHED);
            return patched;
        } catch (MeshTriangulationHashPatcher.NotApplicable rejected) {
            outcome.set(Outcome.SHAPE_REJECTED);
            diagnostic.set(rejected.getMessage());
            return null;
        }
    }
}
