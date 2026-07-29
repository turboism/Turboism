package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.physics.NativePhysicsEditorBridge;
import dev.turboism.adapter.cubism.physics.PhysicsEditorConstructorTransformer;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorHostProfile;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the exact-version Physics Settings panel constructor transformer. */
final class VerifiedPhysicsEditorHookInstaller implements AutoCloseable {
    private final Instrumentation instrumentation;
    private final ClassLoader hostClassLoader;
    private final PhysicsEditorCoordinator coordinator;
    private final PhysicsEditorHostProfile profile;
    private final PhysicsEditorConstructorTransformer transformer;
    private final String targetClassName;
    private final AtomicBoolean installed = new AtomicBoolean();

    VerifiedPhysicsEditorHookInstaller(
        final Instrumentation instrumentation,
        final ClassLoader hostClassLoader,
        final PhysicsEditorCoordinator coordinator,
        final PhysicsEditorHostProfile profile
    ) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.profile = Objects.requireNonNull(profile, "profile");
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
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals(targetClassName)
                    && loaded.getClassLoader() == hostClassLoader
                    && instrumentation.isModifiableClass(loaded)) {
                    instrumentation.retransformClasses(loaded);
                    System.err.println("Turboism physics editor hook retransformed " + targetClassName);
                    break;
                }
            }
            System.err.println("Turboism physics editor hook installed target=" + targetClassName);
        } catch (Throwable failure) {
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
}
