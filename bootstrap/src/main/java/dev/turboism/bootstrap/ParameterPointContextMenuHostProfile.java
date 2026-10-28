package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Objects;
import java.util.Optional;

/** Exact Q-menu owners and context classes for reviewed Cubism artifacts. */
record ParameterPointContextMenuHostProfile(String owner, String contextDescriptor) {

    ParameterPointContextMenuHostProfile {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(contextDescriptor, "contextDescriptor");
    }

    static Optional<ParameterPointContextMenuHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .flatMap(ParameterPointContextMenuHostProfile::forReviewedVersion);
    }

    /**
     * Resolves the reviewed parameter-point profile by declared reviewed
     * generation for a compatibility host that bound its declared version.
     */
    static Optional<ParameterPointContextMenuHostProfile> forReviewedVersion(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        if (!ReviewedHostArtifacts.reviewedCubismVersions().contains(cubismVersion)) {
            return Optional.empty();
        }
        if (cubismVersion.startsWith("5.0.") || cubismVersion.startsWith("5.1.") || cubismVersion.startsWith("5.2.")) {
            return Optional.of(profile("Q"));
        }
        return switch (cubismVersion) {
            case "5.3.00", "5.3.01", "5.3.02" -> Optional.of(profile("ab"));
            case "5.3.03", "5.3.04" -> Optional.of(profile("ac"));
            default -> Optional.empty();
        };
    }

    private static ParameterPointContextMenuHostProfile profile(final String name) {
        final String owner = "com/live2d/cubism/view/palette/parameter/ui/" + name;
        return new ParameterPointContextMenuHostProfile(owner, "L" + owner + "$b;");
    }
}
