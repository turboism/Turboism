package dev.turboism.adapter.cubism.mesh;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact-selector transformer for the host triangulator's constant hash.
 *
 * <p>It rewrites one class only, and only when the observed bytes match a pinned SHA-256. Every
 * other class, and every unrecognised shape, is returned untouched. The transformer is
 * non-retransforming, so it can only act on the first definition — a class the host has already
 * loaded is never patched behind its back.</p>
 */
public final class MeshTriangulationHashTransformer implements ClassFileTransformer {

    /** Internal name of the reviewed host class; nothing else is eligible. */
    static final String TARGET_INTERNAL_NAME =
        "com/live2d/graphics3d/editableMesh/triangulation/l";
    /** Descriptor of the corner point type referenced by the target's three fields. */
    static final String POINT_DESCRIPTOR =
        "Lcom/live2d/graphics3d/editableMesh/triangulation/TriPoint;";
    /** SHA-256 of the reviewed 5.3.03 class bytes. */
    static final String REVIEWED_CLASS_SHA256 =
        "6f06427c59d3907fe0d4ec80c72a318410d2e5169e18a8263ddfaa526813bd90";

    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** The target has not been defined yet. */
        NONE,
        /** The target was defined with the reviewed bytes and was patched. */
        PATCHED,
        /** The target was defined but its bytes differ from the reviewed digest. */
        HASH_MISMATCH,
        /** The target was defined with the reviewed digest but an unexpected shape. */
        SHAPE_REJECTED
    }

    private final String expectedSha256;
    private final MeshTriangulationHashPatcher patcher =
        new MeshTriangulationHashPatcher(TARGET_INTERNAL_NAME, POINT_DESCRIPTOR);
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public MeshTriangulationHashTransformer() {
        this(REVIEWED_CLASS_SHA256);
    }

    MeshTriangulationHashTransformer(final String expectedSha256) {
        this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256");
    }

    /** Latest observed outcome; {@code NONE} until the target class has been defined. */
    public Outcome outcome() {
        return outcome.get();
    }

    /** Human-readable detail for a non-clean outcome, or the empty string. */
    public String diagnostic() {
        return diagnostic.get();
    }

    @Override
    public byte[] transform(final ClassLoader loader, final String className,
                            final Class<?> classBeingRedefined, final ProtectionDomain domain,
                            final byte[] classfileBuffer) {
        if (classfileBuffer == null || !TARGET_INTERNAL_NAME.equals(className)) return null;
        final String observed = sha256(classfileBuffer);
        if (!expectedSha256.equals(observed)) {
            outcome.compareAndSet(Outcome.NONE, Outcome.HASH_MISMATCH);
            diagnostic.compareAndSet("", "observed=" + observed);
            return null;
        }
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

    static String sha256(final byte[] bytes) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder text = new StringBuilder(hash.length * 2);
            for (final byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xF, 16));
                text.append(Character.forDigit(value & 0xF, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
