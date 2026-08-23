package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.util.Objects;

/** Applies and restores bounded palette entries on native deformer-tree labels. */
public final class DeformerTreeControlAppearanceProvider implements AutoCloseable {
    private final PaletteAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final AutoCloseable changeSubscription;

    public DeformerTreeControlAppearanceProvider(final PaletteAppearanceCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::restore);
    }

    /**
     * Applies the currently resolved DEFORMER_PART palette entry to one native deformer-tree label,
     * remembering its original styling so it can be restored later.
     *
     * <p>Must be called on the Swing event dispatch thread; off the EDT the component is returned
     * untouched. {@code selected} and {@code focused} are accepted to match the host renderer hook's
     * shape; palette resolution does not currently vary with them.
     *
     * @param hostGeneration the host generation the caller is rendering for; an entry resolved under a
     *     different generation is not applied
     * @param deformerId the deformer id whose palette entry is looked up; must not be {@code null}
     * @param component the label component to style; must not be {@code null}
     * @param selected whether the host is rendering the row as selected
     * @param focused whether the host is rendering the row as focused
     * @return {@code component}, styled if a palette entry applied and unchanged otherwise
     * @throws NullPointerException if {@code deformerId} or {@code component} is {@code null}
     */
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
        styles.apply(target, coordinator.resolveCurrent(
            hostGeneration, PaletteAppearanceCoordinator.Palette.DEFORMER_PART, deformerId
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
        try {
            changeSubscription.close();
        } catch (Exception ignored) {
            // Listener removal is best-effort; restoration remains mandatory.
        }
        restore();
    }
}
