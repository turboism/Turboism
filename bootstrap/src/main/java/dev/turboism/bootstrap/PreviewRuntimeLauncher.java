package dev.turboism.bootstrap;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.CompatibilityResolution;
import dev.turboism.mapping.verification.CubismHostCompatibilityResolver;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.preview.PreviewRuntime;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the located host's declared identity, extracts exactly the
 * verification records the admission resolution granted, and starts the
 * preview runtime with them.
 *
 * <p>The whole-artifact digest no longer decides the product version or
 * startup eligibility. A byte-identical reviewed artifact takes the exact
 * path; a declared-but-unreviewed artifact is admitted per slice by the
 * compatibility resolver and may degrade gracefully. Record extraction stays
 * fail-closed: a missing embedded record aborts the start.</p>
 */
final class PreviewRuntimeLauncher {

    private PreviewRuntimeLauncher() {
    }

    /**
     * Host artifact resolution outcome handed to {@link #start}.
     *
     * <p>{@code profile} is always the host-declared version.
     * {@code fullRuntimeAdmission} describes exact artifact admission only;
     * compatible hosts prove each runtime or premain hook independently.</p>
     */
    record ResolvedHost(
        HostClassLocator.LocatedHost host,
        CompatibilityResolution resolution,
        String profile,
        Path coreArtifact,
        boolean fullRuntimeAdmission
    ) {
        /** @return whether the host was admitted by structural compatibility */
        boolean compatibilityAdmission() {
            return resolution.mode() == CompatibilityResolution.Mode.COMPATIBLE;
        }
    }

    @FunctionalInterface
    interface PreviewRuntimeStarter {
        PreviewRuntime start() throws Throwable;
    }

    /**
     * Awaits the Cubism host class and resolves declared identity plus slice
     * admission. Empty when the host was never observed; throws when the host
     * is present but its identity or base capability fails admission.
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
        final Path coreCandidate = host.artifact().resolveSibling("Live2DCubismCore.jar")
            .toAbsolutePath().normalize();
        final Path coreArtifact = Files.isRegularFile(coreCandidate) ? coreCandidate : null;
        final CompatibilityResolution resolution = CubismHostCompatibilityResolver.resolve(
            host.artifact(),
            coreArtifact
        );
        switch (resolution.mode()) {
            case REJECTED -> throw new IOException(
                "Cubism host identity rejected: " + resolution.identityProbe().status()
                    + " — " + resolution.detail()
            );
            case COMPATIBLE, VERIFIED -> {
                if (!resolution.runtimeAdmitted()) {
                    throw new IOException(
                        "Cubism host " + resolution.declaredVersion()
                            + " admitted no base runtime capability: " + resolution.detail()
                    );
                }
            }
            default -> throw new IllegalStateException("unknown resolution mode");
        }
        return Optional.of(new ResolvedHost(
            host,
            resolution,
            resolution.declaredVersion(),
            coreArtifact,
            resolution.mode() == CompatibilityResolution.Mode.VERIFIED
                && ReviewedHostArtifacts.admitsFullRuntime(resolution.declaredVersion())
        ));
    }

    /**
     * Runs {@code starter}; on failure the currently installed premain
     * transformer hooks are closed if they are still the given candidates.
     */
    static PreviewRuntime startPreviewRuntime(
        final VerifiedMeshMirrorHookInstaller candidate,
        final VerifiedWarpAltMirrorHookInstaller warpAltCandidate,
        final PreviewRuntimeStarter starter
    ) throws Throwable {
        try {
            return starter.start();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            try {
                closePremainRuntimeHooks(candidate, warpAltCandidate);
            } catch (Throwable cleanupFailure) {
                FatalErrors.rethrowIfFatal(cleanupFailure);
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static void closeDuplicateRuntimeAndHooks(
        final Runnable runtimeClose,
        final VerifiedMeshMirrorHookInstaller candidate,
        final VerifiedWarpAltMirrorHookInstaller warpAltCandidate
    ) {
        try {
            runtimeClose.run();
        } catch (RuntimeException | Error failure) {
            try {
                closePremainRuntimeHooks(candidate, warpAltCandidate);
            } catch (Throwable cleanupFailure) {
                FatalErrors.rethrowIfFatal(cleanupFailure);
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        closePremainRuntimeHooks(candidate, warpAltCandidate);
    }

    /** Closes both runtime-dependent premain hooks even when either cleanup fails. */
    static void closePremainRuntimeHooks(
        final VerifiedMeshMirrorHookInstaller mesh,
        final VerifiedWarpAltMirrorHookInstaller warp
    ) {
        Throwable failure = null;
        try {
            WarpAltMirrorHookContributor.closeCurrent(warp);
        } catch (Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
            failure = problem;
        }
        try {
            MeshMirrorHookContributor.closeCurrent(mesh);
        } catch (Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
        if (failure != null) {
            throw new IllegalStateException("premain runtime-hook cleanup failed", failure);
        }
    }

    /**
     * Extracts the record set the resolution admitted and starts the preview
     * runtime. Slices the resolution rejected contribute no record, so the
     * runtime degrades exactly those capabilities.
     */
    static PreviewRuntime start(
        final AgentOptions options,
        final ResolvedHost resolved
    ) throws Throwable {
        return start(options, resolved, runtime -> { });
    }

    /** Starts plugins only after the caller finishes binding the runtime-dependent hooks. */
    static PreviewRuntime start(
        final AgentOptions options,
        final ResolvedHost resolved,
        final java.util.function.Consumer<PreviewRuntime> beforePlugins
    ) throws Throwable {
        return start(options, resolved, beforePlugins, runtime -> { });
    }

    static PreviewRuntime start(
        final AgentOptions options,
        final ResolvedHost resolved,
        final java.util.function.Consumer<PreviewRuntime> beforePlugins,
        final java.util.function.Consumer<PreviewRuntime> afterPlugins
    ) throws Throwable {
        final Path home = options.home();
        final CompatibilityResolution resolution = resolved.resolution();
        return PreviewRuntime.start(
            home,
            extractContract(home, resolution, "project-workspace"),
            extractContract(home, resolution, "editor-model"),
            extractContract(home, resolution, "core-model-read"),
            extractContract(home, resolution, "ui-main-toolbar"),
            extractContract(home, resolution, "ui-embedded-panel"),
            extractContract(home, resolution, "ui-top-menu"),
            extractContract(home, resolution, "ui-bounding-box-overlay"),
            Optional.ofNullable(extractContract(home, resolution, "ui-status-bar")),
            Optional.ofNullable(extractContract(home, resolution, "clipmask")),
            extractContract(home, resolution, "autobackup"),
            // The protected-export slice is pinned for the same exact reviewed builds as the
            // export-settings hook; the same gate also selects its orchestration record.
            Optional.ofNullable(
                ExportSettingsHookContributor.runtimeAdmitted(
                    resolved.profile(), resolved.fullRuntimeAdmission())
                    ? extract(home, "cubism-" + resolved.profile() + "-protected-export.json")
                    : null),
            resolved.host().artifact(),
            resolved.coreArtifact(),
            resolved.host().classLoader(),
            resolution,
            beforePlugins,
            afterPlugins
        );
    }

    /**
     * Extracts the record bound to {@code sliceId} when admission granted one.
     * Unadmitted slices contribute {@code null}; admitted slices whose record
     * fails to extract fail the whole start closed.
     */
    private static Path extractContract(
        final Path home,
        final CompatibilityResolution resolution,
        final String sliceId
    ) throws IOException {
        final CompatibilityResolution.SliceResolution slice = resolution.slice(sliceId);
        return slice.contract()
            .map(contract -> {
                try {
                    return extract(home, contract.recordFileName());
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            })
            .orElse(null);
    }

    private static Path extract(final Path home, final String fileName) throws IOException {
        return HookEnvironment.extractVerificationRecord(
            home.resolve("state").resolve("verification"),
            fileName
        );
    }
}
