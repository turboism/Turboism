package dev.turboism.bootstrap;

/**
 * Contributor for the skipped-frame sorting-info reuse and allocation-free
 * {@code GMeshRenderer.getMeshContainer()} lookup.
 */
final class SortingInfoCacheHookContributor extends NativeOptimizationHookContributor {

    SortingInfoCacheHookContributor() {
        super("TURBOISM_SORTING_INFO_CACHE", VerifiedSortingInfoCacheInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final String explicit = System.getProperty(VerifiedSortingInfoCacheInstaller.ENABLE_PROPERTY);
        final boolean enabled = explicit != null
                ? Boolean.parseBoolean(explicit)
                : (NativeOptimizationPolicy.modelUpdateSkipEnabled(environment)
                        || NativeOptimizationPolicy.clipMaskDeduplicationEnabled(environment));
        if (!enabled) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedSortingInfoCacheInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedSortingInfoCacheInstaller installer = new VerifiedSortingInfoCacheInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(environment, id() + " sortingCache=ACTIVE meshContainerIndexed=ACTIVE");
        return installer;
    }
}
