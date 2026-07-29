package dev.turboism.adapter.cubism.physics;

import java.util.concurrent.atomic.AtomicReference;

/** Static entrypoint used only by the verified Physics Settings constructor transformer. */
public final class NativePhysicsEditorBridge {
    private static final AtomicReference<Binding> INSTALLED = new AtomicReference<>();

    private NativePhysicsEditorBridge() { }

    public static void install(
        final PhysicsEditorCoordinator coordinator,
        final PhysicsEditorHostProfile profile
    ) {
        final Binding binding = new Binding(coordinator, profile);
        if (!INSTALLED.compareAndSet(null, binding)) {
            throw new IllegalStateException("physics editor bridge is already installed");
        }
    }

    public static void uninstall(final PhysicsEditorCoordinator coordinator) {
        final Binding binding = INSTALLED.get();
        if (binding != null && binding.coordinator() == coordinator) INSTALLED.compareAndSet(binding, null);
    }

    public static void afterConstructed(final Object panel) {
        final Binding binding = INSTALLED.get();
        if (binding == null || panel == null) return;
        System.err.println("Turboism physics editor panel constructed class=" + panel.getClass().getName());
        try {
            binding.coordinator().onPanelConstructed(panel, binding.profile());
        } catch (Throwable failure) {
            System.err.println("Turboism physics editor contribution failed safely: " + failure.getClass().getName());
        }
    }

    private record Binding(PhysicsEditorCoordinator coordinator, PhysicsEditorHostProfile profile) { }
}
