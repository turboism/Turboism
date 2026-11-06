package dev.turboism.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One keyed service persists every native-optimization preference, so these are the invariants the
 * per-optimization services used to repeat: the key resolves to the field that decides the JSON
 * scope, the value round-trips as a boolean, an unreadable store reads as off, and a failed write
 * is an exception instead of a silent success.
 */
class OptimizationSettingsFileServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    static Stream<Optimization> persisted() {
        return OptimizationCatalog.persisted().stream();
    }

    @ParameterizedTest
    @MethodSource("persisted")
    void everyPersistedKeyRoundTripsInItsDeclaredScope(final Optimization optimization) throws Exception {
        final PerformanceSettingsConfig.Field field = PerformanceSettingsConfig.Field.forKey(optimization.key());
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            assertFalse(service.enabled(optimization.key()), optimization.key() + " defaults off");
            service.setEnabled(optimization.key(), true);
            assertTrue(service.enabled(optimization.key()), optimization.key() + " reads back the saved value");
            final JsonNode root = JSON.readTree(home.resolve("config.json").toFile());
            final JsonNode node = (field.launcher() ? root.path("launcher") : root).path(field.key());
            assertTrue(node.isBoolean(), optimization.key() + " is persisted as a JSON boolean");
            assertTrue(node.booleanValue(), optimization.key() + " is persisted where its field reads it");
        }
        try (OptimizationSettingsFileService reopened = new OptimizationSettingsFileService(home)) {
            assertTrue(reopened.enabled(optimization.key()), "a persisted preference survives a restart");
            reopened.setEnabled(optimization.key(), false);
            assertFalse(reopened.enabled(optimization.key()), "an explicit opt-out is persisted too");
        }
    }

    @Test
    void unknownKeyIsRefusedInsteadOfPersistingNothing() {
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            assertThrows(IllegalArgumentException.class, () -> service.enabled("notAnOptimization"));
            assertThrows(IllegalArgumentException.class, () -> service.setEnabled("notAnOptimization", true));
            assertFalse(Files.exists(home.resolve("config.json")), "a refused key writes no config");
        }
    }

    @Test
    void unreadableOrNonBooleanStoreReadsAsOff() throws IOException {
        Files.createDirectory(home.resolve("config.json"));
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            assertFalse(service.enabled("modelUpdateSkip"));
        }
    }

    @Test
    void nonBooleanPersistedValueDoesNotReportEnabled() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "optimization-settings-test",
              "pluginDirs": ["plugins"],
              "logLevel": "INFO",
              "safeMode": false,
              "launcher": {"modelUpdateSkip": "off"},
              "hooks": {"disabledIds": [], "denylistedClasses": [], "startup": {}}
            }
            """);
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            assertFalse(service.enabled("modelUpdateSkip"));
        }
    }

    @Test
    void saveFailurePropagatesInsteadOfReportingSuccess() throws Exception {
        try {
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            assertThrows(IllegalStateException.class, () -> service.setEnabled("modelUpdateSkip", true));
            assertFalse(
                    Files.exists(home.resolve("config.json")),
                    "a failed save must not leave a config that reads back as persisted");
        } finally {
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void persistsOneKeyWithoutTouchingTheOthers() {
        try (OptimizationSettingsFileService service = new OptimizationSettingsFileService(home)) {
            service.setEnabled("modelUpdateSkip", false);
            service.setEnabled("atlasCacheReuse", true);
            assertFalse(service.enabled("modelUpdateSkip"), "an explicit opt-out stays off");
            assertTrue(service.enabled("atlasCacheReuse"), "the other key is persisted independently");
        }
    }
}
