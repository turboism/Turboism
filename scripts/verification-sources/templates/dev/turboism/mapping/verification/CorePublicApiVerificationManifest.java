package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;

import java.util.Map;
import java.util.Objects;

/** Runtime trust roots for the reviewed Cubism Core public read slices. */
final class CorePublicApiVerificationManifest {

    private static final Map<String, PinnedVerifiedResolverWorkflow.Manifest> MANIFESTS = Map.of(
        "${record:cubism-5.2.03-core-model-read.json:cubismVersion}",
        manifest(
            CorePublicApiTrustRoots.verificationId("${record:cubism-5.2.03-core-model-read.json:cubismVersion}"),
            "${record:cubism-5.2.03-core-model-read.json:sha256}",
            "${record:cubism-5.2.03-core-model-read.json:cubismVersion}",
            "${record:cubism-5.2.03-core-model-read.json:cubismVersion}",
            36_237L,
            "${record:cubism-5.2.03-core-model-read.json:artifact.sha256}"
        ),
        "${record:cubism-5.3.02-core-model-read.json:cubismVersion}",
        manifest(
            CorePublicApiTrustRoots.verificationId("${record:cubism-5.3.02-core-model-read.json:cubismVersion}"),
            "${record:cubism-5.3.02-core-model-read.json:sha256}",
            "${record:cubism-5.3.02-core-model-read.json:cubismVersion}",
            "${record:cubism-5.3.02-core-model-read.json:cubismVersion}",
            42_471L,
            "${record:cubism-5.3.02-core-model-read.json:artifact.sha256}"
        )
    );

    private CorePublicApiVerificationManifest() {
    }

    static PinnedVerifiedResolverWorkflow.Manifest require(final String profile) {
        Objects.requireNonNull(profile, "profile");
        final PinnedVerifiedResolverWorkflow.Manifest manifest = MANIFESTS.get(profile);
        if (manifest == null) {
            throw new IllegalArgumentException("unsupported Cubism Core profile: " + profile);
        }
        return manifest;
    }

    static String profileFor(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return MANIFESTS.entrySet().stream()
            .filter(entry -> entry.getValue().artifactSize() == artifact.size()
                && entry.getValue().artifactSha256().equals(artifact.sha256()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unsupported Cubism Core artifact identity"
            ));
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest(
        final String verificationId,
        final String recordSha256,
        final String exactVersion,
        final String profile,
        final long artifactSize,
        final String artifactSha256
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
            recordSha256,
            exactVersion,
            "cubism-" + profile,
            artifactSize,
            artifactSha256,
            CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
            CorePublicApiSelectorContract.CAPABILITY_IDS,
            CorePublicApiSelectorContract.requiredAliasesFor(profile).orElseThrow()
        );
    }
}
