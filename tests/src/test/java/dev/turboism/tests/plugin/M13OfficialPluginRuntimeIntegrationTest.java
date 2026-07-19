package dev.turboism.tests.plugin;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.plugin.clipmask.ClipMaskPlugin;
import dev.turboism.plugin.parameter.ParameterPlugin;
import dev.turboism.plugin.mesh.MeshPlugin;
import dev.turboism.plugin.logfilter.LogFilterPlugin;
import dev.turboism.plugin.maintoolbar.MainToolbarPlugin;
import dev.turboism.plugin.renderopt.RenderOptPlugin;
import dev.turboism.plugin.uitheme.UiThemePlugin;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
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
import dev.turboism.sdk.cubism.transaction.DocumentId;
import dev.turboism.sdk.cubism.transaction.ModelTransaction;
import dev.turboism.sdk.cubism.transaction.TransactionManager;
import dev.turboism.sdk.cubism.transaction.TransactionStatus;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.UiHostStateSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M13OfficialPluginRuntimeIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void logFilterEnablesWithDeclaredPermissionsAndCleansUpPaletteContribution() throws Exception {
        try (M8PluginTestSupport.Harness harness = harnessFor("log-filter", tempDir.resolve("log-filter"))) {
            LogFilterPlugin plugin = new LogFilterPlugin();
            plugin.init(harness.context());
            plugin.enable();

            assertEquals(
                List.of(new PaletteToolbarRegistry.PaletteToolbarContribution(
                    "log-filter.toggle-level",
                    "log-filter.toggle-level",
                    "log-filter.toggle-level.label",
                    "icons/log-filter-toggle.svg",
                    "LOG",
                    "end",
                    100
                )),
                harness.uiHost().paletteToolbars()
            );

            harness.context().disposableScope().close();
            assertTrue(harness.uiHost().paletteToolbars().isEmpty());
        }
    }

    @Test
    void logFilterEnableFailsWhenPalettePermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("log-filter");
        List<PluginPermission> permissionsWithoutPalette = withoutPermission(
            descriptor,
            "turboism.ui.toolbar.palette.contribute"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("log-filter-denied"),
            PermissionChecker.from(permissionsWithoutPalette)
        )) {
            LogFilterPlugin plugin = new LogFilterPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.ui.toolbar.palette.contribute"));
            assertTrue(harness.uiHost().paletteToolbars().isEmpty());
        }
    }

    @Test
    void mainToolbarEnablesWithDeclaredPermissionsAndCleansUpContribution() throws Exception {
        try (M8PluginTestSupport.Harness harness = harnessFor(
            "main-toolbar",
            tempDir.resolve("main-toolbar"),
            UiHostStateSource.DEFAULT,
            new ProjectAndWorkspaceRead()
        )) {
            MainToolbarPlugin plugin = new MainToolbarPlugin();
            plugin.init(harness.context());
            plugin.enable();

            assertEquals(
                List.of(new MainToolbarRegistry.MainToolbarContribution(
                    "main-toolbar.home-entry",
                    "main-toolbar.home-entry.open",
                    "main-toolbar.home-entry.label",
                    "icons/main-toolbar-home.svg",
                    "start",
                    10
                )),
                harness.uiHost().mainToolbars()
            );

            harness.context().disposableScope().close();
            assertTrue(harness.uiHost().mainToolbars().isEmpty());
        }
    }

    @Test
    void mainToolbarEnableFailsWhenMainToolbarPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("main-toolbar");
        List<PluginPermission> permissionsWithoutToolbar = withoutPermission(
            descriptor,
            "turboism.ui.toolbar.main.contribute"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("main-toolbar-denied"),
            PermissionChecker.from(permissionsWithoutToolbar),
            UiHostStateSource.DEFAULT,
            new ProjectAndWorkspaceRead()
        )) {
            MainToolbarPlugin plugin = new MainToolbarPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.ui.toolbar.main.contribute"));
            assertTrue(harness.uiHost().mainToolbars().isEmpty());
        }
    }

    @Test
    void uiThemeStatusNotifyFailsWhenStatusPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("ui-theme");
        List<PluginPermission> permissionsWithoutStatus = withoutPermission(
            descriptor,
            "turboism.ui.status.notify"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("ui-theme-status-denied"),
            PermissionChecker.from(permissionsWithoutStatus),
            UiHostStateSource.DEFAULT,
            new ThemeOnlyRead()
        )) {
            UiThemePlugin plugin = new UiThemePlugin();
            plugin.init(harness.context());
            plugin.enable();

            RuntimeUiHostCapabilityService uiHost = harness.uiHost();
            CubismPermissionException failure = assertThrows(
                CubismPermissionException.class,
                () -> uiHost.notifyStatus(new StatusNotification(
                    "ui-theme.package.status.available",
                    "INFO",
                    "probe"
                ))
            );
            assertTrue(failure.getMessage().contains("turboism.ui.status.notify"));
            assertTrue(uiHost.notifications().isEmpty());
        }
    }

    @Test
    void renderOptEnablesWithDeclaredPermissionsAndCleansUpOverlayContribution() throws Exception {
        try (M8PluginTestSupport.Harness harness = harnessFor(
            "render-opt",
            tempDir.resolve("render-opt"),
            UiHostStateSource.DEFAULT,
            new RenderStatusRead()
        )) {
            RenderOptPlugin plugin = new RenderOptPlugin();
            plugin.init(harness.context());
            plugin.enable();

            assertEquals(
                List.of(new OverlayContribution("render-status.overlay", "viewport", 50)),
                harness.uiHost().overlays()
            );

            harness.context().disposableScope().close();
            assertTrue(harness.uiHost().overlays().isEmpty());
        }
    }

    @Test
    void renderOptEnableFailsWhenOverlayPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("render-opt");
        List<PluginPermission> permissionsWithoutOverlay = withoutPermission(
            descriptor,
            "turboism.ui.overlay.contribute"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("render-opt-denied"),
            PermissionChecker.from(permissionsWithoutOverlay),
            UiHostStateSource.DEFAULT,
            new RenderStatusRead()
        )) {
            RenderOptPlugin plugin = new RenderOptPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.ui.overlay.contribute"));
            assertTrue(harness.uiHost().overlays().isEmpty());
        }
    }

    @Test
    void clipMaskEnablesRunsInspectActionAndCleansUpPanelAndDialog() throws Exception {
        ClipMaskRead read = new ClipMaskRead();
        try (M8PluginTestSupport.Harness harness = harnessFor(
            "clip-mask",
            tempDir.resolve("clip-mask"),
            UiHostStateSource.DEFAULT,
            read
        )) {
            ClipMaskPlugin plugin = new ClipMaskPlugin();
            plugin.init(harness.context());
            plugin.enable();

            assertEquals(
                List.of(new EmbeddedPanelContribution("clip-mask.inspector.panel", "Clip Mask Inspector", "side", 40)),
                harness.uiHost().panels()
            );
            assertEquals(
                List.of(new DialogRequest(
                    "clip-mask.inspector.dialog",
                    "Clip Mask Inspector",
                    "Clip Mask Inspector is ready. Use Inspect to refresh status."
                )),
                harness.uiHost().dialogs()
            );

            harness.executeAction("clip-mask.inspector.inspect");
            awaitNotificationCount(harness, 1);
            assertEquals(
                List.of(new StatusNotification(
                    "clip-mask.inspector.refreshed",
                    "INFO",
                    "Clip masks: 1 target meshes, 1 inverted, 1 mask source refs"
                )),
                harness.uiHost().notifications()
            );
            assertEquals(1, read.clipMaskReads());

            harness.context().disposableScope().close();
            assertTrue(harness.uiHost().panels().isEmpty());
            assertTrue(harness.uiHost().dialogs().isEmpty());
        }
    }

    @Test
    void clipMaskInspectFailsInSchedulerWhenModelReadPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("clip-mask");
        List<PluginPermission> permissionsWithoutModelRead = withoutPermission(
            descriptor,
            "turboism.cubism.model.read"
        );
        ClipMaskRead read = new ClipMaskRead();

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("clip-mask-model-read-denied"),
            PermissionChecker.from(permissionsWithoutModelRead),
            UiHostStateSource.DEFAULT,
            read
        )) {
            ClipMaskPlugin plugin = new ClipMaskPlugin();
            plugin.init(harness.context());
            plugin.enable();

            harness.executeAction("clip-mask.inspector.inspect");
            awaitCallbackPhase(harness, CallbackBudgetEvent.Phase.FAILED);

            assertEquals(0, read.clipMaskReads());
            assertTrue(harness.uiHost().notifications().isEmpty());
            assertEquals(1, harness.uiHost().panels().size());
            assertEquals(1, harness.uiHost().dialogs().size());

            harness.context().disposableScope().close();
            assertTrue(harness.uiHost().panels().isEmpty());
            assertTrue(harness.uiHost().dialogs().isEmpty());
        }
    }

    @Test
    void clipMaskInspectFailsInSchedulerWhenStatusPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("clip-mask");
        List<PluginPermission> permissionsWithoutStatus = withoutPermission(
            descriptor,
            "turboism.ui.status.notify"
        );
        ClipMaskRead read = new ClipMaskRead();

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("clip-mask-status-denied"),
            PermissionChecker.from(permissionsWithoutStatus),
            UiHostStateSource.DEFAULT,
            read
        )) {
            ClipMaskPlugin plugin = new ClipMaskPlugin();
            plugin.init(harness.context());
            plugin.enable();

            harness.executeAction("clip-mask.inspector.inspect");
            awaitCallbackPhase(harness, CallbackBudgetEvent.Phase.FAILED);

            assertEquals(1, read.clipMaskReads());
            assertTrue(harness.uiHost().notifications().isEmpty());
            assertEquals(1, harness.uiHost().panels().size());
            assertEquals(1, harness.uiHost().dialogs().size());
        }
    }

    @Test
    void clipMaskEnableFailsWhenPanelPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("clip-mask");
        List<PluginPermission> permissionsWithoutPanel = withoutPermission(
            descriptor,
            "turboism.ui.panel.contribute"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("clip-mask-denied"),
            PermissionChecker.from(permissionsWithoutPanel),
            UiHostStateSource.DEFAULT,
            new ClipMaskRead()
        )) {
            ClipMaskPlugin plugin = new ClipMaskPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.ui.panel.contribute"));
            assertTrue(harness.uiHost().panels().isEmpty());
            assertTrue(harness.uiHost().dialogs().isEmpty());
        }
    }

    @Test
    void clipMaskEnableFailsWhenDialogPermissionIsMissingAndRollsBackPanel() throws Exception {
        PluginDescriptor descriptor = descriptorFor("clip-mask");
        List<PluginPermission> permissionsWithoutDialog = withoutPermission(
            descriptor,
            "turboism.ui.dialog.contribute"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("clip-mask-dialog-denied"),
            PermissionChecker.from(permissionsWithoutDialog),
            UiHostStateSource.DEFAULT,
            new ClipMaskRead()
        )) {
            ClipMaskPlugin plugin = new ClipMaskPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.ui.dialog.contribute"));
            assertTrue(harness.uiHost().panels().isEmpty());
            assertTrue(harness.uiHost().dialogs().isEmpty());
        }
    }

    @Test
    void clipMaskEnableFailsWhenActionPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("clip-mask");
        List<PluginPermission> permissionsWithoutAction = withoutPermission(
            descriptor,
            "turboism.action.register"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("clip-mask-action-denied"),
            PermissionChecker.from(permissionsWithoutAction),
            UiHostStateSource.DEFAULT,
            new ClipMaskRead()
        )) {
            ClipMaskPlugin plugin = new ClipMaskPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.action.register"));
            assertTrue(harness.uiHost().panels().isEmpty());
            assertTrue(harness.uiHost().dialogs().isEmpty());
        }
    }


    @Test
    void parameterEnablesWithDeclaredPermissionsAndCleansUpActions() throws Exception {
        try (M8PluginTestSupport.Harness harness = harnessFor(
            "parameter",
            tempDir.resolve("parameter"),
            UiHostStateSource.DEFAULT,
            new ParameterRead()
        )) {
            ParameterPlugin plugin = new ParameterPlugin();
            plugin.init(harness.context());
            plugin.enable();

            harness.context().disposableScope().close();
            // enable succeeded without residual UI contributions
            assertTrue(harness.uiHost().panels().isEmpty());
            assertTrue(harness.uiHost().dialogs().isEmpty());
        }
    }

    @Test
    void parameterEnableFailsWhenActionPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("parameter");
        List<PluginPermission> permissionsWithoutAction = withoutPermission(
            descriptor,
            "turboism.action.register"
        );

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("parameter-denied"),
            PermissionChecker.from(permissionsWithoutAction),
            UiHostStateSource.DEFAULT,
            new ParameterRead()
        )) {
            ParameterPlugin plugin = new ParameterPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.action.register"));
        }
    }


    @Test
    void meshEnablesWithDeclaredPermissions() throws Exception {
        try (M8PluginTestSupport.Harness harness = harnessFor(
            "mesh",
            tempDir.resolve("mesh"),
            UiHostStateSource.DEFAULT,
            new MeshRead()
        )) {
            MeshPlugin plugin = new MeshPlugin();
            plugin.init(harness.context());
            plugin.enable();
            harness.context().disposableScope().close();
        }
    }

    @Test
    void meshEnableFailsWhenActionPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("mesh");
        List<PluginPermission> permissionsWithoutAction = withoutPermission(
            descriptor,
            "turboism.action.register"
        );
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("mesh-denied"),
            PermissionChecker.from(permissionsWithoutAction),
            UiHostStateSource.DEFAULT,
            new MeshRead()
        )) {
            MeshPlugin plugin = new MeshPlugin();
            plugin.init(harness.context());
            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.action.register"));
        }
    }

    @Test
    void mainToolbarActionDeniesWhenProjectReadPermissionIsMissing() throws Exception {
        PluginDescriptor descriptor = descriptorFor("main-toolbar");
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("main-toolbar-project-read-denied"),
            PermissionChecker.from(withoutPermission(descriptor, "turboism.cubism.project.read")),
            UiHostStateSource.DEFAULT,
            new ProjectAndWorkspaceRead()
        )) {
            MainToolbarPlugin plugin = new MainToolbarPlugin();
            plugin.init(harness.context());
            plugin.enable();

            harness.executeAction("main-toolbar.home-entry.open");
            awaitCallbackPhase(harness, CallbackBudgetEvent.Phase.FAILED);
            assertTrue(harness.uiHost().notifications().isEmpty());
        }
    }

    @Test
    void renderStatusActionDeniesOperationalPermissions() throws Exception {
        PluginDescriptor descriptor = descriptorFor("render-opt");
        for (String denied : List.of("turboism.cubism.model.read", "turboism.ui.status.notify")) {
            try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
                tempDir.resolve("render-opt-" + denied.replace('.', '-')),
                PermissionChecker.from(withoutPermission(descriptor, denied)),
                UiHostStateSource.DEFAULT,
                new RenderStatusRead()
            )) {
                RenderOptPlugin plugin = new RenderOptPlugin();
                plugin.init(harness.context());
                plugin.enable();

                harness.executeAction("render-status.overlay.refresh");
                awaitCallbackPhase(harness, CallbackBudgetEvent.Phase.FAILED);
                assertTrue(harness.uiHost().notifications().isEmpty());
            }
        }
    }

    @Test
    void meshInspectActionDeniesOperationalPermissions() throws Exception {
        PluginDescriptor descriptor = descriptorFor("mesh");
        for (String denied : List.of(
            "turboism.cubism.model.read",
            "turboism.ui.context-source.read",
            "turboism.ui.status.notify"
        )) {
            try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
                tempDir.resolve("mesh-" + denied.replace('.', '-')),
                PermissionChecker.from(withoutPermission(descriptor, denied)),
                UiHostStateSource.DEFAULT,
                new MeshRead()
            )) {
                MeshPlugin plugin = new MeshPlugin();
                plugin.init(harness.context());
                plugin.enable();

                harness.executeAction("mesh.inspector.inspect");
                awaitCallbackPhase(harness, CallbackBudgetEvent.Phase.FAILED);
                assertTrue(harness.uiHost().notifications().isEmpty());
            }
        }
    }

    @Test
    void parameterExportActionDeniesReadAndStatusPermissions() throws Exception {
        PluginDescriptor descriptor = descriptorFor("parameter");
        for (String denied : List.of("turboism.cubism.model.read", "turboism.ui.status.notify")) {
            try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
                tempDir.resolve("parameter-export-" + denied.replace('.', '-')),
                PermissionChecker.from(withoutPermission(descriptor, denied)),
                UiHostStateSource.DEFAULT,
                new ParameterRead()
            )) {
                ParameterPlugin plugin = new ParameterPlugin();
                plugin.init(harness.context());
                plugin.enable();

                harness.executeAction("parameter.csv.export");
                awaitCallbackPhase(harness, CallbackBudgetEvent.Phase.FAILED);
                assertTrue(harness.uiHost().notifications().isEmpty());
            }
        }
    }

    @Test
    void parameterImportActionDeniesFileChooserPermission() throws Exception {
        PluginDescriptor descriptor = descriptorFor("parameter");
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("parameter-file-chooser-denied"),
            PermissionChecker.from(withoutPermission(descriptor, "turboism.ui.file-chooser.request")),
            fileChooserSource(),
            new ParameterImportRead()
        )) {
            ParameterPlugin plugin = new ParameterPlugin(ignored -> Optional.of("id,value\np1,0.75\n"));
            plugin.init(harness.context());
            plugin.enable();

            harness.executeAction("parameter.csv.import");
            awaitCallbackPhase(harness, CallbackBudgetEvent.Phase.FAILED);
            assertTrue(harness.uiHost().notifications().isEmpty());
        }
    }

    @Test
    void parameterImportActionDeniesModelWritePermissionWithoutPartialApply() throws Exception {
        PluginDescriptor descriptor = descriptorFor("parameter");
        List<PluginPermission> permissions = withoutPermission(descriptor, "turboism.cubism.model.write");
        PermissionChecker checker = PermissionChecker.from(permissions);
        WritePermissionFacade facade = new WritePermissionFacade(checker);
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("parameter-model-write-denied"),
            checker,
            fileChooserSource(),
            new ParameterImportRead(),
            facade
        )) {
            ParameterPlugin plugin = new ParameterPlugin(ignored -> Optional.of("id,value\np1,0.75\n"));
            plugin.init(harness.context());
            plugin.enable();

            harness.executeAction("parameter.csv.import");
            awaitNotificationCount(harness, 1);
            assertEquals("parameter.csv.import.failed", harness.uiHost().notifications().get(0).id());
            assertEquals(0, facade.enqueuedCommands);
        }
    }

    @Test
    void officialM13ManifestsDeclareExpectedPermissions() throws Exception {
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.ui.toolbar.palette.contribute",
                "turboism.ui.status.notify",
                "turboism.config.plugin.read",
                "turboism.config.plugin.write"
            ),
            permissionIdsFor("log-filter")
        );
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.ui.toolbar.main.contribute",
                "turboism.cubism.project.read",
                "turboism.ui.status.notify"
            ),
            permissionIdsFor("main-toolbar")
        );
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.ui.context-menu.contribute",
                "turboism.cubism.project.read",
                "turboism.ui.dialog.contribute",
                "turboism.ui.file-chooser.request",
                "turboism.ui.status.notify"
            ),
            permissionIdsFor("ui-theme")
        );
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.cubism.model.read",
                "turboism.ui.overlay.contribute",
                "turboism.ui.status.notify",
                "turboism.config.plugin.read",
                "turboism.config.plugin.write"
            ),
            permissionIdsFor("render-opt")
        );
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.cubism.model.read",
                "turboism.ui.dialog.contribute",
                "turboism.ui.panel.contribute",
                "turboism.ui.status.notify"
            ),
            permissionIdsFor("clip-mask")
        );
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.cubism.model.read",
                "turboism.cubism.model.write",
                "turboism.ui.file-chooser.request",
                "turboism.ui.status.notify"
            ),
            permissionIdsFor("parameter")
        );
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.cubism.model.read",
                "turboism.ui.context-source.read",
                "turboism.ui.status.notify"
            ),
            permissionIdsFor("mesh")
        );
    }

    private static M8PluginTestSupport.Harness harnessFor(String pluginDirectory, Path dataDir) throws Exception {
        return harnessFor(pluginDirectory, dataDir, UiHostStateSource.DEFAULT, null);
    }

    private static M8PluginTestSupport.Harness harnessFor(
        String pluginDirectory,
        Path dataDir,
        UiHostStateSource uiHostStateSource,
        CubismReadCapabilityService cubismRead
    ) throws Exception {
        PluginDescriptor descriptor = descriptorFor(pluginDirectory);
        return M8PluginTestSupport.harness(
            dataDir,
            PermissionChecker.from(toPermissions(descriptor)),
            uiHostStateSource,
            cubismRead
        );
    }

    private static void awaitNotificationCount(
        final M8PluginTestSupport.Harness harness,
        final int expectedCount
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + 2_000_000_000L;
        while (harness.uiHost().notifications().size() < expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expectedCount, harness.uiHost().notifications().size());
    }

    private static void awaitCallbackPhase(
        final M8PluginTestSupport.Harness harness,
        final CallbackBudgetEvent.Phase expectedPhase
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + 2_000_000_000L;
        while (harness.callbackEvents().stream().noneMatch(event -> event.phase() == expectedPhase)
            && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(
            harness.callbackEvents().stream().anyMatch(event -> event.phase() == expectedPhase),
            () -> "Missing callback phase " + expectedPhase + "; events=" + harness.callbackEvents()
        );
    }

    private static List<PluginPermission> withoutPermission(PluginDescriptor descriptor, String deniedId) {
        return descriptor.permissions().stream()
            .filter(permission -> !deniedId.equals(permission.id()))
            .map(M13OfficialPluginRuntimeIntegrationTest::toPermission)
            .toList();
    }

    private static Set<String> permissionIdsFor(String pluginDirectory) throws Exception {
        return descriptorFor(pluginDirectory).permissions().stream()
            .map(PluginDescriptor.PermissionRef::id)
            .collect(Collectors.toSet());
    }

    private static PluginDescriptor descriptorFor(String pluginDirectory) throws Exception {
        Path root = Path.of(System.getProperty("projectRoot", ".")).toAbsolutePath().normalize();
        Path pluginJson = root.resolve("plugins")
            .resolve(pluginDirectory)
            .resolve("src/main/resources/META-INF/turboism/plugin.json");
        try (InputStream input = Files.newInputStream(pluginJson)) {
            return new PluginDescriptorParser().parse(input);
        } catch (IOException e) {
            throw new AssertionError("Failed to read " + pluginJson, e);
        }
    }

    private static List<PluginPermission> toPermissions(PluginDescriptor descriptor) {
        return descriptor.permissions().stream()
            .map(M13OfficialPluginRuntimeIntegrationTest::toPermission)
            .toList();
    }

    private static PluginPermission toPermission(PluginDescriptor.PermissionRef permission) {
        return new DeclaredPermission(
            permission.id(),
            permission.scope(),
            permission.reason().orElse("")
        );
    }

    private record DeclaredPermission(String id, String scope, String reason) implements PluginPermission {
    }

    private static final class ThemeOnlyRead extends UnsupportedCubismRead {
        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            return Optional.of(new ThemeStatusSnapshot("aurora", "Aurora", true));
        }
    }

    private static final class ProjectAndWorkspaceRead extends UnsupportedCubismRead {
        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.of(new ProjectSnapshot(
                "project-1",
                "Demo Project",
                Optional.of(Path.of("projects/demo")),
                List.of(new DocumentSnapshot("doc-1", "Model A", "model-a.cmo3", Optional.empty(), Optional.empty()))
            ));
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return Optional.of(new WorkspaceSnapshot(
                "workspace-1",
                "workspaces/demo",
                List.of("project-1")
            ));
        }
    }

    private static final class RenderStatusRead extends UnsupportedCubismRead {
        @Override
        public Optional<RenderStatusSnapshot> renderStatus() {
            return Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer"));
        }
    }

    private static final class MeshRead extends UnsupportedCubismRead {
        @Override
        public List<ArtMeshSnapshot> meshes() {
            return List.of(new ArtMeshSnapshot("m1", "Mesh1", Optional.empty(), true, true));
        }
        @Override
        public List<DeformerSnapshot> deformers() {
            return List.of();
        }
    }

    private static class ParameterRead extends UnsupportedCubismRead {
        @Override
        public List<ParameterSnapshot> parameters() {
            return List.of(new ParameterSnapshot("p1", "P1", 0.5, 0.0, -1.0, 1.0, true, true));
        }
    }

    private static final class ParameterImportRead extends ParameterRead {
        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            return Optional.of(new DocumentSnapshot(
                "document-1",
                "Document",
                "models/model.cmo3",
                Optional.empty(),
                Optional.empty()
            ));
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            return Optional.of(new ModelSnapshot(
                "model-1",
                "Model",
                List.of(),
                List.of(),
                List.of(),
                List.of()
            ));
        }
    }

    private static UiHostStateSource fileChooserSource() {
        return new UiHostStateSource() {
            @Override
            public Optional<String> chooseFile(dev.turboism.sdk.ui.FileChooserRequest request) {
                return Optional.of("imports/params.csv");
            }
        };
    }

    private static final class WritePermissionFacade implements CubismFacade {
        private final PermissionChecker permissionChecker;
        private int enqueuedCommands;

        private WritePermissionFacade(final PermissionChecker permissionChecker) {
            this.permissionChecker = permissionChecker;
        }

        @Override public CubismRuntimeSnapshot runtime() { throw new UnsupportedOperationException(); }
        @Override public Optional<ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return true; }

        @Override
        public TransactionManager transactionManager() {
            return (context, documentId) -> {
                permissionChecker.check("turboism.cubism.model.write", "transaction.open");
                return new ModelTransaction() {
                    private TransactionStatus status = TransactionStatus.OPEN;

                    @Override public TransactionStatus status() { return status; }
                    @Override public void enqueue(CubismWriteCommand command) { enqueuedCommands++; }
                    @Override public void commit() { status = TransactionStatus.COMMITTED; }
                    @Override public void rollback() { status = TransactionStatus.ROLLED_BACK; }
                    @Override public String transactionId() { return "permission-test"; }
                };
            };
        }
    }

    private static final class ClipMaskRead extends UnsupportedCubismRead {
        private int clipMaskReads;

        @Override
        public List<ClipMaskSnapshot> clipMasks() {
            clipMaskReads++;
            return List.of(new ClipMaskSnapshot("mesh-1", List.of("mesh-src"), true));
        }

        int clipMaskReads() {
            return clipMaskReads;
        }
    }

    private static class UnsupportedCubismRead implements CubismReadCapabilityService {
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
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this integration test");
        }
    }
}
