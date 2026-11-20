package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.physics.NativePhysicsEditorBridge;
import dev.turboism.adapter.cubism.physics.PhysicsEditorConstructorTransformer;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorHostProfile;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;

/** Owns the exact-version Physics Settings panel constructor transformer. */
final class VerifiedPhysicsEditorHookInstaller implements AutoCloseable {

    private final HookInstallSession session;

    VerifiedPhysicsEditorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final PhysicsEditorCoordinator coordinator,
            final PhysicsEditorHostProfile profile) {
        this(instrumentation, hostClassLoader, coordinator, profile, null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedPhysicsEditorHookInstaller(
            final Instrumentation instrumentation,
            final ClassLoader hostClassLoader,
            final PhysicsEditorCoordinator coordinator,
            final PhysicsEditorHostProfile profile,
            final LoadedClassIndex loadedClasses) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(profile, "profile");
        final PhysicsEditorConstructorTransformer transformer =
                new PhysicsEditorConstructorTransformer(profile.panelOwnerInternalName(), hostClassLoader);
        final String targetClassName = profile.panelOwnerInternalName().replace('/', '.');
        this.session = HookInstallSession.builder(
                        instrumentation,
                        hostClassLoader,
                        Set.of(targetClassName, PhysicsEditorConstructorTransformer.PREVIEW_OWNER.replace('/', '.')))
                .loadedClasses(loadedClasses)
                .transformer(transformer)
                .bridge(
                        () -> NativePhysicsEditorBridge.install(coordinator, profile),
                        () -> NativePhysicsEditorBridge.uninstall(coordinator))
                .retransformOnInstall((inst, loaded, targets, loader) -> {
                    // One enumeration covers both owners: the panel and its preview companion.
                    VerifiedHookInstallerSupport.retransformIfLoaded(inst, loaded, targets, loader);
                    RuntimeDiagnostics.debug("physics-editor", "Installed verified physics editor hook");
                })
                .build();
    }

    void install() throws Exception {
        session.install();
    }

    @Override
    public void close() {
        session.close();
    }
}
