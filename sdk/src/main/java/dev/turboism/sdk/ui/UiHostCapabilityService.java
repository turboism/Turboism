package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

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
     * Opens a bounded runtime-rendered choice dialog without blocking the caller.
     *
     * <p>Returns immediately after scheduling the dialog on the UI thread. The
     * listener receives the selected option id and the secondary action id (or
     * {@code null}s for accept/cancel) once the user closes the dialog.</p>
     */
    default void openChoiceDialog(
        final ChoiceDialogRequest request,
        final ChoiceDialogResultListener listener
    ) {
        throw new UnsupportedOperationException("async choice dialogs are not available");
    }

    /**
     * Contributes a runtime-rendered panel owned by the calling plugin.
     * Control action IDs resolve through that plugin's {@code ActionRegistry}.
     */
    /**
     * Contributes a runtime-rendered panel owned by the calling plugin.
     * Control action IDs resolve through that plugin's {@code ActionRegistry}.
     */

    /** Opens a bounded runtime-rendered single-choice dialog. */
    default Optional<String> choose(final ChoiceDialogRequest request) {
        throw new UnsupportedOperationException("choice dialogs are not available");
    }

    Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution);

    /**
     * Activates an embedded panel owned by the calling plugin.
     *
     * <p>Hosts that do not provide a verified panel surface fail closed.</p>
     */
    default void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
        throw new UnsupportedOperationException("embedded-panel activation is unavailable");
    }

    Optional<String> requestFile(FileChooserRequest request);

    /**
     * Reports the host's active color theme mode (Cubism light/dark). Used by
     * plugins to filter base-compatible options such as theme packages.
     */
    default UiHostColorMode currentColorMode() {
        return UiHostColorMode.LIGHT;
    }

    /**
     * Opens the host file manager at the given plugin storage directory.
     * Implementations must confine the resolved directory to the plugin's
     * storage roots and fail closed when the host cannot open directories.
     */
    default void openDirectory(final dev.turboism.sdk.storage.StoragePath directory) {
        throw new UnsupportedOperationException("open-directory is not available");
    }

    /**
     * Opens a bounded runtime-rendered form dialog (text and color fields)
     * without blocking the caller. The listener receives the field values when
     * the user accepts, a secondary action id when one is pressed, or an empty
     * map on cancel.
     */
    default void openFormDialog(
        final FormDialogRequest request,
        final FormDialogResultListener listener
    ) {
        throw new UnsupportedOperationException("form dialogs are not available");
    }

    Registration notifyStatus(StatusNotification notification);

    Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution);

    Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution);

    Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution);
}
