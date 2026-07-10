package dev.turboism.plugin.clipmask.service;

import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.List;
import java.util.Objects;

/**
 * SDK-only fake-ready service for read-only clip-mask inspection.
 * Enable-time contributions are pure descriptors (no Cubism reads) so partial
 * registration cannot leak after a later permission or host failure.
 */
public final class ClipMaskInspectorService {

    public static final String PANEL_ID = "clip-mask.inspector.panel";
    public static final String DIALOG_ID = "clip-mask.inspector.dialog";
    public static final String REFRESHED_NOTIFICATION_ID = "clip-mask.inspector.refreshed";
    public static final String UNAVAILABLE_NOTIFICATION_ID = "clip-mask.inspector.unavailable";

    private static final String PANEL_TITLE = "Clip Mask Inspector";
    private static final String PANEL_PLACEMENT = "side";
    private static final int PANEL_PRIORITY = 40;
    private static final String DIALOG_TITLE = "Clip Mask Inspector";
    private static final String ENABLE_DIALOG_BODY =
        "Clip Mask Inspector is ready. Use Inspect to refresh status.";

    private final CubismReadCapabilityService cubismRead;
    private final UiHostCapabilityService uiHost;

    public ClipMaskInspectorService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost
    ) {
        this.cubismRead = Objects.requireNonNull(cubismRead, "cubismRead");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    public Registration registerPanel() {
        return uiHost.contributeEmbeddedPanel(
            new EmbeddedPanelContribution(PANEL_ID, PANEL_TITLE, PANEL_PLACEMENT, PANEL_PRIORITY)
        );
    }

    /**
     * Contribute a static dialog descriptor at enable time. Cubism reads happen only in {@link #inspect()}.
     */
    public Registration openInspectorDialog() {
        return uiHost.openDialog(new DialogRequest(DIALOG_ID, DIALOG_TITLE, ENABLE_DIALOG_BODY));
    }

    public void inspect() {
        final List<ClipMaskSnapshot> masks = cubismRead.clipMasks();

        if (masks.isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                UNAVAILABLE_NOTIFICATION_ID,
                "WARNING",
                "No clip masks are available in this host."
            ));
            return;
        }

        final long enabledCount = masks.stream().filter(ClipMaskSnapshot::enabled).count();
        final int clippedMeshRefs = masks.stream().mapToInt(mask -> mask.clippedMeshIds().size()).sum();
        uiHost.notifyStatus(new StatusNotification(
            REFRESHED_NOTIFICATION_ID,
            "INFO",
            "Clip masks: " + masks.size()
                + " total, " + enabledCount
                + " enabled, " + clippedMeshRefs
                + " clipped mesh refs"
        ));
    }
}
