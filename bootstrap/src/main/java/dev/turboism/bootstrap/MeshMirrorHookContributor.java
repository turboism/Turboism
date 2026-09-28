package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHookAdmission;
import dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile;
import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Declarative contributor for the verified mesh-mirror hook. The transformer
 * must be installed before the host's mirror classes load, so it installs in
 * {@link Phase#PREMAIN} from the process classpath, then binds runtime
 * services once the preview runtime has started and the authorized consumer
 * is present.
 */
final class MeshMirrorHookContributor implements HookContributor {

    static final String HOOK_ID = "cubism.mesh.mirror-axis";
    static final AtomicReference<VerifiedMeshMirrorHookInstaller> CURRENT = new AtomicReference<>();

    private final AtomicReference<VerifiedMeshMirrorHookInstaller> installer = new AtomicReference<>();

    static boolean premainOnly(final StartupSuppressionInstaller.AttachmentMode attachmentMode) {
        return attachmentMode == StartupSuppressionInstaller.AttachmentMode.PREMAIN;
    }

    static boolean hookEnabled(final dev.turboism.config.RuntimeStartupConfig policy) {
        return policy != null && policy.hookEnabled(HOOK_ID);
    }

    /**
     * Resolves the reviewed mesh-mirror profile against the actual artifact: the
     * declared version+build picks its own generation's contract when reviewed,
     * otherwise every generation's pinned classes are tried and exactly one distinct
     * contract must match. The whole-artifact digest attests the snapshot without selecting a version.
     */
    static ReviewedHostContract.Resolution<MeshMirrorHostProfile> resolveProfile(final Path artifact) {
        return ReviewedHostContract.resolve(
                artifact,
                ReviewedHostContract.candidates(
                        MeshMirrorHostProfile.reviewedClassSha256(),
                        version -> MeshMirrorHostProfile.forReviewedVersion(version)
                                .orElse(null)));
    }

    static void closeCurrent(final VerifiedMeshMirrorHookInstaller candidate) {
        if (candidate == null || !CURRENT.compareAndSet(candidate, null)) {
            return;
        }
        candidate.close();
    }

    @Override
    public String id() {
        return "TURBOISM_MESH_MIRROR_HOOK";
    }

    @Override
    public Phase phase() {
        return Phase.PREMAIN;
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        return hookEnabled(environment.startupPolicy());
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final Optional<Path> artifact = environment.locateHostArtifact();
        if (artifact.isEmpty()) {
            throw new IllegalStateException("Mesh mirror hook unavailable because the host artifact was not admitted");
        }
        final Path hostArtifact = artifact.orElseThrow();
        final ReviewedHostContract.Resolution<MeshMirrorHostProfile> resolution = resolveProfile(hostArtifact);
        if (!(resolution instanceof ReviewedHostContract.Bound<MeshMirrorHostProfile> bound)
                || !ReviewedHostArtifacts.admitsFullRuntime(bound.sourceVersion())) {
            throw new IllegalStateException("Mesh mirror host artifact is not runtime-admitted: "
                    + (resolution instanceof ReviewedHostContract.Refused<MeshMirrorHostProfile> refused
                            ? refused.reason()
                            : "unsupported host"));
        }
        final MeshMirrorHostProfile profile = bound.contract();
        final VerifiedMeshMirrorHookInstaller candidate = new VerifiedMeshMirrorHookInstaller(
                environment.instrumentation(),
                null,
                hostArtifact.toAbsolutePath().normalize(),
                null,
                null,
                profile,
                MeshMirrorHostProfile.reviewedClassSha256().get(bound.sourceVersion()),
                ignored -> {});
        bound.requireUnchanged(hostArtifact);
        candidate.install();
        try {
            if (!CURRENT.compareAndSet(null, candidate)) {
                throw new IllegalStateException("Mesh mirror hook is already installed");
            }
            installer.set(candidate);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            candidate.close();
            throw failure;
        }
        return () -> {
            final VerifiedMeshMirrorHookInstaller current = installer.getAndSet(null);
            if (current != null) {
                CURRENT.compareAndSet(current, null);
                current.close();
            }
        };
    }

    /**
     * Publishes the mirror bridge once the preview runtime and the authorized
     * consumer plugin are present. Every failure path throws so the
     * orchestration can withdraw the capability; a silent return would expose
     * the feature without its native bridge.
     */
    @Override
    public void bind(final HookEnvironment environment) throws Exception {
        final VerifiedMeshMirrorHookInstaller current = installer.get();
        if (current == null) {
            throw new IllegalStateException("Mesh mirror bind refused: premain installation absent");
        }
        if (environment.runtime().isEmpty()) {
            throw new IllegalStateException("Mesh mirror bind refused: preview runtime not started");
        }
        if (!environment.runtimeSliceAdmitted(NativeOptimizationHookContributor.HOOK_SLICE)) {
            throw new IllegalStateException("Mesh mirror bind refused: runtime slice not admitted");
        }
        final var runtime = environment.runtime().orElseThrow();
        if (!MeshMirrorHookAdmission.admitted(runtime.loadReport().loaded())) {
            current.close();
            installer.compareAndSet(current, null);
            CURRENT.compareAndSet(current, null);
            throw new IllegalStateException("Mesh mirror bind refused: authorized consumer plugin absent");
        }
        try {
            current.defineLazyTargets(environment.host().orElseThrow().classLoader());
            current.bind(
                    runtime.hostAccess().meshMirrorAxisService(),
                    runtime.hostAccess().meshEditUiService());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            current.close();
            installer.compareAndSet(current, null);
            CURRENT.compareAndSet(current, null);
            throw failure;
        }
    }
}
