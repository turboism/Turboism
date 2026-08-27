package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** Shared hardened construction workflow for runtime-owned, hash-pinned verification manifests. */
final class PinnedVerifiedResolverWorkflow {

    private final StaticVerificationRecordLoader loader = new StaticVerificationRecordLoader();
    private final StaticSelectorVerifier verifier = new StaticSelectorVerifier();
    private final HostClassSourceAttestor attestor = new HostClassSourceAttestor();

    VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader,
        final Manifest manifest
    ) throws IOException {
        return create(reviewedRecord, verifiedArtifact, hostClassLoader, manifest, null);
    }

    VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader,
        final Manifest manifest,
        final RuntimeScope runtimeScope
    ) throws IOException {
        Objects.requireNonNull(reviewedRecord, "reviewedRecord");
        Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        Objects.requireNonNull(manifest, "manifest");

        final StaticVerificationRecordLoader.LoadedRecord loaded = loader.load(reviewedRecord);
        if (!loaded.sha256().equals(manifest.recordSha256())) {
            throw new IllegalArgumentException("verification record is not the reviewed trust-root record");
        }
        final StaticVerificationRecord record = loaded.record();
        requireManifestRecord(record, manifest);
        final HostArtifactDigest before = HostArtifactDigest.from(verifiedArtifact);
        if (before.size() != manifest.artifactSize()
            || !before.sha256().equals(manifest.artifactSha256())) {
            throw new IllegalArgumentException("host artifact is not the reviewed Cubism artifact");
        }
        final StaticVerificationReport report = verifier.verify(
            verifiedArtifact,
            record.artifact(),
            record.selectors()
        );
        final VerifiedAccessPlan verifiedPlan = VerifiedAccessPlan.from(record, report);
        final boolean manifestMatches = runtimeScope == null
            ? verifiedPlan.authorizes(
                manifest.adapterSliceId(),
                manifest.capabilityIds(),
                manifest.requiredAliases()
            )
            : verifiedPlan.authorizesFeatureSet(
                manifest.adapterSliceId(),
                manifest.capabilityIds(),
                manifest.requiredAliases()
            );
        if (!manifestMatches) {
            throw new IllegalArgumentException("record selectors do not match the runtime-owned manifest");
        }
        attestor.attest(verifiedArtifact, hostClassLoader, verifiedPlan.selectors());
        final HostArtifactDigest after = HostArtifactDigest.from(verifiedArtifact);
        if (!after.equals(before)) {
            throw new IllegalArgumentException("verified artifact changed during runtime attestation");
        }
        final VerifiedAccessPlan accessPlan = runtimeScope == null
            ? verifiedPlan
            : verifiedPlan.restrictTo(
                runtimeScope.capabilityIds(),
                runtimeScope.requiredAliases()
            );
        return new VerifiedMemberResolver(accessPlan, hostClassLoader);
    }

    private static void requireManifestRecord(
        final StaticVerificationRecord record,
        final Manifest manifest
    ) {
        if (!record.verificationId().equals(manifest.verificationId())
            || !record.adapterSliceId().equals(manifest.adapterSliceId())
            || !record.cubismVersion().equals(manifest.cubismVersion())
            || !record.profileId().equals(manifest.profileId())
            || !Set.copyOf(record.capabilityIds()).equals(manifest.capabilityIds())
            || record.artifact().size() != manifest.artifactSize()
            || !record.artifact().sha256().equals(manifest.artifactSha256())) {
            throw new IllegalArgumentException("verification record fields do not match the reviewed manifest");
        }
    }

    record RuntimeScope(
        Set<String> capabilityIds,
        Set<String> requiredAliases
    ) {
        RuntimeScope {
            capabilityIds = Set.copyOf(capabilityIds);
            requiredAliases = Set.copyOf(requiredAliases);
        }
    }

    record Manifest(
        String verificationId,
        String recordSha256,
        String cubismVersion,
        String profileId,
        long artifactSize,
        String artifactSha256,
        String adapterSliceId,
        Set<String> capabilityIds,
        Set<String> requiredAliases
    ) {
        Manifest {
            Objects.requireNonNull(verificationId, "verificationId");
            Objects.requireNonNull(recordSha256, "recordSha256");
            Objects.requireNonNull(cubismVersion, "cubismVersion");
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(artifactSha256, "artifactSha256");
            Objects.requireNonNull(adapterSliceId, "adapterSliceId");
            capabilityIds = Set.copyOf(capabilityIds);
            requiredAliases = Set.copyOf(requiredAliases);
        }
    }
}
