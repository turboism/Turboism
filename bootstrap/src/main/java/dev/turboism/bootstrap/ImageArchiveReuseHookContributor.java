package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Contributor for the verified image archive reuse optimization (exact 5.3.02). */
final class ImageArchiveReuseHookContributor extends NativeOptimizationHookContributor {

    ImageArchiveReuseHookContributor() {
        super("TURBOISM_IMAGE_ARCHIVE_REUSE");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(ImageArchiveReuseBridge.ENABLE_PROPERTY)) {
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedImageArchiveReuseInstaller.admitted(
            HostArtifactDigest.from(host.artifact()),
            NativeOptimizationPolicy.load(environment.options().home()),
            true
        )) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedImageArchiveReuseInstaller installer = new VerifiedImageArchiveReuseInstaller(
            environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
