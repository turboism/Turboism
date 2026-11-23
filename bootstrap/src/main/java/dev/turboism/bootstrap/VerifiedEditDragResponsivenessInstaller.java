package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.drag.EditDragResponsivenessBridge;
import dev.turboism.adapter.cubism.optimization.drag.EditDragResponsivenessTarget;
import dev.turboism.adapter.cubism.optimization.drag.EditDragResponsivenessTransformer;
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
 * Verified admission, dependency verification, bytecode weaving, and restoration for edit drag
 * responsiveness optimizations.
 */
final class VerifiedEditDragResponsivenessInstaller implements AutoCloseable {

    static final String HOOK_ID = VerifiedWarpDeformEditAccelerationInstaller.HOOK_ID;

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = EditDragResponsivenessTarget.EXPECTED_MATCHES;

    static final int EXPECTED_OWNERS = EditDragResponsivenessTarget.EXPECTED_OWNERS;

    private static final String FEATURE_NAME = "edit drag responsiveness";
    private static final String REFUSED_MESSAGE =
            "edit drag responsiveness refused: host-not-supported-or-hook-disabled";
    private static final String SITES_PREFIX = "sites=";

    private final InstallContext context;
    private EditDragResponsivenessBridge bridge;
    private VerifiedInstall<EditDragResponsivenessTarget> hook;

    VerifiedEditDragResponsivenessInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws IOException {
        return admitted(artifact, config, requested, Runtime.version().feature());
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvmFeature)
            throws IOException {
        if (artifact == null
                || !Files.isRegularFile(artifact)
                || !VerifiedInstall.admits(ADMISSION, config, requested, jvmFeature)) {
            return false;
        }
        return EditDragResponsivenessTarget.of(HostArtifactDigest.cached(artifact)).isPresent();
    }

    private static Optional<EditDragResponsivenessTarget> resolve(final Path artifact) throws Exception {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            return Optional.empty();
        }
        return EditDragResponsivenessTarget.of(HostArtifactDigest.cached(artifact));
    }

    private WeaveSpec<EditDragResponsivenessTarget> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                FEATURE_NAME,
                VerifiedEditDragResponsivenessInstaller::resolve,
                new WeavePlan<>(
                        VerifiedEditDragResponsivenessInstaller::targets,
                        VerifiedEditDragResponsivenessInstaller::sitesProven),
                (target, launch) -> {
                    bridge = new EditDragResponsivenessBridge(launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static List<WeaveTarget> targets(final EditDragResponsivenessTarget target, final WeaveSource source)
            throws Exception {
        verifyDependencies(source);
        final List<Class<?>> loadedClasses = new ArrayList<>(target.targetClasses().size());
        final Map<String, byte[]> references = new LinkedHashMap<>();
        for (final String internalName : target.targetClasses()) {
            final Class<?> loaded = source.load(internalName);
            loadedClasses.add(loaded);
            references.put(internalName, source.referenceBytes(loaded));
        }
        final EditDragResponsivenessTransformer transformer =
                new EditDragResponsivenessTransformer(source.loader(), source.artifact(), references, target);
        final List<WeaveTarget> weaveTargets = new ArrayList<>(loadedClasses.size());
        for (int index = 0; index < loadedClasses.size(); index++) {
            final String internalName = target.targetClasses().get(index);
            final Class<?> loaded = loadedClasses.get(index);
            weaveTargets.add(WeaveTarget.single(
                    loaded,
                    transformer,
                    1,
                    () -> transformer.matchesFor(internalName),
                    transformer::failure));
        }
        return weaveTargets;
    }

    private static void verifyDependencies(final WeaveSource source) throws Exception {
        source.load(EditDragResponsivenessTarget.CANVAS_WIDGET_OWNER);
        final Class<?> listenerClass = source.load(EditDragResponsivenessTarget.MOUSE_LISTENER_OWNER);
        HostMemberVerifier.requireField(
                listenerClass,
                EditDragResponsivenessTarget.LISTENER_DELEGATE_FIELD,
                EditDragResponsivenessTarget.LISTENER_DELEGATE_DESCRIPTOR);
        HostMemberVerifier.requireField(
                listenerClass,
                EditDragResponsivenessTarget.LISTENER_SESSION_FIELD,
                EditDragResponsivenessTarget.LISTENER_SESSION_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                source.load(EditDragResponsivenessTarget.MOUSE_DELEGATE_OWNER),
                EditDragResponsivenessTarget.DELEGATE_WIDGET_METHOD,
                EditDragResponsivenessTarget.DELEGATE_WIDGET_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                source.load(EditDragResponsivenessTarget.WIDGET_OWNER),
                EditDragResponsivenessTarget.GET_COMPONENT_METHOD,
                EditDragResponsivenessTarget.GET_COMPONENT_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                source.load(EditDragResponsivenessTarget.CURSOR_OWNER),
                EditDragResponsivenessTarget.GET_CURSOR_METHOD,
                EditDragResponsivenessTarget.GET_CURSOR_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                source.load(EditDragResponsivenessTarget.INSPECTOR_VIEW_CTRL_OWNER),
                EditDragResponsivenessTarget.UPDATE_METHOD,
                EditDragResponsivenessTarget.UPDATE_DELAY_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                source.load(EditDragResponsivenessTarget.INSPECTOR_INTERNAL_OWNER),
                EditDragResponsivenessTarget.INSPECTOR_INTERNAL_METHOD,
                EditDragResponsivenessTarget.UPDATE_DELAY_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                source.load(EditDragResponsivenessTarget.DEFORMER_SELECTOR_OWNER),
                EditDragResponsivenessTarget.UPDATE_WIDGET_METHOD,
                EditDragResponsivenessTarget.VOID_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                source.load(EditDragResponsivenessTarget.DEFORMER_VIEW_CTRL_OWNER),
                EditDragResponsivenessTarget.UPDATE_METHOD,
                EditDragResponsivenessTarget.UPDATE_DEFORMER_DESCRIPTOR);
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final EditDragResponsivenessTransformer transformer =
                targets.get(0).transformer(EditDragResponsivenessTransformer.class);
        final int actual = transformer.sites();
        final int expected = transformer.expectedTotalSites();
        return actual == expected ? null : SITES_PREFIX + actual + "/" + expected;
    }

    synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<EditDragResponsivenessTarget> installed)) {
            throw new IllegalStateException(REFUSED_MESSAGE);
        }
        hook = installed.hook();
    }

    synchronized Map<String, Long> snapshot() {
        return bridge == null ? Map.of() : bridge.snapshot();
    }

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
