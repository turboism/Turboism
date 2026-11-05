package dev.turboism.bootstrap;

/** Contributor for the verified texture upload preparation optimization. */
final class TextureUploadPreparationHookContributor extends NativeOptimizationHookContributor {

    TextureUploadPreparationHookContributor() {
        super("TURBOISM_TEXTURE_UPLOAD_PREPARATION", VerifiedTextureUploadPreparationInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.textureUploadPreparationEnabled(environment)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        // The launch startup policy snapshot, never a re-read of config.json: a hook that re-read the
        // home directory could admit differently from the agent that gated it.
        if (!VerifiedTextureUploadPreparationInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedTextureUploadPreparationInstaller installer =
                new VerifiedTextureUploadPreparationInstaller(InstallContext.of(environment));
        installer.install();
        return installer;
    }
}
