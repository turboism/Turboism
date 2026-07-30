package dev.turboism.pluginmanagement;

import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.plugin.core.CorePluginManagement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginManagementServiceTest {
    @TempDir Path home;

    @Test
    void coreCannotBeDisabledOrUninstalled() {
        final RuntimePluginManagementService service = service(Optional::empty);
        assertFalse(service.setEnabled(CorePluginManagement.CORE_PLUGIN_ID, false).accepted());
        assertFalse(service.uninstall(CorePluginManagement.CORE_PLUGIN_ID).accepted());
        assertTrue(service.plugins().stream().filter(CorePluginManagement.PluginInfo::core)
            .allMatch(plugin -> plugin.desiredState().equals("ENABLED")));
    }

    @Test
    void installIsStagedAndAppliedOnlyBeforeNextDiscovery() throws Exception {
        final Path source = home.resolve("sample.tplugin");
        Files.write(source, PluginManagementPackageFixture.packageBytes("example.plugin", "1.0.0"));
        final RuntimePluginManagementService service = service(() -> Optional.of(source));

        final var installed = service.install();
        assertTrue(installed.accepted(), installed.message());
        assertFalse(Files.exists(home.resolve("plugins/example.plugin.jar")));
        assertTrue(service.plugins().stream().anyMatch(plugin ->
            plugin.id().equals("example.plugin") && plugin.pendingOperation().orElse("").equals("INSTALL")));

        final var applied = RuntimePluginManagementService.applyPending(home);
        assertTrue(applied.applied(), applied.code());
        assertTrue(Files.isRegularFile(home.resolve("plugins/example.plugin.jar")));
    }

    @Test
    void disableEnablePreservesCurrentArtifactAndUsesCanonicalConfig() throws Exception {
        installNow("example.plugin", "1.0.0");
        final byte[] before = Files.readAllBytes(home.resolve("plugins/example.plugin.jar"));
        final RuntimePluginManagementService service = service(Optional::empty);

        assertTrue(service.setEnabled("example.plugin", false).accepted());
        assertTrue(new RuntimeConfigRepository(home, ignored -> { }).disabledPlugins().contains("example.plugin"));
        assertArrayEquals(before, Files.readAllBytes(home.resolve("plugins/example.plugin.jar")));
        assertTrue(service.setEnabled("example.plugin", true).accepted());
        assertFalse(new RuntimeConfigRepository(home, ignored -> { }).disabledPlugins().contains("example.plugin"));
    }

    @Test
    void uninstallIsPendingAndDoesNotDeleteLoadedJar() throws Exception {
        installNow("example.plugin", "1.0.0");
        final RuntimePluginManagementService service = service(Optional::empty);
        assertTrue(service.uninstall("example.plugin").accepted());
        assertTrue(Files.exists(home.resolve("plugins/example.plugin.jar")));
        assertTrue(RuntimePluginManagementService.applyPending(home).applied());
        assertFalse(Files.exists(home.resolve("plugins/example.plugin.jar")));
    }

    @Test
    void rejectsReservedCorePackage() throws Exception {
        final Path source = home.resolve("core.tplugin");
        Files.write(source, PluginManagementPackageFixture.packageBytes(CorePluginManagement.CORE_PLUGIN_ID, "1.0.0"));
        final var result = service(() -> Optional.of(source)).install();
        assertFalse(result.accepted());
        assertEquals("PLUGIN_RESERVED_ID", result.code());
    }

    @Test
    void invalidCanonicalConfigFailsClosed() throws Exception {
        Files.writeString(home.resolve("config.json"), "{}");
        final RuntimePluginManagementService service = service(Optional::empty);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, service::plugins);
    }

    private void installNow(final String id, final String version) throws Exception {
        final Path source = home.resolve(id + ".tplugin");
        Files.write(source, PluginManagementPackageFixture.packageBytes(id, version));
        assertTrue(service(() -> Optional.of(source)).install().accepted());
        assertTrue(RuntimePluginManagementService.applyPending(home).applied());
    }

    private RuntimePluginManagementService service(final java.util.function.Supplier<Optional<Path>> chooser) {
        return new RuntimePluginManagementService(home, chooser, List::of);
    }
}
