package dev.turboism.ui;

import dev.turboism.adapter.ui.BoundedKeyedStore;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapter;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.panel.PanelCollapsibleContentCoordinator;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime implementation of SDK UI host services.
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
    private static final int MAX_TRANSIENT_ENTRIES = 64;

    private final StatusToolbarAdapter statusToolbarAdapter;
    private final UiSurfaceAdapter uiSurfaceAdapter;
    private final PluginLocalization localization;
    private final EditorUiContributionAuthority contributionAuthority;
    private final RuntimeEmbeddedPanelActivationCoordinator panelActivationCoordinator;
    private final CallbackDispatcher callbackDispatcher;
    private final CopyOnWriteArrayList<OverlayContribution> overlays = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DialogRequest> dialogs = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<EmbeddedPanelContribution> panels = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BoundingBoxOverlayButton> boundingBoxOverlayButtons =
        new CopyOnWriteArrayList<>();
    private final BoundedKeyedStore<String, TrackedNotification> notifications =
        new BoundedKeyedStore<>(MAX_TRANSIENT_ENTRIES);
    private final CopyOnWriteArrayList<ContextMenuRegistry.ContextMenuContribution> contextMenus = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MainToolbarRegistry.MainToolbarContribution> mainToolbars = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbars = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<PaletteFilterRegistry.PaletteFilterContribution> paletteFilters = new CopyOnWriteArrayList<>();
    private final BoundedKeyedStore<String, SafeModeDiagnostic> statusToolbarDiagnostics =
        new BoundedKeyedStore<>(MAX_TRANSIENT_ENTRIES);

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
            UiSurfaceAdapterImpl.safeMode()
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
            UiSurfaceAdapterImpl.safeMode()
        );
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope,
        final StatusToolbarAdapter statusToolbarAdapter,
        final UiSurfaceAdapter uiSurfaceAdapter
    ) {
        this(
            permissionChecker,
            pluginId,
            stateSource,
            disposableScope,
            statusToolbarAdapter,
            uiSurfaceAdapter,
            null
        );
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope,
        final StatusToolbarAdapter statusToolbarAdapter,
        final UiSurfaceAdapter uiSurfaceAdapter,
        final PluginLocalization localization
    ) {
        this(
            permissionChecker,
            pluginId,
            stateSource,
            disposableScope,
            statusToolbarAdapter,
            uiSurfaceAdapter,
            localization,
            new EditorUiContributionAuthority(new RuntimeEditorUiHostLifecycle())
        );
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope,
        final StatusToolbarAdapter statusToolbarAdapter,
        final UiSurfaceAdapter uiSurfaceAdapter,
        final PluginLocalization localization,
        final EditorUiContributionAuthority contributionAuthority
    ) {
        this(
            permissionChecker,
            pluginId,
            stateSource,
            disposableScope,
            statusToolbarAdapter,
            uiSurfaceAdapter,
            localization,
            contributionAuthority,
            new RuntimeEmbeddedPanelActivationCoordinator()
        );
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope,
        final StatusToolbarAdapter statusToolbarAdapter,
        final UiSurfaceAdapter uiSurfaceAdapter,
        final PluginLocalization localization,
        final EditorUiContributionAuthority contributionAuthority,
        final RuntimeEmbeddedPanelActivationCoordinator panelActivationCoordinator
    ) {
        this(
            permissionChecker,
            pluginId,
            stateSource,
            disposableScope,
            statusToolbarAdapter,
            uiSurfaceAdapter,
            localization,
            contributionAuthority,
            panelActivationCoordinator,
            CallbackDispatcher.direct()
        );
    }

    public RuntimeUiHostCapabilityService(
        final PermissionChecker permissionChecker,
        final String pluginId,
        final UiHostStateSource stateSource,
        final DisposableScope disposableScope,
        final StatusToolbarAdapter statusToolbarAdapter,
        final UiSurfaceAdapter uiSurfaceAdapter,
        final PluginLocalization localization,
        final EditorUiContributionAuthority contributionAuthority,
        final RuntimeEmbeddedPanelActivationCoordinator panelActivationCoordinator,
        final CallbackDispatcher callbackDispatcher
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.pluginId = requireText(pluginId, "pluginId");
        this.stateSource = Objects.requireNonNull(stateSource, "stateSource");
        this.disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
        this.statusToolbarAdapter = Objects.requireNonNull(statusToolbarAdapter, "statusToolbarAdapter");
        this.uiSurfaceAdapter = Objects.requireNonNull(uiSurfaceAdapter, "uiSurfaceAdapter");
        this.localization = localization;
        this.contributionAuthority = Objects.requireNonNull(
            contributionAuthority,
            "contributionAuthority"
        );
        this.panelActivationCoordinator = Objects.requireNonNull(
            panelActivationCoordinator,
            "panelActivationCoordinator"
        );
        this.callbackDispatcher = Objects.requireNonNull(callbackDispatcher, "callbackDispatcher");
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
        return authoritativeRegistration(
            EditorUiFamily.OVERLAY_STATUS,
            contribution.id(),
            contribution.priority(),
            contribution,
            overlays
        );
    }

    @Override
    public Registration contributeBoundingBoxOverlayButton(
        final BoundingBoxOverlayButton contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(
            UI_OVERLAY_CONTRIBUTE,
            "ui.bounding-box-overlay-button.contribute"
        );
        return authoritativeRegistration(
            EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
            contribution.id(),
            contribution.order(),
            contribution.withOnClick(() -> callbackDispatcher.dispatch(
                contribution.id(),
                contribution.onClick()
            )),
            boundingBoxOverlayButtons
        );
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
        final UiSurfaceAdapter.AdapterResult<Registration> adapterResult = uiSurfaceAdapter.openDialog(request);
        if (adapterResult.isAvailable()) {
            return enrollAdapterRegistration(adapterResult.value().orElseThrow());
        }
        adapterResult.diagnostic().ifPresent(this::recordDiagnostic);
        dialogs.add(request);
        return scopedRegistration(dialogs, request);
    }

    @Override
    public boolean confirmDialog(final DialogRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.contribute");
        final UiSurfaceAdapter.AdapterResult<Boolean> adapterResult = uiSurfaceAdapter.confirmDialog(request);
        if (adapterResult.isAvailable()) {
            return adapterResult.value().orElseThrow();
        }
        adapterResult.diagnostic().ifPresent(this::recordDiagnostic);
        return stateSource.confirmDialog(request);
    }

    @Override
    public Optional<String> choose(final ChoiceDialogRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.choose");
        return RuntimeChoiceDialogs.choose(request);
    }

    @Override
    public void openChoiceDialog(
        final ChoiceDialogRequest request,
        final dev.turboism.sdk.ui.ChoiceDialogResultListener listener
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.choose");
        RuntimeChoiceDialogs.openAsync(request, listener);
    }

    @Override
    public void openFormDialog(
        final dev.turboism.sdk.ui.FormDialogRequest request,
        final dev.turboism.sdk.ui.FormDialogResultListener listener
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.contribute");
        RuntimeFormDialogs.openAsync(request, listener);
    }

    @Override
    public dev.turboism.sdk.ui.UiHostColorMode currentColorMode() {
        return stateSource.currentColorMode();
    }

    @Override
    public boolean refreshOffCanvasAppearance() {
        final Object value = javax.swing.UIManager.get("CubismCommon.gl.viewArea.background");
        if (!(value instanceof java.awt.Color color)) {
            return false;
        }
        final String hex = String.format("#%02X%02X%02X",
            color.getRed(), color.getGreen(), color.getBlue());
        return new dev.turboism.ui.appearance.OffCanvasAppearanceRefresher().refresh(hex);
    }

    @Override
    public void openDirectory(final dev.turboism.sdk.storage.StoragePath directory) {
        Objects.requireNonNull(directory, "directory");
        permissionChecker.check(UI_DIALOG_CONTRIBUTE, "ui.dialog.contribute");
        stateSource.openDirectory(directory);
    }

    @Override
    public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_PANEL_CONTRIBUTE, "ui.panel.contribute");
        return authoritativeRegistration(
            EditorUiFamily.PANEL,
            contribution.id(),
            contribution.priority(),
            contribution,
            panels
        );
    }

    @Override
    public Registration contributeCollapsibleSection(
        final CollapsibleSectionContribution contribution
    ) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_PANEL_CONTRIBUTE, "ui.panel.contribute");
        final Registration registration =
            PanelCollapsibleContentCoordinator.shared().register(this.pluginId, contribution);
        disposableScope.register(registration);
        return registration;
    }

    @Override
    public void activateEmbeddedPanel(final EmbeddedPanelId panelId) {
        panelActivationCoordinator.activate(pluginId, Objects.requireNonNull(panelId, "panelId"));
    }

    @Override
    public Optional<String> requestFile(final FileChooserRequest request) {
        Objects.requireNonNull(request, "request");
        permissionChecker.check(UI_FILE_CHOOSER_REQUEST, "ui.file-chooser.request");
        final UiSurfaceAdapter.AdapterResult<Optional<String>> adapterResult = uiSurfaceAdapter.requestFile(request);
        if (adapterResult.isAvailable()) {
            return adapterResult.value().orElseThrow().map(RuntimeUiHostCapabilityService::validateRelativePath);
        }
        adapterResult.diagnostic().ifPresent(this::recordDiagnostic);
        return stateSource.chooseFile(request).map(RuntimeUiHostCapabilityService::validateRelativePath);
    }

    @Override
    public Registration notifyStatus(final StatusNotification notification) {
        Objects.requireNonNull(notification, "notification");
        permissionChecker.check(UI_STATUS_NOTIFY, "ui.status.notify");
        final StatusToolbarAdapter.AdapterResult<Registration> adapterResult =
            statusToolbarAdapter.notifyStatus(scopedForAdapter(notification));
        if (adapterResult.isAvailable()) {
            return enrollAdapterRegistration(adapterResult.value().orElseThrow());
        }
        adapterResult.diagnostic().ifPresent(this::recordDiagnostic);
        return trackNotification(notification);
    }

    @Override
    public Registration contributeContextMenu(final ContextMenuRegistry.ContextMenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_CONTEXT_MENU_CONTRIBUTE, "ui.context-menu.contribute");
        return authoritativeRegistration(
            EditorUiFamily.CONTEXT_MENU,
            contribution.id(),
            contribution.priority(),
            contribution,
            contextMenus
        );
    }

    @Override
    public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_TOOLBAR_MAIN_CONTRIBUTE, "ui.main-toolbar.contribute");
        final MainToolbarRegistry.MainToolbarContribution resolved = resolveMainToolbarLabel(contribution);
        return authoritativeRegistration(
            EditorUiFamily.MAIN_TOOLBAR,
            resolved.contributionId(),
            resolved.order(),
            resolved,
            mainToolbars
        );
    }

    @Override
    public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_TOOLBAR_PALETTE_CONTRIBUTE, "ui.palette-toolbar.contribute");
        final PaletteToolbarRegistry.PaletteToolbarContribution resolved = resolvePaletteToolbarLabel(contribution);
        return authoritativeRegistration(
            EditorUiFamily.PALETTE_TOOLBAR,
            resolved.contributionId(),
            resolved.order(),
            resolved,
            paletteToolbars
        );
    }

    @Override
    public Registration contributePaletteFilter(final PaletteFilterRegistry.PaletteFilterContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(UI_TOOLBAR_PALETTE_CONTRIBUTE, "ui.palette-filter.contribute");
        final PaletteFilterRegistry.PaletteFilterContribution resolved = resolvePaletteFilterPlaceholder(contribution);
        return authoritativeRegistration(
            EditorUiFamily.PALETTE_FILTER,
            resolved.contributionId(),
            resolved.order(),
            resolved,
            paletteFilters
        );
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
        return notifications.snapshot().stream()
            .map(TrackedNotification::notification)
            .toList();
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

    public List<PaletteFilterRegistry.PaletteFilterContribution> paletteFilters() {
        return List.copyOf(paletteFilters);
    }

    public List<SafeModeDiagnostic> uiDiagnostics() {
        return statusToolbarDiagnostics.snapshot();
    }

    /** @deprecated use {@link #uiDiagnostics()} because diagnostics cover all UI adapter families. */
    @Deprecated
    public List<SafeModeDiagnostic> statusToolbarDiagnostics() {
        return uiDiagnostics();
    }

    /**
     * Scopes the adapter-visible notification ID with the plugin ID using length-prefix
     * encoding, so two plugins cannot collide on the host even when their local IDs
     * share prefixes or contain separators. Message and severity are unchanged.
     */
    private StatusNotification scopedForAdapter(final StatusNotification notification) {
        final String scopedId = pluginId.length() + ":" + pluginId + ":" + notification.id();
        return new StatusNotification(scopedId, notification.severity(), notification.message());
    }

    private Registration trackNotification(final StatusNotification notification) {
        final TrackedNotification tracked = new TrackedNotification(notification);
        notifications.put(notification.id(), tracked);
        return () -> notifications.removeIfSame(notification.id(), tracked);
    }

    private void recordDiagnostic(final SafeModeDiagnostic diagnostic) {
        statusToolbarDiagnostics.put(
            pluginId + "|" + diagnostic.code().name() + "|" + diagnostic.capability(),
            diagnostic
        );
    }

    private <T> Registration authoritativeRegistration(
        final EditorUiFamily family,
        final String contributionId,
        final int order,
        final T value,
        final CopyOnWriteArrayList<T> target
    ) {
        final Registration authorityRegistration = contributionAuthority.contribute(
            new EditorUiContribution<>(
                new EditorUiContributionIdentity(pluginId, family, contributionId),
                order,
                value
            )
        );
        target.add(value);
        Registration registration = new Registration() {
            private boolean closed;

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                if (target.remove(value)) {
                    authorityRegistration.close();
                }
            }
        };
        disposableScope.register(registration);
        return registration;
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

    @FunctionalInterface
    public interface CallbackDispatcher {
        boolean dispatch(String contributionId, Runnable callback);

        static CallbackDispatcher direct() {
            return (ignored, callback) -> {
                callback.run();
                return true;
            };
        }
    }

    private static final class TrackedNotification {
        private final StatusNotification notification;

        private TrackedNotification(final StatusNotification notification) {
            this.notification = Objects.requireNonNull(notification, "notification");
        }

        private StatusNotification notification() {
            return notification;
        }
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

    private MainToolbarRegistry.MainToolbarContribution resolveMainToolbarLabel(
        final MainToolbarRegistry.MainToolbarContribution contribution
    ) {
        if (localization == null) {
            return contribution;
        }
        return new MainToolbarRegistry.MainToolbarContribution(
            contribution.contributionId(),
            contribution.actionId(),
            localization.text(requireText(contribution.labelKey(), "labelKey")),
            contribution.iconResourcePath(),
            contribution.anchor(),
            contribution.order()
        );
    }

    private PaletteToolbarRegistry.PaletteToolbarContribution resolvePaletteToolbarLabel(
        final PaletteToolbarRegistry.PaletteToolbarContribution contribution
    ) {
        if (localization == null) {
            return contribution;
        }
        return new PaletteToolbarRegistry.PaletteToolbarContribution(
            contribution.contributionId(),
            contribution.actionId(),
            localization.text(requireText(contribution.labelKey(), "labelKey")),
            contribution.iconResourcePath(),
            contribution.paletteId(),
            contribution.anchor(),
            contribution.order()
        );
    }

    private PaletteFilterRegistry.PaletteFilterContribution resolvePaletteFilterPlaceholder(
        final PaletteFilterRegistry.PaletteFilterContribution contribution
    ) {
        if (localization == null) {
            return contribution;
        }
        return new PaletteFilterRegistry.PaletteFilterContribution(
            contribution.contributionId(),
            contribution.paletteId(),
            localization.text(requireText(contribution.placeholderKey(), "placeholderKey")),
            contribution.order()
        );
    }
}
