package dev.turboism.adapter.cubism.physics;

import java.util.concurrent.atomic.AtomicReference;

/** Static entrypoint used only by the verified Physics Settings constructor transformer. */
public final class NativePhysicsEditorBridge {
    private static final AtomicReference<Binding> INSTALLED = new AtomicReference<>();

    private NativePhysicsEditorBridge() { }

    /**
     * Publishes the coordinator and host profile that transformed Physics Settings constructors will
     * call back into. At most one binding exists per process; the swap is atomic.
     *
     * @param coordinator receives each constructed panel
     * @param profile the reviewed selector set matching the running host build
     * @throws IllegalStateException if a binding is already installed
     */
    public static void install(
        final PhysicsEditorCoordinator coordinator,
        final PhysicsEditorHostProfile profile
    ) {
        final Binding binding = new Binding(coordinator, profile);
        if (!INSTALLED.compareAndSet(null, binding)) {
            throw new IllegalStateException("physics editor bridge is already installed");
        }
    }

    /**
     * Removes the installed binding only if it belongs to {@code coordinator}; a no-op otherwise, so
     * a late uninstall cannot detach a binding installed by someone else.
     *
     * @param coordinator the coordinator whose binding should be removed
     */
    public static void uninstall(final PhysicsEditorCoordinator coordinator) {
        final Binding binding = INSTALLED.get();
        if (binding != null && binding.coordinator() == coordinator) INSTALLED.compareAndSet(binding, null);
    }

    /**
     * Ingress called by transformed host bytecode at the end of a Physics Settings panel
     * constructor, handing the panel to the installed coordinator.
     *
     * <p>Runs on whichever host thread built the panel, typically the EDT. Fail-closed and never
     * throws: it returns silently when nothing is installed or {@code panel} is {@code null}, and
     * catches every {@link Throwable} from the contribution so a plugin failure cannot break the
     * host's own construction.
     *
     * @param panel the freshly constructed host panel instance
     */
    public static void afterConstructed(final Object panel) {
        final Binding binding = INSTALLED.get();
        if (binding == null || panel == null) return;
        try {
            binding.coordinator().onPanelConstructed(panel, binding.profile());
        } catch (Throwable failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "physics-editor",
                "Physics editor contribution failed safely",
                failure
            );
        }
    }

    private record Binding(PhysicsEditorCoordinator coordinator, PhysicsEditorHostProfile profile) { }
}
