package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Objects;

/**
 * Reviewed-generation admission for the Editor-model stored authoring, keyform, evaluation and
 * hierarchy surfaces.
 *
 * <p>Every selector these accessors resolve is declared by the per-version
 * {@code cubism-<version>-editor-model} verification record, so the admitted version table is
 * derived from {@link ReviewedHostArtifacts#reviewedCubismVersions()} rather than restated as a
 * literal per-feature set. Admission still demands an exact reviewed artifact: a
 * compatibility-bound host passed selector-level structure checks only and never carries
 * real-host evidence for these write and read-back paths.</p>
 *
 * <p>Capability and alias authorisation stays separate — callers combine this check with
 * {@link VerifiedMemberResolver#authorizesFeature(String, String, java.util.Set)} so a host whose
 * record lacks the feature's selectors is still refused.</p>
 */
final class EditorReviewedGeneration {

    private EditorReviewedGeneration() {}

    /**
     * Returns whether the resolver is bound to an exact reviewed Cubism Editor artifact.
     *
     * @param resolver the verified member resolver of the active host binding
     * @return {@code true} only for an exact (non compatibility-bound) reviewed artifact
     * @throws NullPointerException when {@code resolver} is null
     */
    static boolean admits(final VerifiedMemberResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        return !resolver.isCompatibilityBinding()
                && ReviewedHostArtifacts.reviewedCubismVersions().contains(resolver.cubismVersion());
    }
}
