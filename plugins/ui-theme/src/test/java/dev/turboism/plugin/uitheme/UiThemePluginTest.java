package dev.turboism.plugin.uitheme;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StorageError;
import dev.turboism.sdk.storage.StorageErrorCode;
import dev.turboism.sdk.storage.StorageListResult;
import dev.turboism.sdk.storage.StorageMutationResult;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageReadResult;
import dev.turboism.sdk.storage.StorageWriteResult;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileReadResult;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.sdk.ui.UserFileWriteResult;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiThemePluginTest {

    @Test
    void initRegistersTheProductionThemeSelectionSchema() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);

        assertEquals("ui-theme.selection", context.config.schema.configId());
        assertEquals("ui-theme/selection.cfg", context.config.schema.relativePath());
        plugin.shutdown();
    }

    @Test
    void registersThemeContextMenuContributionsAndActions_whenEnabled() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
            List.of("ui-theme.manager.open"),
            context.contextMenus().contributions().stream()
                .map(ContextMenuRegistry.ContextMenuContribution::id)
                .toList()
        );
        assertEquals(
            List.of(
                "ui-theme.package.status.check",
                "ui-theme.manager.open",
                "ui-theme.appearance.apply-builtin"
            ),
            context.actions().actions().stream()
                .map(ActionRegistry.Action::id)
                .toList()
        );
        assertEquals(
            List.of("Turboism/menu.themeManager"),
            context.menus().contributions().stream()
                .map(MenuRegistry.MenuContribution::menuPath)
                .toList()
        );
    }

    @Test
    void removesThemeContributionsAndActions_whenDisposableScopeCloses() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.disposableScope().close();

        assertTrue(context.contextMenus().contributions().isEmpty());
        assertTrue(context.actions().actions().isEmpty());
    }

    @Test
    void statusActionUsesCubismReadAndUiHostCapabilities_whenInvoked() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.package.status.check");

        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.package.status.available",
                "INFO",
                "Theme package available: Aurora (aurora)"
            )),
            context.uiHost().notifications()
        );
    }

    @Test
    void managerWindowImportActionReportsCanceledWhenNoOpaqueUserFileGrantIsMade() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");

        dev.turboism.sdk.ui.ChoiceDialogRequest request = context.uiHost().lastChoiceRequest();
        assertNotNull(request);
        assertTrue(request.actions().stream()
            .map(dev.turboism.sdk.ui.ChoiceDialogAction::id)
            .anyMatch("import"::equals));
        context.uiHost().lastChoiceListener().onResult(request.options().get(0).id(), "import");

        final List<StatusNotification> notifications =
            awaitNotifications(context.uiHost(), 5);
        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.package.import.canceled",
                "INFO",
                "theme.package.importCanceled"
            )),
            notifications
        );
    }

    private static List<StatusNotification> awaitNotifications(
        final RecordingUiHost host,
        final int seconds
    ) throws Exception {
        final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!host.notifications().isEmpty()) {
                return host.notifications();
            }
            Thread.sleep(50);
        }
        return host.notifications();
    }

    private static dev.turboism.sdk.ui.FormDialogRequest awaitFormRequest(
        final RecordingUiHost host,
        final int seconds
    ) throws Exception {
        final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (host.lastFormRequest() != null) {
                return host.lastFormRequest();
            }
            Thread.sleep(50);
        }
        return host.lastFormRequest();
    }

    @Test
    void managerWindowApplyWithBlankActionAppliesSelectedTheme() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");

        dev.turboism.sdk.ui.ChoiceDialogRequest request = context.uiHost().lastChoiceRequest();
        assertNotNull(request);
        // Accepting the dialog reports the selected option with a blank (not null) action id.
        // The first option is the native entry; apply a real theme option instead.
        final String themeId = request.options().stream()
            .filter(option -> !"__native__".equals(option.id()))
            .findFirst()
            .orElseThrow().id();
        context.uiHost().lastChoiceListener().onResult(themeId, "");

        final dev.turboism.sdk.appearance.AppearanceRequest applied =
            awaitAppearanceRequest(context.appearanceService(), 5);
        assertNotNull(applied);
        assertEquals(themeId, applied.appearanceId());
    }

    @org.junit.jupiter.api.Test
    void managerWindowNativeOptionRestoresAppearance() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");

        final dev.turboism.sdk.ui.ChoiceDialogRequest request = context.uiHost().lastChoiceRequest();
        assertNotNull(request);
        assertEquals("__native__", request.options().get(0).id());
        assertEquals("__native__", request.selectedOptionId().orElseThrow());

        context.uiHost().lastChoiceListener().onResult("__native__", "");

        final dev.turboism.sdk.appearance.AppearanceRestoreResult restored =
            awaitAppearanceRestore(context.appearanceService(), 5);
        assertNotNull(restored);
        assertEquals(
            dev.turboism.sdk.appearance.AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE,
            restored.outcome()
        );
    }

    private static dev.turboism.sdk.appearance.AppearanceRestoreResult awaitAppearanceRestore(
        final RecordingAppearanceService service,
        final int seconds
    ) throws Exception {
        final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (service.lastRestore() != null) {
                return service.lastRestore();
            }
            Thread.sleep(50);
        }
        return service.lastRestore();
    }

    private static dev.turboism.sdk.appearance.AppearanceRequest awaitAppearanceRequest(
        final RecordingAppearanceService service,
        final int seconds
    ) throws Exception {
        final long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (service.lastRequest() != null) {
                return service.lastRequest();
            }
            Thread.sleep(50);
        }
        return service.lastRequest();
    }

    @Test
    void builtinAppearanceActionUsesSemanticPaletteAndReportsUnavailable() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.appearance.apply-builtin");

        AppearanceRequest request = context.appearanceService().lastRequest();
        assertEquals("turboism.nord", request.appearanceId());
        assertEquals(AppearanceBase.DARK, request.base());
        assertEquals("#88C0D0", request.palette().accent());
        assertEquals("#242933", request.palette().viewportBackground());
        assertEquals(
            "ui-theme.appearance.apply.unavailable",
            context.uiHost().notifications().get(0).id()
        );
        assertEquals("Theme apply failed: {0}", context.uiHost().notifications().get(0).message());
    }

    @Test
    void managerApplyReportsThatCubismEditorMustRestartAfterSuccessfulThemeApply() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        context.appearanceService().applyOutcome(AppearanceApplyResult.Outcome.APPLIED);
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");

        final ChoiceDialogRequest request = context.uiHost().lastChoiceRequest();
        final String themeId = request.options().stream()
            .filter(option -> !"__native__".equals(option.id()))
            .findFirst()
            .orElseThrow()
            .id();
        context.uiHost().lastChoiceListener().onResult(themeId, "");

        final List<StatusNotification> notifications = awaitNotifications(context.uiHost(), 5);
        assertEquals("ui-theme.selection.selected", notifications.get(0).id());
        assertEquals("INFO", notifications.get(0).severity());
        assertEquals(
            "Theme applied: " + request.options().stream()
                .filter(option -> option.id().equals(themeId))
                .findFirst()
                .orElseThrow()
                .label()
                + ". Restart Cubism Editor to ensure the theme is applied correctly.",
            notifications.get(0).message()
        );
    }

    @Test
    void builtinAppearanceActionReportsThatCubismEditorMustRestartAfterSuccessfulThemeApply()
        throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        context.appearanceService().applyOutcome(AppearanceApplyResult.Outcome.NO_CHANGE);
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.appearance.apply-builtin");

        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.appearance.apply.no_change",
                "INFO",
                "Theme applied: Nord. Restart Cubism Editor to ensure the theme is applied correctly."
            )),
            context.uiHost().notifications()
        );
    }

    @Test
    void themeEditorReportsThatCubismEditorMustRestartAfterSaveAndApply() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        context.appearanceService().applyOutcome(AppearanceApplyResult.Outcome.APPLIED);
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");
        final ChoiceDialogRequest manager = context.uiHost().lastChoiceRequest();
        final String themeId = manager.options().stream()
            .filter(option -> !"__native__".equals(option.id()))
            .findFirst()
            .orElseThrow()
            .id();
        context.uiHost().lastChoiceListener().onResult(themeId, "edit-theme");

        final dev.turboism.sdk.ui.FormDialogRequest request = awaitFormRequest(context.uiHost(), 5);
        assertNotNull(request);
        context.uiHost().lastFormListener().onResult(true, null, editorValues(request));

        final List<StatusNotification> notifications = awaitNotifications(context.uiHost(), 5);
        assertEquals("ui-theme.editor.saved-applied", notifications.get(0).id());
        assertEquals(
            "Theme applied: Fresh Theme. Restart Cubism Editor to ensure the theme is applied correctly.",
            notifications.get(0).message()
        );
    }

    private static Map<String, String> editorValues(
        final dev.turboism.sdk.ui.FormDialogRequest request
    ) {
        final Map<String, String> values = new java.util.LinkedHashMap<>();
        request.fields().forEach(field -> values.put(field.id(), field.value()));
        values.put("slug", "fresh-theme");
        values.put("name", "Fresh Theme");
        values.put("author", "Test Author");
        return Map.copyOf(values);
    }

    @Test
    void statusActionAllows_whenStatusNotifyPermissionGranted() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(
            new PermissionGatedUiHost(true)
        );
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.package.status.check");

        assertEquals(1, context.uiHost().notifications().size());
    }

    @Test
    void statusActionDenies_whenStatusNotifyPermissionMissing() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(
            new PermissionGatedUiHost(false)
        );
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        CubismPermissionException denied = assertThrows(
            CubismPermissionException.class,
            () -> context.actions().execute("ui-theme.package.status.check")
        );
        assertTrue(denied.getMessage().contains(PermissionIds.TURBOISM_UI_STATUS_NOTIFY));
        assertEquals(List.of(), context.uiHost().notifications());
    }

    private static final class RecordingPluginContext implements PluginContext {
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingContextMenuRegistry contextMenus = new RecordingContextMenuRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final DefaultPluginConfigRegistry config = new DefaultPluginConfigRegistry();
        private final EmptyPluginStorage storage = new EmptyPluginStorage();
        private final CanceledUserFiles userFiles = new CanceledUserFiles();
        private final RecordingUiHost uiHost;
        private final DisposableScope disposableScope = new DisposableScope();
        private final PluginLogger logger = new NoopPluginLogger();
        private final CubismReadCapabilityService cubismRead = new ThemeOnlyReadCapabilityService();
        private final RecordingAppearanceService appearance = new RecordingAppearanceService();

        RecordingPluginContext() {
            this(new RecordingUiHost());
        }

        RecordingPluginContext(final RecordingUiHost uiHost) {
            this.uiHost = uiHost;
        }

        RecordingContextMenuRegistry contextMenus() {
            return contextMenus;
        }

        RecordingAppearanceService appearanceService() {
            return appearance;
        }

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public dev.turboism.sdk.i18n.PluginLocalization localization() {
            return new dev.turboism.sdk.i18n.PluginLocalization() {
                @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
                @Override public String text(final String key) {
                    return switch (key) {
                        case "theme.selection.failed" -> "Theme apply failed: {0}";
                        default -> key;
                    };
                }
                @Override public String format(final String key, final Object... arguments) {
                    return switch (key) {
                        case "theme.selection.applied" -> "Theme applied: " + arguments[0]
                            + ". Restart Cubism Editor to ensure the theme is applied correctly.";
                        case "theme.selection.failed" -> "Theme apply failed: {0}";
                        default -> key;
                    };
                }
                @Override public boolean contains(final String key) { return true; }
            };
        }

        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public CubismReadCapabilityService cubismRead() {
            return cubismRead;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public RecordingActionRegistry actions() {
            return actions;
        }

        @Override
        public RecordingMenuRegistry menus() {
            return menus;
        }

        @Override
        public ContextMenuRegistry contextMenu() {
            return contextMenus;
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        @Override
        public AppearanceService appearance() {
            return appearance;
        }

        @Override
        public PluginConfigRegistry config() {
            return config;
        }

        @Override
        public PluginStorage storage() {
            return storage;
        }

        @Override
        public UserFileAccessService userFiles() {
            return userFiles;
        }

        @Override
        public UiScheduler uiScheduler() {
            return null;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<Action> actions = new ArrayList<>();

        List<Action> actions() {
            return actions;
        }

        @Override
        public Registration register(String id, Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }

        void execute(String id) {
            actions.stream()
                .filter(action -> action.id().equals(id))
                .findFirst()
                .orElseThrow()
                .handler()
                .accept(new ActionContext() {
                });
        }
    }

    private static final class RecordingMenuRegistry implements MenuRegistry {
        private final List<MenuContribution> contributions = new ArrayList<>();

        List<MenuContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(final MenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class DefaultPluginConfigRegistry implements PluginConfigRegistry {
        private ConfigSchema schema;

        @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) {
            this.schema = schema;
            return CompletableFuture.completedFuture(null);
        }
        @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) { return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(key.defaultValue(), ConfigValueSource.DEFAULT_MISSING, 0), Optional.empty())); }
        @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) { return CompletableFuture.completedFuture(new ConfigWriteResult(true, expectedRevision + 1, Optional.empty())); }
        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
        @Override public void writeString(String relativePath, String key, String value) { }
    }

    private static final class EmptyPluginStorage implements PluginStorage {
        @Override public CompletionStage<StorageReadResult<String>> readUtf8(StoragePath path, int maxBytes) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<StorageReadResult<byte[]>> readBytes(StoragePath path, int maxBytes) { return CompletableFuture.completedFuture(new StorageReadResult<>(Optional.empty(), Optional.of(new StorageError(StorageErrorCode.NOT_FOUND, "not found", path)), false)); }
        @Override public CompletionStage<StorageWriteResult> writeUtf8Atomic(StoragePath path, String content) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<StorageWriteResult> writeBytesAtomic(StoragePath path, byte[] content) { return CompletableFuture.completedFuture(new StorageWriteResult(true, Optional.empty())); }
        @Override public CompletionStage<StorageListResult> list(StoragePath directory, int maxEntries) { return CompletableFuture.completedFuture(new StorageListResult(List.of(), Optional.empty(), false)); }
        @Override public CompletionStage<StorageMutationResult> copy(StoragePath source, StoragePath target, boolean replaceExisting) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<StorageMutationResult> moveAtomic(StoragePath source, StoragePath target, boolean replaceExisting) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<StorageMutationResult> delete(StoragePath path, boolean recursive) { return CompletableFuture.completedFuture(new StorageMutationResult(false, Optional.of(new StorageError(StorageErrorCode.NOT_FOUND, "not found", path)))); }
    }

    private static final class CanceledUserFiles implements UserFileAccessService {
        @Override public CompletionStage<UserFileRequestResult> request(UserFileRequest request) { return CompletableFuture.completedFuture(new UserFileRequestResult(UserFileRequestStatus.CANCELED, Optional.empty(), Optional.empty())); }
        @Override public CompletionStage<UserFileReadResult<String>> readUtf8(UserFileHandle handle, int maxBytes) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<UserFileReadResult<byte[]>> readBytes(UserFileHandle handle, int maxBytes) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<UserFileWriteResult> writeUtf8Atomic(UserFileHandle handle, String content) { throw new UnsupportedOperationException(); }
        @Override public CompletionStage<UserFileWriteResult> writeBytesAtomic(UserFileHandle handle, byte[] content) { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingContextMenuRegistry implements ContextMenuRegistry {
        private final List<ContextMenuContribution> contributions = new ArrayList<>();

        List<ContextMenuContribution> contributions() {
            return contributions;
        }

        @Override
        public Registration contribute(ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static class RecordingUiHost implements UiHostCapabilityService {
        private final Optional<String> selectedFile;
        private final boolean confirmResult;
        private final List<DialogRequest> dialogs = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        RecordingUiHost() {
            this(Optional.empty(), true);
        }

        RecordingUiHost(final Optional<String> selectedFile, final boolean confirmResult) {
            this.selectedFile = selectedFile;
            this.confirmResult = confirmResult;
        }

        @Override
        public Optional<String> choose(final ChoiceDialogRequest request) {
            return Optional.empty();
        }

        private ChoiceDialogRequest lastChoiceRequest;
        private dev.turboism.sdk.ui.ChoiceDialogResultListener lastChoiceListener;
        private dev.turboism.sdk.ui.FormDialogRequest lastFormRequest;
        private dev.turboism.sdk.ui.FormDialogResultListener lastFormListener;

        @Override
        public void openChoiceDialog(
            final ChoiceDialogRequest request,
            final dev.turboism.sdk.ui.ChoiceDialogResultListener listener
        ) {
            this.lastChoiceRequest = request;
            this.lastChoiceListener = listener;
        }

        ChoiceDialogRequest lastChoiceRequest() {
            return lastChoiceRequest;
        }

        dev.turboism.sdk.ui.ChoiceDialogResultListener lastChoiceListener() {
            return lastChoiceListener;
        }

        dev.turboism.sdk.ui.FormDialogRequest lastFormRequest() {
            return lastFormRequest;
        }

        dev.turboism.sdk.ui.FormDialogResultListener lastFormListener() {
            return lastFormListener;
        }

        List<DialogRequest> dialogs() {
            return dialogs;
        }

        List<StatusNotification> notifications() {
            return notifications;
        }

        @Override
        public Registration contributeOverlay(OverlayContribution contribution) {
            throw new UnsupportedOperationException("overlay contributions are not used by this plugin test");
        }

        @Override
        public Registration contributeBoundingBoxOverlayButton(BoundingBoxOverlayButton contribution) {
            throw new UnsupportedOperationException("bounding-box overlay buttons are not used by this plugin test");
        }
        @Override
        public ContextSourceSnapshot contextSource() {
            throw new UnsupportedOperationException("context source is not used by this plugin test");
        }

        @Override
        public ViewportSnapshot viewport() {
            throw new UnsupportedOperationException("viewport is not used by this plugin test");
        }

        @Override
        public Registration openDialog(DialogRequest request) {
            dialogs.add(request);
            return () -> dialogs.remove(request);
        }

        @Override
        public boolean confirmDialog(DialogRequest request) {
            dialogs.add(request);
            return confirmResult;
        }

        @Override
        public Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException("embedded panels are not used by this plugin test");
        }

        @Override
        public void openFormDialog(
            final dev.turboism.sdk.ui.FormDialogRequest request,
            final dev.turboism.sdk.ui.FormDialogResultListener listener
        ) {
            this.lastFormRequest = request;
            this.lastFormListener = listener;
        }

        @Override
        public Optional<String> requestFile(FileChooserRequest request) {
            return selectedFile;
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override
        public Registration contributeContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
            throw new UnsupportedOperationException("context menus are not used through uiHost here");
        }

        @Override
        public Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution) {
            throw new UnsupportedOperationException("main toolbar is not used by this plugin test");
        }

        @Override
        public Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw new UnsupportedOperationException("palette toolbar is not used by this plugin test");
        }
    }

    private static final class PermissionGatedUiHost extends RecordingUiHost {
        private final boolean allowStatusNotify;

        PermissionGatedUiHost(final boolean allowStatusNotify) {
            this.allowStatusNotify = allowStatusNotify;
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            if (!allowStatusNotify) {
                throw new CubismPermissionException(
                    "Missing required permission " + PermissionIds.TURBOISM_UI_STATUS_NOTIFY + " for ui.status.notify"
                );
            }
            return super.notifyStatus(notification);
        }
    }

    private static final class RecordingAppearanceService implements AppearanceService {
        private AppearanceRequest lastRequest;
        private AppearanceRestoreResult lastRestore;
        private AppearanceApplyResult.Outcome applyOutcome = AppearanceApplyResult.Outcome.UNAVAILABLE;
        private final AppearanceStatus status = new AppearanceStatus(
            AppearanceStatus.Availability.UNAVAILABLE,
            AppearanceStatus.Source.NATIVE,
            Optional.empty(),
            AppearanceBase.NATIVE,
            0,
            Optional.of("appearance.unavailable")
        );

        AppearanceRequest lastRequest() {
            return lastRequest;
        }

        AppearanceRestoreResult lastRestore() {
            return lastRestore;
        }

        void applyOutcome(final AppearanceApplyResult.Outcome outcome) {
            this.applyOutcome = outcome;
        }

        @Override
        public java.util.concurrent.CompletionStage<AppearanceStatus> current() {
            return java.util.concurrent.CompletableFuture.completedFuture(status);
        }

        @Override
        public java.util.concurrent.CompletionStage<AppearanceApplyResult> apply(
            final AppearanceRequest request
        ) {
            lastRequest = request;
            return java.util.concurrent.CompletableFuture.completedFuture(
                new AppearanceApplyResult(
                    applyOutcome,
                    status,
                    applyOutcome == AppearanceApplyResult.Outcome.UNAVAILABLE
                        ? Optional.of("appearance.unavailable")
                        : Optional.empty()
                )
            );
        }

        @Override
        public java.util.concurrent.CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance() {
            final AppearanceRestoreResult result = new AppearanceRestoreResult(
                AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE,
                status,
                Optional.empty()
            );
            lastRestore = result;
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        }
    }

    private static final class ThemeOnlyReadCapabilityService implements CubismReadCapabilityService {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            throw unsupported();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            throw unsupported();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            throw unsupported();
        }

        @Override
        public SelectionSnapshot selection() {
            throw unsupported();
        }

        @Override
        public List<ParameterSnapshot> parameters() {
            throw unsupported();
        }

        @Override
        public List<ModelObjectSnapshot> modelObjects() {
            throw unsupported();
        }

        @Override
        public List<ArtMeshSnapshot> meshes() {
            throw unsupported();
        }

        @Override
        public List<DeformerSnapshot> deformers() {
            throw unsupported();
        }

        @Override
        public List<PsdDocumentSnapshot> psdDocuments() {
            throw unsupported();
        }

        @Override
        public List<ClipMaskSnapshot> clipMasks() {
            throw unsupported();
        }

        @Override
        public List<TextureAtlasSnapshot> textureAtlases() {
            throw unsupported();
        }

        @Override
        public Optional<RenderStatusSnapshot> renderStatus() {
            throw unsupported();
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            throw unsupported();
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            return Optional.of(new ThemeStatusSnapshot("aurora", "Aurora", true));
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("only themeStatus is used by this plugin test");
        }
    }

    private static final class NoopPluginLogger implements PluginLogger {
        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }
}
