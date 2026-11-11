package dev.turboism.bootstrap;

import java.util.List;
import java.util.Optional;

/**
 * The declarative hook registry: every install-time hook whose contributor existed only to restate a
 * report label, a persisted preference and an installer is declared here instead of in its own class.
 *
 * <p>Adding a declarative hook means one entry here, one manifest token, and the installer itself.
 * {@code TurboismAgent} and the manifest reader stay unchanged, and the {@code OptimizationCatalog}
 * row that owns the preference keeps the settings page, the diagnostics protocol and the kill switch
 * in one place.</p>
 */
final class DeclarativeHooks {

    private static final List<DeclarativeHook> ALL = List.of(
            new DeclarativeHook(
                    "TURBOISM_IMAGE_ARCHIVE_REUSE",
                    VerifiedImageArchiveReuseInstaller.HOOK_ID,
                    NativeOptimizationPolicy::imageArchiveReuseEnabled,
                    VerifiedImageArchiveReuseInstaller::new),
            new DeclarativeHook(
                    "TURBOISM_FLOAT_ARRAY_PARSE_CACHE",
                    VerifiedFloatArrayParseCacheInstaller.HOOK_ID,
                    NativeOptimizationPolicy::floatArrayParseCacheEnabled,
                    VerifiedFloatArrayParseCacheInstaller::new),
            new DeclarativeHook(
                    "TURBOISM_TEXTURE_UPLOAD_PREPARATION",
                    VerifiedTextureUploadPreparationInstaller.HOOK_ID,
                    NativeOptimizationPolicy::textureUploadPreparationEnabled,
                    VerifiedTextureUploadPreparationInstaller::new),
            new DeclarativeHook(
                    "TURBOISM_WARP_POSITION_PROJECTION",
                    VerifiedWarpPositionProjectionInstaller.HOOK_ID,
                    NativeOptimizationPolicy::warpPositionProjectionEnabled,
                    VerifiedWarpPositionProjectionInstaller::new),
            new DeclarativeHook(
                    "TURBOISM_MODEL_UPDATE_SKIP",
                    VerifiedModelUpdateSkipInstaller.HOOK_ID,
                    NativeOptimizationPolicy::modelUpdateSkipEnabled,
                    VerifiedModelUpdateSkipInstaller::new),
            new DeclarativeHook(
                    "TURBOISM_INCREMENTAL_UPDATE",
                    VerifiedIncrementalUpdateInstaller.HOOK_ID,
                    NativeOptimizationPolicy::incrementalUpdateEnabled,
                    VerifiedIncrementalUpdateInstaller::new),
            new DeclarativeHook(
                    "TURBOISM_REINIT_PAIR_ELISION",
                    VerifiedReinitPairElisionInstaller.HOOK_ID,
                    NativeOptimizationPolicy::reinitPairElisionEnabled,
                    VerifiedReinitPairElisionInstaller::new));

    private DeclarativeHooks() {}

    /**
     * @return every declarative entry, in registry order
     */
    static List<DeclarativeHook> all() {
        return ALL;
    }

    /**
     * @param key the manifest token
     * @return the entry declared under {@code key}, or empty when no entry claims it
     */
    static Optional<DeclarativeHook> byKey(final String key) {
        return ALL.stream().filter(hook -> hook.key().equals(key)).findFirst();
    }
}
