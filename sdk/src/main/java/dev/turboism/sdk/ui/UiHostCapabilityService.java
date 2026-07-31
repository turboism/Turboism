package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.Objects;
import java.util.Optional;

/**
 * UI-host service surface for SDK-only plugins.
 *
 * <p>Plugins submit descriptors and receive registration handles or SDK-safe
 * values. Implementations own host UI adaptation, scheduling, placement,
 * disposal, and file/dialog/status behavior. This API must not expose Swing,
 * AWT, Cubism host widgets, native handles, or raw host objects.</p>
 */
public interface UiHostCapabilityService {

    Registration contributeOverlay(OverlayContribution contribution);

    Registration contributeBoundingBoxOverlayButton(BoundingBoxOverlayButton contribution);

    ContextSourceSnapshot contextSource();

    ViewportSnapshot viewport();

    Registration openDialog(DialogRequest request);

    /**
     * Requests a yes/no confirmation for the given dialog descriptor.
     *
     * <p>Implementations must perform the same permission checks as
     * {@link #openDialog(DialogRequest)} and must not expose host widgets.
     * Returning {@code false} means the user declined or the host cancelled.</p>
     */
    boolean confirmDialog(DialogRequest request);

    /**
     * Contributes a runtime-rendered panel owned by the calling plugin.
     * Control action IDs resolve through that plugin's {@code ActionRegistry}.
     */

    Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution);

    /**
     * Activates an embedded panel owned by the calling plugin.
     *
     * <p>Hosts that do not provide a verified panel surface fail closed.</p>
     */
    default void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
        throw new UnsupportedOperationException("embedded-panel activation is unavailable");
    }

    /**
     * Activates an embedded panel owned by the calling plugin as a floating
     * window (Photoshop-style popup). Hosts that do not provide a verified
     * floating surface fail closed to plain activation.
     */
    default void activateEmbeddedPanelFloating(final EmbeddedPanelId panelId) {
        activateEmbeddedPanel(panelId);
    }

    Optional<String> requestFile(FileChooserRequest request);

    Registration notifyStatus(StatusNotification notification);

    Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution);

    Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution);

    Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution);

    /**
     * Contributes a Photoshop-style vertical icon tool strip to the main frame.
     *
     * <p>Hosts that do not provide a verified vertical-toolbar surface fail
     * closed.</p>
     */
    default Registration contributeVerticalToolbar(final VerticalToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        throw new UnsupportedOperationException("vertical-toolbar contribution is unavailable");
    }
}
