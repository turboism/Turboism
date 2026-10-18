package dev.turboism.plugin.parameterlock;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.test.storage.InMemoryPluginStorage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParameterLockStorageTest {

    private ExecutorService executor;
    private InMemoryPluginStorage storage;
    private NoopLogger logger;
    private ParameterLockStorage lockStorage;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        storage = new InMemoryPluginStorage();
        logger = new NoopLogger();
        lockStorage = new ParameterLockStorage(storage, logger, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void roundTripSaveAndLoad() throws Exception {
        final String guid = "model-guid-456";
        final ParameterLockData data = ParameterLockData.of(guid, Set.of("PARAM_A", "PARAM_B"));

        final Boolean saved = lockStorage.save(data).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(saved);

        final ParameterLockData loaded =
                lockStorage.load(guid).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(loaded);
        assertEquals(guid, loaded.modelGuid());
        assertEquals(Set.of("PARAM_A", "PARAM_B"), loaded.lockedParameterIds());
        assertEquals(ParameterLockData.CURRENT_VERSION, loaded.version());
    }

    @Test
    void storagePathUsesSha256Hex() {
        final String guid = "sample-model-guid";
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);

        assertEquals(StorageRoot.STATE, path.root());
        assertTrue(path.relativePath().startsWith("locks/"));
        assertTrue(path.relativePath().endsWith(".json"));

        final String hash = ParameterLockStorage.sha256Hex(guid);
        assertEquals("locks/" + hash + ".json", path.relativePath());
    }

    @Test
    void missingFileReturnsEmptyDataWithoutError() throws Exception {
        final ParameterLockData result =
                lockStorage.load("non-existent-guid").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals("non-existent-guid", result.modelGuid());
        assertTrue(result.lockedParameterIds().isEmpty());
    }

    @Test
    void malformedJsonReturnsEmptyDataAndLogsWarning() throws Exception {
        final String guid = "corrupted-model";
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);
        storage.seed(path, "{ this is not valid json");

        final ParameterLockData result =
                lockStorage.load(guid).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(guid, result.modelGuid());
        assertTrue(result.lockedParameterIds().isEmpty());
        assertTrue(logger.warnings.stream().anyMatch(w -> w.contains("Malformed parameter lock file")));
    }

    @Test
    void mismatchedGuidInFileIsSafelyRejected() throws Exception {
        final String guid = "model-target";
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);
        final String json =
                "{\"version\":1,\"modelGuid\":\"different-guid\",\"lockedParameterIds\":[\"PARAM_1\"],\"savedAt\":\"2025-01-01T00:00:00Z\"}";
        storage.seed(path, json);

        final ParameterLockData result =
                lockStorage.load(guid).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.lockedParameterIds().isEmpty());
        assertTrue(logger.warnings.stream().anyMatch(w -> w.contains("Model GUID mismatch")));
    }

    @Test
    void unsupportedSchemaVersionIsSafelyRejected() throws Exception {
        final String guid = "model-future";
        final StoragePath path = ParameterLockStorage.storagePathFor(guid);
        final String json =
                "{\"version\":999,\"modelGuid\":\"model-future\",\"lockedParameterIds\":[\"PARAM_1\"],\"savedAt\":\"2025-01-01T00:00:00Z\"}";
        storage.seed(path, json);

        final ParameterLockData result =
                lockStorage.load(guid).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertTrue(result.lockedParameterIds().isEmpty());
        assertTrue(logger.warnings.stream().anyMatch(w -> w.contains("Unsupported parameter lock schema version")));
    }

    @Test
    void sequentialSavesAreStrictlySerialized() throws Exception {
        final String guid = "serialized-model";
        final List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            final ParameterLockData data = ParameterLockData.of(guid, Set.of("PARAM_" + i));
            futures.add(lockStorage.save(data).toCompletableFuture());
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

        // After all saves complete in strict order, the final file must contain the latest write (PARAM_20)
        final ParameterLockData loaded =
                lockStorage.load(guid).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(loaded);
        assertEquals(Set.of("PARAM_20"), loaded.lockedParameterIds());
    }

    // ── Test Fakes ──

    static final class NoopLogger implements PluginLogger {
        final List<String> warnings = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {
            warnings.add(message);
        }

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }
}
