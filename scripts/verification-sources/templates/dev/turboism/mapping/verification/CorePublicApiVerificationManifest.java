package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;

import java.util.Map;
import java.util.Objects;

/** Runtime trust roots for the reviewed Cubism Core public read slices. */
final class CorePublicApiVerificationManifest {

    private static final Map<String, PinnedVerifiedResolverWorkflow.Manifest> MANIFESTS = Map.ofEntries(
        Map.entry(
            "${record:cubism-5.0.00-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.0.00-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.0.00-core-model-read.json:sha256}",
                "${record:cubism-5.0.00-core-model-read.json:cubismVersion}",
                "${record:cubism-5.0.00-core-model-read.json:cubismVersion}",
                29_924L,
                "${record:cubism-5.0.00-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.0.01-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.0.01-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.0.01-core-model-read.json:sha256}",
                "${record:cubism-5.0.01-core-model-read.json:cubismVersion}",
                "${record:cubism-5.0.01-core-model-read.json:cubismVersion}",
                29_924L,
                "${record:cubism-5.0.01-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.0.02-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.0.02-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.0.02-core-model-read.json:sha256}",
                "${record:cubism-5.0.02-core-model-read.json:cubismVersion}",
                "${record:cubism-5.0.02-core-model-read.json:cubismVersion}",
                29_919L,
                "${record:cubism-5.0.02-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.0.03-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.0.03-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.0.03-core-model-read.json:sha256}",
                "${record:cubism-5.0.03-core-model-read.json:cubismVersion}",
                "${record:cubism-5.0.03-core-model-read.json:cubismVersion}",
                29_919L,
                "${record:cubism-5.0.03-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.0.04-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.0.04-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.0.04-core-model-read.json:sha256}",
                "${record:cubism-5.0.04-core-model-read.json:cubismVersion}",
                "${record:cubism-5.0.04-core-model-read.json:cubismVersion}",
                29_923L,
                "${record:cubism-5.0.04-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.0.05-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.0.05-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.0.05-core-model-read.json:sha256}",
                "${record:cubism-5.0.05-core-model-read.json:cubismVersion}",
                "${record:cubism-5.0.05-core-model-read.json:cubismVersion}",
                29_918L,
                "${record:cubism-5.0.05-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.0.06-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.0.06-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.0.06-core-model-read.json:sha256}",
                "${record:cubism-5.0.06-core-model-read.json:cubismVersion}",
                "${record:cubism-5.0.06-core-model-read.json:cubismVersion}",
                30_155L,
                "${record:cubism-5.0.06-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.1.00-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.1.00-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.1.00-core-model-read.json:sha256}",
                "${record:cubism-5.1.00-core-model-read.json:cubismVersion}",
                "${record:cubism-5.1.00-core-model-read.json:cubismVersion}",
                30_153L,
                "${record:cubism-5.1.00-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.1.01-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.1.01-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.1.01-core-model-read.json:sha256}",
                "${record:cubism-5.1.01-core-model-read.json:cubismVersion}",
                "${record:cubism-5.1.01-core-model-read.json:cubismVersion}",
                30_161L,
                "${record:cubism-5.1.01-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.1.02-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.1.02-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.1.02-core-model-read.json:sha256}",
                "${record:cubism-5.1.02-core-model-read.json:cubismVersion}",
                "${record:cubism-5.1.02-core-model-read.json:cubismVersion}",
                30_157L,
                "${record:cubism-5.1.02-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.1.03-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.1.03-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.1.03-core-model-read.json:sha256}",
                "${record:cubism-5.1.03-core-model-read.json:cubismVersion}",
                "${record:cubism-5.1.03-core-model-read.json:cubismVersion}",
                30_156L,
                "${record:cubism-5.1.03-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.2.00-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.2.00-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.2.00-core-model-read.json:sha256}",
                "${record:cubism-5.2.00-core-model-read.json:cubismVersion}",
                "${record:cubism-5.2.00-core-model-read.json:cubismVersion}",
                36_241L,
                "${record:cubism-5.2.00-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.2.01-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.2.01-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.2.01-core-model-read.json:sha256}",
                "${record:cubism-5.2.01-core-model-read.json:cubismVersion}",
                "${record:cubism-5.2.01-core-model-read.json:cubismVersion}",
                36_243L,
                "${record:cubism-5.2.01-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.2.02-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.2.02-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.2.02-core-model-read.json:sha256}",
                "${record:cubism-5.2.02-core-model-read.json:cubismVersion}",
                "${record:cubism-5.2.02-core-model-read.json:cubismVersion}",
                36_243L,
                "${record:cubism-5.2.02-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.2.03-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.2.03-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.2.03-core-model-read.json:sha256}",
                "${record:cubism-5.2.03-core-model-read.json:cubismVersion}",
                "${record:cubism-5.2.03-core-model-read.json:cubismVersion}",
                36_237L,
                "${record:cubism-5.2.03-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.3.02-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.3.02-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.3.02-core-model-read.json:sha256}",
                "${record:cubism-5.3.02-core-model-read.json:cubismVersion}",
                "${record:cubism-5.3.02-core-model-read.json:cubismVersion}",
                42_471L,
                "${record:cubism-5.3.02-core-model-read.json:artifact.sha256}"
            )
        ),
        Map.entry(
            "${record:cubism-5.3.04-core-model-read.json:cubismVersion}",
            manifest(
                CorePublicApiTrustRoots.verificationId("${record:cubism-5.3.04-core-model-read.json:cubismVersion}"),
                "${record:cubism-5.3.04-core-model-read.json:sha256}",
                "${record:cubism-5.3.04-core-model-read.json:cubismVersion}",
                "${record:cubism-5.3.04-core-model-read.json:cubismVersion}",
                43_708L,
                "${record:cubism-5.3.04-core-model-read.json:artifact.sha256}"
            )
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
            CorePublicApiSelectorContract.capabilityIdsFor(profile).orElseThrow(),
            CorePublicApiSelectorContract.requiredAliasesFor(profile).orElseThrow()
        );
    }
}
