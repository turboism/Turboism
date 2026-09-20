package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.VerifiedCorePublicApiResolverFactory;
import dev.turboism.preview.PreviewRuntime;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Extracts the embedded verification records the located host profile
 * requires and starts the preview runtime with them.
 *
 * <p>Record extraction is fail-closed: a missing embedded record aborts the
 * start exactly like a failed admission check did before the hook registry
 * refactor.</p>
 */
final class PreviewRuntimeLauncher {

    private PreviewRuntimeLauncher() {
    }

    /** Host artifact resolution outcome handed to {@link #start}. */
    record ResolvedHost(
        HostClassLocator.LocatedHost host,
        String profile,
        String coreProfile,
        Path coreArtifact,
        boolean fullRuntimeAdmission
    ) {
    }

    @FunctionalInterface
    interface PreviewRuntimeStarter {
        PreviewRuntime start() throws Throwable;
    }

    /**
     * Awaits the Cubism host class and resolves the reviewed profiles plus the
     * sibling Core artifact. Empty when the host was never observed; throws
     * when the artifact is present but fails verification.
     */
    static Optional<ResolvedHost> resolveHost(
        final Instrumentation instrumentation,
        final AgentOptions options
    ) throws Exception {
        final Optional<HostClassLocator.LocatedHost> located = new HostClassLocator().await(
            instrumentation,
            options.hostClassName(),
            options.detectionTimeout()
        );
        if (located.isEmpty()) {
            return Optional.empty();
        }
        final HostClassLocator.LocatedHost host = located.orElseThrow();
        final String profile = EditorModelVerificationManifest.resourceProfileForArtifact(
            HostArtifactDigest.from(host.artifact())
        );
        final Path coreArtifact = host.artifact().resolveSibling("Live2DCubismCore.jar")
            .toAbsolutePath().normalize();
        if (!Files.isRegularFile(coreArtifact)) {
            throw new IOException("Exact Cubism Core artifact is missing beside the Editor JAR");
        }
        return Optional.of(new ResolvedHost(
            host,
            profile,
            VerifiedCorePublicApiResolverFactory.profileForArtifact(coreArtifact),
            coreArtifact,
            ReviewedHostArtifacts.admitsFullRuntime(profile)
        ));
    }

    /**
     * Runs {@code starter}; on failure the currently installed mesh-mirror
     * hook is closed if it is still {@code candidate}.
     */
    static PreviewRuntime startPreviewRuntime(
        final VerifiedMeshMirrorHookInstaller candidate,
        final PreviewRuntimeStarter starter
    ) throws Throwable {
        try {
            return starter.start();
        } catch (Throwable failure) {
            MeshMirrorHookContributor.closeCurrent(candidate);
            throw failure;
        }
    }

    static void closeDuplicateRuntimeAndMeshMirrorHook(
        final Runnable runtimeClose,
        final VerifiedMeshMirrorHookInstaller candidate
    ) {
        try {
            runtimeClose.run();
        } finally {
            MeshMirrorHookContributor.closeCurrent(candidate);
        }
    }

    /**
     * Extracts the record set for the resolved host and starts the preview
     * runtime.
     */
    static PreviewRuntime start(
        final AgentOptions options,
        final ResolvedHost resolved
    ) throws Throwable {
        final Path home = options.home();
        final String profile = resolved.profile();
        final String coreProfile = resolved.coreProfile();
        final boolean fullRuntimeAdmission = resolved.fullRuntimeAdmission();
        final HostClassLocator.LocatedHost host = resolved.host();
        final Path coreArtifact = resolved.coreArtifact();
        return PreviewRuntime.start(
            home,
            extract(home, "cubism-" + profile + "-project-workspace.json"),
            extract(home, "cubism-" + profile + "-editor-model.json"),
            extract(home, "cubism-" + coreProfile + "-core-model-read.json"),
            extractIf(home, profile, "ui-main-toolbar", fullRuntimeAdmission),
            extractIf(home, profile, "ui-embedded-panel", fullRuntimeAdmission),
            extractIf(home, profile, "ui-top-menu", fullRuntimeAdmission),
            extractIf(home, profile, "ui-bounding-box-overlay", fullRuntimeAdmission),
            Optional.ofNullable(
                extractIf(home, profile, "ui-status-bar", fullRuntimeAdmission)),
            Optional.ofNullable(
                extractIf(home, profile, "clipmask",
                    ClipMaskVerificationManifest.reviewedCubismVersions().contains(profile))),
            extractIf(home, profile, "autobackup", fullRuntimeAdmission),
            host.artifact(),
            coreArtifact,
            host.classLoader()
        );
    }

    private static Path extractIf(
        final Path home,
        final String profile,
        final String slice,
        final boolean admitted
    ) throws IOException {
        return admitted ? extract(home, "cubism-" + profile + "-" + slice + ".json") : null;
    }

    private static Path extract(final Path home, final String fileName) throws IOException {
        return HookEnvironment.extractVerificationRecord(
            home.resolve("state").resolve("verification"),
            fileName
        );
    }
}
