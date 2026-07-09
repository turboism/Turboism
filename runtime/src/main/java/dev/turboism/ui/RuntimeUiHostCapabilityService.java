package dev.turboism.ui;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.MainToolbarAdapter;
import dev.turboism.adapter.ui.MainToolbarAdapterImpl;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake-first runtime implementation of M12 UI host capabilities.
 *
 * <p>The service stores SDK descriptors only. It performs permission checks,
 * registers every contribution handle in the plugin DisposableScope, and keeps
 * real host UI placement/adaptation as a later adapter/UI bridge concern.</p>
 */
public final class RuntimeUiHostCapabilityService implements UiHostCapabilityService {

    public static final String UI_CONTEXT_SOURCE_READ = PermissionIds.TURBOISM_UI_CONTEXT_SOURCE_READ;
    public static final String UI_OVERLAY_CONTRIBUTE = PermissionIds.TURBOISM_UI_OVERLAY_CONTRIBUTE;
    public static final String UI_VIEWPORT_READ = PermissionIds.TURBOISM_UI_VIEWPORT_READ;
    public static final String UI_DIALOG_CONTRIBUTE = PermissionIds.TURBOISM_UI_DIALOG_CONTRIBUTE;
    public static final String UI_PANEL_CONTRIBUTE = PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE;
    public static final String UI_FILE_CHOOSER_REQUEST = PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST;
    public static final String UI_STATUS_NOTIFY = PermissionIds.TURBOISM_UI_STATUS_NOTIFY;
    public static final String UI_CONTEXT_MENU_CONTRIBUTE = PermissionIds.TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE;
    public static final String UI_TOOLBAR_MAIN_CONTRIBUTE = PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE;
    public static final String UI_TOOLBAR_PALETTE_CONTRIBUTE = PermissionIds.TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE;

    private final PermissionChecker permissionChecker;
    private final String pluginId;
    private final UiHostStateSource stateSource;
    private final DisposableScope disposableScope;
    private final StatusToolbarAdapter statusToolbarAdapter;
    private final MainToolbarAdapter mainToolbarAdapter;
    private final CopyOnWriteArrayList<OverlayContribution> overlays = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DialogRequest> dialogs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EmbeddedPanelContribution> panels = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<StatusNotification> notifications = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ContextMenuRegistry.ContextMenuContribution> contextMenus = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MainToolbarRegistry.MainToolbarContribution> mainToolbars = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbars = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SafeModeDiagnostic> statusToolbarDiagnostics = new CopyOnWriteArrayList<>();

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId
    ) {
        this(permissionChecker, pluginId, UiHostStateSource.DEFAULT, new DisposableScope());
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource
    ) {
        this(permissionChecker, pluginId, stateSource, new DisposableScope());
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope
    ) {
        this(
            permissionChecker,
            pluginId,
            stateSource,
            disposableScope,
            StatusToolbarAdapterImpl.safeMode(),
            MainToolbarAdapterImpl.safeMode()
        );
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope,
        final StatusToolbarAdapter statusToolbarAdapter
    ) {
        this(
            permissionChecker,
            pluginId,
            stateSource,
            disposableScope,
            statusToolbarAdapter,
            MainToolbarAdapterImpl.safeMode()
        );
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope,
        final StatusToolbarAdapter statusToolbarAdapter,
        final MainToolbarAdapter mainToolbarAdapter
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.pluginId = requireText(pluginId, "pluginId");
        this.stateSource = Objects.requireNonNull(stateSource, "stateSource");
        this.disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
        this.statusToolbarAdapter = Objects.requireNonNull(statusToolbarAdapter, "statusToolbarAdapter");
        this.mainToolbarAdapter = Objects.requireNonNull(mainToolbarAdapter, "mainToolbarAdapter");
    }

    @Override
    public ContextSourceSnapshot contextSource() {
        permissionChecker.check(UI_CONTEXT_SOURCE_READ, "ui.context-source.read");
        return stateSource.contextSource();
    }

    @Override
    public Registration contributeOverlay(final OverlayContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_OVERLAY_CONTRIBUTE, "ui.overlay.contribute");
        overlays.add(contribution);
        return scopedRegistration(overlays, contribution);
    }

    @Override
    public ViewportSnapshot viewport() {
        permissionChecker.check(UI_VIEWPORT_READ, "ui.viewport.read");
        return stateSource.viewport();
    }

    @Override
    public Registration openDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.contribute");
        dialogs.add(request);
        return scopedRegistration(dialogs, request);
    }

    @Override
    public boolean confirmDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.contribute");
        dialogs.add(request);
        return stateSource.confirmDialog(request);
    }

    @Override
    public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_PANEL_CONTRIBUTE, "ui.panel.contribute");
        panels.add(contribution);
        return scopedRegistration(panels, contribution);
    }

    @Override
    public Optional<String> requestFile(final FileChooserRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_FILE_CHOOSER_REQUEST, "ui.file-chooser.request");
        return stateSource.chooseFile(request).map(RuntimeUiHostCapabilityService::validateRelativePath);
    }

    @Override
    public Registration notifyStatus(final StatusNotification notification) {
        Objects.requireNonNull(notification, "notification");
        permissionChecker.check(UI_STATUS_NOTIFY, "ui.status.notify");
        final StatusToolbarAdapter.AdapterResult<Registration> adapterResult = statusToolbarAdapter.notifyStatus(notification);
        if (adapterResult.isAvailable()) {
            return enrollAdapterRegistration(adapterResult.value().orElseThrow());
        }
        adapterResult.diagnostic().ifPresent(statusToolbarDiagnostics::add);
        notifications.add(notification);
        return scopedRegistration(notifications, notification);
    }

    @Override
    public Registration contributeContextMenu(final ContextMenuRegistry.ContextMenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_CONTEXT_MENU_CONTRIBUTE, "ui.context-menu.contribute");
        contextMenus.add(contribution);
        return scopedRegistration(contextMenus, contribution);
    }

    @Override
    public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_TOOLBAR_MAIN_CONTRIBUTE, "ui.main-toolbar.contribute");
        final MainToolbarAdapter.AdapterResult<Registration> adapterResult = mainToolbarAdapter.contributeMainToolbar(contribution);
        if (adapterResult.isAvailable()) {
            return enrollAdapterRegistration(adapterResult.value().orElseThrow());
        }
        adapterResult.diagnostic().ifPresent(statusToolbarDiagnostics::add);
        mainToolbars.add(contribution);
        return scopedRegistration(mainToolbars, contribution);
    }

    @Override
    public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_TOOLBAR_PALETTE_CONTRIBUTE, "ui.palette-toolbar.contribute");
        final StatusToolbarAdapter.AdapterResult<Registration> adapterResult = statusToolbarAdapter.contributePaletteToolbar(contribution);
        if (adapterResult.isAvailable()) {
            return enrollAdapterRegistration(adapterResult.value().orElseThrow());
        }
        adapterResult.diagnostic().ifPresent(statusToolbarDiagnostics::add);
        paletteToolbars.add(contribution);
        return scopedRegistration(paletteToolbars, contribution);
    }

    /**
     * Adapter-backed host registrations are auto-enrolled in the plugin scope.
     * Plugins may also enroll the returned handle (for SDK stub hosts that do not auto-scope).
     * Wrap the host registration so dual enrollment cannot double-close the real host handle.
     */
    private Registration enrollAdapterRegistration(final Registration hostRegistration) {
        return disposableScope.register(IdempotentRegistration.of(hostRegistration));
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

    public List<SafeModeDiagnostic> statusToolbarDiagnostics() {
        return List.copyOf(statusToolbarDiagnostics);
    }

    private <T> Registration scopedRegistration(final CopyOnWriteArrayList<T> target, final T value) {
        Registration registration = new Registration() {
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
        disposableScope.register(registration);
        return registration;
    }

    private static String validateRelativePath(final String value) {
        Objects.requireNonNull(value, "file chooser result");
        if (value.isBlank() || value.startsWith("/") || value.contains("..") || value.contains("\\")) {
            throw new IllegalArgumentException("file chooser result must be a relative path without parent segments");
        }
        return value;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
