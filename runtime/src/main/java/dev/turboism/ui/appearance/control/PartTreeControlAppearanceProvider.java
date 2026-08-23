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

    /**
     * Applies the currently resolved palette entry to one native Part-tree label, remembering its
     * original styling so it can be restored later.
     *
     * <p>The palette is chosen from {@code kind}: a part row resolves against PART, while deformer and
     * art-mesh rows share DEFORMER_PART. Must be called on the Swing event dispatch thread; off the
     * EDT the component is returned untouched.
     *
     * @param hostGeneration the host generation the caller is rendering for; an entry resolved under a
     *     different generation is not applied
     * @param partId the row's object id, used as the palette lookup key; must not be {@code null}
     * @param folder whether the row is a folder, accepted to match the host renderer hook's shape
     * @param kind which host object backs the row, selecting the palette
     * @param component the label component to style; must not be {@code null}
     * @return {@code component}, styled if a palette entry applied and unchanged otherwise
     * @throws NullPointerException if {@code partId} or {@code component} is {@code null}
     */
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
