package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Admits a closeable structural tracer from one already-admitted exact-profile provider. */
final class CoreStructuralTracerFactory {

    private CoreStructuralTracerFactory() {
    }

    static CoreProviderResult<CoreStructuralTracer> admit(
        final CorePublicApiProvider provider,
        final VerifiedMemberResolver resolver
    ) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(resolver, "resolver");

        if (!provider.available()) {
            return failed(
                CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
                "Core public API provider is unavailable."
            );
        }

        final String artifactProfile = provider.artifactProfile();
        final String resolverProfile =
            CorePublicApiProviderFactory.artifactProfile(resolver.cubismVersion());
        final Optional<String> expectedProviderId =
            CorePublicApiSelectorContract.providerIdFor(artifactProfile);
        final Optional<Set<String>> requiredAliases =
            CorePublicApiSelectorContract.requiredAliasesFor(artifactProfile);
        if (expectedProviderId.isEmpty()
            || requiredAliases.isEmpty()
            || !expectedProviderId.orElseThrow().equals(provider.providerId())
            || !artifactProfile.equals(resolverProfile)
            || !resolver.authorizes(
                CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
                CorePublicApiSelectorContract.CAPABILITY_IDS,
                requiredAliases.orElseThrow()
            )) {
            return failed(
                CoreProviderFailure.Code.EVIDENCE_REJECTED,
                "Core provider and verified structural evidence do not match."
            );
        }

        try {
            return CoreProviderResult.success(new CoreStructuralTracer(
                provider.providerId(),
                artifactProfile,
                CoreCallSiteTable.bind(resolver, artifactProfile)
            ));
        } catch (VerifiedAccessException exception) {
            return failed(
                exception.failureKind()
                    == VerifiedAccessException.FailureKind.RESOLUTION
                        ? CoreProviderFailure.Code.RESOLUTION_FAILED
                        : CoreProviderFailure.Code.INVOCATION_FAILED,
                "Core structural call sites could not be bound safely."
            );
        } catch (RuntimeException exception) {
            return failed(
                CoreProviderFailure.Code.RESOLUTION_FAILED,
                "Core structural call-site admission failed safely."
            );
        }
    }

    private static <T> CoreProviderResult<T> failed(
        final CoreProviderFailure.Code code,
        final String message
    ) {
        return CoreProviderResult.failed(new CoreProviderFailure(code, message));
    }
}
