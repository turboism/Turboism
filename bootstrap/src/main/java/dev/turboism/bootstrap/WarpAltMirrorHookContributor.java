package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHookAdmission;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.runtime.log.RuntimeDiagnostics;

import java.nio.file.Path;
import java.util.Optional;
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

    static boolean runtimeAdmitted(final HostArtifactDigest artifact) {
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
            .filter(ReviewedHostArtifacts::admitsFullRuntime)
            .isPresent();
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
        final HostArtifactDigest digest = HostArtifactDigest.from(hostArtifact);
        if (!runtimeAdmitted(digest)) {
            throw new IllegalStateException("Warp alt mirror host artifact is not runtime-admitted");
        }
        final var profile = dev.turboism.adapter.cubism.warpalt.WarpAltMirrorHostProfile
            .forArtifact(digest)
            .orElseThrow(() -> new IllegalStateException("Unsupported warp alt mirror host artifact"));
        final VerifiedWarpAltMirrorHookInstaller candidate = new VerifiedWarpAltMirrorHookInstaller(
            environment.instrumentation(),
            null,
            hostArtifact.toAbsolutePath().normalize(),
            profile,
            code -> RuntimeDiagnostics.info("warp-alt-mirror", code)
        );
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

    @Override public void bind(final HookEnvironment environment) throws Exception {
        final VerifiedWarpAltMirrorHookInstaller current = installer.get();
        if (current == null || !environment.fullRuntimeAdmission()) {
            return;
        }
        final var runtime = environment.runtime().orElseThrow();
        final boolean authorized = WarpAltMirrorHookAdmission.admitted(runtime.loadReport().loaded());
        if (!authorized) {
            current.close();
            installer.compareAndSet(current, null);
            CURRENT.compareAndSet(current, null);
            return;
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
