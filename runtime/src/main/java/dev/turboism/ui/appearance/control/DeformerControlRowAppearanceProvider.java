package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.util.Objects;

/** Applies and restores bounded palette entries on native deformer control rows. */
public final class DeformerControlRowAppearanceProvider implements AutoCloseable {
    private final PaletteAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final AutoCloseable changeSubscription;

    public DeformerControlRowAppearanceProvider(final PaletteAppearanceCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::restore);
    }

    public Component apply(final long hostGeneration, final String id, final Component component) {
        Objects.requireNonNull(id, "id");
        final Component target = Objects.requireNonNull(component, "component");
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) return target;
        styles.apply(target, coordinator.resolveCurrent(
            hostGeneration, PaletteAppearanceCoordinator.Palette.DEFORMER, id
        ));
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
