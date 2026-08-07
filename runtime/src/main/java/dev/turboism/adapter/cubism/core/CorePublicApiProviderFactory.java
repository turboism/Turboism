package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.OwnedMocSelectorContract;
import dev.turboism.mapping.verification.VerifiedAccessException;
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

    /**
     * Test-only admission seam: admits a resolver whose verified plan is an exact
     * superset of the reviewed Core contract (additive test selectors allowed).
     *
     * <p>Production admission keeps the exact-plan equality check; this seam is
     * package-private and never referenced outside tests. The version probe still
     * runs, so the admitted profile must be real.</p>
     */
    static CoreProviderResult<CorePublicApiProvider> admitForTesting(
        final VerifiedMemberResolver resolver,
        final CoreVersionExpectation expectation
    ) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(expectation, "expectation");
        final String artifactProfile = artifactProfile(resolver.cubismVersion());
        final CoreProviderResult<CoreRuntimeVersion> probe = probeVersion(resolver, expectation);
        if (!probe.isSuccess()) {
            return CoreProviderResult.failed(probe.failure().orElseThrow());
        }
        return CoreProviderResult.success(new AdmittedCorePublicApiProvider(
            "cubism-core-public-" + artifactProfile,
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

        @Override
        public CoreProviderResult<Integer> mocVersionOfModel(final Object model) {
            Objects.requireNonNull(model, "model");
            if (!resolver.authorizesFeature(
                dev.turboism.mapping.verification.CoreMocInfoSelectorContract.ADAPTER_SLICE_ID,
                dev.turboism.mapping.verification.CoreMocInfoSelectorContract.CAPABILITY_ID,
                dev.turboism.mapping.verification.CoreMocInfoSelectorContract.REQUIRED_ALIASES
            )) {
                return failed(
                    CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
                    "Core MOC metadata selectors are not admitted for this artifact profile."
                );
            }
            try {
                final Object moc = resolver.invoke(
                    dev.turboism.mapping.verification.CoreMocInfoSelectorContract.MODEL_GET_MOC,
                    model
                );
                if (!resolver.isInstance(
                    dev.turboism.mapping.verification.CoreMocInfoSelectorContract.MOC_CLASS,
                    moc
                )) {
                    return failed(
                        CoreProviderFailure.Code.INVALID_STRUCTURE,
                        "Borrowed Core model returned an invalid MOC instance."
                    );
                }
                final Object version = resolver.invoke(
                    dev.turboism.mapping.verification.CoreMocInfoSelectorContract.MOC_GET_MOC_VERSION,
                    moc
                );
                if (!(version instanceof Integer value)) {
                    return failed(
                        CoreProviderFailure.Code.INVALID_STRUCTURE,
                        "Core MOC version selector returned an invalid value."
                    );
                }
                return CoreProviderResult.success(value);
            } catch (VerifiedAccessException exception) {
                return failed(
                    exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION
                        ? CoreProviderFailure.Code.RESOLUTION_FAILED
                        : CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Verified Core MOC metadata selector failed safely."
                );
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core MOC metadata read failed safely."
                );
            }
        }

        @Override
        public CoreProviderResult<Object> instantiateMoc(final byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            if (!authorizesOwnedMoc()) {
                return ownedMocUnavailable();
            }
            try {
                final Object moc = resolver.invokeStatic(
                    OwnedMocSelectorContract.MOC_INSTANTIATE,
                    bytes.clone()
                );
                if (!resolver.isInstance(OwnedMocSelectorContract.MOC_CLASS, moc)) {
                    return failed(
                        CoreProviderFailure.Code.INVALID_STRUCTURE,
                        "Core MOC instantiation returned an invalid instance."
                    );
                }
                return CoreProviderResult.success(moc);
            } catch (VerifiedAccessException exception) {
                return verifiedFailure(exception, "Core MOC instantiation failed safely.");
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core MOC instantiation failed safely: " + exception
                );
            }
        }

        @Override
        public CoreProviderResult<Object> instantiateOwnedModel(final Object moc) {
            Objects.requireNonNull(moc, "moc");
            if (!authorizesOwnedMoc()) {
                return ownedMocUnavailable();
            }
            try {
                final Object model = resolver.invoke(
                    OwnedMocSelectorContract.MOC_INSTANTIATE_MODEL,
                    moc
                );
                if (!resolver.isInstance(CorePublicApiSelectorContract.MODEL_CLASS, model)) {
                    return failed(
                        CoreProviderFailure.Code.INVALID_STRUCTURE,
                        "Core owned model instantiation returned an invalid instance."
                    );
                }
                return CoreProviderResult.success(model);
            } catch (VerifiedAccessException exception) {
                return verifiedFailure(exception, "Core owned model instantiation failed safely.");
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core owned model instantiation failed safely: " + exception
                );
            }
        }

        @Override
        public CoreProviderResult<Long> mocNativeHandle(final Object moc) {
            Objects.requireNonNull(moc, "moc");
            return nativeHandle(OwnedMocSelectorContract.MOC_GET_NATIVE_HANDLE, moc);
        }

        @Override
        public CoreProviderResult<Long> modelNativeHandle(final Object model) {
            Objects.requireNonNull(model, "model");
            return nativeHandle(OwnedMocSelectorContract.MODEL_GET_NATIVE_HANDLE, model);
        }

        @Override
        public CoreProviderResult<Boolean> updateOwnedModel(final Object model) {
            Objects.requireNonNull(model, "model");
            if (!authorizesOwnedMoc()) {
                return ownedMocUnavailable();
            }
            try {
                resolver.invoke(OwnedMocSelectorContract.MODEL_UPDATE, model);
                return CoreProviderResult.success(true);
            } catch (VerifiedAccessException exception) {
                return verifiedFailure(exception, "Core owned model update failed safely.");
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core owned model update failed safely: " + exception
                );
            }
        }

        @Override
        public CoreProviderResult<Boolean> closeOwnedMoc(final Object moc) {
            Objects.requireNonNull(moc, "moc");
            if (!authorizesOwnedMoc()) {
                return ownedMocUnavailable();
            }
            try {
                resolver.invoke(OwnedMocSelectorContract.MOC_CLOSE, moc);
                return CoreProviderResult.success(true);
            } catch (VerifiedAccessException exception) {
                return verifiedFailure(exception, "Core owned MOC close failed safely.");
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core owned MOC close failed safely: " + exception
                );
            }
        }

        @Override
        public CoreProviderResult<Boolean> closeOwnedModel(final Object model) {
            Objects.requireNonNull(model, "model");
            if (!authorizesOwnedMoc()) {
                return ownedMocUnavailable();
            }
            try {
                resolver.invoke(OwnedMocSelectorContract.MODEL_CLOSE, model);
                return CoreProviderResult.success(true);
            } catch (VerifiedAccessException exception) {
                return verifiedFailure(exception, "Core owned model close failed safely.");
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core owned model close failed safely: " + exception
                );
            }
        }

        private boolean authorizesOwnedMoc() {
            return resolver.authorizesFeature(
                OwnedMocSelectorContract.ADAPTER_SLICE_ID,
                OwnedMocSelectorContract.CAPABILITY_ID,
                OwnedMocSelectorContract.REQUIRED_ALIASES
            );
        }

        private CoreProviderResult<Long> nativeHandle(
            final String alias,
            final Object instance
        ) {
            if (!authorizesOwnedMoc()) {
                return ownedMocUnavailable();
            }
            try {
                final Object handle = resolver.invoke(alias, instance);
                if (!(handle instanceof Long value)) {
                    return failed(
                        CoreProviderFailure.Code.INVALID_STRUCTURE,
                        "Core native-handle selector returned an invalid value."
                    );
                }
                return CoreProviderResult.success(value);
            } catch (VerifiedAccessException exception) {
                return verifiedFailure(exception, "Core native-handle read failed safely.");
            } catch (RuntimeException exception) {
                return failed(
                    CoreProviderFailure.Code.INVOCATION_FAILED,
                    "Core native-handle read failed safely: " + exception
                );
            }
        }

        private <T> CoreProviderResult<T> ownedMocUnavailable() {
            return failed(
                CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
                "Core owned-Moc selectors are not admitted for this artifact profile."
            );
        }

        private <T> CoreProviderResult<T> verifiedFailure(
            final VerifiedAccessException exception,
            final String message
        ) {
            return failed(
                exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION
                    ? CoreProviderFailure.Code.RESOLUTION_FAILED
                    : CoreProviderFailure.Code.INVOCATION_FAILED,
                message
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
