package dev.turboism.adapter.cubism.mesh;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
    static final String TARGET_INTERNAL_NAME = "com/live2d/graphics3d/editableMesh/triangulation/l";
    /** Descriptor of the corner point type referenced by the target's three fields. */
    static final String POINT_DESCRIPTOR = "Lcom/live2d/graphics3d/editableMesh/triangulation/TriPoint;";
    /**
     * SHA-256 of every reviewed generation's class bytes. The 5.3.x family ships one
     * shared {@code l} and the 5.0.x–5.2.x family ships another; both carry the exact
     * {@code a},{@code b},{@code c} {@code TriPoint} field layout and the constant
     * {@code hashCode} the patcher was reviewed against.
     */
    static final Set<String> REVIEWED_CLASS_SHA256S = reviewedClassSha256s();

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

    private final Set<String> expectedSha256s;
    private final MeshTriangulationHashPatcher patcher =
            new MeshTriangulationHashPatcher(TARGET_INTERNAL_NAME, POINT_DESCRIPTOR);
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public MeshTriangulationHashTransformer() {
        this(REVIEWED_CLASS_SHA256S);
    }

    MeshTriangulationHashTransformer(final Set<String> expectedSha256s) {
        if (expectedSha256s.isEmpty()) throw new IllegalArgumentException("no reviewed class digest");
        this.expectedSha256s = Set.copyOf(Objects.requireNonNull(expectedSha256s, "expectedSha256s"));
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
        if (!expectedSha256s.contains(observed)) {
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

    private static Set<String> reviewedClassSha256s() {
        final Set<String> digests = new TreeSet<>();
        for (final Map<String, String> block :
                ClassPinTable.load("mesh-triangulation-hash").values()) {
            final String pinned = block.get(TARGET_INTERNAL_NAME);
            if (pinned == null) {
                throw new IllegalStateException("mesh-triangulation-hash pin block lacks " + TARGET_INTERNAL_NAME);
            }
            digests.add(pinned);
        }
        return Set.copyOf(digests);
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
