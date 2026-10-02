package dev.turboism.adapter.cubism.modeling;

/** Nonthrowing ingress for the exact native tool setter and the existing document-mode callback. */
public final class NativeModelingToolBridge {
    private static volatile Binding binding;

    private NativeModelingToolBridge() {}

    /** Binds one host-session owner; a different concurrent owner is rejected. */
    public static synchronized void install(Object owner, ModelingToolCoordinator coordinator) {
        if (binding != null && binding.owner != owner)
            throw new IllegalStateException("modeling bridge has another owner");
        binding = new Binding(java.util.Objects.requireNonNull(owner), java.util.Objects.requireNonNull(coordinator));
    }

    /** Removes only the matching installer owner's binding. */
    public static synchronized void uninstall(Object owner) {
        if (binding != null && binding.owner == owner) binding = null;
    }

    /** Contains coordinator failures after a native tool setter returns normally. */
    public static void afterToolChanged(Object app) {
        final Binding current = binding;
        if (current != null) contain(() -> current.coordinator.nativeToolActivated(app));
    }

    /** Contains coordinator failures after a successful native document-mode transition. */
    public static void afterModeChanged(Object document, Object mode) {
        final Binding current = binding;
        if (current != null) contain(() -> current.coordinator.modeChanged(document, mode));
    }

    private static void contain(Runnable callback) {
        try {
            callback.run();
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                    "modeling-tool",
                    "lifecycle callback failed: " + failure.getClass().getSimpleName());
        }
    }

    private record Binding(Object owner, ModelingToolCoordinator coordinator) {}
}
