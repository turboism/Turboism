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

    /**
     * Applies the currently resolved DEFORMER palette entry to one native deformer control-row
     * component, remembering its original styling so it can be restored later.
     *
     * <p>Must be called on the Swing event dispatch thread; off the EDT the component is returned
     * untouched rather than styled, so a stray host callback cannot corrupt Swing state.
     *
     * @param hostGeneration the host generation the caller is rendering for; an entry resolved under a
     *     different generation is not applied
     * @param id the deformer id whose palette entry is looked up; must not be {@code null}
     * @param component the row component to style; must not be {@code null}
     * @return {@code component}, styled if a palette entry applied and unchanged otherwise
     * @throws NullPointerException if {@code id} or {@code component} is {@code null}
     */
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
