package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.physics.NativePhysicsEditorBridge;
import dev.turboism.adapter.cubism.physics.PhysicsEditorConstructorTransformer;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorHostProfile;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the exact-version Physics Settings panel constructor transformer. */
final class VerifiedPhysicsEditorHookInstaller implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final PhysicsEditorCoordinator coordinator;
    private final PhysicsEditorHostProfile profile;
    private final PhysicsEditorConstructorTransformer transformer;
    private final String targetClassName;
    private final LoadedClassIndex loadedClasses;
    private final AtomicBoolean installed = new AtomicBoolean();

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
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.loadedClasses = loadedClasses;
        this.transformer = new PhysicsEditorConstructorTransformer(profile.panelOwnerInternalName(), hostClassLoader);
        this.targetClassName = profile.panelOwnerInternalName().replace('/', '.');
    }

    void install() throws Exception {
        if (!installed.compareAndSet(false, true)) return;
        if (!instrumentation.isRetransformClassesSupported()) {
            installed.set(false);
            throw new IllegalStateException("Class retransformation is unavailable.");
        }
        NativePhysicsEditorBridge.install(coordinator, profile);
        instrumentation.addTransformer(transformer, true);
        try {
            // One enumeration covers both owners: the panel and its preview companion.
            VerifiedHookInstallerSupport.retransformIfLoaded(
                    instrumentation,
                    loadedClasses(),
                    Set.of(targetClassName, PhysicsEditorConstructorTransformer.PREVIEW_OWNER.replace('/', '.')),
                    hostClassLoader);
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "physics-editor", "Installed verified physics editor hook");
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            close();
            throw failure;
        }
    }

    @Override
    public void close() {
        if (!installed.compareAndSet(true, false)) return;
        instrumentation.removeTransformer(transformer);
        NativePhysicsEditorBridge.uninstall(coordinator);
    }

    private LoadedClassIndex loadedClasses() {
        return loadedClasses != null ? loadedClasses : LoadedClassIndex.snapshot(instrumentation);
    }
}
