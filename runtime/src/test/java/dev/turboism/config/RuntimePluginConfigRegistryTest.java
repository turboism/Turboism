package dev.turboism.config;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.WorkBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginConfigRegistryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private RuntimeScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void readScopeWithoutPermissionThrowsCubismPermissionException(@TempDir Path dataDir) {
        // Given
        RuntimePluginConfigRegistry registry = registry(dataDir, denied(), new RecordingPolicy());

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.readScope("probe/config.properties")
        );
        assertEquals("config.readScope denied", exception.getMessage());
    }

    @Test
    void writeScopeWithoutPermissionThrowsCubismPermissionException(@TempDir Path dataDir) {
        // Given
        RuntimePluginConfigRegistry registry = registry(dataDir, denied(), new RecordingPolicy());

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.writeScope("probe/config.properties")
        );
        assertEquals("config.writeScope denied", exception.getMessage());
    }

    @Test
    void sandboxPathWithParentSegmentIsRejected(@TempDir Path dataDir) {
        // Given
        RuntimePluginConfigRegistry registry = registry(dataDir, (permissionId, operation) -> { }, new RecordingPolicy());

        // When / Then
        assertThrows(IllegalArgumentException.class, () -> registry.readScope("../outside.properties"));
    }

    @Test
    void symlinkedConfigPathCannotEscapePluginDirectory(@TempDir Path dataDir) throws Exception {
        final Path outside = Files.createTempDirectory("turboism-config-outside");
        try {
            final Path link = dataDir.resolve("linked");
            try {
                Files.createSymbolicLink(link, outside);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                org.junit.jupiter.api.Assumptions.abort("symbolic links are unavailable");
            }
            final RuntimePluginConfigRegistry registry = registry(
                dataDir,
                (permissionId, operation) -> { },
                new RecordingPolicy()
            );
            final Registration read = registry.readScope("linked/config.properties");
            final Registration write = registry.writeScope("linked/config.properties");

            assertThrows(
                PluginConfigException.class,
                () -> registry.writeString("linked/config.properties", "name", "Turboism")
            );
            assertTrue(registry.readString("linked/config.properties", "name").isEmpty());
            assertFalse(Files.exists(outside.resolve("config.properties")));
            write.close();
            read.close();
            registry.close();
        } finally {
            Files.deleteIfExists(outside.resolve("config.properties"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void readStringBeforeRegisteringScopeThrows(@TempDir Path dataDir) {
        // Given
        RuntimePluginConfigRegistry registry = registry(dataDir, (permissionId, operation) -> { }, new RecordingPolicy());

        // When / Then
        assertThrows(IllegalStateException.class, () -> registry.readString("probe/config.properties", "name"));
    }

    @Test
    void readAndWriteSucceedWhenSchedulerRejectsAllWork(@TempDir Path dataDir)
        throws PluginConfigException {
        final List<dev.turboism.core.diagnostics.StartupReport.DiagnosticProblem> diagnostics =
            new CopyOnWriteArrayList<>();
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            task -> WorkBudget.REJECTED,
            diagnostics
        );
        final Registration read = registry.readScope("probe/config.properties");
        final Registration write = registry.writeScope("probe/config.properties");

        registry.writeString("probe/config.properties", "name", "Turboism");

        assertEquals(
            Optional.of("Turboism"),
            registry.readString("probe/config.properties", "name")
        );
        assertTrue(diagnostics.isEmpty());
        write.close();
        read.close();
        registry.close();
    }

    @Test
    void legacyConfigDoesNotDispatchThroughTheRuntimeScheduler(@TempDir Path dataDir)
        throws PluginConfigException {
        final RecordingPolicy policy = new RecordingPolicy();
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            policy
        );
        final Registration read = registry.readScope("probe/config.properties");
        final Registration write = registry.writeScope("probe/config.properties");

        registry.writeString("probe/config.properties", "name", "Turboism");
        assertEquals(
            Optional.of("Turboism"),
            registry.readString("probe/config.properties", "name")
        );

        assertEquals(1L, policy.dispatched.getCount());
        assertEquals(null, policy.task.get());
        write.close();
        read.close();
        registry.close();
    }

    @Test
    void diagnosticsUseFixedRedactedLocationAndMessageWithoutScopeOrExceptionText(
        @TempDir Path dataDir
    ) {
        final List<dev.turboism.core.diagnostics.StartupReport.DiagnosticProblem> diagnostics =
            new CopyOnWriteArrayList<>();
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            new RecordingPolicy(),
            diagnostics,
            new RuntimeFailureCollector(),
            new RejectingExecutor()
        );
        final String secretScope = "SECRET/legacy.properties";
        final Registration readScope = registry.readScope(secretScope);

        assertTrue(registry.readString(secretScope, "SECRET").isEmpty());

        final var diagnostic = diagnostics.get(0);
        assertEquals("CONFIG_READ_REJECTED", diagnostic.code());
        assertEquals("Plugin config read failed safely.", diagnostic.message());
        assertEquals("config://<redacted>", diagnostic.path());
        final String serialized = diagnostic.toString();
        assertFalse(serialized.contains("SECRET"));
        assertFalse(serialized.contains("legacy.properties"));
        assertFalse(serialized.contains(dataDir.toString()));
        readScope.close();
        registry.close();
    }

    @Test
    void legacyConfigFailuresAreCollectedOnceWithoutExposingScopePaths(
        @TempDir Path dataDir
    ) throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            new RecordingPolicy(),
            new CopyOnWriteArrayList<>(),
            failures,
            new RejectingExecutor()
        );
        final Registration scope = registry.readScope("private/C:/Users/secret.properties");

        assertTrue(registry.readString(
            "private/C:/Users/secret.properties",
            "private-value"
        ).isEmpty());

        final var collected = failures.snapshot().configFailures();
        assertEquals(1, collected.size());
        assertEquals("CONFIG_READ_REJECTED", collected.get(0).code());
        assertEquals("config.readString", collected.get(0).operationId());
        assertEquals(null, collected.get(0).relativePath());
        assertFalse(collected.get(0).message().contains("Users"));
        scope.close();
        registry.close();
    }

    @Test
    void legacyConfigCompletesInsideSinglePluginWorker(@TempDir Path dataDir) throws Exception {
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            task -> WorkBudget.LIGHTWEIGHT
        );
        final Registration read = registry.readScope("probe/config.properties");
        final Registration write = registry.writeScope("probe/config.properties");
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        scheduler.dispatch(new PluginTask(
            "action.handle",
            "dev.turboism.plugin.config-test",
            "config round trip",
            "none"
        ), () -> {
            try {
                registry.writeString("probe/config.properties", "name", "Turboism");
                assertEquals(
                    Optional.of("Turboism"),
                    registry.readString("probe/config.properties", "name")
                );
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(null, failure.get());
        write.close();
        read.close();
        registry.close();
    }

    @Test
    void writeAfterCloseIsRejectedWithoutCreatingAFile(@TempDir Path dataDir) {
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            new RecordingPolicy()
        );
        final Registration scope = registry.writeScope("probe/config.properties");
        registry.close();

        assertThrows(
            IllegalStateException.class,
            () -> registry.writeString("probe/config.properties", "name", "Turboism")
        );
        assertFalse(Files.exists(dataDir.resolve("probe/config.properties")));
        scope.close();
    }

    @Test
    void scopesCannotBeOpenedAfterRegistryClose(@TempDir Path dataDir) {
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            new RecordingPolicy()
        );
        registry.close();

        assertThrows(
            IllegalStateException.class,
            () -> registry.readScope("probe/config.properties")
        );
        assertThrows(
            IllegalStateException.class,
            () -> registry.writeScope("probe/config.properties")
        );
    }

    private RuntimePluginConfigRegistry registry(
        Path dataDir,
        dev.turboism.permissions.PermissionChecker permissionChecker,
        RecordingPolicy policy
    ) {
        return registry(dataDir, permissionChecker, policy, new CopyOnWriteArrayList<>());
    }

    private RuntimePluginConfigRegistry registry(
        Path dataDir,
        dev.turboism.permissions.PermissionChecker permissionChecker,
        WorkBudgetPolicy policy
    ) {
        return registry(dataDir, permissionChecker, policy, new CopyOnWriteArrayList<>());
    }

    private RuntimePluginConfigRegistry registry(
        Path dataDir,
        dev.turboism.permissions.PermissionChecker permissionChecker,
        WorkBudgetPolicy policy,
        List<dev.turboism.core.diagnostics.StartupReport.DiagnosticProblem> diagnostics
    ) {
        return registry(
            dataDir,
            permissionChecker,
            policy,
            diagnostics,
            new RuntimeFailureCollector()
        );
    }

    private RuntimePluginConfigRegistry registry(
        Path dataDir,
        dev.turboism.permissions.PermissionChecker permissionChecker,
        WorkBudgetPolicy policy,
        List<dev.turboism.core.diagnostics.StartupReport.DiagnosticProblem> diagnostics,
        RuntimeFailureCollector failures
    ) {
        return registry(dataDir, permissionChecker, policy, diagnostics, failures, null);
    }

    private RuntimePluginConfigRegistry registry(
        Path dataDir,
        dev.turboism.permissions.PermissionChecker permissionChecker,
        WorkBudgetPolicy policy,
        List<dev.turboism.core.diagnostics.StartupReport.DiagnosticProblem> diagnostics,
        RuntimeFailureCollector failures,
        java.util.concurrent.ExecutorService io
    ) {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            policy,
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            availableSidecar(),
            events::add
        );
        return io == null
            ? new RuntimePluginConfigRegistry(
                permissionChecker,
                scheduler,
                dataDir,
                "dev.turboism.plugin.config-test",
                diagnostics::add,
                failures
            )
            : new RuntimePluginConfigRegistry(
                permissionChecker,
                scheduler,
                dataDir,
                "dev.turboism.plugin.config-test",
                diagnostics::add,
                failures,
                io
            );
    }


    private static SidecarDispatcher availableSidecar() {
        return (task, callback) -> {
            callback.run();
            return java.util.concurrent.CompletableFuture.completedFuture(
                dev.turboism.core.runtime.sidecar.SidecarResult.success("")
            );
        };
    }

    private static dev.turboism.permissions.PermissionChecker denied() {
        return (permissionId, operation) -> { throw new CubismPermissionException(operation + " denied"); };
    }

    private static final class RecordingPolicy implements WorkBudgetPolicy {

        private final CountDownLatch dispatched = new CountDownLatch(1);
        private final AtomicReference<PluginTask> task = new AtomicReference<>();

        @Override
        public WorkBudget classify(PluginTask task) {
            this.task.set(task);
            dispatched.countDown();
            return WorkBudget.HEAVY;
        }
    }

    private static final class RejectingExecutor extends AbstractExecutorService {

        private final AtomicBoolean shutdown = new AtomicBoolean();

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit) {
            return shutdown.get();
        }

        @Override
        public void execute(final Runnable command) {
            throw new RejectedExecutionException("test rejection");
        }
    }
}
