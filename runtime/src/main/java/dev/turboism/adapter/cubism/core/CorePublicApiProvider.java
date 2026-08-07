package dev.turboism.adapter.cubism.core;

/**
 * Runtime-internal, version-normalized seam over Cubism Core's public API.
 *
 * <p>Plugins never receive this provider or a raw Core value. Later model reads extend this seam
 * with immutable adapter-owned projections.</p>
 */
public interface CorePublicApiProvider {

    String providerId();

    String artifactProfile();

    boolean available();

    CoreProviderResult<CoreRuntimeVersion> runtimeVersion();

    default dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
        return new dev.turboism.sdk.cubism.core.CoreCapabilities(
            "5.3.02".equals(artifactProfile()),
            true,
            false
        );
    }

    default CoreProviderResult<Integer> latestMocVersion() {
        return unavailableMocOperation();
    }

    default CoreProviderResult<Integer> mocVersion(final byte[] bytes) {
        return unavailableMocOperation();
    }

    default CoreProviderResult<Boolean> hasMocConsistency(final byte[] bytes) {
        return unavailableMocOperation();
    }

    private static <T> CoreProviderResult<T> unavailableMocOperation() {
        return CoreProviderResult.failed(new CoreProviderFailure(
            CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
            "Core MOC selector evidence is not admitted for this artifact profile."
        ));
    }

    static CorePublicApiProvider safeMode() {
        return UnavailableCorePublicApiProvider.INSTANCE;
    }
}

final class UnavailableCorePublicApiProvider implements CorePublicApiProvider {

    static final UnavailableCorePublicApiProvider INSTANCE =
        new UnavailableCorePublicApiProvider();

    private UnavailableCorePublicApiProvider() {
    }

    @Override
    public String providerId() {
        return "cubism-core-unavailable";
    }

    @Override
    public String artifactProfile() {
        return "unavailable";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public CoreProviderResult<CoreRuntimeVersion> runtimeVersion() {
        return CoreProviderResult.failed(new CoreProviderFailure(
            CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
            "Cubism Core public API provider is unavailable; safe mode is active."
        ));
    }

    @Override
    public dev.turboism.sdk.cubism.core.CoreCapabilities capabilities() {
        return new dev.turboism.sdk.cubism.core.CoreCapabilities(false, false, false);
    }
}
