package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshToolSessionHostProfile;
import dev.turboism.adapter.cubism.mesh.MeshToolSessionResolver;
import dev.turboism.adapter.cubism.mesh.NativeMeshToolSession;
import dev.turboism.adapter.cubism.mesh.NativeMeshToolSessionBridge;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import java.util.Set;

/** Installs the verified mesh-session lifecycle bridge before plugin initialization. */
final class MeshToolSessionHookContributor implements HookContributor {
    @Override
    public String id() {
        return "TURBOISM_MESH_TOOL_SESSION_HOOK";
    }

    @Override
    public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override
    public boolean closesOnProcessExit() {
        return true;
    }

    @Override
    public Set<String> runtimeHookIds() {
        return Set.of("mesh-tool-session");
    }

    @Override
    public boolean admitted(final HookEnvironment environment) {
        return environment.attachmentMode() == AttachmentMode.PREMAIN
                && environment.runtimeSliceAdmitted("editor-model");
    }

    @Override
    public AutoCloseable install(final HookEnvironment environment) {
        if (!admitted(environment)) throw new IllegalStateException("Mesh-session hook is not admitted");
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final var resolver = runtime.editorModelResolver();
        final var profile = MeshToolSessionHostProfile.from(resolver);
        final var sessions =
                new MeshToolSessionResolver(resolver, runtime.hostAccess().modelAccess());
        final var coordinator = runtime.hostAccess().meshToolCoordinator();
        final long generation =
                runtime.hostAccess().editorUiLifecycle().snapshot().generation();
        final Object owner = new Object();
        final var listener = new NativeMeshToolSessionBridge.SessionListener() {
            @Override
            public void opened(final NativeMeshToolSession session) {
                coordinator.beginSession(session);
            }

            @Override
            public void closed(final NativeMeshToolSession session) {
                coordinator.endSession();
            }
        };
        final var installer = new VerifiedMeshToolSessionHookInstaller(
                environment.instrumentation(),
                environment.attachmentMode(),
                host.classLoader(),
                profile,
                host.artifact().toAbsolutePath().normalize(),
                () -> NativeMeshToolSessionBridge.install(owner, generation, sessions, listener),
                () -> NativeMeshToolSessionBridge.uninstall(owner));
        installer.install();
        return installer;
    }
}
