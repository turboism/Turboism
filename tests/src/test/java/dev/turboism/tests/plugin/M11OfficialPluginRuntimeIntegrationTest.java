package dev.turboism.tests.plugin;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.plugin.perfopt.PerfOptPlugin;
import dev.turboism.plugin.renderopt.RenderOptPlugin;
import dev.turboism.plugin.uitheme.UiThemePlugin;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.ui.context.RuntimeContextMenuRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M11OfficialPluginRuntimeIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void officialM11PluginShellsEnableWithDeclaredRuntimePermissions() throws Exception {
        // ui-theme: context-menu and action registrations are checked by runtime registries.
        try (M8PluginTestSupport.Harness harness = harnessFor("ui-theme", tempDir.resolve("ui-theme"))) {
            UiThemePlugin plugin = new UiThemePlugin();
            plugin.init(harness.context());
            plugin.enable();

            RuntimeContextMenuRegistry contextMenu = (RuntimeContextMenuRegistry) harness.context().contextMenu();
            assertEquals(2, contextMenu.contributions().size());

            harness.context().disposableScope().close();
            assertEquals(0, contextMenu.contributions().size());
        }

        // perf-opt: action and menu registrations are checked by real runtime registries.
        try (M8PluginTestSupport.Harness harness = harnessFor("perf-opt", tempDir.resolve("perf-opt"))) {
            PerfOptPlugin plugin = new PerfOptPlugin();
            plugin.init(harness.context());
            plugin.enable();

            assertTrue(harness.menuTracker().isVisible("perfopt.fps-overlay.toggle"));

            harness.context().disposableScope().close();
            assertFalse(harness.menuTracker().isVisible("perfopt.fps-overlay.toggle"));
        }

        // render-opt: no Cubism/render permission is required while it is only a lifecycle shell.
        try (M8PluginTestSupport.Harness harness = harnessFor("render-opt", tempDir.resolve("render-opt"))) {
            RenderOptPlugin plugin = new RenderOptPlugin();
            plugin.init(harness.context());
            plugin.enable();
            harness.context().disposableScope().close();
        }
    }

    @Test
    void officialM11ManifestsDeclareOnlyPermissionsRequiredByCurrentShells() throws Exception {
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
            Set.of("turboism.action.register", "turboism.ui.menu.contribute"),
            permissionIdsFor("perf-opt")
        );
        assertEquals(Set.of(), permissionIdsFor("render-opt"));
    }

    private static Set<String> permissionIdsFor(String pluginDirectory) throws Exception {
        return descriptorFor(pluginDirectory).permissions().stream()
            .map(PluginDescriptor.PermissionRef::id)
            .collect(Collectors.toSet());
    }

    @Test
    void perfOptEnableFailsWhenActionPermissionIsMissingFromRuntimeGate() throws Exception {
        PluginDescriptor descriptor = descriptorFor("perf-opt");
        List<PluginPermission> permissionsWithoutAction = descriptor.permissions().stream()
            .filter(permission -> !"turboism.action.register".equals(permission.id()))
            .map(M11OfficialPluginRuntimeIntegrationTest::toPermission)
            .toList();

        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(
            tempDir.resolve("perf-opt-denied"),
            PermissionChecker.from(permissionsWithoutAction)
        )) {
            PerfOptPlugin plugin = new PerfOptPlugin();
            plugin.init(harness.context());

            CubismPermissionException failure = assertThrows(CubismPermissionException.class, plugin::enable);
            assertTrue(failure.getMessage().contains("turboism.action.register"));
            assertFalse(harness.menuTracker().isVisible("perfopt.fps-overlay.toggle"));
        }
    }

    private static M8PluginTestSupport.Harness harnessFor(String pluginDirectory, Path dataDir) throws Exception {
        PluginDescriptor descriptor = descriptorFor(pluginDirectory);
        return M8PluginTestSupport.harness(dataDir, PermissionChecker.from(toPermissions(descriptor)));
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
            .map(M11OfficialPluginRuntimeIntegrationTest::toPermission)
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
}
