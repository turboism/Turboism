package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.util.Objects;
import java.util.Optional;

/** Applies and restores bounded styles on native Part-tree labels. */
public final class PartTreeControlAppearanceProvider implements AutoCloseable {
    private final ControlAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final AutoCloseable changeSubscription;

    public PartTreeControlAppearanceProvider(final ControlAppearanceCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::restore);
    }

    public Component apply(
        final long hostGeneration,
        final String partId,
        final boolean folder,
        final Component component
    ) {
        Objects.requireNonNull(partId, "partId");
        final Component target = Objects.requireNonNull(component, "component");
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) return target;
        styles.apply(target, hostGeneration == coordinator.hostGeneration()
            ? (folder ? coordinator.partFolder(partId) : coordinator.partLabel(partId))
            : Optional.empty());
        return target;
    }

    void restore() {
        final Runnable action = styles::restoreAll;
        if (javax.swing.SwingUtilities.isEventDispatchThread()) action.run();
        else javax.swing.SwingUtilities.invokeLater(action);
    }

    @Override
    public void close() {
        try { changeSubscription.close(); } catch (Exception ignored) { }
        restore();
    }
}
