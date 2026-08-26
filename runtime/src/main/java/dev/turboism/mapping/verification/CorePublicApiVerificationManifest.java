package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;

import java.util.Map;
import java.util.Objects;

/** Runtime trust roots for the reviewed Cubism Core public read slices. */
final class CorePublicApiVerificationManifest {

    private static final Map<String, PinnedVerifiedResolverWorkflow.Manifest> MANIFESTS = Map.of(
        "5.2.03",
        manifest(
            CorePublicApiTrustRoots.verificationId("5.2.03"),
            "01d59d8b040bcce13c3b4a9b6a9fd731546ccad730692707ad954d1e57f69a87",
            "5.2.03",
            "5.2.03",
            36_237L,
            "85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e"
        ),
        "5.3.02",
        manifest(
            CorePublicApiTrustRoots.verificationId("5.3.02"),
            "416ec0e9e45e1dfd44216969aa02a5171eb0688145b3738f583beb9bafe5131e",
            "5.3.02",
            "5.3.02",
            42_471L,
            "98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4"
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
