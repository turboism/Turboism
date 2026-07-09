package dev.turboism.plugin.renderopt.service;

import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.Objects;
import java.util.Optional;

public final class RenderStatusOverlayService {

    public static final String OVERLAY_ID = "render-status.overlay";
    public static final String REFRESHED_NOTIFICATION_ID = "render-status.overlay.refreshed";
    public static final String UNAVAILABLE_NOTIFICATION_ID = "render-status.overlay.unavailable";

    private static final String OVERLAY_ANCHOR = "viewport";
    private static final int OVERLAY_PRIORITY = 50;

    private final CubismReadCapabilityService cubismRead;
    private final UiHostCapabilityService uiHost;

    public RenderStatusOverlayService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost
    ) {
        this.cubismRead = Objects.requireNonNull(cubismRead, "cubismRead");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    public Registration registerOverlay() {
        return uiHost.contributeOverlay(new OverlayContribution(OVERLAY_ID, OVERLAY_ANCHOR, OVERLAY_PRIORITY));
    }

    public void refreshStatus() {
        final Optional<RenderStatusSnapshot> renderStatus = cubismRead.renderStatus();
        if (renderStatus.isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                UNAVAILABLE_NOTIFICATION_ID,
                "WARNING",
                "Render status is unavailable in this host."
            ));
            return;
        }

        final RenderStatusSnapshot status = renderStatus.orElseThrow();
        uiHost.notifyStatus(new StatusNotification(
            REFRESHED_NOTIFICATION_ID,
            "INFO",
            "Render status: " + status.framesPerSecond() + " FPS via " + status.rendererName()
        ));
    }
}
