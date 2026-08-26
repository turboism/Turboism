package dev.turboism.tests.runtime;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.service.query.SelectionQueryServiceImpl;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionQueryIsolationTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-08T05:00:00Z"), ZoneOffset.UTC);
    private static final int MAX_COALESCED_DISPATCHES = 1;

    @Test
    void currentSelectionReturnsWithoutRunningListenerCallbackInline() throws CubismServiceException {
        // Given
        final MutableSelectionSource source = MutableSelectionSource.withSelection(List.of("param-angle-x"));
        final RecordingSidecarDispatcher dispatcher = new RecordingSidecarDispatcher();
        final SelectionFixture fixture = serviceWith(source, dispatcher);
        final List<SelectionChangedEvent> events = new CopyOnWriteArrayList<>();
        final AtomicReference<String> subscriberThread = new AtomicReference<>();
        final String callerThread = Thread.currentThread().getName();
        fixture.broker().subscribe(
            fixture.observer().key(),
            SelectionChangedEvent.class,
            event -> {
                subscriberThread.set(Thread.currentThread().getName());
                events.add(event);
            }
        );
        fixture.observer().activate();
        fixture.service().currentSelection();
        source.replaceSelection(List.of("mesh-face"));

        // When
        fixture.service().currentSelection();

        // Then
        dispatcher.drain();
        waitFor(() -> events.size() == 1, Duration.ofSeconds(1));
        assertEquals(1, events.size());
        assertNotEquals(callerThread, subscriberThread.get());
        assertEquals(List.of(new ModelObjectId("mesh-face")), events.get(0).currentSelection().selectedModelObjectIds());
    }

    @Test
    void rapidSelectionChangesAreCoalescedAndListenerReceivesLatestSelection() throws CubismServiceException {
        // Given
        final MutableSelectionSource source = MutableSelectionSource.withSelection(List.of("param-angle-x"));
        final RecordingSidecarDispatcher dispatcher = new RecordingSidecarDispatcher();
        final SelectionFixture fixture = serviceWith(source, dispatcher);
        final List<SelectionChangedEvent> events = new CopyOnWriteArrayList<>();
        fixture.broker().subscribe(
            fixture.observer().key(),
            SelectionChangedEvent.class,
            events::add
        );
        fixture.observer().activate();
        fixture.service().currentSelection();

        // When
        for (int index = 0; index < 10; index++) {
            source.replaceSelection(List.of(index == 9 ? "deformer-root" : "mesh-face"));
            fixture.service().currentSelection();
        }

        // Then
        assertTrue(dispatcher.dispatchCount() <= MAX_COALESCED_DISPATCHES);
        dispatcher.drain();
        waitFor(() -> !events.isEmpty(), Duration.ofSeconds(1));
        assertEquals(
            List.of(new ModelObjectId("deformer-root")),
            events.get(events.size() - 1).currentSelection().selectedModelObjectIds()
        );
    }

    private static SelectionFixture serviceWith(
        final HostSnapshotSource source,
        final RecordingSidecarDispatcher dispatcher
    ) {
        final List<CubismFacadeAuditEvent> auditEvents = new ArrayList<>();
        final CubismPermissionGate permissionGate = new CubismPermissionGate(
            "plugin.selection-tests",
            List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION)),
            auditEvents::add,
            FIXED_CLOCK
        );
        final RuntimeScheduler scheduler = new RuntimeScheduler(
            task -> "event.subscribe".equals(task.taskType())
                ? WorkBudget.LIGHTWEIGHT
                : WorkBudget.SIDECAR,
            new PluginWorkExecutorRegistry(1, 2, event -> { }, FIXED_CLOCK),
            dispatcher,
            event -> { }
        );
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observer = broker.admit("plugin.selection-observer");
        return new SelectionFixture(
            new SelectionQueryServiceImpl(
                new CubismFacadeImpl(source, permissionGate),
                permissionGate,
                broker,
                new AtomicReference<SelectionSummary>()
            ),
            broker,
            observer
        );
    }

    private record SelectionFixture(
        SelectionQueryServiceImpl service,
        RuntimeEventBroker broker,
        RuntimeEventBroker.Owner observer
    ) {
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "read"; }
            @Override public String reason() { return "selection isolation test"; }
        };
    }

    private static final class RecordingSidecarDispatcher implements SidecarDispatcher {
        private final List<Runnable> callbacks = new ArrayList<>();

        @Override
        public CompletionStage<SidecarResult> dispatch(final PluginTask task, final Runnable callback) {
            callbacks.add(callback);
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }

        int dispatchCount() {
            return callbacks.size();
        }

        void drain() {
            final List<Runnable> pending = List.copyOf(callbacks);
            callbacks.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static final class MutableSelectionSource implements HostSnapshotSource {
        private static final HostParameter PARAMETER = new HostParameter("param-angle-x", "Angle X", 0.0, 0.0, -30.0, 30.0, true, true);
        private static final HostArtMesh MESH = new HostArtMesh("mesh-face", "Face Mesh", Optional.empty(), true, true);
        private static final HostDeformer DEFORMER = new HostDeformer("deformer-root", "Root", DeformerType.ROOT, Optional.empty(), List.of("mesh-face"));
        private static final HostModel MODEL = new HostModel("model-1", "Model", List.of(PARAMETER), List.of(MESH), List.of(DEFORMER));

        private List<String> selectedObjectIds;
        private long invalidationToken;

        private MutableSelectionSource(final List<String> selectedObjectIds) {
            this.selectedObjectIds = List.copyOf(selectedObjectIds);
        }

        static MutableSelectionSource withSelection(final List<String> selectedObjectIds) {
            return new MutableSelectionSource(selectedObjectIds);
        }

        void replaceSelection(final List<String> nextSelectedObjectIds) {
            selectedObjectIds = List.copyOf(nextSelectedObjectIds);
            invalidationToken++;
        }

        @Override
        public Optional<HostProject> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<HostDocument> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<HostModel> activeModel() {
            return Optional.of(MODEL);
        }

        @Override
        public HostSelection selection() {
            return new HostSelection(selectedObjectIds, Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public boolean isHostPresent() {
            return true;
        }

        @Override
        public long invalidationToken() {
            return invalidationToken;
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition was not satisfied within " + timeout);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", exception);
            }
        }
    }
}
