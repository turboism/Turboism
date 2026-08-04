package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Objects;
import java.util.Optional;

/**
 * Admits an exact-profile Core provider from an already verified resolver.
 *
 * <p>This factory cannot create verified evidence. Production callers will remain disabled until
 * an independently reviewed, digest-pinned Core resolver factory is available.</p>
 */
public final class CorePublicApiProviderFactory {

    private CorePublicApiProviderFactory() {
    }

    public static CoreProviderResult<CorePublicApiProvider> admit(
        final VerifiedMemberResolver resolver,
        final CoreVersionExpectation expectation
    ) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(expectation, "expectation");

        final String artifactProfile = artifactProfile(resolver.cubismVersion());
        final Optional<String> providerId =
            CorePublicApiSelectorContract.providerIdFor(artifactProfile);
        final Optional<java.util.Set<String>> requiredAliases =
            CorePublicApiSelectorContract.requiredAliasesFor(artifactProfile);
        if (providerId.isEmpty() || requiredAliases.isEmpty()) {
            return failed(
                CoreProviderFailure.Code.EVIDENCE_REJECTED,
                "Core artifact profile is outside the supported selector contract."
            );
        }
        if (!resolver.authorizes(
            CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
            CorePublicApiSelectorContract.CAPABILITY_IDS,
            requiredAliases.orElseThrow()
        )) {
            return failed(
                CoreProviderFailure.Code.EVIDENCE_REJECTED,
                "Verified resolver does not authorize the complete Core selector contract."
            );
        }

        final CoreProviderResult<CoreRuntimeVersion> probe = probeVersion(resolver, expectation);
        if (!probe.isSuccess()) {
            return CoreProviderResult.failed(probe.failure().orElseThrow());
        }
        return CoreProviderResult.success(new AdmittedCorePublicApiProvider(
            providerId.orElseThrow(),
            artifactProfile,
            probe.value().orElseThrow(),
            resolver
        ));
    }


    static String artifactProfile(final String reviewedVersion) {
        return switch (reviewedVersion) {
            case "5.2.0" -> "5.2";
            case "5.3.2" -> "5.3.02";
            default -> reviewedVersion;
        };
    }

    private static CoreProviderResult<CoreRuntimeVersion> probeVersion(
        final VerifiedMemberResolver resolver,
        final CoreVersionExpectation expectation
    ) {
        try {
            final Object rawVersion = resolver.invokeStatic(
                CorePublicApiSelectorContract.GET_VERSION
            );
            if (!resolver.isInstance(
                CorePublicApiSelectorContract.CORE_VERSION_CLASS,
                rawVersion
            )) {
                return failed(
                    CoreProviderFailure.Code.INVALID_VERSION,
                    "Core version probe returned an invalid value."
                );
            }

            final Object major = resolver.invoke(
                CorePublicApiSelectorContract.GET_MAJOR,
                rawVersion
            );
            final Object minor = resolver.invoke(
                CorePublicApiSelectorContract.GET_MINOR,
                rawVersion
            );
            final Object patch = resolver.invoke(
                CorePublicApiSelectorContract.GET_PATCH,
                rawVersion
            );
            if (!(major instanceof Integer majorValue)
                || !(minor instanceof Integer minorValue)
                || !(patch instanceof Integer patchValue)) {
                return failed(
                    CoreProviderFailure.Code.INVALID_VERSION,
                    "Core version components have an invalid representation."
                );
            }

            final CoreRuntimeVersion actual;
            try {
                actual = new CoreRuntimeVersion(majorValue, minorValue, patchValue);
            } catch (IllegalArgumentException exception) {
                return failed(
                    CoreProviderFailure.Code.INVALID_VERSION,
                    "Core version components are outside the accepted domain."
                );
            }
            if (!expectation.matches(actual)) {
                return failed(
                    CoreProviderFailure.Code.VERSION_MISMATCH,
                    "Core runtime version " + actual + " does not match reviewed expectation "
                        + expectation.exactVersion() + "."
                );
            }
            return CoreProviderResult.success(actual);
        } catch (VerifiedAccessException exception) {
            if (exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION) {
                return failed(
                    CoreProviderFailure.Code.RESOLUTION_FAILED,
                    "Core version selector could not be resolved from verified evidence."
                );
            }
            return failed(
                CoreProviderFailure.Code.INVOCATION_FAILED,
                "Core version probe execution failed safely."
            );
        } catch (RuntimeException exception) {
            return failed(
                CoreProviderFailure.Code.INVALID_VERSION,
                "Core version probe could not be normalized safely."
            );
        }
    }

    private static <T> CoreProviderResult<T> failed(
        final CoreProviderFailure.Code code,
        final String message
    ) {
        return CoreProviderResult.failed(new CoreProviderFailure(code, message));
    }

    private static final class AdmittedCorePublicApiProvider
        implements CorePublicApiProvider {

        private final String providerId;
        private final String artifactProfile;
        private final CoreRuntimeVersion version;
        private final VerifiedMemberResolver resolver;

        private AdmittedCorePublicApiProvider(
            final String providerId,
            final String artifactProfile,
            final CoreRuntimeVersion version,
            final VerifiedMemberResolver resolver
        ) {
            this.providerId = providerId;
            this.artifactProfile = artifactProfile;
            this.version = version;
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public String artifactProfile() {
            return artifactProfile;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CoreProviderResult<CoreRuntimeVersion> runtimeVersion() {
            return CoreProviderResult.success(version);
        }

        @Override
        public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
            return new dev.turboism.sdk.cubism.core.CoreCapabilities(
                "5.3.02".equals(artifactProfile),
                true,
                true
            );
        }

        @Override
        public CoreProviderResult<Integer> latestMocVersion() {
            return invokeScalar(CorePublicApiSelectorContract.GET_LATEST_MOC_VERSION, Integer.class);
        }

        @Override
        public CoreProviderResult<Integer> mocVersion(final byte[] bytes) {
            return invokeScalar(
                CorePublicApiSelectorContract.GET_MOC_VERSION,
                Integer.class,
                Objects.requireNonNull(bytes, "bytes")
            );
        }

        @Override
        public CoreProviderResult<Boolean> hasMocConsistency(final byte[] bytes) {
            return invokeScalar(
                CorePublicApiSelectorContract.HAS_MOC_CONSISTENCY,
                Boolean.class,
                Objects.requireNonNull(bytes, "bytes")
            );
        }

        private <T> CoreProviderResult<T> invokeScalar(
            final String alias,
            final Class<T> type,
            final Object... arguments
        ) {
            try {
                final Object value = resolver.invokeStatic(alias, arguments);
                if (!type.isInstance(value)) {
                    return failed(
                        CoreProviderFailure.Code.INVALID_STRUCTURE,
                        "Core metadata selector returned an invalid value."
                    );
                }
                return CoreProviderResult.success(type.cast(value));
            } catch (VerifiedAccessException exception) {
                return failed(
                    exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION
                        ? CoreProviderFailure.Code.RESOLUTION_FAILED
                        : CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core metadata selector failed safely."
                );
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core metadata selector failed safely."
                );
            }
        }
    }
}
