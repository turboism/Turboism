package dev.turboism.plugin.uitheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.uitheme.domain.ThemeBase;
import dev.turboism.plugin.uitheme.domain.ThemeIcons;
import dev.turboism.plugin.uitheme.domain.ThemePackageData;
import dev.turboism.plugin.uitheme.domain.ThemePackageMetadata;
import dev.turboism.plugin.uitheme.service.ThemePackageRepository;
import dev.turboism.plugin.uitheme.service.ThemeSelectionConfig;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceApplyResult;
import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearanceRequest;
import dev.turboism.sdk.appearance.AppearanceRestoreResult;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.appearance.AppearanceStatus;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
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
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileReadResult;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.sdk.ui.UserFileWriteResult;
import dev.turboism.test.action.RecordingActionRegistry;
import dev.turboism.test.concurrent.Await;
import dev.turboism.test.config.InMemoryConfigRegistry;
import dev.turboism.test.menu.RecordingMenuRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.plugin.RecordingPluginLogger;
import dev.turboism.test.storage.InMemoryPluginStorage;
import dev.turboism.test.ui.RecordingContextMenuRegistry;
import dev.turboism.test.ui.RecordingUiHost;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UiThemePluginTest {

    @Test
    void localizedThemeNoticeRequiresCubismEditorRestartAfterSwitchingTheme() throws Exception {
        final Map<String, String> expected = Map.of(
                "messages.properties", "Restart Cubism Editor after switching themes.",
                "messages_en.properties", "Restart Cubism Editor after switching themes.",
                "messages_zh_Hans.properties", "切换主题后请重新启动 Cubism Editor。",
                "messages_zh_Hant.properties", "切換主題後請重新啟動 Cubism Editor。",
                "messages_ja.properties", "テーマを切り替えた後は Cubism Editor を再起動してください。",
                "messages_ko.properties", "테마를 전환한 뒤 Cubism Editor를 다시 시작하세요.");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            final String resource = "/META-INF/turboism/i18n/" + entry.getKey();
            final Properties properties = new Properties();
            try (java.io.Reader reader = new java.io.InputStreamReader(
                    java.util.Objects.requireNonNull(getClass().getResourceAsStream(resource), resource),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            final String notice = properties.getProperty("theme.notice");
            assertTrue(notice.contains(entry.getValue()), entry.getKey());
            assertTrue(!notice.contains("reopen this window"), entry.getKey());
            assertTrue(!notice.contains("重新打开此窗口"), entry.getKey());
        }
    }

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
    void registersThemeMenuAndActionsButNoContextMenu_whenEnabled() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext();
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        assertTrue(context.contextMenus().contributions().isEmpty());
        assertEquals(
                List.of("ui-theme.package.status.check", "ui-theme.manager.open", "ui-theme.appearance.apply-builtin"),
                context.actions().actions().stream()
                        .map(ActionRegistry.Action::id)
                        .toList());
        assertEquals(
                List.of("Turboism/menu.themeManager"),
                context.menus().contributions().stream()
                        .map(MenuRegistry.MenuContribution::menuPath)
                        .toList());
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
                        "ui-theme.package.status.available", "INFO", "Theme package available: Aurora (aurora)")),
                context.uiHost().notifications());
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

        final List<StatusNotification> notifications = awaitNotifications(context.uiHost(), 5);
        assertEquals(
                List.of(new StatusNotification(
                        "ui-theme.package.import.canceled", "INFO", "theme.package.importCanceled")),
                notifications);
    }

    /**
     * The runtime delivers storage results on the plugin's own single worker, so a manager open that
     * waits for one can never be released. The gated read below stands in for that delivery order.
     */
    @Test
    void managerOpenReleasesThePluginWorkerBeforeStorageSettles() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        final CompletableFuture<Void> storageGate = new CompletableFuture<>();
        context.storage.setReadGate(storageGate);
        context.storage.seed("themes/user.aurora.zip", new byte[] {1, 2, 3});
        final UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        final ExecutorService pluginWorker = pluginWorker();
        try {
            final Future<?> open = pluginWorker.submit(() -> context.actions().execute("ui-theme.manager.open"));
            open.get(2, TimeUnit.SECONDS);
            storageGate.complete(null);

            Await.condition(() -> context.uiHost().lastChoiceRequest() != null, Duration.ofSeconds(5));
            assertEquals(
                    "__native__",
                    context.uiHost().lastChoiceRequest().options().get(0).id());
        } finally {
            storageGate.complete(null);
            pluginWorker.shutdownNow();
        }
    }

    /** A manager open requested before disable must not surface a window after the plugin stops. */
    @Test
    void disabledPluginDropsTheManagerOpenThatWasWaitingForStorage() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        final CompletableFuture<Void> storageGate = new CompletableFuture<>();
        context.storage.setReadGate(storageGate);
        context.storage.seed("themes/user.aurora.zip", new byte[] {1, 2, 3});
        final UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        final ExecutorService pluginWorker = pluginWorker();
        try {
            pluginWorker
                    .submit(() -> context.actions().execute("ui-theme.manager.open"))
                    .get(2, TimeUnit.SECONDS);
            plugin.disable();
        } finally {
            storageGate.complete(null);
            pluginWorker.shutdownNow();
        }

        // The in-memory fake settles on the calling thread, so the whole continuation runs here.
        assertNull(context.uiHost().lastChoiceRequest());
        assertEquals(List.of(), context.uiHost().notifications());
    }

    /** Covers the async delete workflow end to end: release, remove, rebuild the cache, report. */
    @Test
    void managerDeleteRemovesTheStoredPackageAndReportsTheDeletion() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        final ThemePackageRepository repository = new ThemePackageRepository(context.storage);
        repository.save(storedTheme("user.aurora"), false).toCompletableFuture().join();
        final UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");
        context.uiHost().lastChoiceListener().onResult("user.aurora", "delete");

        Await.condition(() -> !context.uiHost().notifications().isEmpty(), Duration.ofSeconds(5));
        assertEquals(
                "ui-theme.package.delete.deleted",
                context.uiHost().notifications().get(0).id());
        assertFalse(repository.find("user.aurora").toCompletableFuture().join().isPresent());
    }

    /** One worker lane, mirroring the runtime: storage results are delivered on this same thread. */
    private static ExecutorService pluginWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "theme-plugin-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** One refused window must not block the manager for the rest of the plugin lifecycle. */
    @Test
    void managerReopensAfterTheHostRefusesTheWindowOnce() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext(new RefusingOnceUiHost());
        final UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");
        assertNull(context.uiHost().lastChoiceRequest());

        context.actions().execute("ui-theme.manager.open");

        assertNotNull(context.uiHost().lastChoiceRequest());
    }

    /**
     * The window keeps its four actions and its native reload trigger, and that trigger must not wait
     * for the rebuild the way the blocking port did.
     */
    @Test
    void managerReloadKeepsEveryActionAndRebuildsTheCacheWithoutWaiting() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        final UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.manager.open");
        final ChoiceDialogRequest opened = context.uiHost().lastChoiceRequest();
        assertEquals(
                List.of("new-theme", "edit-theme", "open-dir", "import"),
                opened.actions().stream()
                        .map(dev.turboism.sdk.ui.ChoiceDialogAction::id)
                        .toList());
        final dev.turboism.sdk.ui.ChoiceDialogRefresher reload =
                opened.refresher().orElseThrow();

        // Storage answers only after the trigger returned: it must not wait for the rebuild.
        final CompletableFuture<Void> storageGate = new CompletableFuture<>();
        context.storage.setReadGate(storageGate);
        new ThemePackageRepository(context.storage)
                .save(storedTheme("user.aurora"), true)
                .toCompletableFuture()
                .join();

        final ExecutorService uiThread = pluginWorker();
        final List<dev.turboism.sdk.ui.ChoiceDialogOption> snapshot;
        try {
            snapshot = uiThread.submit(reload::refresh).get(2, TimeUnit.SECONDS);
        } finally {
            uiThread.shutdownNow();
        }
        assertEquals(ThemeSelectionConfig.NATIVE_ID, snapshot.get(0).id());
        assertFalse(snapshot.stream().anyMatch(option -> option.id().equals("user.aurora")));

        storageGate.complete(null);
        Await.condition(
                () -> reload.refresh().stream().anyMatch(option -> option.id().equals("user.aurora")),
                Duration.ofSeconds(5));
    }

    private static ThemePackageData storedTheme(final String id) {
        return new ThemePackageData(
                new ThemePackageMetadata(
                        id, "Aurora", "", "Turboism", "", "1", null, ThemeBase.ANY, ThemeIcons.LIGHT, false),
                Map.of(
                        "accent", "#88C0D0",
                        "background", "#2E3440",
                        "surface", "#3B4252",
                        "input.background", "#434C5E",
                        "foreground", "#ECEFF4",
                        "foreground.muted", "#D8DEE9",
                        "selection.background", "#4C566A",
                        "selection.foreground", "#ECEFF4",
                        "border", "#4C566A",
                        "viewport.background", "#242933"),
                Map.of(),
                "",
                "");
    }

    private static List<StatusNotification> awaitNotifications(final RecordingUiHost host, final int seconds) {
        Await.condition(() -> !host.notifications().isEmpty(), Duration.ofSeconds(seconds));
        return host.notifications();
    }

    private static dev.turboism.sdk.ui.FormDialogRequest awaitFormRequest(
            final RecordingUiHost host, final int seconds) {
        Await.condition(() -> host.lastFormRequest() != null, Duration.ofSeconds(seconds));
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
                .orElseThrow()
                .id();
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
        assertEquals(dev.turboism.sdk.appearance.AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE, restored.outcome());
    }

    private static dev.turboism.sdk.appearance.AppearanceRestoreResult awaitAppearanceRestore(
            final RecordingAppearanceService service, final int seconds) {
        Await.condition(() -> service.lastRestore() != null, Duration.ofSeconds(seconds));
        return service.lastRestore();
    }

    private static dev.turboism.sdk.appearance.AppearanceRequest awaitAppearanceRequest(
            final RecordingAppearanceService service, final int seconds) {
        Await.condition(() -> service.lastRequest() != null, Duration.ofSeconds(seconds));
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
                context.uiHost().notifications().get(0).id());
        assertEquals(
                "Theme apply failed: {0}",
                context.uiHost().notifications().get(0).message());
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
                "Theme applied: "
                        + request.options().stream()
                                .filter(option -> option.id().equals(themeId))
                                .findFirst()
                                .orElseThrow()
                                .label()
                        + ". Restart Cubism Editor to ensure the theme is applied correctly.",
                notifications.get(0).message());
    }

    @Test
    void builtinAppearanceActionReportsThatCubismEditorMustRestartAfterSuccessfulThemeApply() throws Exception {
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
                        "Theme applied: Nord. Restart Cubism Editor to ensure the theme is applied correctly.")),
                context.uiHost().notifications());
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
                notifications.get(0).message());
    }

    private static Map<String, String> editorValues(final dev.turboism.sdk.ui.FormDialogRequest request) {
        final Map<String, String> values = new java.util.LinkedHashMap<>();
        request.fields().forEach(field -> values.put(field.id(), field.value()));
        values.put("slug", "fresh-theme");
        values.put("name", "Fresh Theme");
        values.put("author", "Test Author");
        return Map.copyOf(values);
    }

    @Test
    void statusActionAllows_whenStatusNotifyPermissionGranted() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(true));
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();
        context.actions().execute("ui-theme.package.status.check");

        assertEquals(1, context.uiHost().notifications().size());
    }

    @Test
    void statusActionDenies_whenStatusNotifyPermissionMissing() throws Exception {
        RecordingPluginContext context = new RecordingPluginContext(new PermissionGatedUiHost(false));
        UiThemePlugin plugin = new UiThemePlugin();

        plugin.init(context);
        plugin.enable();

        CubismPermissionException denied = assertThrows(
                CubismPermissionException.class, () -> context.actions().execute("ui-theme.package.status.check"));
        assertTrue(denied.getMessage().contains(PermissionIds.TURBOISM_UI_STATUS_NOTIFY));
        assertEquals(List.of(), context.uiHost().notifications());
    }

    private static final class RecordingPluginContext extends FakePluginContext {
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingContextMenuRegistry contextMenus = new RecordingContextMenuRegistry();
        private final RecordingMenuRegistry menus = new RecordingMenuRegistry();
        private final InMemoryConfigRegistry config = new InMemoryConfigRegistry();
        private final InMemoryPluginStorage storage = new InMemoryPluginStorage();
        private final CanceledUserFiles userFiles = new CanceledUserFiles();
        private final RecordingUiHost uiHost;
        private final DisposableScope disposableScope = new DisposableScope();
        private final PluginLogger logger = new RecordingPluginLogger();
        private final CubismReadCapabilityService cubismRead = new ThemeOnlyReadCapabilityService();
        private final RecordingAppearanceService appearance = new RecordingAppearanceService();

        RecordingPluginContext() {
            this(new RecordingUiHost());
        }

        RecordingPluginContext(final RecordingUiHost uiHost) {
            super(FakePluginContext.builder());
            this.uiHost = uiHost;
        }

        RecordingContextMenuRegistry contextMenus() {
            return contextMenus;
        }

        RecordingAppearanceService appearanceService() {
            return appearance;
        }

        @Override
        public dev.turboism.sdk.i18n.PluginLocalization localization() {
            return new dev.turboism.sdk.i18n.PluginLocalization() {
                @Override
                public java.util.Locale locale() {
                    return java.util.Locale.ENGLISH;
                }

                @Override
                public String text(final String key) {
                    return switch (key) {
                        case "theme.selection.failed" -> "Theme apply failed: {0}";
                        default -> key;
                    };
                }

                @Override
                public String format(final String key, final Object... arguments) {
                    return switch (key) {
                        case "theme.selection.applied" ->
                            "Theme applied: " + arguments[0]
                                    + ". Restart Cubism Editor to ensure the theme is applied correctly.";
                        case "theme.selection.failed" -> "Theme apply failed: {0}";
                        default -> key;
                    };
                }

                @Override
                public boolean contains(final String key) {
                    return true;
                }
            };
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public CubismReadCapabilityService cubismRead() {
            return cubismRead;
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
        public RecordingContextMenuRegistry contextMenu() {
            return contextMenus;
        }

        @Override
        public RecordingUiHost uiHost() {
            return uiHost;
        }

        public AppearanceService appearance() {
            return appearance;
        }

        @Override
        public InMemoryConfigRegistry config() {
            return config;
        }

        @Override
        public InMemoryPluginStorage storage() {
            return storage;
        }

        @Override
        public UserFileAccessService userFiles() {
            return userFiles;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.ui.context.ContextMenuRegistry.class, () -> this.contextMenu())
                    .supply(dev.turboism.sdk.ui.UiHostCapabilityService.class, () -> this.uiHost())
                    .supply(dev.turboism.sdk.appearance.AppearanceService.class, () -> this.appearance())
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }

    private static final class CanceledUserFiles implements UserFileAccessService {
        @Override
        public CompletionStage<UserFileRequestResult> request(UserFileRequest request) {
            return CompletableFuture.completedFuture(
                    new UserFileRequestResult(UserFileRequestStatus.CANCELED, Optional.empty(), Optional.empty()));
        }

        @Override
        public CompletionStage<UserFileReadResult<String>> readUtf8(UserFileHandle handle, int maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<UserFileReadResult<byte[]>> readBytes(UserFileHandle handle, int maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<UserFileWriteResult> writeUtf8Atomic(UserFileHandle handle, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<UserFileWriteResult> writeBytesAtomic(UserFileHandle handle, byte[] content) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RefusingOnceUiHost extends RecordingUiHost {
        private boolean refused;

        @Override
        public void openChoiceDialog(
                final ChoiceDialogRequest request, final dev.turboism.sdk.ui.ChoiceDialogResultListener listener) {
            if (!refused) {
                refused = true;
                throw new IllegalStateException("window refused");
            }
            super.openChoiceDialog(request, listener);
        }
    }

    private static final class PermissionGatedUiHost extends RecordingUiHost {
        private final boolean allowStatusNotify;

        PermissionGatedUiHost(final boolean allowStatusNotify) {
            super(Optional.empty(), true);
            this.allowStatusNotify = allowStatusNotify;
        }

        @Override
        public Registration notifyStatus(StatusNotification notification) {
            if (!allowStatusNotify) {
                throw new CubismPermissionException("Missing required permission "
                        + PermissionIds.TURBOISM_UI_STATUS_NOTIFY + " for ui.status.notify");
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
                Optional.of("appearance.unavailable"));

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
        public java.util.concurrent.CompletionStage<AppearanceApplyResult> apply(final AppearanceRequest request) {
            lastRequest = request;
            return java.util.concurrent.CompletableFuture.completedFuture(new AppearanceApplyResult(
                    applyOutcome,
                    status,
                    applyOutcome == AppearanceApplyResult.Outcome.UNAVAILABLE
                            ? Optional.of("appearance.unavailable")
                            : Optional.empty()));
        }

        @Override
        public java.util.concurrent.CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance() {
            final AppearanceRestoreResult result = new AppearanceRestoreResult(
                    AppearanceRestoreResult.Outcome.NO_OWNED_OVERRIDE, status, Optional.empty());
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
}
