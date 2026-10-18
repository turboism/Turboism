package dev.turboism.plugin.parameterlock;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.plugin.PluginServices;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;
import dev.turboism.sdk.ui.appearance.PaletteEntryState;
import dev.turboism.sdk.ui.appearance.PaletteRowAction;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.sdk.ui.resource.UiResourceService;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.PaletteToolbarContribution;
import dev.turboism.test.concurrent.Await;
import dev.turboism.test.storage.InMemoryPluginStorage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParameterLockPluginTest {

    private FakePluginContext context;
    private ParameterLockPlugin plugin;
    private FakeModel modelA;
    private FakeModel modelB;

    @BeforeEach
    void setUp() throws Exception {
        context = new FakePluginContext();
        modelA = new FakeModel("guid-model-a", List.of("PARAM_ANGLE_X", "PARAM_ANGLE_Y", "PARAM_EYE_L_OPEN"));
        modelB = new FakeModel("guid-model-b", List.of("PARAM_ANGLE_X", "PARAM_BODY_ANGLE_Z"));
        context.activeModel = modelA;

        plugin = new ParameterLockPlugin();
        plugin.init(context);
        plugin.enable();
        Await.condition(plugin::isFullyDecorated);
    }

    @AfterEach
    void tearDown() {
        if (plugin != null) {
            plugin.shutdown();
        }
    }

    @Test
    void pluginJsonDeclaresEveryPermissionTheCodeTouches() throws Exception {
        final String json;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/turboism/plugin.json")) {
            assertNotNull(in, "plugin.json resource must be present");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final List<String> requiredPermissions = List.of(
                "turboism.action.register",
                "turboism.ui.context-menu.contribute",
                "turboism.ui.appearance.modify",
                "turboism.cubism.model.read",
                "turboism.cubism.model.observe",
                "turboism.cubism.model.intercept",
                "turboism.file.read",
                "turboism.file.write");
        for (final String perm : requiredPermissions) {
            assertTrue(json.contains("\"" + perm + "\""), "plugin.json must declare permission: " + perm);
        }
        assertFalse(
                json.contains("\"turboism.ui.toolbar.palette.contribute\""),
                "plugin.json must not declare unused toolbar permission");
        assertTrue(json.contains("\"dev.turboism.plugin.parameter-lock\""));
        assertTrue(json.contains("\"dev.turboism.plugin.parameterlock.ParameterLockPlugin\""));
    }

    @Test
    void lockingBlocksMutationAndUnlockingAllows() {
        final FakeParameter param = modelA.findParam("PARAM_ANGLE_X");
        param.setValue(10.0f);

        // Before locking, beforeSetParameterValue passes through the new value
        assertEquals(20.0f, plugin.beforeSetParameterValue(param, 20.0f));

        // Lock the parameter
        plugin.lockState().setLocked(modelA.id().value(), "PARAM_ANGLE_X", true);
        assertTrue(plugin.isParameterLocked(modelA.id().value(), "PARAM_ANGLE_X"));

        // When locked, beforeSetParameterValue returns the existing value (10.0f), suppressing mutation
        assertEquals(10.0f, plugin.beforeSetParameterValue(param, 20.0f));
        assertEquals(10.0f, plugin.beforeSetParameterValue(param, -5.0f));

        // Unlock the parameter
        plugin.lockState().setLocked(modelA.id().value(), "PARAM_ANGLE_X", false);
        assertFalse(plugin.isParameterLocked(modelA.id().value(), "PARAM_ANGLE_X"));

        // When unlocked, beforeSetParameterValue passes through the new value again
        assertEquals(25.0f, plugin.beforeSetParameterValue(param, 25.0f));
    }

    @Test
    void crossModelIsolation() throws Exception {
        final FakeParameter paramA = modelA.findParam("PARAM_ANGLE_X");
        paramA.setValue(5.0f);
        final FakeParameter paramB = modelB.findParam("PARAM_ANGLE_X");
        paramB.setValue(50.0f);

        // Lock PARAM_ANGLE_X on Model A only
        plugin.lockState().setLocked(modelA.id().value(), "PARAM_ANGLE_X", true);

        // Model A is active: mutation blocked
        context.activeModel = modelA;
        assertEquals(5.0f, plugin.beforeSetParameterValue(paramA, 15.0f));

        // Switch to Model B: PARAM_ANGLE_X on Model B is NOT locked, mutation allowed!
        context.activeModel = modelB;
        plugin.onModelOpened(null);
        Await.condition(() -> modelB.findParam("PARAM_BODY_ANGLE_Z").paletteEntry.currentAction != null);
        assertEquals(75.0f, plugin.beforeSetParameterValue(paramB, 75.0f));

        // Verify Model A lock did not leak to Model B
        assertFalse(plugin.isParameterLocked(modelB.id().value(), "PARAM_ANGLE_X"));
        assertTrue(plugin.isParameterLocked(modelA.id().value(), "PARAM_ANGLE_X"));
    }

    @Test
    void disableClearsActiveEnforcementWhilePreservingStorage() throws Exception {
        final FakeParameter param = modelA.findParam("PARAM_ANGLE_X");
        param.setValue(12.0f);

        // Lock and save
        plugin.lockState().setLocked(modelA.id().value(), "PARAM_ANGLE_X", true);
        final ParameterLockData data = ParameterLockData.of(modelA.id().value(), Set.of("PARAM_ANGLE_X"));
        plugin.storage().save(data).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Mutation blocked while enabled
        assertEquals(12.0f, plugin.beforeSetParameterValue(param, 99.0f));

        // Disable plugin
        plugin.disable();

        // While disabled, enforcement is cleared, mutation is allowed
        assertEquals(99.0f, plugin.beforeSetParameterValue(param, 99.0f));

        // Verify file in storage is preserved!
        final StoragePath storagePath =
                ParameterLockStorage.storagePathFor(modelA.id().value());
        assertTrue(context.storage.content(storagePath).isPresent());

        // Re-enable plugin
        plugin.enable();

        // Wait for preload/storage load
        Await.condition(() -> plugin.isParameterLocked(modelA.id().value(), "PARAM_ANGLE_X"));

        // Active lock restored from storage, mutation blocked again!
        assertEquals(12.0f, plugin.beforeSetParameterValue(param, 88.0f));
    }

    @Test
    void crossSessionRestoreInNewPluginInstance() throws Exception {
        // Plugin 1 locks parameters and saves
        final String guid = modelA.id().value();
        final ParameterLockData data = ParameterLockData.of(guid, Set.of("PARAM_ANGLE_X", "PARAM_ANGLE_Y"));
        plugin.storage().save(data).toCompletableFuture().get(5, TimeUnit.SECONDS);
        plugin.shutdown();

        // Plugin 2 starts with same storage
        final ParameterLockPlugin plugin2 = new ParameterLockPlugin();
        try {
            plugin2.init(context);
            plugin2.enable();

            // Wait for async load
            Await.condition(() -> plugin2.isParameterLocked(guid, "PARAM_ANGLE_X"));

            assertTrue(plugin2.isParameterLocked(guid, "PARAM_ANGLE_X"));
            assertTrue(plugin2.isParameterLocked(guid, "PARAM_ANGLE_Y"));
            assertFalse(plugin2.isParameterLocked(guid, "PARAM_EYE_L_OPEN"));

            final FakeParameter paramX = modelA.findParam("PARAM_ANGLE_X");
            paramX.setValue(0.0f);
            assertEquals(0.0f, plugin2.beforeSetParameterValue(paramX, 1.0f));
        } finally {
            plugin2.shutdown();
        }
    }

    @Test
    void unknownOrDeletedParametersSafelyIgnoredOnLoad() throws Exception {
        final String guid = modelA.id().value();
        // Storage contains PARAM_ANGLE_X (valid) and PARAM_NONEXISTENT (deleted/unknown)
        final ParameterLockData data = ParameterLockData.of(guid, Set.of("PARAM_ANGLE_X", "PARAM_NONEXISTENT"));
        plugin.storage().save(data).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Re-enable to trigger filtered preload
        plugin.disable();
        plugin.enable();
        Await.condition(() -> plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));

        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        // PARAM_NONEXISTENT was filtered out and safely ignored
        assertFalse(plugin.isParameterLocked(guid, "PARAM_NONEXISTENT"));
    }

    @Test
    void contextMenuAndRowRegistrations() {
        final FakeContextMenuRegistry contextMenuRegistry = context.contextMenuRegistry;
        assertEquals(3, contextMenuRegistry.contributions.size());

        assertTrue(contextMenuRegistry.contributions.stream()
                .anyMatch(c -> c.id().equals(ParameterLockPlugin.CONTEXT_MENU_LOCK_ID)
                        && c.location() == Location.PARAMETER_TAB
                        && c.objectKinds().contains(ObjectKind.PARAMETER)));
        assertTrue(contextMenuRegistry.contributions.stream()
                .anyMatch(c -> c.id().equals(ParameterLockPlugin.CONTEXT_MENU_UNLOCK_ID)
                        && c.location() == Location.PARAMETER_TAB));
        assertTrue(contextMenuRegistry.contributions.stream()
                .anyMatch(c -> c.id().equals(ParameterLockPlugin.CONTEXT_MENU_TOGGLE_ID)
                        && c.location() == Location.PARAMETER_TAB));

        // Toolbar contribution removed per user specification
        final FakePaletteToolbarRegistry toolbarRegistry = context.paletteToolbarRegistry;
        assertEquals(0, toolbarRegistry.contributions.size());

        // Parameter rows have registered leading actions
        assertFalse(plugin.activeRowRegistrations().isEmpty());
    }

    @Test
    void contextMenuActionsLockUnlockAndToggleExecution() {
        final String guid = modelA.id().value();
        final ContextMenuSelection selection = new ContextMenuSelection(
                1L,
                "doc-1",
                Location.PARAMETER_TAB,
                List.of(
                        new ContextMenuSelection.Item(ObjectKind.PARAMETER, "PARAM_ANGLE_X"),
                        new ContextMenuSelection.Item(ObjectKind.PARAMETER, "PARAM_ANGLE_Y")));

        final FakeActionContext actionContext = new FakeActionContext(selection);

        // Invoke lock action
        final ActionRegistry.Action lockAction =
                context.actionRegistry.actions.get(ParameterLockPlugin.ACTION_LOCK_SELECTED);
        assertNotNull(lockAction);
        lockAction.handler().accept(actionContext);

        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_Y"));

        // Invoke unlock action
        final ActionRegistry.Action unlockAction =
                context.actionRegistry.actions.get(ParameterLockPlugin.ACTION_UNLOCK_SELECTED);
        assertNotNull(unlockAction);
        unlockAction.handler().accept(actionContext);

        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_Y"));

        // Invoke toggle action (was unlocked -> will lock)
        final ActionRegistry.Action toggleAction =
                context.actionRegistry.actions.get(ParameterLockPlugin.ACTION_TOGGLE_SELECTED);
        assertNotNull(toggleAction);
        toggleAction.handler().accept(actionContext);

        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_Y"));

        // Toggle again (was locked -> will unlock)
        toggleAction.handler().accept(actionContext);
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_Y"));
    }

    @Test
    void actionInvocationUsesActiveParameterWhenOutsideContextMenu() {
        final String guid = modelA.id().value();
        context.selectionSnapshot =
                new SelectionSnapshot(List.of(), Optional.of("PARAM_EYE_L_OPEN"), Optional.empty(), Optional.empty());

        // Context with empty contextMenuSelection (as when triggered by shortcut key)
        final FakeActionContext actionContext = new FakeActionContext(null);
        final ActionRegistry.Action toggleAction =
                context.actionRegistry.actions.get(ParameterLockPlugin.ACTION_TOGGLE_SELECTED);
        assertNotNull(toggleAction);

        assertFalse(plugin.isParameterLocked(guid, "PARAM_EYE_L_OPEN"));
        toggleAction.handler().accept(actionContext);
        assertTrue(plugin.isParameterLocked(guid, "PARAM_EYE_L_OPEN"));
    }

    @Test
    void temporaryProtectionBeforeLoadCompletes() {
        final FakeModel modelC = new FakeModel("guid-model-c", List.of("PARAM_C"));
        final FakeParameter paramC = modelC.findParam("PARAM_C");
        paramC.setValue(3.14f);

        context.activeModel = modelC;
        // Notice: we do NOT sleep or wait for load here!
        // While load is not completed, beforeSetParameterValue must temporarily return getValue() (3.14f)
        assertEquals(3.14f, plugin.beforeSetParameterValue(paramC, 9.99f));
    }

    @Test
    void diskHasBUserLocksABeforeLoadFinishesThenSavesBothAB() throws Exception {
        final String guid = "model-merge-test";
        final FakeModel modelMerge = new FakeModel(guid, List.of("PARAM_A", "PARAM_B"));
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);

        // Pre-populate disk storage with PARAM_B
        final ParameterLockData diskData = ParameterLockData.of(guid, Set.of("PARAM_B"));
        context.storage.seed(path, Json.stringify(diskData.toMap()));

        // User switches to this model
        context.activeModel = modelMerge;
        // User locks PARAM_A immediately before load finishes
        plugin.lockState().setLocked(guid, "PARAM_A", true);

        // Trigger load
        plugin.onModelOpened(null);
        Await.condition(() -> plugin.isParameterLocked(guid, "PARAM_B"));

        // Now trigger save
        final ParameterLockData merged =
                ParameterLockData.of(guid, plugin.lockState().getLockedParameters(guid));
        plugin.storage().save(merged).toCompletableFuture().get(5, TimeUnit.SECONDS);

        // Verify in-memory state has BOTH PARAM_A and PARAM_B!
        assertTrue(plugin.isParameterLocked(guid, "PARAM_A"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_B"));

        // Verify disk storage has BOTH PARAM_A and PARAM_B!
        final ParameterLockData savedDisk =
                plugin.storage().load(guid).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Set.of("PARAM_A", "PARAM_B"), savedDisk.lockedParameterIds());
    }

    @Test
    void userUnlocksBBeforeLoadFinishesTombstonePreserved() throws Exception {
        final String guid = "model-tombstone-test";
        final FakeModel modelTomb = new FakeModel(guid, List.of("PARAM_B", "PARAM_C"));
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);

        // Pre-populate disk storage with PARAM_B and PARAM_C
        final ParameterLockData diskData = ParameterLockData.of(guid, Set.of("PARAM_B", "PARAM_C"));
        context.storage.seed(path, Json.stringify(diskData.toMap()));

        context.activeModel = modelTomb;
        // User explicitly triggers the unlock action for PARAM_B before load completes
        final ContextMenuSelection selection = new ContextMenuSelection(
                1L,
                "doc-tomb",
                Location.PARAMETER_TAB,
                List.of(new ContextMenuSelection.Item(ObjectKind.PARAMETER, "PARAM_B")));
        final ActionRegistry.Action unlockAction =
                context.actionRegistry.actions.get(ParameterLockPlugin.ACTION_UNLOCK_SELECTED);
        assertNotNull(unlockAction);
        unlockAction.handler().accept(new FakeActionContext(selection));

        // Now load finishes
        plugin.onModelOpened(null);
        Await.condition(() -> plugin.isParameterLocked(guid, "PARAM_C"));

        // PARAM_B must NOT be re-locked (tombstone preserved)! PARAM_C must be locked!
        assertFalse(plugin.isParameterLocked(guid, "PARAM_B"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_C"));
    }

    @Test
    void nativeLockIconAvailabilityReportsAvailable() {
        final UiIconAvailability availability = plugin.lockIconAvailability();
        assertEquals(UiIconAvailability.AVAILABLE, availability);
    }

    @Test
    void lockAcceptedBeforeDisableFinishesSavingBeforeReenableLoads() throws Exception {
        final String guid = modelB.id().value();
        context.activeModel = modelB;
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);
        context.storage.seed(
                path,
                Json.stringify(
                        ParameterLockData.of(guid, Set.of("PARAM_ANGLE_X")).toMap()));
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        context.storage.setReadGate(gate);
        context.storage.setReadStarted(new java.util.concurrent.CountDownLatch(1));
        invokeSelected(ParameterLockPlugin.ACTION_LOCK_SELECTED, "PARAM_BODY_ANGLE_Z");
        assertTrue(context.storage.readStarted().await(2, TimeUnit.SECONDS));
        plugin.disable();
        plugin.enable();
        gate.complete(null);
        awaitCondition(() -> plugin.lockState().hasLoaded(guid));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_BODY_ANGLE_Z"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertEquals(
                Set.of("PARAM_BODY_ANGLE_Z", "PARAM_ANGLE_X"),
                plugin.storage()
                        .load(guid)
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS)
                        .lockedParameterIds());
    }

    @Test
    void unlockActionBeforeInitialLoadPersistsRemovalAndKeepsOtherLocks() throws Exception {
        final String guid = modelB.id().value();
        context.activeModel = modelB;
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);
        context.storage.seed(
                path,
                Json.stringify(ParameterLockData.of(guid, Set.of("PARAM_ANGLE_X", "PARAM_BODY_ANGLE_Z"))
                        .toMap()));
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        context.storage.setReadGate(gate);
        context.storage.setReadStarted(new java.util.concurrent.CountDownLatch(1));
        invokeSelected(ParameterLockPlugin.ACTION_UNLOCK_SELECTED, "PARAM_BODY_ANGLE_Z");
        assertTrue(context.storage.readStarted().await(2, TimeUnit.SECONDS));
        gate.complete(null);
        awaitCondition(() -> {
            final String json = context.storage.content(path).orElse(null);
            return json != null
                    && ParameterLockData.fromMap(Json.parseObject(json))
                            .lockedParameterIds()
                            .equals(Set.of("PARAM_ANGLE_X"));
        });
        assertFalse(plugin.isParameterLocked(guid, "PARAM_BODY_ANGLE_Z"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
    }

    @Test
    void immediateShutdownDrainsAcceptedActionPersistence() throws Exception {
        final String guid = modelB.id().value();
        context.activeModel = modelB;
        invokeSelected(ParameterLockPlugin.ACTION_LOCK_SELECTED, "PARAM_BODY_ANGLE_Z");
        plugin.shutdown();
        final String json = context.storage
                .content(ParameterLockStorage.storagePathFor(guid))
                .orElse(null);
        assertNotNull(json);
        assertEquals(
                Set.of("PARAM_BODY_ANGLE_Z"),
                ParameterLockData.fromMap(Json.parseObject(json)).lockedParameterIds());
    }

    @Test
    void toggleDuringInitialLoadUsesPersistedLockState() throws Exception {
        final String guid = modelB.id().value();
        context.activeModel = modelB;
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);
        context.storage.seed(
                path,
                Json.stringify(
                        ParameterLockData.of(guid, Set.of("PARAM_ANGLE_X")).toMap()));
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        context.storage.setReadGate(gate);
        context.storage.setReadStarted(new java.util.concurrent.CountDownLatch(1));
        invokeSelected(ParameterLockPlugin.ACTION_TOGGLE_SELECTED, "PARAM_ANGLE_X");
        assertTrue(context.storage.readStarted().await(2, TimeUnit.SECONDS));
        gate.complete(null);
        awaitCondition(() -> {
            final String json = context.storage.content(path).orElse(null);
            return json != null
                    && ParameterLockData.fromMap(Json.parseObject(json))
                            .lockedParameterIds()
                            .isEmpty();
        });
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
    }

    // ── Meaningful New Tests ──

    @Test
    void parameterRowClickOnlyAffectsItselfAndIgnoresGlobalSelection() {
        final String guid = modelA.id().value();
        final FakeParameter paramX = modelA.findParam("PARAM_ANGLE_X");
        final FakeParameter paramY = modelA.findParam("PARAM_ANGLE_Y");

        // Set global selection pointing to PARAM_ANGLE_Y
        context.selectionSnapshot = new SelectionSnapshot(
                List.of("PARAM_ANGLE_Y"), Optional.of("PARAM_ANGLE_Y"), Optional.empty(), Optional.empty());

        assertNotNull(paramX.paletteEntry.currentAction);
        assertEquals(PaletteRowAction.State.UNLOCKED, paramX.paletteEntry.currentAction.state());
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_Y"));

        // Click PARAM_ANGLE_X row action
        paramX.paletteEntry.currentAction.onClick().run();

        // Exactly PARAM_ANGLE_X is locked; PARAM_ANGLE_Y remains untouched
        assertTrue(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_Y"));
        assertEquals(PaletteRowAction.State.LOCKED, paramX.paletteEntry.currentAction.state());
        assertTrue(paramX.paletteEntry.currentAction.editingDisabled());
        assertEquals(PaletteRowAction.State.UNLOCKED, paramY.paletteEntry.currentAction.state());
        assertFalse(paramY.paletteEntry.currentAction.editingDisabled());

        // Click PARAM_ANGLE_X row action again to unlock
        paramX.paletteEntry.currentAction.onClick().run();
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ANGLE_X"));
        assertEquals(PaletteRowAction.State.UNLOCKED, paramX.paletteEntry.currentAction.state());
    }

    @Test
    void groupRecursiveAllChildrenAndMixedTransitions() {
        final String guid = "guid-hierarchy-model";
        final FakeParameterGroup childGroup =
                new FakeParameterGroup("group_child", List.of("PARAM_CHILD_1", "PARAM_CHILD_2"), List.of());
        final FakeParameterGroup rootGroup =
                new FakeParameterGroup("group_root", List.of("PARAM_ROOT_1"), List.of("group_child"));

        final FakeModel hierarchyModel = new FakeModel(
                guid, List.of("PARAM_ROOT_1", "PARAM_CHILD_1", "PARAM_CHILD_2"), List.of(rootGroup, childGroup));

        context.activeModel = hierarchyModel;
        plugin.refreshDecorations(hierarchyModel);

        final FakeParameter paramRoot = hierarchyModel.findParam("PARAM_ROOT_1");
        final FakeParameter paramC1 = hierarchyModel.findParam("PARAM_CHILD_1");
        final FakeParameter paramC2 = hierarchyModel.findParam("PARAM_CHILD_2");

        // Initial state: all UNLOCKED
        assertEquals(PaletteRowAction.State.UNLOCKED, rootGroup.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.UNLOCKED, childGroup.paletteEntry.currentAction.state());

        // 1. Lock a single child parameter directly
        paramC1.paletteEntry.currentAction.onClick().run();
        assertTrue(plugin.isParameterLocked(guid, "PARAM_CHILD_1"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_CHILD_2"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ROOT_1"));

        // Both childGroup and rootGroup transition to MIXED
        assertEquals(PaletteRowAction.State.MIXED, childGroup.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.MIXED, rootGroup.paletteEntry.currentAction.state());

        // 2. Click childGroup row action while MIXED -> locks all in childGroup
        childGroup.paletteEntry.currentAction.onClick().run();
        assertTrue(plugin.isParameterLocked(guid, "PARAM_CHILD_1"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_CHILD_2"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ROOT_1"));

        // childGroup is fully LOCKED; rootGroup is still MIXED (2 of 3 locked)
        assertEquals(PaletteRowAction.State.LOCKED, childGroup.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.MIXED, rootGroup.paletteEntry.currentAction.state());

        // 3. Click rootGroup row action while MIXED -> locks ALL descendants
        rootGroup.paletteEntry.currentAction.onClick().run();
        assertTrue(plugin.isParameterLocked(guid, "PARAM_ROOT_1"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_CHILD_1"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_CHILD_2"));

        assertEquals(PaletteRowAction.State.LOCKED, rootGroup.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.LOCKED, childGroup.paletteEntry.currentAction.state());

        // 4. Click rootGroup row action while fully LOCKED -> unlocks ALL descendants
        rootGroup.paletteEntry.currentAction.onClick().run();
        assertFalse(plugin.isParameterLocked(guid, "PARAM_ROOT_1"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_CHILD_1"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_CHILD_2"));

        assertEquals(PaletteRowAction.State.UNLOCKED, rootGroup.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.UNLOCKED, childGroup.paletteEntry.currentAction.state());
    }

    @Test
    void groupCyclePreventionDoesNotInfiniteLoop() {
        final String guid = "guid-cycle-model";
        // Group A points to Group B; Group B points back to Group A (cycle!)
        final FakeParameterGroup groupA = new FakeParameterGroup("group_a", List.of("PARAM_A"), List.of("group_b"));
        final FakeParameterGroup groupB = new FakeParameterGroup("group_b", List.of("PARAM_B"), List.of("group_a"));

        final FakeModel cycleModel = new FakeModel(guid, List.of("PARAM_A", "PARAM_B"), List.of(groupA, groupB));

        // Descendant collection must safely terminate and collect both without StackOverflowError
        final Set<String> descendantsA =
                ParameterLockPlugin.collectDescendantParameterIds(cycleModel, new ParameterGroupId("group_a"));
        assertEquals(Set.of("PARAM_A", "PARAM_B"), descendantsA);

        context.activeModel = cycleModel;
        plugin.refreshDecorations(cycleModel);

        // Click Group A row action -> locks both
        groupA.paletteEntry.currentAction.onClick().run();
        assertTrue(plugin.isParameterLocked(guid, "PARAM_A"));
        assertTrue(plugin.isParameterLocked(guid, "PARAM_B"));
        assertEquals(PaletteRowAction.State.LOCKED, groupA.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.LOCKED, groupB.paletteEntry.currentAction.state());
    }

    @Test
    void emptyGroupDoesNotFalselyLock() {
        final String guid = "guid-empty-group-model";
        final FakeParameterGroup emptyGroup = new FakeParameterGroup("group_empty", List.of(), List.of());
        final FakeModel emptyGroupModel = new FakeModel(guid, List.of(), List.of(emptyGroup));

        context.activeModel = emptyGroupModel;
        plugin.refreshDecorations(emptyGroupModel);

        assertNotNull(emptyGroup.paletteEntry.currentAction);
        assertEquals(PaletteRowAction.State.UNLOCKED, emptyGroup.paletteEntry.currentAction.state());
        assertEquals("row.action.empty_group", emptyGroup.paletteEntry.currentAction.tooltip());

        // Click empty group row action
        emptyGroup.paletteEntry.currentAction.onClick().run();

        // Nothing locked, state remains UNLOCKED
        assertTrue(plugin.lockState().getLockedParameters(guid).isEmpty());
        assertEquals(PaletteRowAction.State.UNLOCKED, emptyGroup.paletteEntry.currentAction.state());
    }

    @Test
    void childParameterChangeUpdatesParentGroupState() {
        final String guid = "guid-parent-update-model";
        final FakeParameterGroup group = new FakeParameterGroup("group_p", List.of("PARAM_1", "PARAM_2"), List.of());
        final FakeModel model = new FakeModel(guid, List.of("PARAM_1", "PARAM_2"), List.of(group));

        context.activeModel = model;
        plugin.refreshDecorations(model);

        final FakeParameter p1 = model.findParam("PARAM_1");
        final FakeParameter p2 = model.findParam("PARAM_2");

        assertEquals(PaletteRowAction.State.UNLOCKED, group.paletteEntry.currentAction.state());

        // Toggle P1 -> group becomes MIXED
        p1.paletteEntry.currentAction.onClick().run();
        assertEquals(PaletteRowAction.State.MIXED, group.paletteEntry.currentAction.state());

        // Toggle P2 -> group becomes LOCKED
        p2.paletteEntry.currentAction.onClick().run();
        assertEquals(PaletteRowAction.State.LOCKED, group.paletteEntry.currentAction.state());

        // Toggle P1 back -> group becomes MIXED
        p1.paletteEntry.currentAction.onClick().run();
        assertEquals(PaletteRowAction.State.MIXED, group.paletteEntry.currentAction.state());

        // Toggle P2 back -> group becomes UNLOCKED
        p2.paletteEntry.currentAction.onClick().run();
        assertEquals(PaletteRowAction.State.UNLOCKED, group.paletteEntry.currentAction.state());
    }

    @Test
    void persistenceRestoresRowActionStates() throws Exception {
        final String guid = "guid-persist-row-model";
        final FakeParameterGroup group =
                new FakeParameterGroup("group_persisted", List.of("PARAM_P1", "PARAM_P2"), List.of());
        final FakeModel persistModel = new FakeModel(guid, List.of("PARAM_P1", "PARAM_P2"), List.of(group));

        // Pre-populate storage with PARAM_P1 locked
        final ParameterLockData diskData = ParameterLockData.of(guid, Set.of("PARAM_P1"));
        final StoragePath storagePath = ParameterLockStorage.storagePathFor(guid);
        context.storage.seed(storagePath, Json.stringify(diskData.toMap()));

        // Switch to this model and reload
        context.activeModel = persistModel;
        plugin.onModelOpened(null);

        final FakeParameter p1 = persistModel.findParam("PARAM_P1");
        final FakeParameter p2 = persistModel.findParam("PARAM_P2");

        Await.condition(() -> plugin.isParameterLocked(guid, "PARAM_P1") && p1.paletteEntry.currentAction != null);

        assertTrue(plugin.isParameterLocked(guid, "PARAM_P1"));
        assertFalse(plugin.isParameterLocked(guid, "PARAM_P2"));

        assertNotNull(p1.paletteEntry.currentAction);
        assertNotNull(p2.paletteEntry.currentAction);
        assertNotNull(group.paletteEntry.currentAction);

        assertEquals(PaletteRowAction.State.LOCKED, p1.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.UNLOCKED, p2.paletteEntry.currentAction.state());
        assertEquals(PaletteRowAction.State.MIXED, group.paletteEntry.currentAction.state());
    }

    @Test
    void disableClearsRowDecorations() throws Exception {
        assertFalse(plugin.activeRowRegistrations().isEmpty());
        final FakeParameter paramX = modelA.findParam("PARAM_ANGLE_X");
        assertNotNull(paramX.paletteEntry.currentAction);
        assertFalse(paramX.paletteEntry.closed);

        // Disable plugin
        plugin.disable();

        // Active registrations cleared and entries closed
        assertTrue(plugin.activeRowRegistrations().isEmpty());
        assertNull(paramX.paletteEntry.currentAction);
        assertTrue(paramX.paletteEntry.closed);

        // Re-enable restores decorations
        plugin.enable();
        Await.condition(() -> paramX.paletteEntry.currentAction != null);
        assertNotNull(paramX.paletteEntry.currentAction);
        assertFalse(paramX.paletteEntry.closed);
    }

    @Test
    void modelTabSwitchingUpdatesRowDecorations() throws Exception {
        // Initially Model A is active
        assertTrue(plugin.isFullyDecorated());
        assertNotNull(modelA.findParam("PARAM_ANGLE_X").paletteEntry.currentAction);

        // Switch tab to Model B
        context.activeModel = modelB;
        plugin.checkActivation();
        Await.condition(() -> modelB.findParam("PARAM_BODY_ANGLE_Z").paletteEntry.currentAction != null);

        // Model A's old decorations closed
        assertNull(modelA.findParam("PARAM_ANGLE_X").paletteEntry.currentAction);
        // Model B is now decorated
        assertNotNull(modelB.findParam("PARAM_BODY_ANGLE_Z").paletteEntry.currentAction);

        // Switch back to Model A
        context.activeModel = modelA;
        plugin.checkActivation();
        Await.condition(() -> modelA.findParam("PARAM_ANGLE_X").paletteEntry.currentAction != null);

        assertNull(modelB.findParam("PARAM_BODY_ANGLE_Z").paletteEntry.currentAction);
        assertNotNull(modelA.findParam("PARAM_ANGLE_X").paletteEntry.currentAction);
    }

    @Test
    void clonedGuidDifferentDocumentTriggersReactivation() throws Exception {
        final String sharedGuid = "guid-shared-copy";
        final FakeModel copy1 = new FakeModel(sharedGuid, List.of("PARAM_X"));
        final FakeModel copy2 = new FakeModel(sharedGuid, List.of("PARAM_X"));

        context.activeDocumentSnapshot = new DocumentSnapshot(
                "doc-tab-1",
                "ModelCopy1",
                "model1.cmo3",
                Optional.empty(),
                Optional.empty(),
                dev.turboism.sdk.cubism.DocumentKind.MODEL,
                Optional.of("content-1"),
                Optional.empty());
        context.activeModel = copy1;
        plugin.checkActivation();
        Await.condition(() -> copy1.findParam("PARAM_X").paletteEntry.currentAction != null);

        assertNotNull(copy1.findParam("PARAM_X").paletteEntry.currentAction);
        final var key1 = plugin.currentActivationKey();
        assertNotNull(key1);
        assertEquals("doc-tab-1", key1.documentId());

        // Now switch to copy 2 in a different tab/document with the same model GUID
        context.activeDocumentSnapshot = new DocumentSnapshot(
                "doc-tab-2",
                "ModelCopy2",
                "model2.cmo3",
                Optional.empty(),
                Optional.empty(),
                dev.turboism.sdk.cubism.DocumentKind.MODEL,
                Optional.of("content-2"),
                Optional.empty());
        context.activeModel = copy2;
        plugin.checkActivation();
        Await.condition(() -> copy2.findParam("PARAM_X").paletteEntry.currentAction != null);

        assertNull(copy1.findParam("PARAM_X").paletteEntry.currentAction);
        assertNotNull(copy2.findParam("PARAM_X").paletteEntry.currentAction);
        final var key2 = plugin.currentActivationKey();
        assertNotNull(key2);
        assertEquals("doc-tab-2", key2.documentId());
        assertNotEquals(key1, key2);
    }

    @Test
    void viewScopeNotReadyInitialRetrySucceeds() throws Exception {
        final FakeModel newModel = new FakeModel("guid-scope-retry", List.of("PARAM_S1"));
        final FakeParameter p = newModel.findParam("PARAM_S1");
        p.viewScopeReady = false; // Initially viewscope not ready!

        context.activeModel = newModel;
        plugin.checkActivation();
        awaitCondition(() -> plugin.lockState().hasLoaded("guid-scope-retry"));

        // Not fully decorated because viewscope wasn't ready
        assertFalse(plugin.isFullyDecorated());
        assertNull(p.paletteEntry.currentAction);

        // Now viewscope becomes ready
        p.viewScopeReady = true;
        // Next activation check tick (retry!)
        plugin.checkActivationOnce();

        assertTrue(plugin.isFullyDecorated());
        assertNotNull(p.paletteEntry.currentAction);
        assertEquals(PaletteRowAction.State.UNLOCKED, p.paletteEntry.currentAction.state());
    }

    @Test
    void activeModelInstantiatingNewWrapperEachReadRemainsKeyStable() throws Exception {
        // Simulates DynamicCubismModelAccess / EditorBackedCubismModelAccess returning a new wrapper on every active()
        // call
        final FakeModel underlying = new FakeModel("guid-dynamic-wrapper", List.of("PARAM_DW_1"));
        context.modelAccessOverride = () -> new FakeModelWrapper(underlying);

        plugin.checkActivationOnce();
        awaitCondition(() -> plugin.lockState().hasLoaded("guid-dynamic-wrapper"));
        awaitCondition(() -> plugin.isFullyDecorated());
        final var key1 = plugin.currentActivationKey();
        assertNotNull(key1);

        final int registrationCount = plugin.activeRowRegistrations().size();
        final Registration firstReg = plugin.activeRowRegistrations().get("param:PARAM_DW_1");
        assertNotNull(firstReg);

        // Next call: active() returns a completely different wrapper object instance!
        plugin.checkActivationOnce();
        final var key2 = plugin.currentActivationKey();

        // Key is strictly stable across wrapper instances
        assertEquals(key1, key2);
        // Registration is NOT rebuilt or replaced
        assertSame(firstReg, plugin.activeRowRegistrations().get("param:PARAM_DW_1"));
        assertEquals(registrationCount, plugin.activeRowRegistrations().size());

        // Now switch document ID: this MUST rebuild
        context.activeDocumentSnapshot = new DocumentSnapshot(
                "doc-new",
                "DocNew",
                "new.cmo3",
                Optional.empty(),
                Optional.empty(),
                dev.turboism.sdk.cubism.DocumentKind.MODEL,
                Optional.of("content-new"),
                Optional.empty());

        plugin.checkActivationOnce();
        awaitCondition(() -> "doc-new".equals(plugin.currentActivationKey().documentId()));
        final var key3 = plugin.currentActivationKey();
        assertNotEquals(key1, key3);
        assertEquals("doc-new", key3.documentId());
    }

    @Test
    void disableProtectsAgainstQueuedScheduledPollTasks() {
        // Plugin is enabled, poll is scheduled
        assertFalse(context.scheduledWork.isEmpty());

        // Disable plugin
        plugin.disable();
        assertTrue(plugin.activeRowRegistrations().isEmpty());

        // Run any previously queued runnable that was sitting in the scheduler
        for (final Runnable r : new ArrayList<>(context.scheduledWork)) {
            r.run();
        }

        // Epoch check prevented running: no row decorations were registered and no new work queued
        assertTrue(plugin.activeRowRegistrations().isEmpty());
    }

    @Test
    void missingDocumentDefersActivationUntilDocumentIsReady() throws Exception {
        final FakeModel newModel = new FakeModel("guid-doc-missing", List.of("PARAM_M1"));
        context.activeModel = newModel;
        context.simulateDocumentMissing = true; // Document not ready yet!

        plugin.checkActivationOnce();

        // Must not consider ready or fully decorated
        assertFalse(plugin.isFullyDecorated());
        assertNull(plugin.currentActivationKey());
        assertNull(newModel.findParam("PARAM_M1").paletteEntry.currentAction);

        // Now document becomes ready
        context.simulateDocumentMissing = false;
        plugin.checkActivationOnce();
        awaitCondition(() -> plugin.isFullyDecorated());

        // Now decorated properly
        assertTrue(plugin.isFullyDecorated());
        assertNotNull(plugin.currentActivationKey());
        assertEquals("guid-doc-missing", plugin.currentActivationKey().modelGuid());
        assertNotNull(newModel.findParam("PARAM_M1").paletteEntry.currentAction);
    }

    private void invokeSelected(final String action, final String parameter) {
        final ContextMenuSelection selection = new ContextMenuSelection(
                1L,
                "doc-1",
                Location.PARAMETER_TAB,
                List.of(new ContextMenuSelection.Item(ObjectKind.PARAMETER, parameter)));
        context.actionRegistry.actions.get(action).handler().accept(new FakeActionContext(selection));
    }

    private static void awaitCondition(final java.util.function.BooleanSupplier condition) {
        Await.condition(condition);
    }

    static final class FakePluginContext implements PluginContext {
        final InMemoryPluginStorage storage = new InMemoryPluginStorage();
        final FakeActionRegistry actionRegistry = new FakeActionRegistry();
        final FakeContextMenuRegistry contextMenuRegistry = new FakeContextMenuRegistry();
        final FakePaletteToolbarRegistry paletteToolbarRegistry = new FakePaletteToolbarRegistry();
        final FakeUiResourceService uiResourceService = new FakeUiResourceService();
        final DisposableScope disposableScope = new DisposableScope();
        final PluginLocalization localization = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ROOT;
            }

            @Override
            public String text(final String key) {
                return key;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return key;
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };
        final PluginLogger logger = new NoopLogger();
        DocumentSnapshot activeDocumentSnapshot;
        boolean simulateDocumentMissing = false;
        java.util.function.Supplier<CubismModel> modelAccessOverride;
        final List<Runnable> scheduledWork = new ArrayList<>();
        final UiScheduler uiScheduler = new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final java.time.Duration delay) {
                scheduledWork.add(work);
                return () -> scheduledWork.remove(work);
            }
        };
        FakeModel activeModel;
        SelectionSnapshot selectionSnapshot =
                new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty());

        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public PluginLocalization localization() {
            return localization;
        }

        @Override
        public PluginStorage storage() {
            return storage;
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
        public ActionRegistry actions() {
            return actionRegistry;
        }

        @Override
        public MenuRegistry menus() {
            return null;
        }

        @Override
        public UiScheduler uiScheduler() {
            return uiScheduler;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return disposableScope;
        }

        @Override
        public PluginServiceDirectory services() {
            return PluginServices.builder()
                    .supply(ContextMenuRegistry.class, () -> contextMenuRegistry)
                    .supply(PaletteToolbarRegistry.class, () -> paletteToolbarRegistry)
                    .supply(UiResourceService.class, () -> uiResourceService)
                    .fallback(PluginServices.of(this))
                    .build();
        }

        @Override
        public CubismFacade cubism() {
            return new FakeCubismFacade(this);
        }
    }

    static final class FakeCubismFacade implements CubismFacade {
        private final FakePluginContext context;

        FakeCubismFacade(final FakePluginContext context) {
            this.context = context;
        }

        @Override
        public CubismModelAccess model() {
            return new CubismModelAccess() {
                @Override
                public boolean isAvailable() {
                    return context.modelAccessOverride != null || context.activeModel != null;
                }

                @Override
                public CubismModel active() {
                    if (context.modelAccessOverride != null) {
                        return context.modelAccessOverride.get();
                    }
                    if (context.activeModel == null) throw new IllegalStateException("no active model");
                    return context.activeModel;
                }
            };
        }

        @Override
        public CubismRuntimeSnapshot runtime() {
            return new CubismRuntimeSnapshot(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    context.selectionSnapshot,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            if (context.simulateDocumentMissing) {
                return Optional.empty();
            }
            if (context.activeDocumentSnapshot != null) {
                return Optional.of(context.activeDocumentSnapshot);
            }
            if (context.activeModel != null) {
                final String id = context.activeModel.id().value();
                return Optional.of(new DocumentSnapshot(
                        "doc-" + id,
                        context.activeModel.name(),
                        "models/" + id + ".cmo3",
                        Optional.empty(),
                        Optional.empty(),
                        dev.turboism.sdk.cubism.DocumentKind.MODEL,
                        Optional.of("content-" + id),
                        Optional.empty()));
            }
            return Optional.empty();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            return Optional.empty();
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }
    }

    static final class FakePaletteEntry implements PaletteEntry {
        PaletteRowAction currentAction;
        boolean closed = false;

        @Override
        public Registration overrideLeadingAction(final PaletteRowAction action) {
            this.currentAction = action;
            this.closed = false;
            return () -> {
                this.closed = true;
                if (this.currentAction == action) {
                    this.currentAction = null;
                }
            };
        }

        @Override
        public Registration overrideFontSize(final float points) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration overrideBold(final boolean bold) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration overrideItalic(final boolean italic) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration overrideTextColor(final UiColor color) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Registration overrideBackgroundColor(final UiColor color) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaletteEntryState resolved() {
            return PaletteEntryState.empty();
        }

        @Override
        public Optional<PaletteEntryState> actual() {
            return Optional.empty();
        }
    }

    static final class FakeModel implements CubismModel {
        private final String guid;
        private final Map<String, FakeParameter> params = new LinkedHashMap<>();
        private final Map<String, FakeParameterGroup> groups = new LinkedHashMap<>();

        FakeModel(final String guid, final List<String> parameterIds) {
            this(guid, parameterIds, List.of());
        }

        FakeModel(final String guid, final List<String> parameterIds, final List<FakeParameterGroup> groupList) {
            this.guid = guid;
            for (final String id : parameterIds) {
                params.put(id, new FakeParameter(id));
            }
            if (groupList != null) {
                for (final FakeParameterGroup g : groupList) {
                    groups.put(g.id().value(), g);
                }
            }
        }

        FakeParameter findParam(final String id) {
            return params.get(id);
        }

        FakeParameterGroup findGroup(final String id) {
            return groups.get(id);
        }

        @Override
        public ModelId id() {
            return new ModelId(guid);
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return new ArrayList<>(params.values());
                }

                @Override
                public Parameter find(final ParameterId parameterId) {
                    final FakeParameter p = params.get(parameterId.value());
                    if (p == null) throw new NoSuchElementException(parameterId.value());
                    return p;
                }
            };
        }

        @Override
        public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override
                public List<ParameterGroup> all() {
                    return new ArrayList<>(groups.values());
                }

                @Override
                public ParameterGroup root() {
                    return groups.isEmpty() ? null : groups.values().iterator().next();
                }

                @Override
                public ParameterGroup find(final ParameterGroupId id) {
                    final FakeParameterGroup g = groups.get(id.value());
                    if (g == null) throw new NoSuchElementException(id.value());
                    return g;
                }
            };
        }

        @Override
        public String name() {
            return "FakeModel";
        }

        @Override
        public Parts parts() {
            return null;
        }

        @Override
        public Drawables drawables() {
            return null;
        }

        @Override
        public Deformers deformers() {
            return null;
        }

        @Override
        public Glues glues() {
            return null;
        }

        @Override
        public void update() {}
    }

    static final class FakeModelWrapper implements CubismModel {
        private final FakeModel delegate;

        FakeModelWrapper(final FakeModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelId id() {
            return delegate.id();
        }

        @Override
        public Parameters parameters() {
            return delegate.parameters();
        }

        @Override
        public ParameterGroups parameterGroups() {
            return delegate.parameterGroups();
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public Parts parts() {
            return delegate.parts();
        }

        @Override
        public Drawables drawables() {
            return delegate.drawables();
        }

        @Override
        public Deformers deformers() {
            return delegate.deformers();
        }

        @Override
        public Glues glues() {
            return delegate.glues();
        }

        @Override
        public void update() {
            delegate.update();
        }
    }

    static final class FakeParameter implements Parameter {
        private final String id;
        private float value;
        boolean viewScopeReady = true;
        final FakePaletteEntry paletteEntry = new FakePaletteEntry();

        FakeParameter(final String id) {
            this.id = id;
            this.value = 0.0f;
        }

        @Override
        public ParameterId id() {
            return new ParameterId(id);
        }

        @Override
        public ParameterAppearance ui() {
            return new ParameterAppearance() {
                @Override
                public Optional<PaletteEntry> parameterPaletteEntry() {
                    return viewScopeReady ? Optional.of(paletteEntry) : Optional.empty();
                }
            };
        }

        @Override
        public float getValue() {
            return value;
        }

        @Override
        public void setValue(final float value) {
            this.value = value;
        }

        @Override
        public float getMinimumValue() {
            return -30.0f;
        }

        @Override
        public float getMaximumValue() {
            return 30.0f;
        }

        @Override
        public float getDefaultValue() {
            return 0.0f;
        }
    }

    static final class FakeParameterGroup implements ParameterGroup {
        private final String id;
        private final List<ParameterId> parameterIds = new ArrayList<>();
        private final List<ParameterGroupId> childGroupIds = new ArrayList<>();
        final FakePaletteEntry paletteEntry = new FakePaletteEntry();

        FakeParameterGroup(final String id, final List<String> paramIds, final List<String> childIds) {
            this.id = id;
            if (paramIds != null) {
                for (final String pid : paramIds) {
                    this.parameterIds.add(new ParameterId(pid));
                }
            }
            if (childIds != null) {
                for (final String cid : childIds) {
                    this.childGroupIds.add(new ParameterGroupId(cid));
                }
            }
        }

        @Override
        public ParameterGroupId id() {
            return new ParameterGroupId(id);
        }

        @Override
        public ParameterGroupAppearance ui() {
            return new ParameterGroupAppearance() {
                @Override
                public Optional<PaletteEntry> parameterPaletteEntry() {
                    return Optional.of(paletteEntry);
                }

                @Override
                public Optional<NativeLabelColorState> nativeLabelColor() {
                    return Optional.empty();
                }

                @Override
                public void setNativeLabelColor(final NativeLabelColor color) {}
            };
        }

        @Override
        public Optional<String> name() {
            return Optional.of(id);
        }

        @Override
        public Optional<ParameterGroupId> parentId() {
            return Optional.empty();
        }

        @Override
        public List<ParameterGroupId> childGroupIds() {
            return childGroupIds;
        }

        @Override
        public List<ParameterId> parameterIds() {
            return parameterIds;
        }
    }

    static final class FakeActionRegistry implements ActionRegistry {
        final Map<String, Action> actions = new LinkedHashMap<>();

        @Override
        public Registration register(final String id, final Action action) {
            actions.put(id, action);
            return () -> actions.remove(id);
        }
    }

    static final class FakeContextMenuRegistry implements ContextMenuRegistry {
        final List<ContextMenuContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final ContextMenuContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    static final class FakePaletteToolbarRegistry implements PaletteToolbarRegistry {
        final List<PaletteToolbarContribution> contributions = new ArrayList<>();

        @Override
        public Registration contribute(final PaletteToolbarContribution contribution) {
            contributions.add(contribution);
            return () -> contributions.remove(contribution);
        }
    }

    static final class FakeUiResourceService implements UiResourceService {
        @Override
        public UiIconAvailability availability(final UiIconRef reference) {
            if (reference != null && reference.icon() == CubismIcon.LOCK) {
                return UiIconAvailability.AVAILABLE;
            }
            return UiIconAvailability.SERVICE_UNAVAILABLE;
        }
    }

    static final class FakeActionContext implements ActionRegistry.ActionContext {
        private final ContextMenuSelection selection;

        FakeActionContext(final ContextMenuSelection selection) {
            this.selection = selection;
        }

        @Override
        public Optional<ContextMenuSelection> contextMenuSelection() {
            return Optional.ofNullable(selection);
        }
    }

    static final class NoopLogger implements PluginLogger {
        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {}

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }
}
