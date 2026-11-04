package dev.turboism.bootstrap;

/**
 * Contributor for the line-renderer dirty-buffer split under the upload-elision
 * preference.
 *
 * <p>The generic skipped-frame upload elision is refused when the uniform-location
 * cache is enabled because it retains GL payload baselines across frames. This split
 * does not retain GL baselines: it compares the renderer's source arrays before the
 * host marks them dirty, so it is safe with the uniform cache and remains useful
 * during deformation. Only the upload-elision preference gates it.</p>
 */
final class LineRendererUploadSplitHookContributor extends NativeOptimizationHookContributor {

    static final String ENABLE_PROPERTY = "turboism.validation.lineRendererUploadSplit";

    LineRendererUploadSplitHookContributor() {
        super("TURBOISM_LINE_RENDERER_UPLOAD_SPLIT", VerifiedLineRendererUploadSplitInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean production = NativeOptimizationPolicy.uploadElisionEnabled(environment);
        final String override = System.getProperty(ENABLE_PROPERTY);
        final boolean enabled = override == null ? production : Boolean.parseBoolean(override);
        if (!enabled) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedLineRendererUploadSplitInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedLineRendererUploadSplitInstaller installer = new VerifiedLineRendererUploadSplitInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(environment, id() + " uploadSplit=ACTIVE");
        return installer;
    }
}
