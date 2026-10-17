package dev.turboism.plugin.palettelabelstyle;

import static dev.turboism.plugin.palettelabelstyle.PaletteLabelStyleTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuEntry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
            "palette-label-style.text.custom", "palette-label-style.background.custom");

    @Test
    void pluginJsonDeclaresEveryPermissionTheCodeTouches() throws Exception {
        final String json;
        try (var in = PaletteLabelStylePluginTest.class.getResourceAsStream("/META-INF/turboism/plugin.json")) {
            json = new String(
                    java.util.Objects.requireNonNull(in, "plugin.json resource").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        for (final String permission : List.of(
                "turboism.action.register",
                "turboism.ui.context-menu.contribute",
                "turboism.ui.appearance.modify",
                "turboism.ui.dialog.contribute",
                "turboism.cubism.model.read",
                "turboism.cubism.model.write",
                "turboism.cubism.project.read",
                "turboism.file.read",
                "turboism.file.write")) {
            assertTrue(json.contains("\"" + permission + "\""), "plugin.json must declare permission " + permission);
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

        final List<ContextMenuContribution> contributions =
                context.contextMenu().contributions();
        assertEquals(5, contributions.size());
        assertContribution(
                contributions.get(0),
                Location.DEFORMER_TAB,
                Set.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
                "palette-label-style.text.none",
                "palette-label-style.text.custom");
        assertContribution(
                contributions.get(1),
                Location.DEFORMER_TAB,
                Set.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH),
                "palette-label-style.background.none",
                "palette-label-style.background.custom");
        assertContribution(
                contributions.get(2),
                Location.PART_TAB,
                Set.of(
                        ObjectKind.PART,
                        ObjectKind.PART_FOLDER,
                        ObjectKind.WARP_DEFORMER,
                        ObjectKind.ROTATION_DEFORMER,
                        ObjectKind.ART_MESH),
                "palette-label-style.text.none",
                "palette-label-style.text.custom");
        assertContribution(
                contributions.get(3),
                Location.PARAMETER_TAB,
                Set.of(ObjectKind.PARAMETER, ObjectKind.PARAMETER_FOLDER),
                "palette-label-style.text.none",
                "palette-label-style.text.custom");
        assertContribution(
                contributions.get(4),
                Location.PARAMETER_TAB,
                Set.of(ObjectKind.PARAMETER),
                "palette-label-style.background.none",
                "palette-label-style.background.custom");
    }

    private static void assertContribution(
            final ContextMenuRegistry.ContextMenuContribution contribution,
            final Location location,
            final Set<ObjectKind> kinds,
            final String firstActionId,
            final String lastActionId) {
        assertEquals(location, contribution.location());
        assertEquals(kinds, contribution.objectKinds());
        final ContextMenuEntry entry = contribution.entry();
        assertEquals(ContextMenuRegistry.EntryKind.SUBMENU, entry.kind());
        assertEquals(10, entry.children().size());
        assertEquals(ContextMenuRegistry.EntryKind.ITEM, entry.children().get(0).kind());
        assertEquals(firstActionId, entry.children().get(0).actionId());
        assertEquals(
                ContextMenuRegistry.EntryKind.SEPARATOR, entry.children().get(8).kind());
        assertEquals(lastActionId, entry.children().get(9).actionId());
    }

    @Test
    void textPresetActionOverridesAndPersistsForSelectedParameter() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1); // enable-time replay read

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));
        context.storage().awaitOperations(2); // persistence write

        assertEquals(
                List.of("text:#E53935"), context.model().parameter("p1").entry.textEvents());
        assertEquals(
                Map.of("PARAMETER_TAB:p1:text", "#E53935"),
                LabelStylePersistence.parse(context.storage()
                        .content(LabelStylePersistence.filePath("project-1").relativePath())));
    }

    @Test
    void backgroundPresetOnDeformerTabDeformerSyncsNativeOnlyWithoutPersistence() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions()
                .execute(
                        "palette-label-style.background.blue",
                        new ContextMenuSelection(
                                1L,
                                "doc",
                                Location.DEFORMER_TAB,
                                List.of(new ContextMenuSelection.Item(ObjectKind.WARP_DEFORMER, "warp1"))));

        assertEquals(
                List.of(new dev.turboism.sdk.ui.appearance.NativeLabelColor.Preset(
                        dev.turboism.sdk.ui.appearance.PresetColor.BLUE)),
                context.model().deformer("warp1").nativeLabelColors);
        assertEquals(List.of(), context.model().deformer("warp1").partEntry.events());
        assertEquals(List.of(), context.model().deformer("warp1").deformerEntry.events());
        assertTrue(LabelStylePersistence.parse(context.storage()
                        .content(LabelStylePersistence.filePath("project-1").relativePath()))
                .isEmpty());
    }

    @Test
    void deformerTabTextOnDeformerOverridesPartPaletteEntryAndPersists() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions()
                .execute(
                        "palette-label-style.text.red",
                        new ContextMenuSelection(
                                1L,
                                "doc",
                                Location.DEFORMER_TAB,
                                List.of(new ContextMenuSelection.Item(ObjectKind.WARP_DEFORMER, "warp1"))));
        context.storage().awaitOperations(2);

        assertEquals(
                List.of("text:#E53935"),
                context.model().deformer("warp1").partEntry.textEvents());
        assertTrue(context.model().deformer("warp1").nativeLabelColors.isEmpty());
        assertEquals(
                Map.of("DEFORMER_TAB:warp1:text", "#E53935"),
                LabelStylePersistence.parse(context.storage()
                        .content(LabelStylePersistence.filePath("project-1").relativePath())));
    }

    @Test
    void noneActionClearsPersistedEntryAndClosesOverride() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));
        context.storage().awaitOperations(2);
        context.actions().execute("palette-label-style.text.none", parameterSelection("p1"));
        context.storage().awaitOperations(3);

        assertEquals(
                List.of("text:#E53935", "text:closed"),
                context.model().parameter("p1").entry.events());
        assertTrue(LabelStylePersistence.parse(context.storage()
                        .content(LabelStylePersistence.filePath("project-1").relativePath()))
                .isEmpty());
    }

    @Test
    void partTabAndParameterFolderTextActionsDispatchBySelection() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.actions()
                .execute(
                        "palette-label-style.text.green",
                        new ContextMenuSelection(
                                1L,
                                "doc",
                                Location.PART_TAB,
                                List.of(new ContextMenuSelection.Item(ObjectKind.PART, "part1"))));
        context.actions()
                .execute(
                        "palette-label-style.text.yellow",
                        new ContextMenuSelection(
                                1L,
                                "doc",
                                Location.PARAMETER_TAB,
                                List.of(new ContextMenuSelection.Item(ObjectKind.PARAMETER_FOLDER, "folder1"))));

        assertEquals(
                List.of("text:#4CAF50"), context.model().part("part1").entry.events());
        assertEquals(
                List.of("text:#FDD835"), context.model().group("folder1").entry.events());
    }

    @Test
    void artMeshOnDeformerTabSupportsTextAndBackgroundOverrides() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        final ContextMenuSelection selection = new ContextMenuSelection(
                1L, "doc", Location.DEFORMER_TAB, List.of(new ContextMenuSelection.Item(ObjectKind.ART_MESH, "mesh1")));
        context.actions().execute("palette-label-style.text.purple", selection);
        context.actions().execute("palette-label-style.background.gray", selection);

        assertEquals(
                List.of("text:#9C27B0"),
                context.model().drawable("mesh1").partEntry.textEvents());
        assertEquals(List.of(), context.model().drawable("mesh1").partEntry.backgroundEvents());
        assertEquals(
                List.of(new dev.turboism.sdk.ui.appearance.NativeLabelColor.Preset(
                        dev.turboism.sdk.ui.appearance.PresetColor.GRAY)),
                context.model().drawable("mesh1").nativeLabelColors);
    }

    @Test
    void customActionOpensColorPickerAndAppliesAcceptedValue() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions().execute("palette-label-style.text.custom", parameterSelection("p1"));

        assertEquals(1, context.uiHost().colorPickers().size());
        assertEquals(
                "palette-label-style.custom-color",
                context.uiHost().colorPickers().get(0).id());
        assertEquals(null, context.uiHost().colorPickers().get(0).initial());

        context.uiHost().acceptColorPicker(0, "#123456");
        context.storage().awaitOperations(2);

        assertEquals(
                List.of("text:#123456"), context.model().parameter("p1").entry.textEvents());
        assertEquals(
                Map.of("PARAMETER_TAB:p1:text", "#123456"),
                LabelStylePersistence.parse(context.storage()
                        .content(LabelStylePersistence.filePath("project-1").relativePath())));
    }

    @Test
    void customPickerPrefillsPersistedColorAsInitialValue() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));
        context.storage().awaitOperations(2);
        context.actions().execute("palette-label-style.text.custom", parameterSelection("p1"));

        assertEquals("#E53935", context.uiHost().colorPickers().get(0).initial());
    }

    @ParameterizedTest(name = "custom color picker rejected with {0}")
    @ValueSource(strings = {"cancel", "not-a-color"})
    void customPickerCancelOrInvalidInputAppliesNothing(String input) {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        final String actionId =
                "cancel".equals(input) ? "palette-label-style.text.custom" : "palette-label-style.background.custom";
        context.actions().execute(actionId, parameterSelection("p1"));
        if ("cancel".equals(input)) {
            context.uiHost().cancelColorPicker(0);
        } else {
            context.uiHost().acceptColorPicker(0, input);
        }

        assertTrue(context.model().parameter("p1").entry.events().isEmpty());
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
    void enableReplaysStoredColorsForCurrentProject() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.storage()
                .seed(LabelStylePersistence.filePath("project-1").relativePath(), "PARAMETER_TAB:p1:text=#E53935\n");
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();

        context.storage().awaitOperations(1);

        assertEquals(
                List.of("text:#E53935"), context.model().parameter("p1").entry.textEvents());
    }

    @Test
    void onModelOpenedReplaysStoredColorsForCurrentProject() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.storage()
                .seed(LabelStylePersistence.filePath("project-1").relativePath(), "PARAMETER_TAB:p1:text=#E53935\n");
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        plugin.onModelOpened(null);
        context.storage().awaitOperations(2);

        assertEquals(
                List.of("text:#E53935", "text:closed", "text:#E53935"),
                context.model().parameter("p1").entry.events());
    }

    @Test
    void replayClosesPreviousOverridesBeforeApplyingStoredColors() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.storage()
                .seed(LabelStylePersistence.filePath("project-1").relativePath(), "PARAMETER_TAB:p1:text=#E53935\n");
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions().execute("palette-label-style.text.blue", parameterSelection("p1"));
        context.storage().awaitOperations(2);
        assertEquals(
                List.of("text:#E53935", "text:closed", "text:#2196F3"),
                context.model().parameter("p1").entry.events());

        plugin.onModelOpened(null);
        context.storage().awaitOperations(3);
        // The replay applies colors in the read's continuation, after the storage operation completes.
        final long deadline =
                System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (context.model().parameter("p1").entry.events().size() < 5 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        // Replay reads the persisted file, which now stores the blue override.
        assertEquals(
                List.of("text:#E53935", "text:closed", "text:#2196F3", "text:closed", "text:#2196F3"),
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
    void blankProjectIdUsesDefaultFile() {
        final RecordingPluginContext context = new RecordingPluginContext();
        context.cubism().noProject();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));
        context.storage().awaitOperations(2);

        assertEquals(
                Map.of("PARAMETER_TAB:p1:text", "#E53935"),
                LabelStylePersistence.parse(context.storage()
                        .content(LabelStylePersistence.filePath("default").relativePath())));
    }

    @Test
    void projectsAreIsolatedInStorage() {
        final RecordingPluginContext context = new RecordingPluginContext();
        final PaletteLabelStylePlugin plugin = new PaletteLabelStylePlugin();
        plugin.init(context);
        plugin.enable();
        context.storage().awaitOperations(1);

        context.actions().execute("palette-label-style.text.red", parameterSelection("p1"));
        context.storage().awaitOperations(2);

        assertTrue(context.storage()
                .content(LabelStylePersistence.filePath("project-1").relativePath())
                .contains("PARAMETER_TAB:p1:text=#E53935"));
        assertEquals(
                Optional.empty(),
                Optional.ofNullable(context.storage()
                        .content(LabelStylePersistence.filePath("other-project").relativePath())));
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
        return new ContextMenuSelection(
                1L,
                "document-1",
                Location.PARAMETER_TAB,
                List.of(new ContextMenuSelection.Item(ObjectKind.PARAMETER, id)));
    }
}
