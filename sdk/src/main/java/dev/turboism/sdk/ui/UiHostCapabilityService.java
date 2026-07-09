package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.Optional;

/**
 * M12 UI-host capability aggregation surface for SDK-only plugins.
 *
 * <p>Plugins submit descriptors and receive registration handles or SDK-safe
 * values. Implementations own host UI adaptation, scheduling, placement,
 * disposal, and file/dialog/status behavior. This API must not expose Swing,
 * AWT, Cubism host widgets, native handles, or raw host objects.</p>
 */
public interface UiHostCapabilityService {

    Registration contributeOverlay(OverlayContribution contribution);

    ViewportSnapshot viewport();

    Registration openDialog(DialogRequest request);

    Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution);

    Optional<String> requestFile(FileChooserRequest request);

    Registration notifyStatus(StatusNotification notification);

    Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution);

    Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution);

    Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution);
}
