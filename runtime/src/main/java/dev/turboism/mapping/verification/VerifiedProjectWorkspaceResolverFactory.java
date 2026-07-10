package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Sole public resolver entrypoint pinned to the reviewed project/workspace trust root. */
public final class VerifiedProjectWorkspaceResolverFactory {

    private final StaticVerificationRecordLoader loader = new StaticVerificationRecordLoader();
    private final StaticSelectorVerifier verifier = new StaticSelectorVerifier();
    private final HostClassSourceAttestor attestor = new HostClassSourceAttestor();

    public VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        Objects.requireNonNull(reviewedRecord, "reviewedRecord");
        Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");

        final StaticVerificationRecordLoader.LoadedRecord loaded = loader.load(reviewedRecord);
        if (!loaded.sha256().equals(ProjectWorkspaceVerificationManifest.RECORD_SHA256)) {
            throw new IllegalArgumentException("verification record is not the reviewed trust-root record");
        }
        final StaticVerificationRecord record = loaded.record();
        requireManifestRecord(record);
        final HostArtifactDigest before = HostArtifactDigest.from(verifiedArtifact);
        if (before.size() != ProjectWorkspaceVerificationManifest.ARTIFACT_SIZE
            || !before.sha256().equals(ProjectWorkspaceVerificationManifest.ARTIFACT_SHA256)) {
            throw new IllegalArgumentException("host artifact is not the reviewed Cubism 5.3.02 artifact");
        }
        final StaticVerificationReport report = verifier.verify(
            verifiedArtifact,
            record.artifact(),
            record.selectors()
        );
        final VerifiedAccessPlan accessPlan = VerifiedAccessPlan.from(record, report);
        if (!accessPlan.authorizes(
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES
        )) {
            throw new IllegalArgumentException("record selectors do not match the runtime-owned manifest");
        }
        attestor.attest(verifiedArtifact, hostClassLoader, accessPlan.selectors());
        final HostArtifactDigest after = HostArtifactDigest.from(verifiedArtifact);
        if (!after.equals(before)) {
            throw new IllegalArgumentException("verified artifact changed during runtime attestation");
        }
        return new VerifiedMemberResolver(accessPlan, hostClassLoader);
    }

    private static void requireManifestRecord(final StaticVerificationRecord record) {
        if (!record.verificationId().equals(ProjectWorkspaceVerificationManifest.VERIFICATION_ID)
            || !record.adapterSliceId().equals(ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID)
            || !record.cubismVersion().equals(ProjectWorkspaceVerificationManifest.CUBISM_VERSION)
            || !record.profileId().equals(ProjectWorkspaceVerificationManifest.PROFILE_ID)
            || !java.util.Set.copyOf(record.capabilityIds()).equals(
                ProjectWorkspaceVerificationManifest.CAPABILITY_IDS
            )
            || record.artifact().size() != ProjectWorkspaceVerificationManifest.ARTIFACT_SIZE
            || !record.artifact().sha256().equals(ProjectWorkspaceVerificationManifest.ARTIFACT_SHA256)) {
            throw new IllegalArgumentException("verification record fields do not match the reviewed manifest");
        }
    }
}
