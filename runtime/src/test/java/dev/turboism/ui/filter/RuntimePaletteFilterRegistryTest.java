package dev.turboism.ui.filter;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePaletteFilterRegistryTest {

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
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { throw new CubismPermissionException(operation + " denied"); },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.contribute(contribution("probe.palette.filter", "PARAMETER"))
        );
        assertEquals("ui.palette-filter.contribute denied", exception.getMessage());
        assertEquals(0, registry.registrationCount());
    }

    @Test
    void contributeWithPermissionReturnsRegistrationAndContributionIsVisible() {
        // Given
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> assertEquals(PermissionIds.TURBOISM_UI_TOOLBAR_PALETTE_CONTRIBUTE, permissionId),
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When
        Registration registration = registry.contribute(contribution("probe.palette.filter", "PARAMETER"));

        // Then
        assertTrue(registry.isRegistered("probe.palette.filter"));
        assertEquals(1, registry.registrationCount());
        registration.close();
    }

    @Test
    void closingRegistrationRemovesContribution() {
        // Given
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );
        Registration registration = registry.contribute(contribution("probe.palette.filter", "PARAMETER"));

        // When
        registration.close();

        // Then
        assertFalse(registry.isRegistered("probe.palette.filter"));
        assertEquals(0, registry.registrationCount());
    }

    @Test
    void visibilityUpdateIsDispatchedThroughRuntimeSchedulerWithPaletteId() throws InterruptedException {
        // Given
        RecordingPolicy policy = new RecordingPolicy();
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(policy),
            PLUGIN_ID
        );

        // When
        registry.contribute(contribution("probe.palette.filter", "PARAMETER"));

        // Then
        assertTrue(policy.dispatched.await(1, TimeUnit.SECONDS));
        PluginTask task = policy.task.get();
        assertEquals("ui.schedule", task.taskType());
        assertEquals(PLUGIN_ID, task.pluginId());
        assertEquals("palette filter visibility for PARAMETER:probe.palette.filter", task.payloadDescription());
    }

    @Test
    void placeholderKeysResolveWithinTheContributingPluginLocalizationContext() throws InterruptedException {
        // Given
        RuntimeScheduler runtimeScheduler = scheduler(new RecordingPolicy());
        RecordingVisibilitySink firstSink = new RecordingVisibilitySink(1);
        RecordingVisibilitySink secondSink = new RecordingVisibilitySink(1);
        RuntimePaletteFilterRegistry first = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            runtimeScheduler,
            "dev.turboism.plugin.first",
            firstSink
        );
        RuntimePaletteFilterRegistry second = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            runtimeScheduler,
            "dev.turboism.plugin.second",
            secondSink
        );
        RecordingLocalization firstLocalization = localization("First palette placeholder");
        RecordingLocalization secondLocalization = localization("Second palette placeholder");
        first.bindLocalization(firstLocalization);
        second.bindLocalization(secondLocalization);

        // When
        first.contribute(contribution("first.palette.filter", "PARAMETER"));
        second.contribute(contribution("second.palette.filter", "SCENE"));

        // Then
        assertTrue(firstSink.updated.await(1, TimeUnit.SECONDS));
        assertTrue(secondSink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("probe.placeholder"), firstLocalization.textKeys);
        assertEquals(List.of("probe.placeholder"), secondLocalization.textKeys);
        assertEquals(List.of("First palette placeholder"), firstSink.palettePlaceholders);
        assertEquals(List.of("Second palette placeholder"), secondSink.palettePlaceholders);
    }

    @Test
    void explicitNoLocalizationLockKeepsTheRawPlaceholderKey() throws InterruptedException {
        // Given
        RecordingVisibilitySink sink = new RecordingVisibilitySink(1);
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID,
            sink
        );
        registry.lockWithoutLocalization();

        // When
        registry.contribute(contribution("probe.palette.filter", "PARAMETER"));

        // Then
        assertTrue(sink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("probe.placeholder"), sink.palettePlaceholders);
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.bindLocalization(localization("Late placeholder"))
        );
        assertEquals("localization ownership is already locked", error.getMessage());
    }

    @Test
    void firstUnboundContributionLocksTheRawFallback() {
        // Given
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When
        registry.contribute(contribution("probe.palette.filter", "PARAMETER"));

        // Then
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.bindLocalization(localization("Late placeholder"))
        );
        assertEquals("localization ownership is already locked", error.getMessage());
    }

    @Test
    void localizationBindingIsIdempotentOnlyForTheSameInstance() {
        // Given
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );
        RecordingLocalization localization = localization("Bound placeholder");
        registry.bindLocalization(localization);

        // When / Then
        assertDoesNotThrow(() -> registry.bindLocalization(localization));
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.bindLocalization(localization("Other placeholder"))
        );
        assertEquals("localization ownership is already locked", error.getMessage());
    }

    @Test
    void visibilitySinkReceivesSnapshotsOnContributeAndClose() throws InterruptedException {
        // Given
        RecordingPolicy policy = new RecordingPolicy();
        RecordingVisibilitySink sink = new RecordingVisibilitySink(2);
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(policy),
            PLUGIN_ID,
            sink
        );

        // When
        Registration registration = registry.contribute(contribution("probe.palette.filter", "PARAMETER"));
        registration.close();

        // Then
        assertTrue(sink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(1, 0), sink.paletteContributionCounts);
        assertEquals(List.of(PLUGIN_ID, PLUGIN_ID), sink.pluginIds);
    }

    @Test
    void bindingVisibilitySinkAfterConstructionForwardsSnapshots() throws InterruptedException {
        // Given
        RecordingVisibilitySink sink = new RecordingVisibilitySink(1);
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );
        registry.bindVisibilitySink(sink);

        // When
        registry.contribute(contribution("probe.palette.filter", "LOG"));

        // Then
        assertTrue(sink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("LOG"), sink.paletteIds);
    }

    @Test
    void bindingVisibilitySinkTwiceFails() {
        // Given
        RuntimePaletteFilterRegistry registry = new RuntimePaletteFilterRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID,
            new RecordingVisibilitySink(0)
        );

        // When / Then
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.bindVisibilitySink(new RecordingVisibilitySink(0))
        );
        assertEquals("palette filter visibility sink is already bound", error.getMessage());
    }

    private RuntimeScheduler scheduler(RecordingPolicy policy) {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            policy,
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        return scheduler;
    }

    private static RecordingLocalization localization(final String value) {
        return new RecordingLocalization(Map.of("probe.placeholder", value));
    }

    private static PaletteFilterRegistry.PaletteFilterContribution contribution(String id, String paletteId) {
        return new PaletteFilterRegistry.PaletteFilterContribution(id, paletteId, "probe.placeholder", 100);
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

    private static final class RecordingLocalization implements PluginLocalization {
        private final Map<String, String> catalog;
        private final List<String> textKeys = new CopyOnWriteArrayList<>();

        private RecordingLocalization(final Map<String, String> catalog) {
            this.catalog = Map.copyOf(catalog);
        }

        @Override public Locale locale() { return Locale.ENGLISH; }

        @Override
        public String text(final String key) {
            textKeys.add(key);
            return catalog.getOrDefault(key, key);
        }

        @Override public String format(final String key, final Object... arguments) { return text(key); }
        @Override public boolean contains(final String key) { return catalog.containsKey(key); }
    }

    private static final class RecordingVisibilitySink implements PaletteFilterVisibilitySink {
        private final CountDownLatch updated;
        private final List<String> pluginIds = new CopyOnWriteArrayList<>();
        private final List<Integer> paletteContributionCounts = new CopyOnWriteArrayList<>();
        private final List<String> palettePlaceholders = new CopyOnWriteArrayList<>();
        private final List<String> paletteIds = new CopyOnWriteArrayList<>();

        private RecordingVisibilitySink(final int expectedUpdates) {
            updated = new CountDownLatch(expectedUpdates);
        }

        @Override
        public void onPaletteFilterVisibilityChanged(
            final String pluginId,
            final List<PaletteFilterRegistry.PaletteFilterContribution> contributions
        ) {
            pluginIds.add(pluginId);
            paletteContributionCounts.add(contributions.size());
            contributions.stream()
                .map(PaletteFilterRegistry.PaletteFilterContribution::placeholderKey)
                .forEach(palettePlaceholders::add);
            contributions.stream()
                .map(PaletteFilterRegistry.PaletteFilterContribution::paletteId)
                .forEach(paletteIds::add);
            updated.countDown();
        }
    }
}
