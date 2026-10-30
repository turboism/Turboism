package dev.turboism.bootstrap;

/** Contributor for the verified image archive reuse optimization (exact 5.3.02). */
final class ImageArchiveReuseHookContributor extends NativeOptimizationHookContributor {

    ImageArchiveReuseHookContributor() {
        super("TURBOISM_IMAGE_ARCHIVE_REUSE", VerifiedImageArchiveReuseInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.imageArchiveReuseEnabled(environment)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedImageArchiveReuseInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedImageArchiveReuseInstaller installer = new VerifiedImageArchiveReuseInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
