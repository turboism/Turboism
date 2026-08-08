package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.ClipMaskReadAdapter;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.cubism.RenderStatusAdapter;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.adapter.ui.StatusToolbarAdapter;
import dev.turboism.adapter.ui.StatusToolbarAdapterImpl;
import dev.turboism.adapter.ui.ThemeStatusAdapterImpl;
import dev.turboism.adapter.ui.UiSurfaceAdapter;
import dev.turboism.adapter.ui.UiSurfaceAdapterImpl;
import dev.turboism.core.plugin.context.CorePluginContext;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.event.EventBus.TurboismEvent;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry;
import dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry;
import dev.turboism.ui.toolbar.ToolbarVisibilitySink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorePluginContextDescriptorPermissionsTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.descriptor-permission";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    record TestEvent(String payload) implements TurboismEvent {
    }

    @Test
    void productionFactoryBindsModelUiToItsVerifiedProjectWorkspaceSource(@TempDir Path dataDir) throws Exception {
        final ModelSnapshot modelSnapshot = new ModelSnapshot(
            "model-a", "Model A", List.of(), List.of(), List.of(), List.of()
        );
        final DocumentSnapshot document = new DocumentSnapshot(
            "document-a", "Model A", "models/model-a.cmo3", Optional.empty(),
            Optional.of(modelSnapshot), DocumentKind.MODEL, Optional.of("content-a"), Optional.empty()
        );
        final ProjectWorkspaceAdapter projectWorkspace = ProjectWorkspaceAdapter.Impl.connected(
            new ProjectWorkspaceAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.02"; }
                @Override public boolean supportsProjectWorkspaceRead() { return true; }
                @Override public Optional<ProjectSnapshot> activeProject() {
                    return Optional.of(new ProjectSnapshot(
                        "project-a", "Project A", Optional.empty(), List.of(document), List.of()
                    ));
                }
                @Override public Optional<DocumentSnapshot> activeDocument() {
                    return Optional.of(document);
                }
                @Override public Optional<WorkspaceSnapshot> workspace() {
                    return Optional.of(new WorkspaceSnapshot("workspace-a", "Workspace A", List.of()));
                }
            }
        );
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        final RuntimeHostAdapters adapters = new RuntimeHostAdapters(
            safe.themeStatus(), safe.renderStatus(), projectWorkspace, safe.clipMaskRead(),
            safe.statusToolbar(), safe.uiSurface()
        );
        final DefaultCubismServicesFactory factory = new DefaultCubismServicesFactory(
            adapters, fixedModelAccess()
        );
        final List<String> permissions = List.of(
            PermissionIds.TURBOISM_CUBISM_MODEL_READ,
            PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY
        );
        final CorePluginContext.Dependencies firstDependencies = dependencies(
            dataDir.resolve("first"), descriptorWithPermissions("plugin.first", permissions), ignored -> { }
        );
        final CorePluginContext.Dependencies secondDependencies = dependencies(
            dataDir.resolve("second"), descriptorWithPermissions("plugin.second", permissions), ignored -> { }
        );
        final CorePluginContext first = new CorePluginContext(firstDependencies, factory);
        final CorePluginContext second = new CorePluginContext(secondDependencies, factory);

        final var firstEntry = first.cubism().model().active().parameters().all().get(0)
            .ui().parameterPaletteEntry().orElseThrow();
        firstEntry.overrideBold(true);
        final var secondEntry = second.cubism().model().active().parameters().all().get(0)
            .ui().parameterPaletteEntry().orElseThrow();

        assertEquals(Optional.of(true), firstEntry.resolved().bold());
        assertEquals(Optional.of(true), secondEntry.resolved().bold());
        firstDependencies.disposableScope().close();
        secondDependencies.disposableScope().close();
    }

    @Test
    void defaultConstructorDeniesEveryM8OperationWhenDescriptorHasNoPermissions(@TempDir Path dataDir) {
        // Given: a descriptor with NO declared permissions; the convenience constructor derives the list.
        List<CubismFacadeAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        CorePluginContext context = context(dataDir, descriptorWithPermissions(), auditEvents::add);

        // Then: every M8 seam is denied because the descriptor declares no permissions.
        assertThrows(CubismPermissionException.class, () ->
            context.actions().register("test.action", actionDefinition("test.action", "Test"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.menus().contribute(menuContribution("Test", "test.action", 1))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.eventBus().subscribe(TurboismEvent.class, e -> { })
        );
        assertThrows(CubismPermissionException.class, () ->
            context.eventBus().publish(new TestEvent("test"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.mainToolbar().contribute(mainToolbarContribution("test", "label", "icon"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.paletteToolbar().contribute(paletteToolbarContribution("test", "label", "icon"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.contextMenu().contribute(contextMenuContribution("test", "label"))
        );
        assertThrows(CubismPermissionException.class, () ->
            context.config().readScope("test/config.json")
        );
        assertThrows(CubismPermissionException.class, () ->
            context.config().writeString("test/config.json", "key", "value")
        );
        assertTrue(auditEvents.size() >= 8, "Expected at least one audit event per denied operation");
    }

    @Test
    void defaultConstructorGrantsM8OperationsWhenDescriptorDeclaresPermissions(@TempDir Path dataDir) {
        // Given: a descriptor that declares all M8 permissions.
        List<CubismFacadeAuditEvent> auditEvents = new CopyOnWriteArrayList<>();
        CorePluginContext context = context(dataDir, descriptorWithAllM8Permissions(), auditEvents::add);

        // Then: all M8 registrations and uses succeed without throwing.
        assertDoesNotThrow(() -> {
            Registration action = context.actions().register("test.action", actionDefinition("test.action", "Test"));
            Registration menu = context.menus().contribute(menuContribution("Test", "test.action", 1));
            Registration subscription = context.eventBus().subscribe(TurboismEvent.class, e -> { });
            Registration mainToolbar = context.mainToolbar().contribute(mainToolbarContribution("test", "label", "icon"));
            Registration paletteToolbar = context.paletteToolbar().contribute(paletteToolbarContribution("test", "label", "icon"));
            Registration contextMenu = context.contextMenu().contribute(contextMenuContribution("test", "label"));
            Registration configReadScope = context.config().readScope("test/config.json");
            Registration configWriteScope = context.config().writeScope("test/config.json");
            context.config().writeString("test/config.json", "key", "value");
            context.eventBus().publish(new TestEvent("test"));

            action.close();
            menu.close();
            subscription.close();
            mainToolbar.close();
            paletteToolbar.close();
            contextMenu.close();
            configReadScope.close();
            configWriteScope.close();
        });

        // And the cubism side is audited from the descriptor-derived permission list, but no host is present.
        assertTrue(auditEvents.isEmpty(), "No host Cubism reads were attempted");
    }

    @Test
    void sharedServicesAreExplicitlyInjectedWhileLegacyCompositionFailsClosed(@TempDir Path dataDir) {
        final PluginLocalization localization = new PluginLocalization() {
            @Override public Locale locale() { return Locale.SIMPLIFIED_CHINESE; }
            @Override public String text(String key) { return key; }
            @Override public String format(String key, Object... arguments) { return key; }
            @Override public boolean contains(String key) { return true; }
        };
        final PluginTaskScheduler tasks = new PluginTaskScheduler() {
            @Override public TaskSubmission submit(PluginTaskRequest request) { return null; }
            @Override public TaskSubmission scheduleWithFixedDelay(FixedDelayTaskRequest request) { return null; }
        };
        final PluginStorage storage = (PluginStorage) Proxy.newProxyInstance(
            PluginStorage.class.getClassLoader(),
            new Class<?>[] {PluginStorage.class},
            (proxy, method, arguments) -> null
        );
        final UserFileAccessService userFiles = (UserFileAccessService) Proxy.newProxyInstance(
            UserFileAccessService.class.getClassLoader(),
            new Class<?>[] {UserFileAccessService.class},
            (proxy, method, arguments) -> null
        );
        final PluginDescriptor descriptor = descriptorWithPermissions();
        final CorePluginContext injected = context(
            dataDir,
            descriptor,
            ignored -> { },
            RuntimeHostAdapters.safeMode(),
            localization,
            tasks,
            storage,
            userFiles
        );
        assertSame(localization, injected.localization());
        assertSame(tasks, injected.tasks());
        assertSame(storage, injected.storage());
        assertSame(userFiles, injected.userFiles());

        final CorePluginContext legacy = context(dataDir, descriptor, ignored -> { });
        final UnsupportedOperationException error = assertThrows(
            UnsupportedOperationException.class,
            legacy::localization
        );
        assertEquals("localization service is not available", error.getMessage());
        final UnsupportedOperationException taskError = assertThrows(
            UnsupportedOperationException.class,
            legacy::tasks
        );
        assertEquals("task scheduler is not available", taskError.getMessage());
        final UnsupportedOperationException storageError = assertThrows(
            UnsupportedOperationException.class,
            legacy::storage
        );
        assertEquals("storage service is not available", storageError.getMessage());
        final UnsupportedOperationException userFileError = assertThrows(
            UnsupportedOperationException.class,
            legacy::userFiles
        );
        assertEquals(
            "user file access service is not available",
            userFileError.getMessage()
        );
    }

    @Test
    void contextAutomaticallyWiresLocalizationAndExplicitRawFallback(@TempDir Path dataDir) throws InterruptedException {
        final PluginDescriptor descriptor = descriptorWithAllM8Permissions();
        final RuntimeScheduler runtimeScheduler = scheduler();
        final RecordingToolbarVisibilitySink localizedSink = new RecordingToolbarVisibilitySink(2);
        final RuntimeMainToolbarRegistry localizedMain = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            runtimeScheduler,
            PLUGIN_ID,
            localizedSink
        );
        final RuntimePaletteToolbarRegistry localizedPalette = new RuntimePaletteToolbarRegistry(
            (permissionId, operation) -> { },
            runtimeScheduler,
            PLUGIN_ID,
            localizedSink
        );
        final PluginLocalization localization = localization(Map.of(
            "main.label", "Localized main",
            "palette.label", "Localized palette"
        ));
        final CorePluginContext localized = new CorePluginContext(
            dependencies(dataDir, descriptor, ignored -> { }, runtimeScheduler, localizedMain, localizedPalette),
            RuntimeHostAdapters.safeMode(),
            localization
        );

        localized.mainToolbar().contribute(mainToolbarContribution("main", "main.label", "icon"));
        localized.paletteToolbar().contribute(paletteToolbarContribution("palette", "palette.label", "icon"));

        assertTrue(localizedSink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("Localized main"), localizedSink.mainLabels);
        assertEquals(List.of("Localized palette"), localizedSink.paletteLabels);
        assertDoesNotThrow(() -> localizedMain.bindLocalization(localization));
        assertDoesNotThrow(() -> localizedPalette.bindLocalization(localization));

        final RuntimeScheduler rawScheduler = scheduler();
        final RecordingToolbarVisibilitySink rawSink = new RecordingToolbarVisibilitySink(2);
        final RuntimeMainToolbarRegistry rawMain = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            rawScheduler,
            PLUGIN_ID,
            rawSink
        );
        final RuntimePaletteToolbarRegistry rawPalette = new RuntimePaletteToolbarRegistry(
            (permissionId, operation) -> { },
            rawScheduler,
            PLUGIN_ID,
            rawSink
        );
        final CorePluginContext raw = new CorePluginContext(
            dependencies(dataDir, descriptor, ignored -> { }, rawScheduler, rawMain, rawPalette),
            RuntimeHostAdapters.safeMode()
        );

        raw.mainToolbar().contribute(mainToolbarContribution("main", "main.label", "icon"));
        raw.paletteToolbar().contribute(paletteToolbarContribution("palette", "palette.label", "icon"));

        assertTrue(rawSink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("main.label"), rawSink.mainLabels);
        assertEquals(List.of("palette.label"), rawSink.paletteLabels);
        assertThrows(IllegalStateException.class, () -> rawMain.bindLocalization(localization));
        assertThrows(IllegalStateException.class, () -> rawPalette.bindLocalization(localization));
    }

    @Test
    void connectedReadAdaptersAreAvailableThroughProductionContextConstructor(@TempDir Path dataDir) {
        RuntimeHostAdapters adapters = adapters(new RecordingUiSurfaceHost());
        CorePluginContext context = context(
            dataDir,
            descriptorWithPermissions(List.of(
                "turboism.cubism.project.read",
                "turboism.cubism.model.read"
            )),
            ignored -> { },
            adapters
        );

        assertEquals("project-1", context.cubismRead().activeProject().orElseThrow().projectId());
        assertEquals("workspace-1", context.cubismRead().workspace().orElseThrow().workspaceId());
        assertEquals("fake-renderer", context.cubismRead().renderStatus().orElseThrow().rendererName());
        assertEquals("mesh-1", context.cubismRead().clipMasks().get(0).targetMeshId());
    }

    @Test
    void connectedProjectAdapterCannotBypassDescriptorPermission(@TempDir Path dataDir) {
        CorePluginContext context = context(
            dataDir,
            descriptorWithPermissions(),
            ignored -> { },
            adapters(new RecordingUiSurfaceHost())
        );

        assertThrows(CubismPermissionException.class, () -> context.cubismRead().activeProject());
    }

    @Test
    void connectedUiSurfaceAdapterIsUsedThroughProductionContextConstructor(@TempDir Path dataDir) {
        RecordingUiSurfaceHost host = new RecordingUiSurfaceHost();
        CorePluginContext context = context(
            dataDir,
            descriptorWithPermissions(List.of(
                "turboism.ui.dialog.contribute",
                "turboism.ui.file-chooser.request"
            )),
            ignored -> { },
            adapters(host)
        );

        context.uiHost().openDialog(new DialogRequest("dialog", "Dialog", "Body"));
        assertTrue(context.uiHost().confirmDialog(new DialogRequest("confirm", "Confirm", "Proceed?")));
        assertEquals(Optional.of("imports/params.csv"), context.uiHost().requestFile(
            new FileChooserRequest("file", "File", List.of("csv"))
        ));

        assertEquals(1, host.dialogCount);
    }

    @Test
    void exposesTheRuntimeOwnedMeshMirrorAxisService(@TempDir Path dataDir) {
        CorePluginContext context = context(
            dataDir,
            descriptorWithPermissions(List.of(
                "turboism.cubism.model.read",
                "turboism.cubism.model.write",
                "turboism.ui.panel.contribute"
            )),
            ignored -> { },
            RuntimeHostAdapters.safeMode()
        );

        context.meshMirrorAxis().setCurrentAngleDegrees(225.0f);

        assertEquals(-135.0f, context.meshMirrorAxis().currentAngleDegrees());
    }

    private static CubismModelAccess fixedModelAccess() {
        return () -> {
            final Parameter parameter = new Parameter() {
                @Override public ParameterId id() { return new ParameterId("ParamA"); }
                @Override public float getValue() { return 1.0F; }
                @Override public float getMinimumValue() { return 0.0F; }
                @Override public float getMaximumValue() { return 2.0F; }
                @Override public float getDefaultValue() { return 1.0F; }
                @Override public void setValue(final float value) { throw new UnsupportedOperationException(); }
            };
            final Parameters parameters = new Parameters() {
                @Override public List<Parameter> all() { return List.of(parameter); }
                @Override public Parameter find(final ParameterId id) { return parameter; }
            };
            return new CubismModel() {
                @Override public ModelId id() { return new ModelId("model-a"); }
                @Override public Parameters parameters() { return parameters; }
                @Override public dev.turboism.sdk.cubism.model.Parts parts() { throw unsupported(); }
                @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { throw unsupported(); }
                @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { throw unsupported(); }
                @Override public dev.turboism.sdk.cubism.model.Glues glues() { throw unsupported(); }
                @Override public void update() { throw unsupported(); }
                private UnsupportedOperationException unsupported() {
                    return new UnsupportedOperationException();
                }
            };
        };
    }

    private static CorePluginContext context(Path dataDir, PluginDescriptor descriptor, Consumer<CubismFacadeAuditEvent> auditSink) {
        return context(dataDir, descriptor, auditSink, RuntimeHostAdapters.safeMode());
    }

    private static CorePluginContext context(
        Path dataDir,
        PluginDescriptor descriptor,
        Consumer<CubismFacadeAuditEvent> auditSink,
        RuntimeHostAdapters adapters
    ) {
        return new CorePluginContext(dependencies(dataDir, descriptor, auditSink), adapters);
    }

    private static CorePluginContext context(
        Path dataDir,
        PluginDescriptor descriptor,
        Consumer<CubismFacadeAuditEvent> auditSink,
        RuntimeHostAdapters adapters,
        PluginLocalization localization,
        PluginTaskScheduler tasks,
        PluginStorage storage,
        UserFileAccessService userFiles
    ) {
        return new CorePluginContext(
            dependencies(dataDir, descriptor, auditSink),
            adapters,
            localization,
            tasks,
            storage,
            userFiles
        );
    }

    private static CorePluginContext.Dependencies dependencies(
        Path dataDir,
        PluginDescriptor descriptor,
        Consumer<CubismFacadeAuditEvent> auditSink
    ) {
        return new CorePluginContext.Dependencies(
            descriptor,
            logger(),
            paths(dataDir),
            uiScheduler(),
            scheduler(),
            diagnostics(),
            new DisposableScope(),
            noopHostSnapshotSource(),
            auditSink,
            CLOCK
        );
    }

    private static CorePluginContext.Dependencies dependencies(
        Path dataDir,
        PluginDescriptor descriptor,
        Consumer<CubismFacadeAuditEvent> auditSink,
        RuntimeScheduler runtimeScheduler,
        MainToolbarRegistry mainToolbar,
        PaletteToolbarRegistry paletteToolbar
    ) {
        final CorePluginContext.Dependencies defaults = dependencies(dataDir, descriptor, auditSink);
        return new CorePluginContext.Dependencies(
            defaults.descriptor(),
            defaults.logger(),
            defaults.paths(),
            defaults.permissions(),
            defaults.eventBus(),
            defaults.actions(),
            defaults.menus(),
            mainToolbar,
            paletteToolbar,
            defaults.paletteFilter(),
            defaults.contextMenu(),
            defaults.config(),
            defaults.uiScheduler(),
            runtimeScheduler,
            defaults.diagnostics(),
            defaults.disposableScope(),
            defaults.hostSnapshotSource(),
            defaults.m12ReadSnapshotSource(),
            defaults.uiHostStateSource(),
            defaults.cubismAuditSink(),
            defaults.clock()
        );
    }

    private static SidecarDispatcher availableSidecar() {
        return (task, callback) -> {
            callback.run();
            return java.util.concurrent.CompletableFuture.completedFuture(
                dev.turboism.core.runtime.sidecar.SidecarResult.success("")
            );
        };
    }

    private static RuntimeScheduler scheduler() {
        List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            availableSidecar(),
            events::add
        );
    }

    private static PluginLocalization localization(final Map<String, String> catalog) {
        return new PluginLocalization() {
            @Override public Locale locale() { return Locale.ENGLISH; }
            @Override public String text(final String key) { return catalog.getOrDefault(key, key); }
            @Override public String format(final String key, final Object... arguments) { return text(key); }
            @Override public boolean contains(final String key) { return catalog.containsKey(key); }
        };
    }

    private static ActionRegistry.Action actionDefinition(String id, String label) {
        return new ActionRegistry.Action() {
            @Override public String id() { return id; }
            @Override public String label() { return label; }
            @Override public Consumer<ActionRegistry.ActionContext> handler() { return ctx -> { }; }
        };
    }

    private static MenuRegistry.MenuContribution menuContribution(String menuPath, String actionId, int order) {
        return new MenuRegistry.MenuContribution() {
            @Override public String menuPath() { return menuPath; }
            @Override public String actionId() { return actionId; }
            @Override public int order() { return order; }
        };
    }

    private static MainToolbarRegistry.MainToolbarContribution mainToolbarContribution(String id, String label, String icon) {
        return new MainToolbarRegistry.MainToolbarContribution(id, id, label, icon, "end", 1);
    }

    private static PaletteToolbarRegistry.PaletteToolbarContribution paletteToolbarContribution(String id, String label, String icon) {
        return new PaletteToolbarRegistry.PaletteToolbarContribution(id, id, label, icon, "parameters", "end", 1);
    }

    private static dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution contextMenuContribution(String id, String label) {
        return new dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution(id, label, null, "parameter", 1);
    }

    private static PluginDescriptor descriptorWithPermissions() {
        return descriptorWithPermissions(List.of());
    }

    private static PluginDescriptor descriptorWithAllM8Permissions() {
        return descriptorWithPermissions(List.of(
            "turboism.action.register",
            "turboism.ui.menu.contribute",
            "turboism.event.subscribe",
            "turboism.event.publish",
            "turboism.ui.toolbar.main.contribute",
            "turboism.ui.toolbar.palette.contribute",
            "turboism.ui.context-menu.contribute",
            "turboism.config.plugin.read",
            "turboism.config.plugin.write"
        ));
    }

    private static PluginDescriptor descriptorWithPermissions(List<String> ids) {
        return descriptorWithPermissions(PLUGIN_ID, ids);
    }

    private static PluginDescriptor descriptorWithPermissions(
        final String pluginId,
        final List<String> ids
    ) {
        return new PluginDescriptor() {
            @Override public String id() { return pluginId; }
            @Override public String name() { return "Descriptor Permission Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public List<String> entrypoints() { return List.of("dev.turboism.test.PermissionPlugin"); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.of("https://turboism.dev"); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return emptyI18n(); }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() {
                return ids.stream()
                    .<PermissionRef>map(id -> new PermissionRef() {
                        @Override public String id() { return id; }
                        @Override public String scope() { return "application"; }
                        @Override public Optional<String> reason() { return Optional.empty(); }
                    })
                    .toList();
            }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginDescriptor.I18n emptyI18n() {
        return new PluginDescriptor.I18n() {
            @Override public String baseName() { return "META-INF/turboism/i18n/messages"; }
            @Override public List<String> locales() { return List.of(); }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        };
    }

    private static PluginPaths paths(Path dataDir) {
        return new PluginPaths() {
            @Override public Path dataDir() { return dataDir; }
            @Override public Path logsDir() { return dataDir; }
            @Override public Path stateDir() { return dataDir; }
            @Override public Path cacheDir() { return dataDir; }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(Runnable work) { work.run(); return () -> { }; }
            @Override public Registration runOnUiThreadLater(Runnable work, Duration delay) { return () -> { }; }
        };
    }

    private static dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
        return new dev.turboism.sdk.diagnostics.DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() { return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty()); }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }

    private static RuntimeHostAdapters adapters(final RecordingUiSurfaceHost uiSurfaceHost) {
        RenderStatusAdapter renderStatus = RenderStatusAdapter.Impl.connected(new RenderStatusAdapter.HostOperations() {
            @Override public String hostVersion() { return "5.3.2"; }
            @Override public boolean supportsRenderStatusRead() { return true; }
            @Override public Optional<RenderStatusSnapshot> renderStatus() {
                return Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer"));
            }
        });
        ProjectWorkspaceAdapter projectWorkspace = ProjectWorkspaceAdapter.Impl.connected(
            new ProjectWorkspaceAdapter.HostOperations() {
                @Override public String hostVersion() { return "5.3.2"; }
                @Override public boolean supportsProjectWorkspaceRead() { return true; }
                @Override public Optional<ProjectSnapshot> activeProject() {
                    return Optional.of(new ProjectSnapshot("project-1", "Project", Optional.empty(), List.of()));
                }
                @Override public Optional<WorkspaceSnapshot> workspace() {
                    return Optional.of(new WorkspaceSnapshot("workspace-1", "workspace", List.of("project-1")));
                }
            }
        );
        ClipMaskReadAdapter clipMask = ClipMaskReadAdapter.Impl.connected(new ClipMaskReadAdapter.HostOperations() {
            @Override public String hostVersion() { return "5.3.2"; }
            @Override public boolean supportsClipMaskRead() { return true; }
            @Override public List<ClipMaskSnapshot> clipMasks() {
                return List.of(new ClipMaskSnapshot("mesh-1", List.of("source"), true));
            }
        });
        return new RuntimeHostAdapters(
            ThemeStatusAdapterImpl.safeMode(),
            renderStatus,
            projectWorkspace,
            clipMask,
            StatusToolbarAdapterImpl.safeMode(),
            UiSurfaceAdapterImpl.connected(uiSurfaceHost)
        );
    }

    private static final class RecordingToolbarVisibilitySink implements ToolbarVisibilitySink {
        private final CountDownLatch updated;
        private final List<String> mainLabels = new CopyOnWriteArrayList<>();
        private final List<String> paletteLabels = new CopyOnWriteArrayList<>();

        private RecordingToolbarVisibilitySink(final int expectedUpdates) {
            updated = new CountDownLatch(expectedUpdates);
        }

        @Override
        public void onMainToolbarVisibilityChanged(
            final String pluginId,
            final List<MainToolbarRegistry.MainToolbarContribution> contributions
        ) {
            contributions.stream()
                .map(MainToolbarRegistry.MainToolbarContribution::labelKey)
                .forEach(mainLabels::add);
            updated.countDown();
        }

        @Override
        public void onPaletteToolbarVisibilityChanged(
            final String pluginId,
            final List<PaletteToolbarRegistry.PaletteToolbarContribution> contributions
        ) {
            contributions.stream()
                .map(PaletteToolbarRegistry.PaletteToolbarContribution::labelKey)
                .forEach(paletteLabels::add);
            updated.countDown();
        }
    }

    private static final class RecordingUiSurfaceHost implements UiSurfaceAdapter.HostOperations {
        private int dialogCount;

        @Override public String hostVersion() { return "5.3.2"; }
        @Override public boolean supports(UiSurfaceAdapter.Capability capability) { return true; }

        @Override
        public Registration openDialog(DialogRequest request) {
            dialogCount++;
            return () -> dialogCount--;
        }

        @Override public boolean confirmDialog(DialogRequest request) { return true; }

        @Override public Optional<String> requestFile(FileChooserRequest request) {
            return Optional.of("imports/params.csv");
        }
    }
}
