package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.util.Objects;
import java.util.Optional;

/** Applies and restores bounded styles on native deformer control-row renderers. */
public final class DeformerControlRowAppearanceProvider implements AutoCloseable {
    private final ControlAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final AutoCloseable changeSubscription;

    public DeformerControlRowAppearanceProvider(final ControlAppearanceCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::restore);
    }

    public Component apply(final long generation, final String id, final Component component) {
        Objects.requireNonNull(id, "id");
        final Component target = Objects.requireNonNull(component, "component");
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) return target;
        styles.apply(target, generation == coordinator.hostGeneration()
            ? coordinator.deformerControlRow(id) : Optional.empty());
        return target;
    }

    void restore() {
        final Runnable action = styles::restoreAll;
        if (javax.swing.SwingUtilities.isEventDispatchThread()) action.run();
        else javax.swing.SwingUtilities.invokeLater(action);
    }

    @Override public void close() {
        try { changeSubscription.close(); } catch (Exception ignored) { }
        restore();
    }
}
