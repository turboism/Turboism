package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshMirrorHookAdmission;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.mapping.verification.HostArtifactDigest;
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
    static final AtomicReference<VerifiedMeshMirrorHookInstaller> CURRENT =
        new AtomicReference<>();

    private final AtomicReference<VerifiedMeshMirrorHookInstaller> installer =
        new AtomicReference<>();

    static boolean premainOnly(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode
    ) {
        return attachmentMode == StartupSuppressionInstaller.AttachmentMode.PREMAIN;
    }

    static boolean hookEnabled(final dev.turboism.config.RuntimeStartupConfig policy) {
        return policy != null && policy.hookEnabled(HOOK_ID);
    }

    static boolean runtimeAdmitted(final HostArtifactDigest artifact) {
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
            .filter(ReviewedHostArtifacts::admitsFullRuntime)
            .isPresent();
    }

    static void closeCurrent(final VerifiedMeshMirrorHookInstaller candidate) {
        if (candidate == null || !CURRENT.compareAndSet(candidate, null)) {
            return;
        }
        candidate.close();
    }

    @Override public String id() {
        return "TURBOISM_MESH_MIRROR_HOOK";
    }

    @Override public Phase phase() {
        return Phase.PREMAIN;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return hookEnabled(environment.startupPolicy());
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final Optional<Path> artifact = environment.locateHostArtifact();
        if (artifact.isEmpty()) {
            throw new IllegalStateException(
                "Mesh mirror hook unavailable because the host artifact was not admitted"
            );
        }
        final Path hostArtifact = artifact.orElseThrow();
        final HostArtifactDigest digest = HostArtifactDigest.from(hostArtifact);
        if (!runtimeAdmitted(digest)) {
            throw new IllegalStateException("Mesh mirror host artifact is not runtime-admitted");
        }
        final var profile = dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile
            .forArtifact(digest)
            .orElseThrow(() -> new IllegalStateException("Unsupported mesh mirror host artifact"));
        final VerifiedMeshMirrorHookInstaller candidate = new VerifiedMeshMirrorHookInstaller(
            environment.instrumentation(),
            null,
            hostArtifact.toAbsolutePath().normalize(),
            profile
        );
        candidate.install();
        try {
            if (!CURRENT.compareAndSet(null, candidate)) {
                throw new IllegalStateException(
                    "Mesh mirror hook is already installed"
                );
            }
            installer.set(candidate);
        } catch (Throwable failure) {
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

    @Override public void bind(final HookEnvironment environment) throws Exception {
        final VerifiedMeshMirrorHookInstaller current = installer.get();
        if (current == null || !environment.fullRuntimeAdmission()) {
            return;
        }
        final var runtime = environment.runtime().orElseThrow();
        final boolean authorized = MeshMirrorHookAdmission.admitted(runtime.loadReport().loaded());
        if (!authorized) {
            current.close();
            installer.compareAndSet(current, null);
            CURRENT.compareAndSet(current, null);
            return;
        }
        try {
            current.defineLazyTargets();
            current.bind(
                runtime.hostAccess().meshMirrorAxisService(),
                runtime.hostAccess().meshEditUiService()
            );
        } catch (Throwable failure) {
            current.close();
            installer.compareAndSet(current, null);
            CURRENT.compareAndSet(current, null);
            throw failure;
        }
    }
}
