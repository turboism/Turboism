package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationBridge;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationTarget;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Exact-host admission, bridge binding, and verified restoration for warp grid interpolation.
 */
final class VerifiedWarpGridInterpolationInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.deformer.warp-grid-interpolation";

    /** The frozen admission row for this hook. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    /** Three rewritten helper/grid method sites expected on retransformation. */
    static final int EXPECTED_MATCHES = 3;

    /** Three rewritten host owners restored on close. */
    static final int EXPECTED_OWNERS = 3;

    private static final String FEATURE_NAME = "warp grid interpolation";
    private static final String DEP_SOURCE_MISMATCH = "warp grid dependency loader/source mismatch";

    static final WeaveSpec<WarpGridInterpolationTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedWarpGridInterpolationInstaller::resolve,
            new WeavePlan<>(VerifiedWarpGridInterpolationInstaller::targets, WeavePlan.PostCheck.NONE),
            (target, context) -> {
                final WarpGridInterpolationBridge bridge = WarpGridInterpolationBridge.open(context.hostLoader());
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<WarpGridInterpolationTarget> hook;

    VerifiedWarpGridInterpolationInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws IOException {
        // Evaluate shared admission before opening the artifact digest.
        return VerifiedInstall.admits(
                        ADMISSION, config, requested, Runtime.version().feature())
                && WarpGridInterpolationTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<WarpGridInterpolationTarget> resolve(final Path artifact) throws Exception {
        return WarpGridInterpolationTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final WarpGridInterpolationTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> owner = source.load(WarpGridInterpolationTarget.OWNER);
        final Class<?> simpleGridOwner = source.load(WarpGridInterpolationTarget.SIMPLE_GRID_OWNER);
        final Class<?> borderGridOwner = source.load(WarpGridInterpolationTarget.BORDER_GRID_OWNER);
        source.load(WarpGridInterpolationTarget.BORDER_GRID_ITEM_OWNER);
        source.load(WarpGridInterpolationTarget.GRECTF_OWNER);
        source.load(WarpGridInterpolationTarget.GVECTOR2_OWNER);
        for (final WarpGridInterpolationTarget.Dep dep : target.dependencies()) {
            final Class<?> depOwner = source.load(dep.owner());
            VerifiedHookInstallerSupport.attestClassSource(
                    depOwner, source.loader(), source.artifact(), DEP_SOURCE_MISMATCH);
            HostMemberVerifier.requireMethod(depOwner, dep.name(), dep.descriptor());
        }
        final WarpGridInterpolationTransformer transformer = new WarpGridInterpolationTransformer(
                source.loader(),
                source.artifact(),
                source.referenceBytes(owner),
                source.referenceBytes(simpleGridOwner),
                source.referenceBytes(borderGridOwner),
                target);
        return List.of(
                WeaveTarget.single(owner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure),
                WeaveTarget.single(
                        simpleGridOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure),
                WeaveTarget.single(
                        borderGridOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    @Override
    public boolean admits(final InstallContext context) {
        try {
            return admitted(context.artifact(), context.policy(), true);
        } catch (final java.io.IOException unreadable) {
            return false;
        }
    }

    @Override
    public synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<WarpGridInterpolationTarget> installed)) {
            throw new IllegalStateException("warp grid interpolation refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) {
            hook.close();
        }
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
