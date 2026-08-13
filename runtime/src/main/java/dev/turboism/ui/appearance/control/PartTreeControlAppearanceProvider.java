package dev.turboism.ui.appearance.control;

import java.awt.Component;
import java.util.Objects;

/** Applies and restores bounded palette entries on native Part-tree labels. */
public final class PartTreeControlAppearanceProvider implements AutoCloseable {
    private final PaletteAppearanceCoordinator coordinator;
    private final NativeStyleTracker styles = new NativeStyleTracker();
    private final AutoCloseable changeSubscription;

    public PartTreeControlAppearanceProvider(final PaletteAppearanceCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changeSubscription = coordinator.onChange(this::restore);
    }

    public Component apply(
        final long hostGeneration,
        final String partId,
        final boolean folder,
        final NativePartTreeAppearanceBridge.Selectors.SourceKind kind,
        final Component component
    ) {
        Objects.requireNonNull(partId, "partId");
        final Component target = Objects.requireNonNull(component, "component");
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) return target;
        final PaletteAppearanceCoordinator.Palette palette =
            kind == NativePartTreeAppearanceBridge.Selectors.SourceKind.PART
                ? PaletteAppearanceCoordinator.Palette.PART
                : PaletteAppearanceCoordinator.Palette.DEFORMER_PART;
        styles.apply(target, coordinator.resolveCurrent(hostGeneration, palette, partId));
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
