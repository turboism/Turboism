package dev.turboism.ui.toolbar;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePaletteToolbarRegistryTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private RuntimeScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void contributeWithoutPermissionThrowsCubismPermissionException() {
        // Given
        RuntimePaletteToolbarRegistry registry = new RuntimePaletteToolbarRegistry(
            (permissionId, operation) -> { throw new CubismPermissionException(operation + " denied"); },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.contribute(contribution("probe.palette.toolbar", "parameters"))
        );
        assertEquals("ui.palette-toolbar.contribute denied", exception.getMessage());
        assertEquals(0, registry.registrationCount());
    }

    @Test
    void contributeWithPermissionReturnsRegistrationAndContributionIsVisible() {
        // Given
        RuntimePaletteToolbarRegistry registry = new RuntimePaletteToolbarRegistry(
            (permissionId, operation) -> assertEquals(PermissionIds.TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE, permissionId),
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When
        Registration registration = registry.contribute(contribution("probe.palette.toolbar", "parameters"));

        // Then
        assertTrue(registry.isRegistered("probe.palette.toolbar"));
        assertEquals(1, registry.registrationCount());
        registration.close();
    }

    @Test
    void closingRegistrationRemovesContribution() {
        // Given
        RuntimePaletteToolbarRegistry registry = new RuntimePaletteToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );
        Registration registration = registry.contribute(contribution("probe.palette.toolbar", "parameters"));

        // When
        registration.close();

        // Then
        assertFalse(registry.isRegistered("probe.palette.toolbar"));
        assertEquals(0, registry.registrationCount());
    }

    @Test
    void visibilityUpdateIsDispatchedThroughRuntimeSchedulerWithPaletteId() throws InterruptedException {
        // Given
        RecordingPolicy policy = new RecordingPolicy();
        RuntimePaletteToolbarRegistry registry = new RuntimePaletteToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(policy),
            PLUGIN_ID
        );

        // When
        registry.contribute(contribution("probe.palette.toolbar", "parameters"));

        // Then
        assertTrue(policy.dispatched.await(1, TimeUnit.SECONDS));
        PluginTask task = policy.task.get();
        assertEquals("ui.schedule", task.taskType());
        assertEquals(PLUGIN_ID, task.pluginId());
        assertEquals("palette toolbar visibility for parameters:probe.palette.toolbar", task.payloadDescription());
    }

    @Test
    void visibilitySinkReceivesSnapshotsOnContributeAndClose() throws InterruptedException {
        // Given
        RecordingPolicy policy = new RecordingPolicy();
        RecordingVisibilitySink sink = new RecordingVisibilitySink(2);
        RuntimePaletteToolbarRegistry registry = new RuntimePaletteToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(policy),
            PLUGIN_ID,
            sink
        );

        // When
        Registration registration = registry.contribute(contribution("probe.palette.toolbar", "parameters"));
        registration.close();

        // Then
        assertTrue(sink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(1, 0), sink.paletteContributionCounts);
        assertEquals(List.of(PLUGIN_ID, PLUGIN_ID), sink.pluginIds);
    }

    private RuntimeScheduler scheduler(RecordingPolicy policy) {
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            policy,
            new PluginExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        return scheduler;
    }

    private static PaletteToolbarRegistry.PaletteToolbarContribution contribution(String id, String paletteId) {
        return new PaletteToolbarRegistry.PaletteToolbarContribution(
            id,
            "probe.action",
            "probe.label",
            "/probe/icon.png",
            paletteId,
            "end",
            100
        );
    }

    private static final class RecordingPolicy implements WorkBudgetPolicy {

        private final CountDownLatch dispatched = new CountDownLatch(1);
        private final AtomicReference<PluginTask> task = new AtomicReference<>();

        @Override
        public WorkBudget classify(PluginTask task) {
            this.task.set(task);
            dispatched.countDown();
            return WorkBudget.LIGHTWEIGHT;
        }
    }

    private static final class RecordingVisibilitySink implements ToolbarVisibilitySink {
        private final CountDownLatch updated;
        private final List<String> pluginIds = new CopyOnWriteArrayList<>();
        private final List<Integer> paletteContributionCounts = new CopyOnWriteArrayList<>();

        private RecordingVisibilitySink(final int expectedUpdates) {
            updated = new CountDownLatch(expectedUpdates);
        }

        @Override
        public void onPaletteToolbarVisibilityChanged(
            final String pluginId,
            final List<PaletteToolbarRegistry.PaletteToolbarContribution> contributions
        ) {
            pluginIds.add(pluginId);
            paletteContributionCounts.add(contributions.size());
            updated.countDown();
        }
    }
}
