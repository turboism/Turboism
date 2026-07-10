package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** Sole public factory for attested, verified runtime member resolution. */
final class VerifiedMemberResolverFactory {

    private final StaticVerificationRecordLoader loader = new StaticVerificationRecordLoader();
    private final StaticSelectorVerifier verifier = new StaticSelectorVerifier();
    private final HostClassSourceAttestor attestor = new HostClassSourceAttestor();

    VerifiedMemberResolver create(
        final Path recordPath,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader,
        final String requiredVerificationId,
        final String requiredAdapterSliceId,
        final Set<String> requiredCapabilityIds,
        final Set<String> requiredAliases
    ) throws IOException {
        Objects.requireNonNull(recordPath, "recordPath");
        Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        Objects.requireNonNull(requiredVerificationId, "requiredVerificationId");
        Objects.requireNonNull(requiredAdapterSliceId, "requiredAdapterSliceId");
        Objects.requireNonNull(requiredCapabilityIds, "requiredCapabilityIds");
        Objects.requireNonNull(requiredAliases, "requiredAliases");

        final StaticVerificationRecordLoader.LoadedRecord loaded = loader.load(recordPath);
        final StaticVerificationRecord record = loaded.record();
        if (!record.verificationId().equals(requiredVerificationId)) {
            throw new IllegalArgumentException("verification record ID is not authorized");
        }
        final StaticVerificationReport report = verifier.verify(
            verifiedArtifact,
            record.artifact(),
            record.selectors()
        );
        final VerifiedAccessPlan accessPlan = VerifiedAccessPlan.from(record, report);
        if (!accessPlan.authorizes(
            requiredAdapterSliceId,
            Set.copyOf(requiredCapabilityIds),
            Set.copyOf(requiredAliases)
        )) {
            throw new IllegalArgumentException(
                "verification record does not authorize the exact adapter capabilities and selectors"
            );
        }
        attestor.attest(verifiedArtifact, hostClassLoader, accessPlan.selectors());
        final HostArtifactDigest afterAttestation = HostArtifactDigest.from(verifiedArtifact);
        if (afterAttestation.size() != record.artifact().size()
            || !afterAttestation.sha256().equals(record.artifact().sha256())) {
            throw new IllegalArgumentException("verified artifact changed during runtime attestation");
        }
        return new VerifiedMemberResolver(accessPlan, hostClassLoader);
    }
}
