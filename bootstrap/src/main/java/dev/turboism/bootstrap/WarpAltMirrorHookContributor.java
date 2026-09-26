package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHookAdmission;
import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHostProfile;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.runtime.log.RuntimeDiagnostics;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Declarative contributor for the verified warp alt-symmetry mirror hook. The
 * transformer must be installed before the host's point-move and drag-tick
 * classes load, so it installs in {@link Phase#PREMAIN} from the process
 * classpath, then defines its lazy targets and binds the native bridge once
 * the preview runtime has started and the authorized consumer plugin is
 * present.
 */
final class WarpAltMirrorHookContributor implements HookContributor {

    static final String HOOK_ID = "cubism.warp.alt-symmetry";
    static final AtomicReference<VerifiedWarpAltMirrorHookInstaller> CURRENT =
        new AtomicReference<>();

    private final AtomicReference<VerifiedWarpAltMirrorHookInstaller> installer =
        new AtomicReference<>();

    static boolean hookEnabled(final dev.turboism.config.RuntimeStartupConfig policy) {
        return policy != null && policy.hookEnabled(HOOK_ID);
    }

    /**
     * Resolves the reviewed warp-alt profile against the actual artifact: the declared
     * version+build picks its own generation's contract when reviewed, otherwise every
     * generation's pinned classes are tried and exactly one distinct contract must
     * match. The whole-artifact digest attests the snapshot without selecting a version.
     */
    static ReviewedHostContract.Resolution<WarpAltMirrorHostProfile> resolveProfile(
        final Path artifact
    ) {
        return ReviewedHostContract.resolve(artifact, ReviewedHostContract.candidates(
            WarpAltMirrorHostProfile.reviewedClassSha256(),
            version -> WarpAltMirrorHostProfile.forReviewedVersion(version).orElse(null)));
    }

    static void closeCurrent(final VerifiedWarpAltMirrorHookInstaller candidate) {
        if (candidate == null || !CURRENT.compareAndSet(candidate, null)) {
            return;
        }
        candidate.close();
    }

    @Override public String id() {
        return "TURBOISM_WARP_ALT_MIRROR_HOOK";
    }

    @Override public Phase phase() {
        return Phase.PREMAIN;
    }

    @Override public Set<String> runtimeHookIds() {
        return Set.of("warp-alt-mirror");
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return hookEnabled(environment.startupPolicy());
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final Optional<Path> artifact = environment.locateHostArtifact();
        if (artifact.isEmpty()) {
            throw new IllegalStateException(
                "Warp alt mirror hook unavailable because the host artifact was not admitted"
            );
        }
        final Path hostArtifact = artifact.orElseThrow();
        final ReviewedHostContract.Resolution<WarpAltMirrorHostProfile> resolution =
            resolveProfile(hostArtifact);
        if (!(resolution instanceof ReviewedHostContract.Bound<WarpAltMirrorHostProfile> bound)
            || !ReviewedHostArtifacts.admitsFullRuntime(bound.sourceVersion())) {
            throw new IllegalStateException(
                "Warp alt mirror host artifact is not runtime-admitted: "
                    + (resolution instanceof ReviewedHostContract.Refused<WarpAltMirrorHostProfile> refused
                        ? refused.reason() : "unsupported host"));
        }
        final WarpAltMirrorHostProfile profile = bound.contract();
        final VerifiedWarpAltMirrorHookInstaller candidate = new VerifiedWarpAltMirrorHookInstaller(
            environment.instrumentation(),
            null,
            hostArtifact.toAbsolutePath().normalize(),
            profile,
            WarpAltMirrorHostProfile.reviewedClassSha256().get(bound.sourceVersion()),
            code -> RuntimeDiagnostics.info("warp-alt-mirror", code)
        );
        bound.requireUnchanged(hostArtifact);
        candidate.install();
        try {
            if (!CURRENT.compareAndSet(null, candidate)) {
                throw new IllegalStateException(
                    "Warp alt mirror hook is already installed"
                );
            }
            installer.set(candidate);
        } catch (Throwable failure) {
            candidate.close();
            throw failure;
        }
        return () -> {
            final VerifiedWarpAltMirrorHookInstaller current = installer.getAndSet(null);
            if (current != null) {
                CURRENT.compareAndSet(current, null);
                current.close();
            }
        };
    }

    /**
     * Publishes the mirror bridge once the preview runtime and the authorized
     * consumer plugin are present. Every failure path throws so the
     * orchestration can withdraw the {@code warp-alt-mirror} capability; a
     * silent return would expose the feature without its native bridge.
     */
    @Override public void bind(final HookEnvironment environment) throws Exception {
        final VerifiedWarpAltMirrorHookInstaller current = installer.get();
        if (current == null) {
            throw new IllegalStateException(
                "Warp alt mirror bind refused: premain installation absent");
        }
        if (environment.runtime().isEmpty()) {
            throw new IllegalStateException(
                "Warp alt mirror bind refused: preview runtime not started");
        }
        if (!environment.runtimeSliceAdmitted(NativeOptimizationHookContributor.HOOK_SLICE)) {
            throw new IllegalStateException(
                "Warp alt mirror bind refused: runtime slice not admitted");
        }
        final var runtime = environment.runtime().orElseThrow();
        current.onClose(() -> runtime.disableEditorCapabilitiesRequiringHook("warp-alt-mirror"));
        if (!WarpAltMirrorHookAdmission.admitted(runtime.loadReport().loaded())) {
            current.close();
            installer.compareAndSet(current, null);
            CURRENT.compareAndSet(current, null);
            throw new IllegalStateException(
                "Warp alt mirror bind refused: authorized consumer plugin absent");
        }
        try {
            current.defineLazyTargets(environment.host().orElseThrow().classLoader());
            current.bind();
        } catch (Throwable failure) {
            current.close();
            installer.compareAndSet(current, null);
            CURRENT.compareAndSet(current, null);
            throw failure;
        }
    }
}
