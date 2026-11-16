package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.warpdraw.WarpNormalPointElisionTarget;
import dev.turboism.adapter.cubism.optimization.warpdraw.WarpNormalPointElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified disabled outside-of-parent warp control-point elision weave.
 */
final class VerifiedWarpNormalPointElisionInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.deformer.warp-normal-point-elision";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    static final int EXPECTED_MATCHES = 1;

    static final int EXPECTED_OWNERS = 1;

    private static final int EXPECTED_SITES = 1;

    static final WeaveSpec<WarpNormalPointElisionTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            "warp normal-point elision",
            VerifiedWarpNormalPointElisionInstaller::resolve,
            new WeavePlan<>(
                    VerifiedWarpNormalPointElisionInstaller::targets,
                    VerifiedWarpNormalPointElisionInstaller::sitesProven),
            BridgeFactory.none(),
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<WarpNormalPointElisionTarget> hook;

    VerifiedWarpNormalPointElisionInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws IOException {
        return VerifiedInstall.admits(
                        ADMISSION, config, requested, Runtime.version().feature())
                && WarpNormalPointElisionTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<WarpNormalPointElisionTarget> resolve(final Path artifact) throws Exception {
        return WarpNormalPointElisionTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final WarpNormalPointElisionTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> owner = source.load(WarpNormalPointElisionTarget.OWNER);
        for (final WarpNormalPointElisionTarget.MethodDep dep : target.methodDependencies()) {
            HostMemberVerifier.requireMethod(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        for (final WarpNormalPointElisionTarget.FieldDep dep : target.fieldDependencies()) {
            HostMemberVerifier.requireField(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final WarpNormalPointElisionTransformer transformer = new WarpNormalPointElisionTransformer(
                source.loader(), source.artifact(), source.referenceBytes(owner), target);
        return List.of(
                WeaveTarget.single(owner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(WarpNormalPointElisionTransformer.class)
                .sites();
        return sites == EXPECTED_SITES ? null : "sites=" + sites;
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
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<WarpNormalPointElisionTarget> installed)) {
            throw new IllegalStateException("warp normal-point elision refused: host-not-supported-or-hook-disabled");
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
