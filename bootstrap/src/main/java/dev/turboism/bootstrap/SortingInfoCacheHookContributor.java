package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for the skipped-frame sorting-info reuse and allocation-free
 * {@code GMeshRenderer.getMeshContainer()} lookup.
 *
 * <p>Validation-only, so it persists no preference of its own: the launch plan
 * honours {@code -Dturboism.optimization.sortingInfoCache} first and otherwise
 * derives the default from the three elisions whose state it reuses.</p>
 */
final class SortingInfoCacheHookContributor extends NativeOptimizationHookContributor {

    SortingInfoCacheHookContributor() {
        super("TURBOISM_SORTING_INFO_CACHE", VerifiedSortingInfoCacheInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        // The probe persists no preference: the plan resolves its explicit switch first and
        // otherwise derives the default from the three elisions it reuses state from.
        if (!environment.plan().admitted(OptimizationCatalog.SORTING_INFO_CACHE)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedSortingInfoCacheInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedSortingInfoCacheInstaller installer =
                new VerifiedSortingInfoCacheInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " sortingCache=ACTIVE meshContainerIndexed=ACTIVE");
        return installer;
    }
}
