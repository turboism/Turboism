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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void readStringBeforeRegisteringScopeThrows(@TempDir Path dataDir) {
        // Given
        RuntimePluginConfigRegistry registry = registry(dataDir, (permissionId, operation) -> { }, new RecordingPolicy());

        // When / Then
        assertThrows(IllegalStateException.class, () -> registry.readString("probe/config.properties", "name"));
    }

    @Test
    void readStringReturnsEmptyImmediatelyWhenSchedulerRejectsConfigWork(@TempDir Path dataDir) {
        // Given
        List<dev.turboism.core.diagnostics.StartupReport.DiagnosticProblem> diagnostics = new CopyOnWriteArrayList<>();
        RuntimePluginConfigRegistry registry = registry(dataDir, (permissionId, operation) -> { }, task -> WorkBudget.REJECTED, diagnostics);
        Registration scope = registry.readScope("probe/config.properties");

        // When
        Optional<String> value = registry.readString("probe/config.properties", "name");

        // Then
        assertTrue(value.isEmpty());
        assertTrue(diagnostics.stream().anyMatch(problem -> problem.code().equals("CONFIG_READ_REJECTED")));
        scope.close();
    }

    @Test
    void writeStringThrowsStableFailureImmediatelyWhenSchedulerRejectsConfigWork(@TempDir Path dataDir) {
        // Given
        List<dev.turboism.core.diagnostics.StartupReport.DiagnosticProblem> diagnostics = new CopyOnWriteArrayList<>();
        RuntimePluginConfigRegistry registry = registry(dataDir, (permissionId, operation) -> { }, task -> WorkBudget.REJECTED, diagnostics);
        Registration scope = registry.writeScope("probe/config.properties");

        // When / Then
        PluginConfigException exception = assertThrows(
            PluginConfigException.class,
            () -> registry.writeString("probe/config.properties", "name", "Turboism")
        );
        assertEquals(
            "Plugin config write was rejected for probe/config.properties",
            exception.getMessage()
        );
        assertTrue(diagnostics.stream().anyMatch(problem -> problem.code().equals("CONFIG_WRITE_REJECTED")));
        scope.close();
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
            task -> WorkBudget.REJECTED,
            diagnostics
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
    }

    @Test
    void legacyConfigFailuresAreCollectedOnceWithoutExposingScopePaths(
        @TempDir Path dataDir
    ) throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        final RuntimePluginConfigRegistry registry = registry(
            dataDir,
            (permissionId, operation) -> { },
            task -> WorkBudget.REJECTED,
            new CopyOnWriteArrayList<>(),
            failures
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
    }

    @Test
    void writeStringSchedulesThroughRuntimeSchedulerAsHeavyWork(@TempDir Path dataDir) throws InterruptedException, PluginConfigException {
        // Given
        RecordingPolicy policy = new RecordingPolicy();
        RuntimePluginConfigRegistry registry = registry(dataDir, (permissionId, operation) -> { }, policy);
        Registration scope = registry.writeScope("probe/config.properties");

        // When
        registry.writeString("probe/config.properties", "name", "Turboism");

        // Then
        assertTrue(policy.dispatched.await(1, TimeUnit.SECONDS));
        PluginTask task = policy.task.get();
        assertEquals("config.write", task.taskType());
        assertEquals("dev.turboism.plugin.config-test", task.pluginId());
        assertEquals("probe/config.properties", task.payloadDescription());
        scope.close();
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
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            policy,
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            availableSidecar(),
            events::add
        );
        return new RuntimePluginConfigRegistry(
            permissionChecker,
            scheduler,
            dataDir,
            "dev.turboism.plugin.config-test",
            diagnostics::add,
            failures
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
}
