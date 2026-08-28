package dev.turboism.preview;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.config.RuntimePluginConfigRegistry;
import dev.turboism.config.RuntimeTypedPluginConfigRegistry;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.preview.report.PreviewReportType;
import dev.turboism.preview.report.PreviewReportValidator;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.storage.RuntimePluginStorage;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.userfile.RuntimeUserFileAccessService;
import dev.turboism.userfile.UserFileGrantSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewRuntimeFailureIntegrationTest {

    private static final String PLUGIN_ID = "dev.example.plugin";

    @TempDir
    Path temporary;

    @Test
    void serviceFailuresArePreservedAcrossInitialAndFinalReports() throws Exception {
        final Path home = temporary.resolve("home");
        final RuntimeFailureCollector collector = new RuntimeFailureCollector();
        final var scheduler = PreviewRuntimeTestSupport.rejectedScheduler();
        final DisposableScope servicesScope = new DisposableScope();
        try {
            triggerServiceFailures(home, scheduler, servicesScope, collector);
            assertEquals(1, collector.snapshot().taskFailures().size());
            assertEquals(2, collector.snapshot().storageFailures().size());
            assertEquals(3, collector.snapshot().configFailures().size());

            assertReportsPreserveFailures(home, scheduler, collector);
        } finally {
            servicesScope.close();
            scheduler.shutdown();
        }
    }

    private static void triggerServiceFailures(
        final Path home,
        final dev.turboism.core.runtime.RuntimeScheduler scheduler,
        final DisposableScope servicesScope,
        final RuntimeFailureCollector collector
    ) throws Exception {
        final RuntimePluginConfigRegistry legacyConfig = legacyConfig(home, scheduler, collector);
        servicesScope.register(legacyConfig);
        final Path pluginData = home.resolve("plugin-data");
        final Path unreadableScope = pluginData.resolve("private/read.properties");
        Files.createDirectories(unreadableScope);
        final Registration readScope = legacyConfig.readScope("private/read.properties");
        assertTrue(legacyConfig.readString("private/read.properties", "private-value").isEmpty());
        readScope.close();

        final Path blockedParent = pluginData.resolve("private/blocker");
        Files.createDirectories(blockedParent.getParent());
        Files.writeString(blockedParent, "not-a-directory");
        final Registration writeScope = legacyConfig.writeScope("private/blocker/config.properties");
        assertThrows(
            dev.turboism.sdk.config.PluginConfigException.class,
            () -> legacyConfig.writeString(
                "private/blocker/config.properties",
                "private-value",
                "private-secret-must-not-leak"
            )
        );
        writeScope.close();

        final CleanupEvidenceCollector cleanupEvidence = new CleanupEvidenceCollector();
        final RuntimePluginTaskScheduler tasks = new RuntimePluginTaskScheduler(
            PLUGIN_ID, scheduler, servicesScope, cleanupEvidence, collector
        );
        final RuntimePluginStorage storage = new RuntimePluginStorage(
            PLUGIN_ID,
            Map.of(
                StorageRoot.DATA, home.resolve("data"),
                StorageRoot.STATE, home.resolve("state-storage"),
                StorageRoot.CACHE, home.resolve("cache")
            ),
            servicePermissions(),
            tasks,
            servicesScope,
            cleanupEvidence,
            collector
        );
        assertEquals("PERMISSION_DENIED", storage.readUtf8(
            new StoragePath(StorageRoot.DATA, "private/read.txt"), 32
        ).toCompletableFuture().get(2, TimeUnit.SECONDS).error().orElseThrow().code().name());

        final RuntimeUserFileAccessService userFiles = new RuntimeUserFileAccessService(
            PLUGIN_ID,
            servicePermissions(),
            UserFileGrantSource.unavailable(),
            tasks,
            servicesScope,
            cleanupEvidence,
            collector
        );
        assertEquals("PERMISSION_DENIED", userFiles.request(new UserFileRequest(
            "private-request",
            "Select a private file",
            List.of("txt"),
            UserFileMode.READ,
            UserFileLifetime.UNTIL_DISABLE
        )).toCompletableFuture().get(2, TimeUnit.SECONDS).error().orElseThrow().code().name());

        final RuntimeTypedPluginConfigRegistry typedConfig = new RuntimeTypedPluginConfigRegistry(
            legacyConfig,
            PLUGIN_ID,
            home.resolve("typed-config"),
            servicePermissions(),
            tasks,
            servicesScope,
            cleanupEvidence,
            collector
        );
        typedConfig.read(new ConfigKey<>(
            "private-config", "enabled", true, ConfigCodecs.booleanValue()
        )).toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertFalse(tasks.submit(new PluginTaskRequest(
            new TaskId("task-1"),
            PluginTaskKind.COMPUTE,
            PluginTaskPriority.NORMAL,
            token -> { }
        )).accepted());
        servicesScope.close();
    }

    private void assertReportsPreserveFailures(
        final Path home,
        final dev.turboism.core.runtime.RuntimeScheduler scheduler,
        final RuntimeFailureCollector collector
    ) throws Exception {
        final HostSession host = new HostSession(java.util.Optional::empty);
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime plugins = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log, collector
            );
            final PreviewRuntime runtime = PreviewRuntimeTestSupport.runtime(home, log, scheduler, plugins);
            PreviewRuntimeTestSupport.writeInitialReports(runtime, HostSession.State.ACTIVE);
            assertReport(home, 1, 2, 3);

            runtime.close();
            assertReport(home, 1, 2, 3);
        } finally {
            host.close();
        }
    }

    private static RuntimePluginConfigRegistry legacyConfig(
        final Path home,
        final dev.turboism.core.runtime.RuntimeScheduler scheduler,
        final RuntimeFailureCollector collector
    ) {
        return new RuntimePluginConfigRegistry(
            (permissionId, operation) -> { },
            scheduler,
            home.resolve("plugin-data"),
            PLUGIN_ID,
            problem -> { },
            collector
        );
    }

    private static Set<String> servicePermissions() {
        return Set.of(
            PermissionIds.TURBOISM_CONFIG_PLUGIN_READ,
            PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE
        );
    }

    private static void assertReport(
        final Path home,
        final long taskCount,
        final long storageCount,
        final long configCount
    ) throws Exception {
        final JsonNode report = PreviewReportValidator.validate(Files.readAllBytes(
            home.resolve("state").resolve(PreviewReportType.PREVIEW_RUNTIME.fileName())
        )).document();
        final JsonNode payload = report.path("payload");
        assertFailureCount(payload.path("taskFailures"), taskCount);
        assertFailureCount(payload.path("storageFailures"), storageCount);
        assertFailureCount(payload.path("configFailures"), configCount);
        assertFalse(report.toString().contains("private/read.txt"));
        assertFalse(report.toString().contains("private/config"));
        assertFalse(report.toString().contains("private-secret-must-not-leak"));
        assertTrue(report.toString().contains("TASK_REJECTED_POLICY_REJECTED"));
    }

    private static void assertFailureCount(final JsonNode failures, final long expectedCount) {
        assertEquals(
            expectedCount,
            java.util.stream.StreamSupport.stream(failures.spliterator(), false)
                .mapToLong(failure -> failure.path("count").longValue())
                .sum()
        );
    }
}
