package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.cursor.CursorValidationElisionBridge;
import dev.turboism.adapter.cubism.optimization.cursor.CursorValidationElisionTarget;
import dev.turboism.adapter.cubism.optimization.cursor.CursorValidationElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified AWT cursor-validation and pointer-bounds elision weave.
 */
final class VerifiedCursorValidationElisionInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.input.cursor-validation-elision";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = 2;

    static final int EXPECTED_OWNERS = 2;

    private static final int EXPECTED_SITES = 5;

    private static final URI JRT_ROOT_URI = URI.create("jrt:/");

    private final InstallContext context;
    private VerifiedInstall<CursorValidationElisionTarget> hook;

    VerifiedCursorValidationElisionInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm)
            throws IOException {
        return VerifiedInstall.admits(ADMISSION, config, requested, jvm)
                && CursorValidationElisionTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private WeaveSpec<CursorValidationElisionTarget> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                "cursor validation elision",
                VerifiedCursorValidationElisionInstaller::resolve,
                new WeavePlan<>(
                        VerifiedCursorValidationElisionInstaller::targets,
                        VerifiedCursorValidationElisionInstaller::sitesProven),
                (contract, launch) -> {
                    final CursorValidationElisionBridge bridge = new CursorValidationElisionBridge();
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<CursorValidationElisionTarget> resolve(final Path artifact) throws Exception {
        return CursorValidationElisionTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(
            final CursorValidationElisionTarget contract, final WeaveSource source) throws Exception {
        final byte[] containerReference = readJrtBytes(CursorValidationElisionTarget.CONTAINER_JRT_PATH);
        final byte[] componentReference = readJrtBytes(CursorValidationElisionTarget.COMPONENT_JRT_PATH);
        final Class<?> containerClass =
                Class.forName(CursorValidationElisionTarget.CONTAINER_OWNER.replace('/', '.'), false, null);
        final Class<?> componentClass =
                Class.forName(CursorValidationElisionTarget.COMPONENT_OWNER.replace('/', '.'), false, null);
        VerifiedHookInstallerSupport.attestJdkClass(
                containerClass,
                "cursor validation elision JDK target attestation failed: " + containerClass.getName());
        VerifiedHookInstallerSupport.attestJdkClass(
                componentClass,
                "cursor validation elision JDK target attestation failed: " + componentClass.getName());
        final CursorValidationElisionTransformer transformer =
                new CursorValidationElisionTransformer(containerReference, componentReference);
        return List.of(
                new WeaveTarget(
                        containerClass,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure),
                new WeaveTarget(
                        componentClass,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(CursorValidationElisionTransformer.class)
                .sites();
        return sites == EXPECTED_SITES ? null : "sites=" + sites;
    }

    private static byte[] readJrtBytes(final String path) throws IOException {
        return Files.readAllBytes(FileSystems.getFileSystem(JRT_ROOT_URI).getPath(path));
    }

    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<CursorValidationElisionTarget> installed)) {
            throw new IllegalStateException(
                    "cursor validation elision refused: host-not-supported-or-hook-disabled");
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
