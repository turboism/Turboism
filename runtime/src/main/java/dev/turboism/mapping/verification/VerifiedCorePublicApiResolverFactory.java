package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Sole public resolver entrypoint pinned to reviewed Cubism Core public-API evidence. */
public final class VerifiedCorePublicApiResolverFactory {

    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    /**
     * Builds a Core public-API resolver, deriving the Cubism profile from the
     * artifact digest rather than trusting a caller-supplied version.
     *
     * @param reviewedRecord path to the reviewed verification record JSON
     * @param verifiedArtifact path to the host jar being admitted
     * @param hostClassLoader loader the verified members will be resolved
     *     against
     * @return a resolver limited to the aliases the Core manifest authorizes
     * @throws IOException if the record or artifact cannot be read
     * @throws IllegalArgumentException if the artifact is not one of the two
     *     reviewed Cubism builds, or any pinned check fails
     */
    public VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        return create(
            CorePublicApiVerificationManifest.profileFor(
                HostArtifactDigest.from(verifiedArtifact)
            ),
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader
        );
    }

    /**
     * Builds a Core public-API resolver against an explicitly named profile.
     * The profile only selects which manifest to require; it does not relax
     * any check, so naming the wrong profile for an artifact fails rather than
     * admitting it.
     *
     * @param profile Cubism Core profile, {@code "5.2"} or {@code "5.3.02"}
     * @param reviewedRecord path to the reviewed verification record JSON
     * @param verifiedArtifact path to the host jar being admitted
     * @param hostClassLoader loader the verified members will be resolved
     *     against
     * @return a resolver limited to the aliases that profile authorizes
     * @throws IOException if the record or artifact cannot be read
     * @throws IllegalArgumentException if the profile is unsupported or any
     *     pinned check fails
     */
    public VerifiedMemberResolver create(
        final String profile,
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        return workflow.create(
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader,
            CorePublicApiVerificationManifest.require(profile)
        );
    }
}
