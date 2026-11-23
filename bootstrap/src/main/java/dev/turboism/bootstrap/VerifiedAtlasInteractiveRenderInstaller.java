package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.textureatlas.interactive.AtlasInteractiveRenderBridge;
import dev.turboism.adapter.cubism.textureatlas.interactive.AtlasInteractiveRenderTarget;
import dev.turboism.adapter.cubism.textureatlas.interactive.AtlasInteractiveRenderTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified interactive Texture Atlas Editor canvas, drag, and viewport
 * optimization weave.
 */
final class VerifiedAtlasInteractiveRenderInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.textureatlas.interactive-render";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = AtlasInteractiveRenderTarget.EXPECTED_MATCHES;

    static final int EXPECTED_OWNERS = AtlasInteractiveRenderTarget.EXPECTED_MATCHES;

    private static final String FEATURE_NAME = "atlas interactive render";
    private static final String SITES_PREFIX = "sites=";
    private static final String REFUSED_MESSAGE =
            "atlas interactive render refused: host-not-supported-or-hook-disabled";

    static final WeaveSpec<AtlasInteractiveRenderTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedAtlasInteractiveRenderInstaller::resolve,
            new WeavePlan<>(
                    VerifiedAtlasInteractiveRenderInstaller::targets,
                    VerifiedAtlasInteractiveRenderInstaller::sitesProven),
            (target, launch) -> {
                final AtlasInteractiveRenderBridge bridge =
                        new AtlasInteractiveRenderBridge(launch.hostLoader(), target);
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<AtlasInteractiveRenderTarget> hook;

    VerifiedAtlasInteractiveRenderInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm)
            throws IOException {
        return VerifiedInstall.admits(ADMISSION, config, requested, jvm)
                && artifact != null
                && java.nio.file.Files.isRegularFile(artifact)
                && AtlasInteractiveRenderTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<AtlasInteractiveRenderTarget> resolve(final Path artifact) throws Exception {
        return AtlasInteractiveRenderTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final AtlasInteractiveRenderTarget target, final WeaveSource source)
            throws Exception {
        final AtlasInteractiveRenderTarget.Generation gen = target.generation();
        final Class<?> editLayerOwner = source.load(AtlasInteractiveRenderTarget.EDIT_LAYER_OWNER);
        final Class<?> canvasOwner = source.load(gen.canvasOwner());
        final Class<?> unitPanelOwner = source.load(gen.unitPanelOwner());
        final Class<?> cameraOwner = source.load(gen.cameraOwner());
        final Class<?> scrollDragOwner = source.load(gen.scrollDragOwner());
        final Class<?> inspectorOwner = source.load(AtlasInteractiveRenderTarget.INSPECTOR_OWNER);

        for (final AtlasInteractiveRenderTarget.MethodDep dep : target.methodDependencies()) {
            HostMemberVerifier.requireMethod(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        for (final AtlasInteractiveRenderTarget.FieldDep dep : target.fieldDependencies()) {
            HostMemberVerifier.requireField(source.load(dep.owner()), dep.name(), dep.descriptor());
        }

        final AtlasInteractiveRenderTransformer transformer = new AtlasInteractiveRenderTransformer(
                source.loader(),
                source.artifact(),
                target,
                source.referenceBytes(editLayerOwner),
                source.referenceBytes(canvasOwner),
                source.referenceBytes(unitPanelOwner),
                source.referenceBytes(cameraOwner),
                source.referenceBytes(scrollDragOwner),
                source.referenceBytes(inspectorOwner));

        return List.of(
                new WeaveTarget(
                        editLayerOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure),
                new WeaveTarget(canvasOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure),
                new WeaveTarget(
                        unitPanelOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure),
                new WeaveTarget(cameraOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure),
                new WeaveTarget(
                        scrollDragOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure),
                new WeaveTarget(
                        inspectorOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(AtlasInteractiveRenderTransformer.class)
                .sites();
        return sites == AtlasInteractiveRenderTarget.EXPECTED_SITES ? null : SITES_PREFIX + sites;
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
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<AtlasInteractiveRenderTarget> installed)) {
            throw new IllegalStateException(REFUSED_MESSAGE);
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
