package dev.turboism.plugin.parametersync.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.plugin.parametersync.domain.ParameterSyncData;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.test.storage.InMemoryPluginStorage;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParameterSyncStorageTest {

    private ExecutorService executor;
    private InMemoryPluginStorage storage;
    private NoopLogger logger;
    private ParameterSyncStorage syncStorage;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        storage = new InMemoryPluginStorage();
        logger = new NoopLogger();
        syncStorage = new ParameterSyncStorage(storage, logger, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void roundTripSaveAndLoad() throws Exception {
        final String guid = "model-guid-sync-123";
        final Set<String> ids = Set.of("ParamEyeL", "ParamEyeR", "ParamAngleX");

        final ParameterSyncData data = ParameterSyncData.of(guid, true, ids);

        final Boolean saved = syncStorage.save(data).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(saved);

        final ParameterSyncData loaded =
                syncStorage.load(guid).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(loaded);
        assertEquals(guid, loaded.modelGuid());
        assertTrue(loaded.globalEnabled());
        assertEquals(3, loaded.synchronizedParameterIds().size());
        assertTrue(loaded.synchronizedParameterIds().contains("ParamEyeL"));
        assertTrue(loaded.synchronizedParameterIds().contains("ParamEyeR"));
        assertTrue(loaded.synchronizedParameterIds().contains("ParamAngleX"));
    }

    @Test
    void loadNonExistentModelReturnsEmpty() throws Exception {
        final ParameterSyncData loaded =
                syncStorage.load("non-existent-guid").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(loaded);
        assertEquals("non-existent-guid", loaded.modelGuid());
        assertTrue(loaded.synchronizedParameterIds().isEmpty());
    }

    @Test
    void storagePathUsesStateRootAndSha256() {
        final StoragePath path = ParameterSyncStorage.storagePathFor("my-guid");
        assertEquals(StorageRoot.STATE, path.root());
        assertTrue(path.relativePath().startsWith("parameter-sync/"));
        assertTrue(path.relativePath().endsWith(".json"));
    }

    private static final class NoopLogger implements PluginLogger {
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
