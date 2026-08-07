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


    /**
     * Returns the borrowed model's MOC format version (one of the Core
     * {@code Live2DCubismCore.MocVersion} constants), when the exact profile
     * authorizes the MOC metadata selectors.
     */
    default CoreProviderResult<Integer> mocVersionOfModel(final Object model) {
        return unavailableMocOperation();
    }

    /**
     * Builds a plugin-owned Core {@code CubismMoc} from MOC bytes.
     *
     * <p>Fail-closed: requires the additive owned-Moc selector evidence for the exact
     * artifact profile; otherwise returns {@code ADAPTER_UNAVAILABLE}.</p>
     */
    default CoreProviderResult<Object> instantiateMoc(final byte[] bytes) {
        return unavailableOwnedMocOperation();
    }

    /**
     * Instantiates one Core {@code CubismModel} from an owned MOC instance.
     */
    default CoreProviderResult<Object> instantiateOwnedModel(final Object moc) {
        return unavailableOwnedMocOperation();
    }

    /** Returns the native handle of an owned MOC instance. */
    default CoreProviderResult<Long> mocNativeHandle(final Object moc) {
        return unavailableOwnedMocOperation();
    }

    /** Returns the native handle of an owned model instance. */
    default CoreProviderResult<Long> modelNativeHandle(final Object model) {
        return unavailableOwnedMocOperation();
    }

    /** Runs the Core evaluation step on an owned model instance. */
    default CoreProviderResult<Boolean> updateOwnedModel(final Object model) {
        return unavailableOwnedMocOperation();
    }

    /** Closes an owned MOC instance (Core close semantics). */
    default CoreProviderResult<Boolean> closeOwnedMoc(final Object moc) {
        return unavailableOwnedMocOperation();
    }

    /** Closes an owned model instance (Core close semantics). */
    default CoreProviderResult<Boolean> closeOwnedModel(final Object model) {
        return unavailableOwnedMocOperation();
    }

    private static <T> CoreProviderResult<T> unavailableOwnedMocOperation() {
        return CoreProviderResult.failed(new CoreProviderFailure(
            CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
            "Core owned-Moc selector evidence is not admitted for this artifact profile."
        ));
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
