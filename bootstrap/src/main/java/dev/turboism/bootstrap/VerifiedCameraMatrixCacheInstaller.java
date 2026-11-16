package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.camera.CameraMatrixCacheBridge;
import dev.turboism.adapter.cubism.optimization.camera.CameraMatrixCacheTarget;
import dev.turboism.adapter.cubism.optimization.camera.CameraMatrixCacheTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified root-camera world-to-camera matrix cache.
 */
final class VerifiedCameraMatrixCacheInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.render.camera-matrix-cache";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = 1;

    static final int EXPECTED_OWNERS = 1;

    private static final int EXPECTED_SITES = 1;

    private final InstallContext context;
    private VerifiedInstall<CameraMatrixCacheTarget> hook;

    VerifiedCameraMatrixCacheInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm)
            throws IOException {
        return VerifiedInstall.admits(ADMISSION, config, requested, jvm)
                && CameraMatrixCacheTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private WeaveSpec<CameraMatrixCacheTarget> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                "camera matrix cache",
                VerifiedCameraMatrixCacheInstaller::resolve,
                new WeavePlan<>(
                        VerifiedCameraMatrixCacheInstaller::targets, VerifiedCameraMatrixCacheInstaller::sitesProven),
                (target, launch) -> {
                    final CameraMatrixCacheBridge bridge = new CameraMatrixCacheBridge(launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<CameraMatrixCacheTarget> resolve(final Path artifact) throws Exception {
        return CameraMatrixCacheTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final CameraMatrixCacheTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> cameraOwner = source.load(CameraMatrixCacheTarget.CAMERA_OWNER);
        source.load(CameraMatrixCacheTarget.MATRIX_OWNER);
        for (final CameraMatrixCacheTarget.Dep dep : target.dependencies()) {
            HostMemberVerifier.requireMethod(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final CameraMatrixCacheTransformer transformer = new CameraMatrixCacheTransformer(
                source.loader(), source.artifact(), source.referenceBytes(cameraOwner));
        return List.of(WeaveTarget.single(
                cameraOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites =
                targets.get(0).transformer(CameraMatrixCacheTransformer.class).sites();
        return sites == EXPECTED_SITES ? null : "sites=" + sites;
    }

    @Override
    public boolean admits(final InstallContext context) {
        try {
            return admitted(context.artifact(), context.policy(), true, context.jvmFeature());
        } catch (final java.io.IOException unreadable) {
            return false;
        }
    }

    @Override
    public synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<CameraMatrixCacheTarget> installed)) {
            throw new IllegalStateException("camera matrix cache refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
