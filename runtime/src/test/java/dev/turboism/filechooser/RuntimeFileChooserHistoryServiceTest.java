package dev.turboism.filechooser;

import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.runtime.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFileChooserHistoryServiceTest {

    @TempDir
    Path home;

    private RuntimeFileChooserHistoryService service() {
        return new RuntimeFileChooserHistoryService(new RuntimeConfigRepository(home, ignored -> { }));
    }

    @Test
    void setGetRoundTripPersistsBothDirectories() {
        final RuntimeFileChooserHistoryService service = service();
        final Path project = home.resolve("project-saves");
        final Path export = home.resolve("export-saves");

        service.setProjectRecentDirectory(project);
        service.setExportRecentDirectory(export);

        assertEquals(project, service.projectRecentDirectory().orElseThrow());
        assertEquals(export, service.exportRecentDirectory().orElseThrow());
    }

    @Test
    void directoriesPersistAcrossServiceInstances() {
        final Path export = home.resolve("export-saves");
        service().setExportRecentDirectory(export);

        assertEquals(export, service().exportRecentDirectory().orElseThrow());
    }

    @Test
    void unsetDirectoriesAreEmpty() {
        assertTrue(service().projectRecentDirectory().isEmpty());
        assertTrue(service().exportRecentDirectory().isEmpty());
    }

    @Test
    void exportSeparationDefaultsToFalse() {
        assertFalse(service().exportSeparationEnabled());
    }

    @Test
    void exportSeparationFollowsSettingsFileServiceWrites() {
        final RuntimeConfigRepository config = new RuntimeConfigRepository(home, ignored -> { });
        final RuntimeFileChooserHistoryService fileChooser = new RuntimeFileChooserHistoryService(config);
        final dev.turboism.config.RuntimeSettingsFileService settings =
            new dev.turboism.config.RuntimeSettingsFileService(
                config,
                new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator()
            );

        settings.save(new RuntimeSettings(false, "INFO", 100, false, false, false, true));

        assertTrue(fileChooser.exportSeparationEnabled());
    }

    @Test
    void legacyConfigWithoutNewFieldsReadsAsDefaults() throws Exception {
        java.nio.file.Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "legacy-test",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        final RuntimeFileChooserHistoryService service = service();

        assertTrue(service.projectRecentDirectory().isEmpty());
        assertTrue(service.exportRecentDirectory().isEmpty());
        assertFalse(service.exportSeparationEnabled());
    }

    @Test
    void nullDirectoryIsRejected() {
        assertThrows(NullPointerException.class, () -> service().setProjectRecentDirectory(null));
        assertThrows(NullPointerException.class, () -> service().setExportRecentDirectory(null));
    }

    @Test
    void unavailableInstanceIsFailClosed() {
        final FileChooserHistoryService unavailable = FileChooserHistoryService.unavailable();

        assertTrue(unavailable.projectRecentDirectory().isEmpty());
        assertTrue(unavailable.exportRecentDirectory().isEmpty());
        assertFalse(unavailable.exportSeparationEnabled());
        assertThrows(UnsupportedOperationException.class,
            () -> unavailable.setProjectRecentDirectory(home));
        assertThrows(UnsupportedOperationException.class,
            () -> unavailable.setExportRecentDirectory(home));
    }
}
