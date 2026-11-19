package dev.turboism.adapter.cubism.profile;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact Q-menu owner and context class for one reviewed Cubism generation.
 *
 * <p>The parameter-point menu is opened by two methods of one palette class; both take the class's
 * own inner context type. The reviewed generation decides the class letter, so the profile binds
 * the owner and the context descriptor together instead of restating them per install.</p>
 *
 * @param owner internal name of the palette class that opens the Q-menu
 * @param contextDescriptor JVM descriptor of the inner context type both methods receive
 */
public record ParameterPointContextMenuHostProfile(String owner, String contextDescriptor) {

    /** Palette owner prefix; the reviewed generation supplies the class letter. */
    private static final String OWNER_PREFIX = "com/live2d/cubism/view/palette/parameter/ui/";

    /** Inner context type both menu-opening methods receive. */
    private static final String CONTEXT_SUFFIX = "$b";

    /** Generations before 5.3 share one palette class letter. */
    private static final String LEGACY_OWNER = "Q";

    public ParameterPointContextMenuHostProfile {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(contextDescriptor, "contextDescriptor");
    }

    /**
     * Resolves the reviewed profile for a host artifact.
     *
     * @param artifact the located host artifact digest
     * @return the matching profile, or empty for an unreviewed artifact
     */
    public static Optional<ParameterPointContextMenuHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .flatMap(ParameterPointContextMenuHostProfile::forReviewedVersion);
    }

    /**
     * Resolves the reviewed profile by declared reviewed generation, for a compatibility host that
     * bound its declared version.
     *
     * @param cubismVersion the host-declared reviewed version
     * @return the matching profile, or empty for any unreviewed version
     */
    public static Optional<ParameterPointContextMenuHostProfile> forReviewedVersion(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        if (!ReviewedHostArtifacts.reviewedCubismVersions().contains(cubismVersion)) {
            return Optional.empty();
        }
        if (cubismVersion.startsWith("5.0.") || cubismVersion.startsWith("5.1.") || cubismVersion.startsWith("5.2.")) {
            return Optional.of(profile(LEGACY_OWNER));
        }
        return switch (cubismVersion) {
            case "5.3.00", "5.3.01", "5.3.02" -> Optional.of(profile("ab"));
            case "5.3.03", "5.3.04", "5.3.05" -> Optional.of(profile("ac"));
            default -> Optional.empty();
        };
    }

    private static ParameterPointContextMenuHostProfile profile(final String name) {
        final String owner = OWNER_PREFIX + name;
        return new ParameterPointContextMenuHostProfile(owner, "L" + owner + CONTEXT_SUFFIX + ";");
    }
}
