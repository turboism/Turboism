package dev.turboism.kernel;

import dev.turboism.adapter.HostVerificationEvidence;
import dev.turboism.mapping.verification.CompatibilityResolution;
import dev.turboism.mapping.verification.CubismEditorReleaseDeclaration;
import dev.turboism.mapping.verification.CubismEditorReleaseDetector;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Host-artifact admission and verification-evidence assembly for {@link KernelRuntime} startup.
 *
 * <p>The admission contract is the same one {@code KernelRuntime.start} documents: {@code null}
 * resolution keeps the legacy exact-review requirement, a {@code COMPATIBLE} resolution binds
 * admitted slices structurally, and a {@code REJECTED} verdict always fails closed.</p>
 */
final class KernelHostAdmission {

    private KernelHostAdmission() {}

    /**
     * Admission gate for the editor artifact. {@code hostResolution == null} keeps the legacy exact
     * requirement; a resolution must be DECLARED — {@code VERIFIED} hosts satisfy the
     * reviewed-artifact check as before, {@code COMPATIBLE} hosts are admitted on their structurally
     * verified contracts instead, and {@code REJECTED} verdicts always fail closed.
     */
    static String requireAdmittedEditorRelease(final Path hostArtifact, final CompatibilityResolution hostResolution)
            throws IOException {
        if (hostResolution == null) {
            return requireReviewedEditorRelease(hostArtifact);
        }
        if (hostResolution.mode() == CompatibilityResolution.Mode.REJECTED) {
            throw new IllegalStateException("Cubism host identity rejected: " + hostResolution.detail());
        }
        if (!hostResolution.runtimeAdmitted()) {
            throw new IllegalStateException("Cubism admission resolved no base runtime capability");
        }
        if (!dev.turboism.mapping.verification.HostArtifactDigest.from(hostArtifact)
                .equals(hostResolution.identity().orElseThrow().artifact())) {
            throw new IllegalStateException("Cubism host artifact changed since compatibility probing");
        }
        if (hostResolution.mode() == CompatibilityResolution.Mode.COMPATIBLE) {
            return hostResolution.declaredVersion();
        }
        return requireReviewedEditorRelease(hostArtifact);
    }

    /**
     * Version published to the editor-lifecycle startup event. Compatibility resolutions publish the
     * host-declared version; the legacy path keeps the exact reviewed profile lookup.
     */
    static String startupHostVersion(final Path normalizedHostArtifact, final CompatibilityResolution hostResolution)
            throws IOException {
        if (hostResolution != null) {
            return hostResolution.declaredVersion();
        }
        return dev.turboism.mapping.verification.EditorModelVerificationManifest.resourceProfileForArtifact(
                dev.turboism.mapping.verification.HostArtifactDigest.from(normalizedHostArtifact));
    }

    /**
     * Builds an evidence slice, attaching the admitted compatibility contract when a resolution
     * governs this start. A resolution in {@code COMPATIBLE} mode must have admitted the slice; a
     * missing contract fails closed so a caller cannot bypass per-feature admission by passing a
     * record anyway.
     */
    static HostVerificationEvidence.Slice editorSlice(
            final Path record,
            final Path artifact,
            final ClassLoader hostClassLoader,
            final CompatibilityResolution hostResolution,
            final String sliceId) {
        if (hostResolution == null) {
            return new HostVerificationEvidence.Slice(record, artifact, hostClassLoader);
        }
        final CompatibilityResolution.SliceResolution slice = hostResolution.slice(sliceId);
        if (hostResolution.mode() == CompatibilityResolution.Mode.COMPATIBLE
                && slice.contract().isEmpty()) {
            throw new IllegalStateException(
                    "record passed for slice " + sliceId + " that compatibility admission rejected");
        }
        return new HostVerificationEvidence.Slice(record, artifact, hostClassLoader, slice.contract());
    }

    /** Package-private exact release/artifact agreement seam for focused admission tests. */
    static String requireReviewedEditorRelease(final Path hostArtifact) throws IOException {
        final Path normalized = Objects.requireNonNull(hostArtifact, "hostArtifact")
                .toAbsolutePath()
                .normalize();
        final CubismEditorReleaseDeclaration declaration = CubismEditorReleaseDetector.detect(normalized)
                .orElseThrow(() -> new IllegalStateException(
                        "Cubism host release declaration missing or ambiguous; admission failed closed"));
        final String reviewedVersion = ReviewedHostArtifacts.cubismVersionOf(
                        dev.turboism.mapping.verification.HostArtifactDigest.from(normalized))
                .orElseThrow(() -> new IllegalStateException(
                        "Cubism host artifact is not an exact reviewed identity; admission failed closed"));
        return requireReleaseAgreement(reviewedVersion, declaration.version());
    }

    /** Package-private semantic release and exact-artifact agreement seam for focused tests. */
    static String requireReleaseAgreement(final String reviewedVersion, final String declaredVersion) {
        final String reviewed = Objects.requireNonNull(reviewedVersion, "reviewedVersion");
        final String declared = Objects.requireNonNull(declaredVersion, "declaredVersion");
        if (!reviewed.equals(declared)) {
            throw new IllegalStateException("Cubism host release/artifact mismatch; admission failed closed");
        }
        return reviewed;
    }
}
