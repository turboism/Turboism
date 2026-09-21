package dev.turboism.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the persisted mesh triangulation preference. */
class MeshTriangulationSettingsFileServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = MeshTriangulationPreference.KEY;

    @TempDir
    Path home;

    @Test
    void defaultsToEnabledWhenNothingIsPersisted() {
        MeshTriangulationSettingsFileService service = new MeshTriangulationSettingsFileService(home);
        assertTrue(service.read());
    }

    @Test
    void persistedFalseIsHonoredAcrossInstances() throws Exception {
        MeshTriangulationSettingsFileService service = new MeshTriangulationSettingsFileService(home);
        service.save(false);

        assertTrue(JSON.readTree(home.resolve("config.json").toFile()).path(KEY).isBoolean());
        assertFalse(JSON.readTree(home.resolve("config.json").toFile()).path(KEY).asBoolean());
        assertFalse(service.read());
        assertFalse(new MeshTriangulationSettingsFileService(home).read());
    }

    @Test
    void nonBooleanPersistedValueFallsBackToEnabled() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "settings-test",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}},
              "%s": "off"
            }
            """.formatted(KEY));

        assertTrue(new MeshTriangulationSettingsFileService(home).read());
    }

    @Test
    void saveFailurePropagatesInsteadOfReportingSuccess() throws Exception {
        try {
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        try {
            MeshTriangulationSettingsFileService service =
                new MeshTriangulationSettingsFileService(home);
            assertThrows(IllegalStateException.class, () -> service.save(false));
            assertFalse(Files.exists(home.resolve("config.json")),
                "a failed save must not leave a config that reads back as persisted");
        } finally {
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void unreadablePersistedValueReadsAsEnabled() throws IOException {
        Files.createDirectory(home.resolve("config.json"));
        assertTrue(new MeshTriangulationSettingsFileService(home).read());
    }
}
