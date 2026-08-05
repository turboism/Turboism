package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.FormDialogRequest;
import dev.turboism.sdk.ui.FormDialogResultListener;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuEntry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteLabelStylePluginTest {

    private static final List<String> ALL_ACTION_IDS = List.of(
        "palette-label-style.text.none", "palette-label-style.background.none",
        "palette-label-style.text.red", "palette-label-style.background.red",
        "palette-label-style.text.orange", "palette-label-style.background.orange",
        "palette-label-style.text.yellow", "palette-label-style.background.yellow",
        "palette-label-style.text.green", "palette-label-style.background.green",
        "palette-label-style.text.blue", "palette-label-style.background.blue",
        "palette-label-style.text.purple", "palette-label-style.background.purple",
        "palette-label-style.text.gray", "palette-label-style.background.gray",
        "palette-label-style.text.custom", "palette-label-style.background.custom"
    );

    @Test
    void pluginJsonDeclaresEveryPermissionTheCodeTouches() throws Exception {
        final String json;
        try (var in = PaletteLabelStylePluginTest.class.getResourceAsStream("/META-INF/turboism/plugin.json")) {
            json = new String(java.util.Objects.requireNonNull(in, "plugin.json resource").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        }
        for (final String permission : List.of(
            "turboism.action.register",
            "turboism.ui.context-menu.contribute",
            "turboism.ui.appearance.modify",
            "turboism.ui.dialog.contribute",
            "turboism.cubism.model.read",
            "turboism.cubism.project.read",
            "turboism.config.plugin.read",
            "turboism.config.plugin.write"
        )) {
            assertTrue(json.contains("\"" + permission + "\""),
                "plugin.json must declare permission " + permission);
        }
    }
    @Test
    void enableRegistersAllTextAndBackgroundActions() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(ALL_ACTION_IDS, context.actions().ids());
    }

    @Test
    void enableContributesFiveSubmenusMatchingTheMenuMatrix() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        final Map<String, ContextMenuRegistry.ContextMenuContribution> byId = new HashMap<>();
        for (final ContextMenuRegistry.ContextMenuContribution contribution : context.contextMenu().contributions()) {
            byId.put(contribution.id(), contribution);
        }
        assertEquals(
            Set.of(
                "palette-label-style.deformer-tab.text",
                "palette-label-style.deformer-tab.background",
                "palette-label-style.part-tab.text",
                "palette-label-style.parameter-tab.text",
                "palette-label-style.parameter-tab.background"
            ),
            byId.keySet()
        );

        assertContribution(byId.get("palette-label-style.deformer-tab.text"),
            Location.DEFORMER_TAB, Set.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
            "palette-label-style.text.none", "palette-label-style.text.custom");
        assertContribution(byId.get("palette-label-style.deformer-tab.background"),
            Location.DEFORMER_TAB, Set.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
            "palette-label-style.background.none", "palette-label-style.background.custom");
        assertContribution(byId.get("palette-label-style.part-tab.text"),
            Location.PART_TAB, Set.of(ObjectKind.PART, ObjectKind.PART_FOLDER, ObjectKind.WARP_DEFORMER,
                ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
            "palette-label-style.text.none", "palette-label-style.text.custom");
        assertContribution(byId.get("palette-label-style.parameter-tab.text"),
            Location.PARAMETER_TAB, Set.of(ObjectKind.PARAMETER, ObjectKind.PARAMETER_FOLDER),
            "palette-label-style.text.none", "palette-label-style.text.custom");
        assertContribution(byId.get("palette-label-style.parameter-tab.background"),
            Location.PARAMETER_TAB, Set.of(ObjectKind.PARAMETER),
            "palette-label-style.background.none", "palette-label-style.background.custom");
    }

    private static void assertContribution(
        final ContextMenuRegistry.ContextMenuContribution contribution,
        final Location location,
        final Set<ObjectKind> kinds,
        final String firstActionId,
        final String lastActionId
    ) {
        assertEquals(location, contribution.location());
        assertEquals(kinds, contribution.objectKinds());
        final ContextMenuEntry entry = contribution.entry();
        assertEquals(ContextMenuRegistry.EntryKind.SUBMENU, entry.kind());
        assertEquals(10, entry.children().size());
        assertEquals(ContextMenuRegistry.EntryKind.ITEM, entry.children().get(0).kind());
        assertEquals(firstActionId, entry.children().get(0).actionId());
        assertEquals(ContextMenuRegistry.EntryKind.SEPARATOR, entry.children().get(8).kind());
        assertEquals(lastActionId, entry.children().get(9).actionId());
    }

    @Test
    void textPresetActionOverridesAndPersistsForSelectedParameter() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));

        assertEquals(List.of("text:#E53935"), context.model().parameter("p1").entry.textEvents());
        assertEquals(
            "#E53935",
            context.config().readString(LabelStylePersistence.scopePath("project-1"),
                "PARAMETER_TAB:p1:text").orElseThrow()
        );
    }

    @Test
    void backgroundPresetOnDeformerTabDeformerUsesNativeLabelColorWithoutConfigWrite() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.background.blue",
            new ContextMenuSelection(1L, "doc", Location.DEFORMER_TAB,
                List.of(new ContextMenuSelection.Item(ObjectKind.WARP_DEFORMER, "warp1"))));

        assertEquals(List.of(new dev.turboism.sdk.ui.appearance.NativeLabelColor.Preset(
            dev.turboism.sdk.ui.appearance.PresetColor.BLUE)),
            context.model().deformer("warp1").nativeLabelColors);
        assertTrue(context.config().entries().isEmpty());
    }

    @Test
    void noneActionClearsPersistedEntryAndClosesOverride() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));
        context.actions().execute("palette-label-style.text.none", parameterSelection("p1"));

        assertEquals(List.of("text:#E53935", "text:closed"), context.model().parameter("p1").entry.events());
        assertEquals(Optional.of(""), context.config().readString(
            LabelStylePersistence.scopePath("project-1"), "PARAMETER_TAB:p1:text"));
        assertTrue(LabelStylePersistence.readAll(context.config(), "project-1").isEmpty());
    }

    @Test
    void partTabAndParameterFolderTextActionsDispatchBySelection() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.green",
            new ContextMenuSelection(1L, "doc", Location.PART_TAB,
                List.of(new ContextMenuSelection.Item(ObjectKind.PART, "part1"))));
        context.actions().execute("palette-label-style.text.yellow",
            new ContextMenuSelection(1L, "doc", Location.PARAMETER_TAB,
                List.of(new ContextMenuSelection.Item(ObjectKind.PARAMETER_FOLDER, "folder1"))));

        assertEquals(List.of("text:#4CAF50"), context.model().part("part1").entry.events());
        assertEquals(List.of("text:#FDD835"), context.model().group("folder1").entry.events());
    }

    @Test
    void artMeshOnDeformerTabSupportsTextAndBackgroundOverrides() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection selection = new ContextMenuSelection(1L, "doc", Location.DEFORMER_TAB,
            List.of(new ContextMenuSelection.Item(ObjectKind.ART_MESH, "mesh1")));
        context.actions().execute("palette-label-style.text.purple", selection);
        context.actions().execute("palette-label-style.background.gray", selection);

        assertEquals(List.of("text:#9C27B0"), context.model().drawable("mesh1").deformerEntry.textEvents());
        assertEquals(List.of("background:#9E9E9E"), context.model().drawable("mesh1").deformerEntry.backgroundEvents());
    }

    @Test
    void customActionOpensColorDialogAndAppliesAcceptedValue() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.custom", parameterSelection("p1"));

        assertEquals(1, context.uiHost().formDialogs().size());
        assertEquals("palette-label-style.custom-color", context.uiHost().formDialogs().get(0).id());
        assertEquals(dev.turboism.sdk.ui.FormFieldKind.COLOR,
            context.uiHost().formDialogs().get(0).fields().get(0).kind());

        context.uiHost().acceptForm(0, Map.of("color", "#123456"));

        assertEquals(List.of("text:#123456"), context.model().parameter("p1").entry.textEvents());
        assertEquals(
            "#123456",
            context.config().readString(LabelStylePersistence.scopePath("project-1"),
                "PARAMETER_TAB:p1:text").orElseThrow()
        );
    }

    @Test
    void customDialogCancelAppliesNothing() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.custom", parameterSelection("p1"));
        context.uiHost().cancelForm(0);

        assertTrue(context.model().parameter("p1").entry.events().isEmpty());
        assertTrue(context.config().entries().isEmpty());
    }

    @Test
    void invalidCustomHexIsIgnored() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.background.custom", parameterSelection("p1"));
        context.uiHost().acceptForm(0, Map.of("color", "not-a-color"));

        assertTrue(context.model().parameter("p1").entry.events().isEmpty());
        assertTrue(context.config().entries().isEmpty());
    }

    @Test
    void actionWithoutSelectionDoesNothing() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.red");

        assertTrue(context.model().parameter("p1").entry.events().isEmpty());
    }

    @Test
    void onModelOpenedReplaysStoredColorsForCurrentProject() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.config().rawWrite(LabelStylePersistence.scopePath("project-1"),
            "PARAMETER_TAB:p1:text", "#E53935");
        context.config().rawWrite(LabelStylePersistence.scopePath("project-1"),
            "index", "PARAMETER_TAB:p1:text");
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        plugin.onModelOpened(null);

        assertEquals(List.of("text:#E53935", "text:closed", "text:#E53935"),
            context.model().parameter("p1").entry.events());
    }

    @Test
    void replayClosesPreviousOverridesBeforeApplyingStoredColors() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.config().rawWrite(LabelStylePersistence.scopePath("project-1"),
            "PARAMETER_TAB:p1:text", "#E53935");
        context.config().rawWrite(LabelStylePersistence.scopePath("project-1"),
            "index", "PARAMETER_TAB:p1:text");
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.blue", parameterSelection("p1"));
        assertEquals(List.of("text:#E53935", "text:closed", "text:#2196F3"),
            context.model().parameter("p1").entry.events());

        plugin.onModelOpened(null);

        // Replay reads the current config, which now stores the blue override.
        assertEquals(List.of("text:#E53935", "text:closed", "text:#2196F3", "text:closed", "text:#2196F3"),
            context.model().parameter("p1").entry.events());
    }

    @Test
    void enableIsSafeWithoutActiveModel() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.cubism().noModel();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(18, context.actions().ids().size());
    }

    @Test
    void blankProjectIdUsesDefaultScope() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.cubism().noProject();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));

        assertEquals(
            "#E53935",
            context.config().readString(LabelStylePersistence.scopePath("default"),
                "PARAMETER_TAB:p1:text").orElseThrow()
        );
    }

    @Test
    void disposableScopeClosesActionsAndMenus() throws Exception {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.disposableScope().close();

        assertTrue(context.actions().ids().isEmpty());
        assertTrue(context.contextMenu().contributions().isEmpty());
    }

    private static ContextMenuSelection parameterSelection(final String id) {
        return new ContextMenuSelection(1L, "document-1", Location.PARAMETER_TAB,
            List.of(new ContextMenuSelection.Item(ObjectKind.PARAMETER, id)));
    }

    // ------------------------------------------------------------- fakes

    private static final class RecordingPluginContext implements PluginContext {
        private final DisposableScope disposableScope = new DisposableScope();
        private final RecordingActionRegistry actions = new RecordingActionRegistry();
        private final RecordingContextMenuRegistry contextMenu = new RecordingContextMenuRegistry();
        private final RecordingUiHost uiHost = new RecordingUiHost();
        private final RecordingPluginConfigRegistry config = new RecordingPluginConfigRegistry();
        private final FixedCubismFacade cubism = new FixedCubismFacade();
        private final TestPluginLogger logger = new TestPluginLogger();

        @Override public PluginDescriptor descriptor() { throw new UnsupportedOperationException(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw new UnsupportedOperationException(); }
        @Override public PluginLocalization localization() {
            return new PluginLocalization() {
                @Override public Locale locale() { return Locale.ROOT; }
                @Override public String text(final String key) { return key; }
                @Override public String format(final String key, final Object... arguments) { return key; }
                @Override public boolean contains(final String key) { return true; }
            };
        }
        @Override public FixedCubismFacade cubism() { return cubism; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw new UnsupportedOperationException(); }
        @Override public RecordingActionRegistry actions() { return actions; }
        @Override public MenuRegistry menus() { throw new UnsupportedOperationException(); }
        @Override public RecordingContextMenuRegistry contextMenu() { return contextMenu; }
        @Override public UiScheduler uiScheduler() { throw new UnsupportedOperationException(); }
        @Override public DiagnosticReport diagnostics() { throw new UnsupportedOperationException(); }
        @Override public DisposableScope disposableScope() { return disposableScope; }
        @Override public RecordingUiHost uiHost() { return uiHost; }
        @Override public RecordingPluginConfigRegistry config() { return config; }

        FakeModel model() {
            return cubism.model;
        }
    }

    private static final class FixedCubismFacade implements CubismFacade {
        private final FakeModel model = new FakeModel();
        private Optional<ProjectSnapshot> project = Optional.of(new ProjectSnapshot(
            "project-1", "Project 1", Optional.empty(), List.of(), List.of()));
        private boolean modelPresent = true;

        void noProject() {
            project = Optional.empty();
        }

        void noModel() {
            modelPresent = false;
        }

        @Override public CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
        @Override public Optional<ProjectSnapshot> activeProject() { return project; }
        @Override public Optional<DocumentSnapshot> activeDocument() { throw new UnsupportedOperationException(); }
        @Override public Optional<ModelSnapshot> activeModel() { throw new UnsupportedOperationException(); }
        @Override public boolean isHostPresent() { return true; }
        @Override public CubismModelAccess model() {
            return () -> {
                if (!modelPresent) {
                    throw new IllegalStateException("no model active");
                }
                return model;
            };
        }
        @Override public TransactionManager transactionManager() { throw new UnsupportedOperationException(); }
    }

    private static final class RecordingActionRegistry implements ActionRegistry {
        private final List<ActionRegistry.Action> actions = new ArrayList<>();

        List<String> ids() {
            return actions.stream().map(ActionRegistry.Action::id).toList();
        }

        @Override public Registration register(final String id, final ActionRegistry.Action action) {
            actions.add(action);
            return () -> actions.remove(action);
        }

        void execute(final String id) {
            execute(id, new ActionRegistry.ActionContext() { });
        }

        void execute(final String id, final ContextMenuSelection selection) {
            execute(id, new ActionRegistry.ActionContext() {
                @Override public Optional<ContextMenuSelection> contextMenuSelection() {
                    return Optional.of(selection);
                }
            });
        }

        private void execute(final String id, final ActionRegistry.ActionContext actionContext) {
            actions.stream().filter(action -> action.id().equals(id)).findFirst().orElseThrow()
                .handler().accept(actionContext);
        }
    }

    private static final class RecordingContextMenuRegistry implements ContextMenuRegistry {
        private final List<ContextMenuContribution> contributions = new ArrayList<>();

        List<ContextMenuContribution> contributions() {
            return List.copyOf(contributions);
        }

        @Override public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    private static final class RecordingPluginConfigRegistry implements dev.turboism.sdk.config.PluginConfigRegistry {
        private final Map<String, Map<String, String>> scopes = new HashMap<>();

        @Override public Registration readScope(final String relativePath) {
            scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>());
            return () -> scopes.remove(relativePath);
        }

        @Override public Registration writeScope(final String relativePath) {
            scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>());
            return () -> scopes.remove(relativePath);
        }

        @Override public Optional<String> readString(final String relativePath, final String key) {
            final Map<String, String> scope = scopes.get(relativePath);
            return scope == null ? Optional.empty() : Optional.ofNullable(scope.get(key));
        }

        @Override public void writeString(final String relativePath, final String key, final String value) {
            scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>()).put(key, value);
        }

        Map<String, String> entries() {
            final Map<String, String> all = new HashMap<>();
            for (final Map<String, String> scope : scopes.values()) {
                all.putAll(scope);
            }
            return all;
        }

        void rawWrite(final String relativePath, final String key, final String value) {
            scopes.computeIfAbsent(relativePath, ignored -> new HashMap<>()).put(key, value);
        }
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final List<FormDialogRequest> formDialogs = new ArrayList<>();
        private final List<FormDialogResultListener> formListeners = new ArrayList<>();

        List<FormDialogRequest> formDialogs() {
            return List.copyOf(formDialogs);
        }

        void acceptForm(final int index, final Map<String, String> values) {
            formListeners.get(index).onResult(true, null, values);
        }

        void cancelForm(final int index) {
            formListeners.get(index).onResult(false, null, Map.of());
        }

        @Override public void openFormDialog(final FormDialogRequest request, final FormDialogResultListener listener) {
            formDialogs.add(request);
            formListeners.add(listener);
        }

        @Override public Registration contributeOverlay(dev.turboism.sdk.ui.OverlayContribution contribution) { throw unsupported(); }
        @Override public Registration contributeBoundingBoxOverlayButton(dev.turboism.sdk.ui.BoundingBoxOverlayButton contribution) { throw unsupported(); }
        @Override public dev.turboism.sdk.ui.context.ContextSourceSnapshot contextSource() { throw unsupported(); }
        @Override public dev.turboism.sdk.ui.ViewportSnapshot viewport() { throw unsupported(); }
        @Override public Registration openDialog(dev.turboism.sdk.ui.DialogRequest request) { throw unsupported(); }
        @Override public boolean confirmDialog(dev.turboism.sdk.ui.DialogRequest request) { throw unsupported(); }
        @Override public Registration contributeEmbeddedPanel(dev.turboism.sdk.ui.EmbeddedPanelContribution contribution) { throw unsupported(); }
        @Override public Optional<String> requestFile(dev.turboism.sdk.ui.FileChooserRequest request) { throw unsupported(); }
        @Override public Registration notifyStatus(dev.turboism.sdk.ui.StatusNotification notification) { throw unsupported(); }
        @Override public Registration contributeContextMenu(ContextMenuContribution contribution) { throw unsupported(); }
        @Override public Registration contributeMainToolbar(dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.MainToolbarContribution contribution) { throw unsupported(); }
        @Override public Registration contributePaletteToolbar(dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.PaletteToolbarContribution contribution) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used");
        }
    }

    private static final class TestPluginLogger implements PluginLogger {
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message) { }
        @Override public void error(String message, Throwable throwable) { }
    }

    // ---------------------------------------------------------- model fakes

    private static final class FakeModel implements CubismModel {
        private final List<FakeParameter> parameters = List.of(new FakeParameter("p1"));
        private final List<FakeParameterGroup> groups = List.of(new FakeParameterGroup("folder1"));
        private final List<FakePart> parts = List.of(new FakePart("part1"));
        private final List<FakeDeformer> deformers = List.of(new FakeDeformer("warp1"));
        private final List<FakeDrawable> drawables = List.of(new FakeDrawable("mesh1"));

        FakeParameter parameter(final String id) { return find(parameters, "parameter:" + id); }
        FakeParameterGroup group(final String id) { return find(groups, "group:" + id); }
        FakePart part(final String id) { return find(parts, "part:" + id); }
        FakeDeformer deformer(final String id) { return find(deformers, "deformer:" + id); }
        FakeDrawable drawable(final String id) { return find(drawables, "drawable:" + id); }

        private static <T> T find(final List<T> values, final String marker) {
            return values.stream()
                .filter(value -> String.valueOf(value).contains(marker))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(marker));
        }

        @Override public ModelId id() { return new ModelId("model-1"); }
        @Override public Parameters parameters() {
            return new Parameters() {
                @Override public List<Parameter> all() { return List.copyOf(parameters); }
                @Override public Parameter find(final ParameterId id) {
                    return parameters.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override public List<ParameterGroup> all() { return List.copyOf(groups); }
                @Override public ParameterGroup root() { return groups.get(0); }
                @Override public ParameterGroup find(ParameterGroupId id) {
                    return groups.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Parts parts() {
            return new Parts() {
                @Override public List<Part> all() { return List.copyOf(parts); }
                @Override public Part find(PartId id) {
                    return parts.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Deformers deformers() {
            return new Deformers() {
                @Override public List<Deformer> all() { return List.copyOf(deformers); }
                @Override public Deformer find(DeformerId id) {
                    return deformers.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Drawables drawables() {
            return new Drawables() {
                @Override public List<Drawable> all() { return List.copyOf(drawables); }
                @Override public Drawable find(ArtMeshId id) {
                    return drawables.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
                }
            };
        }
        @Override public Glues glues() { throw new UnsupportedOperationException("not used"); }
        @Override public void update() { throw new UnsupportedOperationException("not used"); }
    }

    private static final class FakeParameter implements Parameter {
        final FakePaletteEntry entry = new FakePaletteEntry();
        private final String id;
        FakeParameter(final String id) { this.id = id; }
        @Override public ParameterId id() { return new ParameterId(id); }
        @Override public dev.turboism.sdk.ui.appearance.model.ParameterAppearance ui() {
            return () -> Optional.of(entry);
        }
        @Override public float getValue() { return 0.0F; }
        @Override public float getMinimumValue() { return -1.0F; }
        @Override public float getMaximumValue() { return 1.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { }
        @Override public String toString() { return "parameter:" + id; }
    }

    private static final class FakeParameterGroup implements ParameterGroup {
        final FakePaletteEntry entry = new FakePaletteEntry();
        private final String id;
        FakeParameterGroup(final String id) { this.id = id; }
        @Override public ParameterGroupId id() { return new ParameterGroupId(id); }
        @Override public dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance() {
                @Override public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> parameterPaletteEntry() { return Optional.of(entry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
                @Override public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) { }
            };
        }
        @Override public Optional<String> name() { return Optional.of(id); }
        @Override public Optional<ParameterGroupId> parentId() { return Optional.empty(); }
        @Override public List<ParameterGroupId> childGroupIds() { return List.of(); }
        @Override public List<ParameterId> parameterIds() { return List.of(); }
        @Override public String toString() { return "group:" + id; }
    }

    private static final class FakePart implements Part {
        final FakePaletteEntry entry = new FakePaletteEntry();
        private final String id;
        FakePart(final String id) { this.id = id; }
        @Override public PartId id() { return new PartId(id); }
        @Override public dev.turboism.sdk.ui.appearance.model.PartAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.PartAppearance() {
                @Override public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> partPaletteEntry() { return Optional.of(entry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
                @Override public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) { }
            };
        }
        @Override public void setName(final String name) { }
        @Override public float getOpacity() { return 1.0F; }
        @Override public int parentIndex() { return -1; }
        @Override public void setOpacity(final float opacity) { }
        @Override public String toString() { return "part:" + id; }
    }

    private static final class FakeDeformer implements Deformer {
        final FakePaletteEntry partEntry = new FakePaletteEntry();
        final FakePaletteEntry deformerEntry = new FakePaletteEntry();
        final List<dev.turboism.sdk.ui.appearance.NativeLabelColor> nativeLabelColors = new ArrayList<>();
        private final String id;
        FakeDeformer(final String id) { this.id = id; }
        @Override public DeformerId id() { return new DeformerId(id); }
        @Override public dev.turboism.sdk.ui.appearance.model.DeformerAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.DeformerAppearance() {
                @Override public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> partPaletteEntry() { return Optional.of(partEntry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> deformerPaletteEntry() { return Optional.of(deformerEntry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.NativeLabelColorState> nativeLabelColor() { return Optional.empty(); }
                @Override public void setNativeLabelColor(dev.turboism.sdk.ui.appearance.NativeLabelColor color) { nativeLabelColors.add(color); }
            };
        }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return emptyInts(); }
        @Override public String toString() { return "deformer:" + id; }
    }

    private static final class FakeDrawable implements Drawable {
        final FakePaletteEntry partEntry = new FakePaletteEntry();
        final FakePaletteEntry deformerEntry = new FakePaletteEntry();
        private final String id;
        FakeDrawable(final String id) { this.id = id; }
        @Override public ArtMeshId id() { return new ArtMeshId(id); }
        @Override public dev.turboism.sdk.ui.appearance.model.DrawableAppearance ui() {
            return new dev.turboism.sdk.ui.appearance.model.DrawableAppearance() {
                @Override public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> partPaletteEntry() { return Optional.of(partEntry); }
                @Override public Optional<dev.turboism.sdk.ui.appearance.PaletteEntry> deformerPaletteEntry() { return Optional.of(deformerEntry); }
            };
        }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public float getOpacity() { return 1.0F; }
        @Override public IntSequence masks() { return emptyInts(); }
        @Override public FloatSequence vertexPositions() { return emptyFloats(); }
        @Override public FloatSequence vertexUvs() { return emptyFloats(); }
        @Override public IntSequence indices() { return emptyInts(); }
        @Override public Color multiplyColor() { return new Color(1.0F, 1.0F, 1.0F, 1.0F); }
        @Override public Color screenColor() { return new Color(0.0F, 0.0F, 0.0F, 0.0F); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return emptyInts(); }
        @Override public String toString() { return "drawable:" + id; }
    }

    private static final class FakePaletteEntry implements dev.turboism.sdk.ui.appearance.PaletteEntry {
        private final List<String> events = new ArrayList<>();

        List<String> events() { return List.copyOf(events); }

        List<String> textEvents() {
            return events.stream().filter(e -> e.startsWith("text:")).toList();
        }

        List<String> backgroundEvents() {
            return events.stream().filter(e -> e.startsWith("background:")).toList();
        }

        @Override public Registration overrideTextColor(dev.turboism.sdk.ui.appearance.UiColor color) {
            return override("text", color);
        }

        @Override public Registration overrideBackgroundColor(dev.turboism.sdk.ui.appearance.UiColor color) {
            return override("background", color);
        }

        private Registration override(final String property, final dev.turboism.sdk.ui.appearance.UiColor color) {
            events.add(property + ":" + LabelStylePresets.toHex(color));
            return () -> events.add(property + ":closed");
        }

        @Override public Registration overrideFontSize(final float points) { throw new UnsupportedOperationException("not used"); }
        @Override public Registration overrideBold(final boolean bold) { throw new UnsupportedOperationException("not used"); }
        @Override public Registration overrideItalic(final boolean italic) { throw new UnsupportedOperationException("not used"); }
        @Override public dev.turboism.sdk.ui.appearance.PaletteEntryState resolved() {
            return dev.turboism.sdk.ui.appearance.PaletteEntryState.empty();
        }
        @Override public Optional<dev.turboism.sdk.ui.appearance.PaletteEntryState> actual() { return Optional.empty(); }
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(); }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(); }
        };
    }
}
