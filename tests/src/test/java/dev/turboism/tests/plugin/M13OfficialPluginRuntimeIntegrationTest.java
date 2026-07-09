package dev.turboism.tests.plugin;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.plugin.logfilter.LogFilterPlugin;
import dev.turboism.plugin.maintoolbar.MainToolbarPlugin;
import dev.turboism.plugin.renderopt.RenderOptPlugin;
import dev.turboism.plugin.uitheme.UiThemePlugin;
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
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
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
    void officialM13ManifestsDeclareExpectedPermissions() throws Exception {
        assertEquals(
            Set.of(
                "turboism.action.register",
                "turboism.ui.toolbar.palette.contribute",
                "turboism.ui.status.notify"
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
                "turboism.ui.status.notify"
            ),
            permissionIdsFor("render-opt")
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
