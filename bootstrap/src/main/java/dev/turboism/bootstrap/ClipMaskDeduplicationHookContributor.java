package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.clipmask.ClipMaskDeduplicationBridge;

/**
 * Contributor for the zero-quality-loss clipping-mask deduplication optimization.
 *
 * <p>Activated when {@code -Dturboism.optimization.clipMaskDeduplication=true}
 * (default true when optimization is enabled) or
 * {@code -Dturboism.validation.clipMaskDeduplication=true}.</p>
 */
final class ClipMaskDeduplicationHookContributor extends NativeOptimizationHookContributor {

    ClipMaskDeduplicationHookContributor() {
        super("TURBOISM_CLIP_MASK_DEDUPE", VerifiedClipMaskDeduplicationInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean validation = Boolean.getBoolean(ClipMaskDeduplicationBridge.VALIDATION_PROPERTY);
        final boolean production = ClipMaskDeduplicationBridge.enabledByPreference();
        if (!validation && !production) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        final VerifiedClipMaskDeduplicationInstaller installer = new VerifiedClipMaskDeduplicationInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " dedupe=ACTIVE sites=" + installer.sites()
                        + " version=" + installer.cubismVersion()
                        + (installer.failure() == null ? "" : " refused=" + installer.failure()));
        return () -> {
            final java.util.Map<String, Long> stats = installer.snapshot();
            installer.close();
            log(environment, id() + " dedupe=CLOSED stats=" + stats);
        };
    }
}
