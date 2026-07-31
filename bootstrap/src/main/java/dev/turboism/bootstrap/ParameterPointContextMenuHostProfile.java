package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;

import java.util.Objects;
import java.util.Optional;

/** Exact Q-menu owners and context classes for reviewed Cubism artifacts. */
record ParameterPointContextMenuHostProfile(String owner, String contextDescriptor) {

    private static final HostArtifactDigest CUBISM_52 = new HostArtifactDigest(
        40_805_584L, "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
    );
    private static final HostArtifactDigest CUBISM_53 = new HostArtifactDigest(
        41_922_739L, "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
    );

    ParameterPointContextMenuHostProfile {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(contextDescriptor, "contextDescriptor");
    }

    static Optional<ParameterPointContextMenuHostProfile> forArtifact(final HostArtifactDigest artifact) {
        if (CUBISM_52.equals(artifact)) return Optional.of(profile("Q"));
        if (CUBISM_53.equals(artifact)) return Optional.of(profile("ab"));
        return Optional.empty();
    }

    private static ParameterPointContextMenuHostProfile profile(final String name) {
        final String owner = "com/live2d/cubism/view/palette/parameter/ui/" + name;
        return new ParameterPointContextMenuHostProfile(owner, "L" + owner + "$b;");
    }
}
