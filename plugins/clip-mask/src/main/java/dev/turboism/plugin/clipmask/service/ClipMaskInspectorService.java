package dev.turboism.plugin.clipmask.service;

import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.i18n.PluginLocalization;
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

    private static final String PANEL_TITLE_KEY = "clip-mask.inspector.title";
    private static final String PANEL_PLACEMENT = "side";
    private static final int PANEL_PRIORITY = 40;
    private static final String DIALOG_TITLE_KEY = "clip-mask.inspector.dialog.title";
    private static final String ENABLE_DIALOG_BODY_KEY = "clip-mask.inspector.ready";

    private final CubismReadCapabilityService cubismRead;
    private final UiHostCapabilityService uiHost;
    private final PluginLocalization localization;

    public ClipMaskInspectorService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost
    ) {
        this(cubismRead, uiHost, new LegacyLocalization());
    }

    public ClipMaskInspectorService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost,
        final PluginLocalization localization
    ) {
        this.cubismRead = Objects.requireNonNull(cubismRead, "cubismRead");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.localization = Objects.requireNonNull(localization, "localization");
    }

    public Registration registerPanel() {
        return uiHost.contributeEmbeddedPanel(
            new EmbeddedPanelContribution(PANEL_ID, localization.text(PANEL_TITLE_KEY), PANEL_PLACEMENT, PANEL_PRIORITY)
        );
    }

    /**
     * Contribute a static dialog descriptor at enable time. Cubism reads happen only in {@link #inspect()}.
     */
    public Registration openInspectorDialog() {
        return uiHost.openDialog(new DialogRequest(
            DIALOG_ID, localization.text(DIALOG_TITLE_KEY), localization.text(ENABLE_DIALOG_BODY_KEY)
        ));
    }

    public void inspect() {
        final List<ClipMaskSnapshot> masks = cubismRead.clipMasks();

        if (masks.isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                UNAVAILABLE_NOTIFICATION_ID,
                "WARNING",
                localization.text("clip-mask.inspector.unavailable")
            ));
            return;
        }

        final long invertedCount = masks.stream().filter(ClipMaskSnapshot::inverted).count();
        final int maskSourceRefs = masks.stream().mapToInt(mask -> mask.orderedMaskSourceIds().size()).sum();
        uiHost.notifyStatus(new StatusNotification(
            REFRESHED_NOTIFICATION_ID,
            "INFO",
            localization.format(
                "clip-mask.inspector.refreshed",
                masks.size(), invertedCount, maskSourceRefs
            )
        ));
    }

    private static final class LegacyLocalization implements PluginLocalization {
        @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
        @Override public String text(final String key) {
            return switch (key) {
                case PANEL_TITLE_KEY, DIALOG_TITLE_KEY -> "Clip Mask Inspector";
                case ENABLE_DIALOG_BODY_KEY -> "Clip Mask Inspector is ready. Use Inspect to refresh status.";
                case "clip-mask.inspector.unavailable" -> "No clip masks are available in this host.";
                default -> key;
            };
        }
        @Override public String format(final String key, final Object... args) {
            if ("clip-mask.inspector.refreshed".equals(key)) {
                return "Clip masks: " + args[0] + " target meshes, " + args[1]
                    + " inverted, " + args[2] + " mask source refs";
            }
            return text(key);
        }
        @Override public boolean contains(final String key) { return true; }
    }
}
