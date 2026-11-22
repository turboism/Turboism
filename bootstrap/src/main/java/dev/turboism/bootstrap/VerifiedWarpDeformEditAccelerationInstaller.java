package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.warpdeform.WarpDeformEditBridge;
import dev.turboism.adapter.cubism.optimization.warpdeform.WarpDeformEditTarget;
import dev.turboism.adapter.cubism.optimization.warpdeform.WarpDeformEditTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Installs and restores verified edit-mode warp deformation, overlay caching, and deform-brush weaves.
 */
final class VerifiedWarpDeformEditAccelerationInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.deformer.warp-deform-edit-acceleration";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    private static final String FEATURE_NAME = "warp deform edit acceleration";
    private static final String SITES_PREFIX = "sites=";
    private static final String REFUSED_MESSAGE =
            "warp deform edit acceleration refused: host-not-supported-or-hook-disabled";

    static final WeaveSpec<WarpDeformEditTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedWarpDeformEditAccelerationInstaller::resolve,
            new WeavePlan<>(
                    VerifiedWarpDeformEditAccelerationInstaller::targets,
                    VerifiedWarpDeformEditAccelerationInstaller::sitesProven),
            (target, launch) -> {
                final WarpDeformEditBridge bridge = new WarpDeformEditBridge(launch.hostLoader());
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<WarpDeformEditTarget> hook;

    VerifiedWarpDeformEditAccelerationInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws IOException {
        if (artifact == null
                || !Files.isRegularFile(artifact)
                || !VerifiedInstall.admits(ADMISSION, config, requested, Runtime.version().feature())) {
            return false;
        }
        return WarpDeformEditTarget.of(HostArtifactDigest.cached(artifact)).isPresent();
    }

    private static Optional<WarpDeformEditTarget> resolve(final Path artifact) throws Exception {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            return Optional.empty();
        }
        return WarpDeformEditTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final WarpDeformEditTarget target, final WeaveSource source)
            throws Exception {
        final List<Class<?>> loadedClasses = new ArrayList<>(target.targetClasses().size());
        final Map<String, byte[]> references = new LinkedHashMap<>();
        for (final String internalName : target.targetClasses()) {
            final Class<?> loaded = source.load(internalName);
            loadedClasses.add(loaded);
            references.put(internalName, source.referenceBytes(loaded));
        }
        final WarpDeformEditTransformer transformer =
                new WarpDeformEditTransformer(source.loader(), source.artifact(), references, target);
        final List<WeaveTarget> weaveTargets = new ArrayList<>(loadedClasses.size());
        for (int i = 0; i < loadedClasses.size(); i++) {
            final String internalName = target.targetClasses().get(i);
            final Class<?> loaded = loadedClasses.get(i);
            final int expectedForOwner = transformer.expectedMatchesFor(internalName);
            weaveTargets.add(WeaveTarget.single(
                    loaded,
                    transformer,
                    expectedForOwner,
                    () -> transformer.matchesFor(internalName),
                    transformer::failure));
        }
        return weaveTargets;
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final WarpDeformEditTransformer transformer = targets.get(0).transformer(WarpDeformEditTransformer.class);
        final int actual = transformer.sites();
        final int expected = transformer.expectedTotalSites();
        return actual == expected ? null : SITES_PREFIX + actual + "/" + expected;
    }

    @Override
    public boolean admits(final InstallContext context) {
        try {
            return admitted(context.artifact(), context.policy(), true);
        } catch (final IOException unreadable) {
            return false;
        }
    }

    @Override
    public synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<WarpDeformEditTarget> installed)) {
            throw new IllegalStateException(REFUSED_MESSAGE);
        }
        hook = installed.hook();
    }

    /** Returns whether the weave removed and restored all rewritten classes cleanly. */
    boolean restored() {
        return hook != null && hook.restored();
    }

    @Override
    public synchronized void close() {
        if (hook != null) {
            hook.close();
        }
    }
}
