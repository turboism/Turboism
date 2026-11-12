package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Exact-selector transformer for indexed edge queries, membership fusion and fresh-edge searches.
 *
 * <p>Each of the two targets has independent reviewed class hashes and shape gates. Unknown
 * bytes remain untouched. Fusion calls the original add method and does not require an indexed
 * TriangleList, so either transformation can safely decline independently. Both outcomes are
 * exposed separately; success of one must not be reported as success of another. Fresh-edge
 * search elimination additionally checks the actual loaded equality semantics at runtime.</p>
 *
 * <p>Only initial definitions are eligible. The installer declines if either target was already
 * loaded, and redefinition callbacks are ignored. The query patch retains its native scan
 * fallback; membership fusion retains the original contains/add branch when debug is enabled.</p>
 */
public final class TriangulationEdgeIndexTransformer implements ClassFileTransformer {

    /** Internal name of the reviewed host class; nothing else is eligible. */
    static final String TARGET_INTERNAL_NAME = "com/live2d/graphics3d/editableMesh/triangulation/TriangleList";
    /** Binary name used for already-loaded detection. */
    static final String TARGET_CLASS_NAME = "com.live2d.graphics3d.editableMesh.triangulation.TriangleList";

    static final String MEMBERSHIP_INTERNAL_NAME = "com/live2d/graphics3d/editableMesh/triangulation/h";
    static final String MEMBERSHIP_CLASS_NAME = MEMBERSHIP_INTERNAL_NAME.replace('/', '.');
    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** The target has not been defined yet. */
        NONE,
        /** The target was defined with reviewed bytes and was patched. */
        PATCHED,
        /** The target was defined but its body differs from the reviewed shape. */
        SHAPE_MISMATCH,
        /** The target was defined with a reviewed digest but an unexpected shape. */
        SHAPE_REJECTED
    }

    private final Consumer<String> membershipReceipt;
    private final TriangulationDefinitionLifecycle lifecycle;
    private final TriangulationEdgeIndexPatcher patcher = new TriangulationEdgeIndexPatcher();
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");
    private final AtomicReference<Outcome> membershipOutcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> membershipDiagnostic = new AtomicReference<>("");
    private final AtomicReference<Outcome> freshEdgeOutcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> freshEdgeDiagnostic = new AtomicReference<>("");
    private final AtomicReference<Outcome> lazyEdgeOutcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> lazyEdgeDiagnostic = new AtomicReference<>("");
    private final AtomicReference<Outcome> builderOutcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> builderDiagnostic = new AtomicReference<>("");

    public TriangulationEdgeIndexTransformer() {
        this(ignored -> {});
    }

    TriangulationEdgeIndexTransformer(final Consumer<String> membershipReceipt) {
        this(membershipReceipt, null);
    }

    TriangulationEdgeIndexTransformer(
            final Consumer<String> receipt, final TriangulationDefinitionLifecycle lifecycle) {
        this.membershipReceipt = Objects.requireNonNull(receipt, "receipt");
        this.lifecycle = lifecycle;
    }

    /** Latest observed outcome; {@code NONE} until the target class has been defined. */
    public Outcome outcome() {
        return outcome.get();
    }

    /** Human-readable detail for a non-clean outcome, or the empty string. */
    public String diagnostic() {
        return diagnostic.get();
    }

    /** Outcome for the membership caller, independent of the edge-query transformation. */
    public Outcome membershipOutcome() {
        return membershipOutcome.get();
    }

    /** Rejection detail for the membership caller, or empty when no rejection was observed. */
    public String membershipDiagnostic() {
        return membershipDiagnostic.get();
    }

    /** Outcome for fresh-edge search elimination, separate from membership fusion. */
    public Outcome freshEdgeOutcome() {
        return freshEdgeOutcome.get();
    }

    /** Rejection detail for the fresh-edge caller. */
    public String freshEdgeDiagnostic() {
        return freshEdgeDiagnostic.get();
    }

    /** Outcome for guarded temporary-edge construction, independent of the older stages. */
    public Outcome lazyEdgeOutcome() {
        return lazyEdgeOutcome.get();
    }

    /** Why the guarded construction weave declined; ordinary unsupported starts do not attempt it. */
    public String lazyEdgeDiagnostic() {
        return lazyEdgeDiagnostic.get();
    }

    /** Outcome for leased local edge-list construction, independent of indexed triangle queries. */
    public Outcome builderOutcome() {
        return builderOutcome.get();
    }

    /** Why the local edge-list construction weave declined, or empty if it was not attempted. */
    public String builderDiagnostic() {
        return builderDiagnostic.get();
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String name,
            final Class<?> redefined,
            final ProtectionDomain domain,
            final byte[] bytes) {
        return transform(loader, name, redefined, domain, bytes, module == null || !module.isNamed());
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain domain,
            final byte[] classfileBuffer) {
        return transform(loader, className, classBeingRedefined, domain, classfileBuffer, true);
    }

    private byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain domain,
            final byte[] classfileBuffer,
            final boolean unnamedModule) {
        if (classfileBuffer == null || classBeingRedefined != null) return null;
        if (MEMBERSHIP_INTERNAL_NAME.equals(className)) {
            final String observed = sha256(classfileBuffer);
            byte[] patched = classfileBuffer;
            boolean freshPatched = false;
            boolean membershipPatched = false;
            boolean lazyPatched = false;
            try {
                patched = FreshTriangulationEdgePatcher.patch(classfileBuffer);
                freshEdgeOutcome.set(Outcome.PATCHED);
                freshPatched = true;
            } catch (RuntimeException rejected) {
                freshEdgeOutcome.set(Outcome.SHAPE_REJECTED);
                freshEdgeDiagnostic.set(rejected.getMessage());
            }
            try {
                patched = TriangulationMembershipPatcher.patch(patched);
                membershipOutcome.set(Outcome.PATCHED);
                membershipPatched = true;
            } catch (RuntimeException rejected) {
                membershipOutcome.set(Outcome.SHAPE_REJECTED);
                membershipDiagnostic.set(rejected.getMessage());
            }
            if (lifecycle != null && freshPatched && membershipPatched) {
                try {
                    if (!unnamedModule) throw new IllegalArgumentException("named host module rejected");
                    patched = LazyTriangulationEdgePreparation.prepare(
                            patched, domain, loader, lifecycle, this::reportMembership);
                    lazyPatched = true;
                    lazyEdgeOutcome.set(Outcome.PATCHED);
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    lazyEdgeOutcome.set(Outcome.SHAPE_REJECTED);
                    lazyEdgeDiagnostic.set(failure.getClass().getSimpleName() + ":" + failure.getMessage());
                    reportMembership("TRIANGULATION_LAZY_EDGE_DECLINED reason=" + lazyEdgeDiagnostic.get());
                }
            }
            // Each receipt binds the final returned bytes; either independent stage may decline.
            if (freshPatched)
                reportMembership("TRIANGULATION_FRESH_EDGE_PATCHED inputSha256=" + observed + " outputSha256="
                        + sha256(patched));
            if (membershipPatched)
                reportMembership("TRIANGULATION_MEMBERSHIP_PATCHED inputSha256=" + observed + " outputSha256="
                        + sha256(patched));
            if (lazyPatched)
                reportMembership(
                        "TRIANGULATION_LAZY_EDGE_PATCHED inputSha256=" + observed + " outputSha256=" + sha256(patched));
            if (lazyPatched)
                reportMembership("TRIANGULATION_ANGLE_GUARD_PATCHED inputSha256=" + observed + " outputSha256="
                        + sha256(patched));
            return freshPatched || membershipPatched ? patched : null;
        }
        if (!TARGET_INTERNAL_NAME.equals(className)) return null;
        final String observed = sha256(classfileBuffer);
        try {
            byte[] patched = patcher.patch(classfileBuffer);
            if (lifecycle != null && unnamedModule && lifecycle.startupReason().equals("SUPPORTED_OWNED_PREMAIN")) {
                try {
                    patched = TriangleListEdgeBuilderPatcher.patch(patched);
                    builderOutcome.set(Outcome.PATCHED);
                    reportMembership("TRIANGULATION_LOCAL_BUILDER_PATCHED inputSha256=" + observed + " outputSha256="
                            + sha256(patched));
                } catch (IllegalArgumentException rejected) {
                    builderOutcome.set(Outcome.SHAPE_REJECTED);
                    builderDiagnostic.set(rejected.getMessage());
                    reportMembership("TRIANGULATION_LOCAL_BUILDER_DECLINED reason=" + rejected.getMessage());
                }
            }
            outcome.set(Outcome.PATCHED);
            return patched;
        } catch (TriangulationEdgeIndexPatcher.NotApplicable rejected) {
            outcome.set(Outcome.SHAPE_REJECTED);
            diagnostic.set(rejected.getMessage());
            return null;
        }
    }

    private void reportMembership(final String receipt) {
        try {
            membershipReceipt.accept(receipt);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // Diagnostics must not discard an otherwise verified transformation.
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
