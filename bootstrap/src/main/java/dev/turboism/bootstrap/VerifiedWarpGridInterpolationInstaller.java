package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationBridge;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationTarget;
import dev.turboism.adapter.cubism.optimization.warpgrid.WarpGridInterpolationTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Exact-host admission, bridge binding, and verified restoration for warp grid interpolation.
 */
final class VerifiedWarpGridInterpolationInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.deformer.warp-grid-interpolation";

    /** The frozen admission row for this hook. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    /** Single rewritten helper method site expected on retransformation. */
    static final int EXPECTED_MATCHES = 1;

    /** Single rewritten helper class owner re-captured during restoration proof. */
    static final int EXPECTED_OWNERS = 1;

    private static final String FEATURE_NAME = "warp grid interpolation";
    private static final String DEP_SOURCE_MISMATCH = "warp grid dependency loader/source mismatch";

    static final WeaveSpec<WarpGridInterpolationTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedWarpGridInterpolationInstaller::resolve,
            new WeavePlan<>(VerifiedWarpGridInterpolationInstaller::targets, WeavePlan.PostCheck.NONE),
            (target, context) -> {
                final WarpGridInterpolationBridge bridge =
                        WarpGridInterpolationBridge.open(context.hostLoader());
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
        return VerifiedInstall.admits(ADMISSION, config, requested, Runtime.version().feature())
                && WarpGridInterpolationTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<WarpGridInterpolationTarget> resolve(final Path artifact) throws Exception {
        return WarpGridInterpolationTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(
            final WarpGridInterpolationTarget target, final WeaveSource source) throws Exception {
        final Class<?> owner = source.load(WarpGridInterpolationTarget.OWNER);
        for (final WarpGridInterpolationTarget.Dep dep : target.dependencies()) {
            final Class<?> depOwner = source.load(dep.owner());
            VerifiedHookInstallerSupport.attestClassSource(
                    depOwner, source.loader(), source.artifact(), DEP_SOURCE_MISMATCH);
            verifyMethodPresent(depOwner, dep.name(), dep.descriptor());
        }
        final WarpGridInterpolationTransformer transformer = new WarpGridInterpolationTransformer(
                source.loader(), source.artifact(), source.referenceBytes(owner), target);
        return List.of(WeaveTarget.single(
                owner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        for (final Method method : owner.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            final String desc = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (desc.equals(descriptor)) {
                return;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
    }

    synchronized void install() throws Exception {
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
