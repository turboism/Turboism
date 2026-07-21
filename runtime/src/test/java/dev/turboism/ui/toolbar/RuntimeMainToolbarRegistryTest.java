package dev.turboism.ui.toolbar;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
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

class RuntimeMainToolbarRegistryTest {

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
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { throw new CubismPermissionException(operation + " denied"); },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When / Then
        CubismPermissionException exception = assertThrows(
            CubismPermissionException.class,
            () -> registry.contribute(contribution("probe.toolbar"))
        );
        assertEquals("ui.main-toolbar.contribute denied", exception.getMessage());
        assertEquals(0, registry.registrationCount());
    }

    @Test
    void contributeWithPermissionReturnsRegistrationAndContributionIsVisible() {
        // Given
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> assertEquals(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE, permissionId),
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When
        Registration registration = registry.contribute(contribution("probe.toolbar"));

        // Then
        assertTrue(registry.isRegistered("probe.toolbar"));
        assertEquals(1, registry.registrationCount());
        registration.close();
    }

    @Test
    void closingRegistrationRemovesContribution() {
        // Given
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );
        Registration registration = registry.contribute(contribution("probe.toolbar"));

        // When
        registration.close();

        // Then
        assertFalse(registry.isRegistered("probe.toolbar"));
        assertEquals(0, registry.registrationCount());
    }

    @Test
    void visibilityUpdateIsDispatchedThroughRuntimeScheduler() throws InterruptedException {
        // Given
        RecordingPolicy policy = new RecordingPolicy();
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(policy),
            PLUGIN_ID
        );

        // When
        registry.contribute(contribution("probe.toolbar"));

        // Then
        assertTrue(policy.dispatched.await(1, TimeUnit.SECONDS));
        PluginTask task = policy.task.get();
        assertEquals("ui.schedule", task.taskType());
        assertEquals(PLUGIN_ID, task.pluginId());
        assertEquals("main toolbar visibility for probe.toolbar", task.payloadDescription());
    }

    @Test
    void labelKeysResolveWithinTheContributingPluginLocalizationContext() throws InterruptedException {
        // Given
        RuntimeScheduler runtimeScheduler = scheduler(new RecordingPolicy());
        RecordingVisibilitySink firstSink = new RecordingVisibilitySink(1);
        RecordingVisibilitySink secondSink = new RecordingVisibilitySink(1);
        RuntimeMainToolbarRegistry first = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            runtimeScheduler,
            "dev.turboism.plugin.first",
            firstSink
        );
        RuntimeMainToolbarRegistry second = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            runtimeScheduler,
            "dev.turboism.plugin.second",
            secondSink
        );
        RecordingLocalization firstLocalization = localization("First label");
        RecordingLocalization secondLocalization = localization("Second label");
        first.bindLocalization(firstLocalization);
        second.bindLocalization(secondLocalization);

        // When
        first.contribute(contribution("first.toolbar"));
        second.contribute(contribution("second.toolbar"));

        // Then
        assertTrue(firstSink.updated.await(1, TimeUnit.SECONDS));
        assertTrue(secondSink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("probe.label"), firstLocalization.textKeys);
        assertEquals(List.of("probe.label"), secondLocalization.textKeys);
        assertEquals(List.of("First label"), firstSink.mainLabels);
        assertEquals(List.of("Second label"), secondSink.mainLabels);
    }

    @Test
    void explicitNoLocalizationLockKeepsTheRawLabelKey() throws InterruptedException {
        // Given
        RecordingVisibilitySink sink = new RecordingVisibilitySink(1);
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID,
            sink
        );
        registry.lockWithoutLocalization();

        // When
        registry.contribute(contribution("probe.toolbar"));

        // Then
        assertTrue(sink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("probe.label"), sink.mainLabels);
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.bindLocalization(localization("Late label"))
        );
        assertEquals("localization ownership is already locked", error.getMessage());
    }

    @Test
    void firstUnboundContributionLocksTheRawFallback() {
        // Given
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );

        // When
        registry.contribute(contribution("probe.toolbar"));

        // Then
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.bindLocalization(localization("Late label"))
        );
        assertEquals("localization ownership is already locked", error.getMessage());
    }

    @Test
    void localizationBindingIsIdempotentOnlyForTheSameInstance() {
        // Given
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(new RecordingPolicy()),
            PLUGIN_ID
        );
        RecordingLocalization localization = localization("Bound label");
        registry.bindLocalization(localization);

        // When / Then
        assertDoesNotThrow(() -> registry.bindLocalization(localization));
        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> registry.bindLocalization(localization("Other label"))
        );
        assertEquals("localization ownership is already locked", error.getMessage());
    }

    @Test
    void visibilitySinkReceivesSnapshotsOnContributeAndClose() throws InterruptedException {
        // Given
        RecordingPolicy policy = new RecordingPolicy();
        RecordingVisibilitySink sink = new RecordingVisibilitySink(2);
        RuntimeMainToolbarRegistry registry = new RuntimeMainToolbarRegistry(
            (permissionId, operation) -> { },
            scheduler(policy),
            PLUGIN_ID,
            sink
        );

        // When
        Registration registration = registry.contribute(contribution("probe.toolbar"));
        registration.close();

        // Then
        assertTrue(sink.updated.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(1, 0), sink.mainContributionCounts);
        assertEquals(List.of(PLUGIN_ID, PLUGIN_ID), sink.pluginIds);
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
        return new RecordingLocalization(Map.of("probe.label", value));
    }

    private static MainToolbarRegistry.MainToolbarContribution contribution(String id) {
        return new MainToolbarRegistry.MainToolbarContribution(
            id,
            "probe.action",
            "probe.label",
            "/probe/icon.png",
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

    private static final class RecordingVisibilitySink implements ToolbarVisibilitySink {
        private final CountDownLatch updated;
        private final List<String> pluginIds = new CopyOnWriteArrayList<>();
        private final List<Integer> mainContributionCounts = new CopyOnWriteArrayList<>();
        private final List<String> mainLabels = new CopyOnWriteArrayList<>();

        private RecordingVisibilitySink(final int expectedUpdates) {
            updated = new CountDownLatch(expectedUpdates);
        }

        @Override
        public void onMainToolbarVisibilityChanged(
            final String pluginId,
            final List<MainToolbarRegistry.MainToolbarContribution> contributions
        ) {
            pluginIds.add(pluginId);
            mainContributionCounts.add(contributions.size());
            contributions.stream()
                .map(MainToolbarRegistry.MainToolbarContribution::labelKey)
                .forEach(mainLabels::add);
            updated.countDown();
        }
    }
}
