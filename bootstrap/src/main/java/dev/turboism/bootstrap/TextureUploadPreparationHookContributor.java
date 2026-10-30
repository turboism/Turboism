package dev.turboism.bootstrap;

/** Contributor for the verified texture upload preparation optimization. */
final class TextureUploadPreparationHookContributor extends NativeOptimizationHookContributor {

    TextureUploadPreparationHookContributor() {
        super("TURBOISM_TEXTURE_UPLOAD_PREPARATION", VerifiedTextureUploadPreparationInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.textureUploadPreparationEnabled(environment)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedTextureUploadPreparationInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedTextureUploadPreparationInstaller installer = new VerifiedTextureUploadPreparationInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
