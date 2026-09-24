package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Contributor for the verified texture upload preparation optimization. */
final class TextureUploadPreparationHookContributor extends NativeOptimizationHookContributor {

    TextureUploadPreparationHookContributor() {
        super("TURBOISM_TEXTURE_UPLOAD_PREPARATION");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(TextureUploadPreparationBridge.ENABLE_PROPERTY)) {
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedTextureUploadPreparationInstaller.admitted(
            HostArtifactDigest.from(host.artifact()),
            NativeOptimizationPolicy.load(environment.options().home()),
            true,
            Runtime.version().feature()
        )) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedTextureUploadPreparationInstaller installer =
            new VerifiedTextureUploadPreparationInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
