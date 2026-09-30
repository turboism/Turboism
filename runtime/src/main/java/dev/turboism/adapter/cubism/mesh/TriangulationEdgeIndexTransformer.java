package dev.turboism.adapter.cubism.mesh;

import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact-selector transformer for the host triangulator's per-edge triangle lookup.
 *
 * <p>It rewrites one class only — {@code TriangleList} — and only when the observed bytes match a
 * reviewed digest. Two digests are admitted: the bytes shared by the reviewed 5.3.x builds
 * (5.3.00 through 5.3.04 ship identical {@code TriangleList} bytes) and the reviewed 5.2.03
 * bytes, whose instruction shape was independently verified to satisfy the same four-method
 * contract. Every other class, and every unrecognised shape, is returned untouched. The
 * transformer is non-retransforming, so it can only act on the first definition — a class the
 * host has already loaded is never patched behind its back.</p>
 *
 * <p>The patch itself is fail-closed: {@link TriangulationEdgeIndexPatcher} refuses any class
 * whose four target methods do not each carry exactly the reviewed call-site/prologue shape, and
 * the woven {@code a(j)} keeps the original scan body as the automatic fallback.</p>
 */
public final class TriangulationEdgeIndexTransformer implements ClassFileTransformer {

    /** Internal name of the reviewed host class; nothing else is eligible. */
    static final String TARGET_INTERNAL_NAME =
            "com/live2d/graphics3d/editableMesh/triangulation/TriangleList";
    /** Binary name used for already-loaded detection. */
    static final String TARGET_CLASS_NAME =
            "com.live2d.graphics3d.editableMesh.triangulation.TriangleList";

    /** SHA-256 of the 5.3.x-family class bytes (identical on 5.3.00 through 5.3.04). */
    static final String REVIEWED_CLASS_SHA256_53X =
            "87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29";
    /**
     * SHA-256 of the 5.0.x–5.2.x-family class bytes. All reviewed builds from 5.0.00 through
     * 5.2.03 ship identical {@code TriangleList} bytes; the four-method patch contract was
     * verified on that shared shape. Product support is still bounded to the reviewed
     * installation versions — the digest pins bytes, it does not widen the support surface.
     */
    static final String REVIEWED_CLASS_SHA256_5203 =
            "b0a11ffc8969e5a8d1266ca01db85dacb75ca4de64e14b9f282ba4c32169d920";

    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** The target has not been defined yet. */
        NONE,
        /** The target was defined with reviewed bytes and was patched. */
        PATCHED,
        /** The target was defined but its bytes differ from every reviewed digest. */
        HASH_MISMATCH,
        /** The target was defined with a reviewed digest but an unexpected shape. */
        SHAPE_REJECTED
    }

    private final Set<String> admittedDigests;
    private final TriangulationEdgeIndexPatcher patcher = new TriangulationEdgeIndexPatcher();
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public TriangulationEdgeIndexTransformer() {
        this(Set.of(REVIEWED_CLASS_SHA256_53X, REVIEWED_CLASS_SHA256_5203));
    }

    TriangulationEdgeIndexTransformer(final Set<String> admittedDigests) {
        this.admittedDigests = Objects.requireNonNull(admittedDigests, "admittedDigests");
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
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain domain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || !TARGET_INTERNAL_NAME.equals(className)) return null;
        final String observed = sha256(classfileBuffer);
        if (!admittedDigests.contains(observed)) {
            outcome.compareAndSet(Outcome.NONE, Outcome.HASH_MISMATCH);
            diagnostic.compareAndSet("", "observed=" + observed);
            return null;
        }
        try {
            final byte[] patched = patcher.patch(classfileBuffer);
            outcome.set(Outcome.PATCHED);
            return patched;
        } catch (TriangulationEdgeIndexPatcher.NotApplicable rejected) {
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
