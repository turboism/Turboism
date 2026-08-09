package dev.turboism.plugin.renderopt.service;

import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.i18n.PluginLocalization;
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
    private final PluginLocalization localization;

    public RenderStatusOverlayService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost
    ) {
        this(cubismRead, uiHost, new LegacyLocalization());
    }

    public RenderStatusOverlayService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost,
        final PluginLocalization localization
    ) {
        this.cubismRead = Objects.requireNonNull(cubismRead, "cubismRead");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.localization = Objects.requireNonNull(localization, "localization");
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
                localization.text("render-opt.status.unavailable")
            ));
            return;
        }

        final RenderStatusSnapshot status = renderStatus.orElseThrow();
        uiHost.notifyStatus(new StatusNotification(
            REFRESHED_NOTIFICATION_ID,
            "INFO",
            localization.format("render-opt.status.refreshed", status.framesPerSecond(), status.rendererName())
        ));
    }

    private static final class LegacyLocalization implements PluginLocalization {
        @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
        @Override public String text(final String key) {
            return "render-opt.status.unavailable".equals(key)
                ? "Render status is unavailable in this host." : key;
        }
        @Override public String format(final String key, final Object... args) {
            return "render-opt.status.refreshed".equals(key)
                ? "Render status: " + args[0] + " FPS via " + args[1] : text(key);
        }
        @Override public boolean contains(final String key) { return true; }
    }
}
