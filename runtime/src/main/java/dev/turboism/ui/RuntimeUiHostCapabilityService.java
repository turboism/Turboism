package dev.turboism.ui;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake-first runtime implementation of M12 UI host capabilities.
 *
 * <p>The service stores SDK descriptors only. It performs permission checks and
 * returns idempotent registrations; real host UI placement/adaptation remains a
 * later adapter/UI bridge concern.</p>
 */
public final class RuntimeUiHostCapabilityService implements UiHostCapabilityService {

    public static final String UI_CONTEXT_SOURCE_READ = "turboism.ui.context-source.read";
    public static final String UI_OVERLAY_CONTRIBUTE = "turboism.ui.overlay.contribute";
    public static final String UI_DIALOG_CONTRIBUTE = "turboism.ui.dialog.contribute";
    public static final String UI_PANEL_CONTRIBUTE = "turboism.ui.panel.contribute";
    public static final String UI_FILE_CHOOSER_REQUEST = "turboism.ui.file-chooser.request";
    public static final String UI_STATUS_NOTIFY = "turboism.ui.status.notify";
    public static final String UI_TOOLBAR_CONTRIBUTE = "turboism.ui.toolbar.contribute";
    public static final String UI_CONTEXT_MENU_CONTRIBUTE = "turboism.ui.context-menu.contribute";

    private final PermissionChecker permissionChecker;
    private final String pluginId;
    private final UiHostStateSource stateSource;
    private final CopyOnWriteArrayList<OverlayContribution> overlays = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DialogRequest> dialogs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EmbeddedPanelContribution> panels = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<StatusNotification> notifications = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ContextMenuRegistry.ContextMenuContribution> contextMenus = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MainToolbarRegistry.MainToolbarContribution> mainToolbars = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbars = new CopyOnWriteArrayList<>();

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId
    ) {
        this(permissionChecker, pluginId, UiHostStateSource.DEFAULT);
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.pluginId = requireText(pluginId, "pluginId");
        this.stateSource = Objects.requireNonNull(stateSource, "stateSource");
    }

    @Override
    public Registration contributeOverlay(final OverlayContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_OVERLAY_CONTRIBUTE, "ui.overlay.contribute");
        overlays.add(contribution);
        return registration(overlays, contribution);
    }

    @Override
    public ViewportSnapshot viewport() {
        permissionChecker.check(UI_OVERLAY_CONTRIBUTE, "ui.viewport.read");
        return stateSource.viewport();
    }

    @Override
    public Registration openDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.contribute");
        dialogs.add(request);
        return registration(dialogs, request);
    }

    @Override
    public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_PANEL_CONTRIBUTE, "ui.panel.contribute");
        panels.add(contribution);
        return registration(panels, contribution);
    }

    @Override
    public Optional<String> requestFile(final FileChooserRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_FILE_CHOOSER_REQUEST, "ui.file-chooser.request");
        return stateSource.chooseFile(request);
    }

    @Override
    public Registration notifyStatus(final StatusNotification notification) {
        Objects.requireNonNull(notification, "notification");
        permissionChecker.check(UI_STATUS_NOTIFY, "ui.status.notify");
        notifications.add(notification);
        return registration(notifications, notification);
    }

    @Override
    public Registration contributeContextMenu(final ContextMenuRegistry.ContextMenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_CONTEXT_MENU_CONTRIBUTE, "ui.context-menu.contribute");
        contextMenus.add(contribution);
        return registration(contextMenus, contribution);
    }

    @Override
    public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_TOOLBAR_CONTRIBUTE, "ui.toolbar.main.contribute");
        mainToolbars.add(contribution);
        return registration(mainToolbars, contribution);
    }

    @Override
    public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_TOOLBAR_CONTRIBUTE, "ui.toolbar.palette.contribute");
        paletteToolbars.add(contribution);
        return registration(paletteToolbars, contribution);
    }

    public String pluginId() {
        return pluginId;
    }

    public List<OverlayContribution> overlays() {
        return List.copyOf(overlays);
    }

    public List<DialogRequest> dialogs() {
        return List.copyOf(dialogs);
    }

    public List<EmbeddedPanelContribution> panels() {
        return List.copyOf(panels);
    }

    public List<StatusNotification> notifications() {
        return List.copyOf(notifications);
    }

    public List<ContextMenuRegistry.ContextMenuContribution> contextMenus() {
        return List.copyOf(contextMenus);
    }

    public List<MainToolbarRegistry.MainToolbarContribution> mainToolbars() {
        return List.copyOf(mainToolbars);
    }

    public List<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbars() {
        return List.copyOf(paletteToolbars);
    }

    private static <T> Registration registration(final CopyOnWriteArrayList<T> target, final T value) {
        return new Registration() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                target.remove(value);
            }
        };
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
