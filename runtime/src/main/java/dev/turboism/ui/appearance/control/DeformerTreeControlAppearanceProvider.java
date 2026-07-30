package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.util.Objects;
import java.util.Optional;

/** Applies and restores bounded styles on native deformer-tree labels. */
public final class DeformerTreeControlAppearanceProvider implements AutoCloseable {
    private final ControlAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final AutoCloseable changeSubscription;

    public DeformerTreeControlAppearanceProvider(final ControlAppearanceCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::restore);
    }

    public Component apply(
        final long hostGeneration,
        final String deformerId,
        final Component component,
        final boolean selected,
        final boolean focused
    ) {
        Objects.requireNonNull(deformerId, "deformerId");
        final Component target = Objects.requireNonNull(component, "component");
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) return target;
        styles.apply(
            target,
            hostGeneration == coordinator.hostGeneration()
                ? coordinator.deformerLabel(deformerId)
                : Optional.empty()
        );
        return target;
    }

    void restore() {
        final Runnable action = styles::restoreAll;
        if (javax.swing.SwingUtilities.isEventDispatchThread()) action.run();
        else javax.swing.SwingUtilities.invokeLater(action);
    }

    @Override
    public void close() {
        try {
            changeSubscription.close();
        } catch (Exception ignored) {
            // Listener removal is best-effort; restoration remains mandatory.
        }
        restore();
    }
}
