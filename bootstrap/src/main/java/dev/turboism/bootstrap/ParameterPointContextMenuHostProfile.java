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
        if (CUBISM_52.equals(artifact)) return Optional.of(profile("Q"));
        if (CUBISM_53.equals(artifact)) return Optional.of(profile("ab"));
        if (CUBISM_5303.equals(artifact)) return Optional.of(profile("ac"));
        return Optional.empty();
    }

    private static ParameterPointContextMenuHostProfile profile(final String name) {
        final String owner = "com/live2d/cubism/view/palette/parameter/ui/" + name;
        return new ParameterPointContextMenuHostProfile(owner, "L" + owner + "$b;");
    }
}
