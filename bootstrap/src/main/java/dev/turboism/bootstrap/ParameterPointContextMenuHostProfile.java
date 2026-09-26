package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

import java.util.Objects;
import java.util.Optional;

/** Exact Q-menu owners and context classes for reviewed Cubism artifacts. */
record ParameterPointContextMenuHostProfile(String owner, String contextDescriptor) {

    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;

    ParameterPointContextMenuHostProfile {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(contextDescriptor, "contextDescriptor");
    }

    static Optional<ParameterPointContextMenuHostProfile> forArtifact(final HostArtifactDigest artifact) {
        if (CUBISM_52.equals(artifact)) {
            return forReviewedVersion(ReviewedHostArtifacts.CUBISM_5_2_03_VERSION);
        }
        if (CUBISM_53.equals(artifact)) {
            return forReviewedVersion(ReviewedHostArtifacts.CUBISM_5_3_02_VERSION);
        }
        if (CUBISM_5303.equals(artifact)) {
            return forReviewedVersion(ReviewedHostArtifacts.CUBISM_5_3_03_VERSION);
        }
        return Optional.empty();
    }

    /**
     * Resolves the reviewed parameter-point profile by declared reviewed
     * generation for a compatibility host that bound its declared version.
     */
    static Optional<ParameterPointContextMenuHostProfile> forReviewedVersion(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        if (ReviewedHostArtifacts.CUBISM_5_2_03_VERSION.equals(cubismVersion)) {
            return Optional.of(profile("Q"));
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02_VERSION.equals(cubismVersion)) {
            return Optional.of(profile("ab"));
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03_VERSION.equals(cubismVersion)) {
            return Optional.of(profile("ac"));
        }
        return Optional.empty();
    }

    private static ParameterPointContextMenuHostProfile profile(final String name) {
        final String owner = "com/live2d/cubism/view/palette/parameter/ui/" + name;
        return new ParameterPointContextMenuHostProfile(owner, "L" + owner + "$b;");
    }
}
