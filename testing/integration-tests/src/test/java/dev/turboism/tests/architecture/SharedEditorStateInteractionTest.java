package dev.turboism.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.SelectionObservation;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.service.query.SelectionQueryServiceImpl;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryEntryId;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.test.Stubs;
import dev.turboism.testkit.RecordingHostSnapshotSource;
import dev.turboism.testkit.RuntimeTestKit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies shared host-state invariants (`FR8`/`DP8` and `FR3`/`DP3`): atomic edit-scope exclusion,
 * reverse rollback compensation, model/document generation invalidation, and per-plugin fencing.
 */
class SharedEditorStateInteractionTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
    private static final String PLUGIN_ALPHA = "dev.turboism.test.alpha";
    private static final String PLUGIN_BETA = "dev.turboism.test.beta";
    private static final String DOCUMENT_ID = "doc-shared-1";
    private static final String MODEL_ID = "model-shared-1";
    private static final String OPERATION_ID = "cubism.parameter.set-value";
    private static final String ENTRY_ID_PREFIX = "hist-entry-";
    private static final String MESH_OBJECT_ID = "mesh-eye-l";

    @Test
    void editScopeContentionRejectsCleanlyAndMidTransactionFailureCompensatesInReverseOrder() {
        final AtomicBoolean sharedEditScopeGate = new AtomicBoolean(false);
        final FakeTransactionHost host = new FakeTransactionHost();
        final EditorAuthoringTransactionCoordinator coordinator =
                new EditorAuthoringTransactionCoordinator(host, sharedEditScopeGate);
        final EditorAuthoringTransactionCoordinator.Binding alphaBinding =
                new EditorAuthoringTransactionCoordinator.Binding(
                        PLUGIN_ALPHA, DOCUMENT_ID, 1L, MODEL_ID, 1L, Thread.currentThread());
        final EditorAuthoringTransactionCoordinator.Binding betaBinding =
                new EditorAuthoringTransactionCoordinator.Binding(
                        PLUGIN_BETA, DOCUMENT_ID, 1L, MODEL_ID, 1L, Thread.currentThread());

        sharedEditScopeGate.set(true);
        final AuthoringTransactionResult<Void> contended =
                coordinator.execute(betaBinding, AuthoringTransactionOptions.of("Contended Write"), () -> null);
        assertEquals(AuthoringTransactionOutcome.REJECTED_SCOPE, contended.outcome());
        assertEquals(Optional.of("authoring.edit-scope-conflict"), contended.diagnosticId());
        sharedEditScopeGate.set(false);

        final List<String> log = new ArrayList<>();
        final AtomicBoolean firstApplied = new AtomicBoolean(false);
        final AtomicBoolean secondApplied = new AtomicBoolean(false);
        final AuthoringTransactionResult<Void> rolledBack =
                coordinator.execute(alphaBinding, AuthoringTransactionOptions.of("Grouped Edit"), () -> {
                    coordinator.mutate(
                            alphaBinding,
                            contribution(PLUGIN_ALPHA, "ParamA", firstApplied, () -> log.add("compensate:first")));
                    coordinator.mutate(
                            alphaBinding,
                            contribution(PLUGIN_ALPHA, "ParamB", secondApplied, () -> log.add("compensate:second")));
                    throw new IllegalStateException("simulated mid-transaction failure");
                });

        assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, rolledBack.outcome());
        assertEquals(List.of("compensate:second", "compensate:first"), log);
        assertTrue(!firstApplied.get() && !secondApplied.get(), "all mutations must be restored on rollback");
        assertEquals(0, host.entries.size(), "rolled-back transaction must leave history unchanged");

        final AuthoringTransactionResult<String> committed =
                coordinator.execute(betaBinding, AuthoringTransactionOptions.of("Clean Commit"), () -> {
                    coordinator.mutate(betaBinding, contribution(PLUGIN_BETA, "ParamA", firstApplied, () -> {}));
                    return "ok";
                });
        assertEquals(AuthoringTransactionOutcome.COMMITTED, committed.outcome());
        assertEquals(Optional.of("ok"), committed.value());
        assertEquals(1, host.entries.size());
    }

    @Test
    void advancingModelGenerationAbortsInFlightCommitAndFencingOnePluginLeavesNeighborActive() throws Exception {
        final FakeTransactionHost host = new FakeTransactionHost();
        final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(host);
        final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
                PLUGIN_ALPHA, DOCUMENT_ID, 1L, MODEL_ID, 1L, Thread.currentThread());
        final AtomicBoolean mutated = new AtomicBoolean(false);

        final AuthoringTransactionResult<Void> staleResult =
                coordinator.execute(binding, AuthoringTransactionOptions.of("Generation Race"), () -> {
                    coordinator.mutate(binding, contribution(PLUGIN_ALPHA, "ParamA", mutated, () -> {}));
                    host.modelGeneration.incrementAndGet();
                    return null;
                });
        assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, staleResult.outcome());
        assertTrue(!mutated.get(), "stale generation transition before commit must unwind mutations");

        final RuntimeScheduler scheduler = RuntimeTestKit.scheduler(FIXED_CLOCK, 1, 8);
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final RecordingHostSnapshotSource source = RecordingHostSnapshotSource.builder()
                    .model(new HostSnapshotSource.HostModel(
                            MODEL_ID,
                            "SharedModel",
                            List.of(),
                            List.of(new HostSnapshotSource.HostArtMesh(
                                    MESH_OBJECT_ID, "Eye", Optional.empty(), true, true)),
                            List.of(new HostSnapshotSource.HostDeformer(
                                    "root", "Root", DeformerType.ROOT, Optional.empty(), List.of(MESH_OBJECT_ID)))))
                    .selectedObjectIds(List.of(MESH_OBJECT_ID))
                    .hostPresent(true)
                    .build();

            final AtomicBoolean alphaOpen = new AtomicBoolean(true);
            final AtomicBoolean betaOpen = new AtomicBoolean(true);
            final CubismPermissionGate alphaGate = new CubismPermissionGate(
                    PLUGIN_ALPHA,
                    List.of(Stubs.permission(CubismFacadeImpl.MODEL_READ_PERMISSION, "application", "test")),
                    ignored -> {},
                    FIXED_CLOCK);
            final CubismPermissionGate betaGate = new CubismPermissionGate(
                    PLUGIN_BETA,
                    List.of(Stubs.permission(CubismFacadeImpl.MODEL_READ_PERMISSION, "application", "test")),
                    ignored -> {},
                    FIXED_CLOCK);

            final CubismFacadeImpl alphaFacade = new CubismFacadeImpl(
                    source,
                    alphaGate,
                    CubismModelAccess.unavailable(),
                    new ParameterLifecycleCoordinator(),
                    new PartLifecycleCoordinator(),
                    alphaOpen::get);
            final CubismFacadeImpl betaFacade = new CubismFacadeImpl(
                    source,
                    betaGate,
                    CubismModelAccess.unavailable(),
                    new ParameterLifecycleCoordinator(),
                    new PartLifecycleCoordinator(),
                    betaOpen::get);
            final SelectionQueryServiceImpl betaSelection = new SelectionQueryServiceImpl(
                    betaFacade, betaGate, broker, new AtomicReference<SelectionObservation>(), source);

            assertTrue(alphaFacade.activeModel().isPresent());
            alphaOpen.set(false);
            assertThrows(IllegalStateException.class, alphaFacade::runtime);

            assertTrue(betaFacade.activeModel().isPresent());
            assertEquals(
                    List.of(new ModelObjectId(MESH_OBJECT_ID)),
                    betaSelection.currentSelection().selectedModelObjectIds());
        } finally {
            scheduler.shutdown();
        }
    }

    private static EditorUndoContribution contribution(
            final String pluginId, final String targetId, final AtomicBoolean state, final Runnable extraCompensation) {
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                "Set " + targetId,
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.turboism(pluginId, OPERATION_ID),
                List.of(new HistoryTarget("PARAMETER", Optional.of(targetId), Optional.empty())),
                List.of(HistoryChange.set(0, "value", "0.0", "1.0")),
                Optional.empty(),
                Optional.empty());
        return new EditorUndoContribution(
                OPERATION_ID,
                targetId,
                "Set " + targetId,
                (edit, label) -> {},
                () -> state.set(true),
                state::get,
                () -> {
                    extraCompensation.run();
                    state.set(false);
                },
                () -> !state.get(),
                Set.of(EditorRefreshRequirement.MODEL_INSTANCES),
                detail);
    }

    private static final class FakeTransactionHost implements EditorAuthoringTransactionCoordinator.Host {
        private final AtomicLong documentGeneration = new AtomicLong(1L);
        private final AtomicLong modelGeneration = new AtomicLong(1L);
        private final AtomicLong revision = new AtomicLong(0L);
        private final List<HistoryEntry> entries = new ArrayList<>();
        private String pendingEntryId;
        private String pendingTxId;
        private String pendingLabel;
        private HistoryEntryDetail pendingDetail;
        private Optional<HistoryAction> pendingAction = Optional.empty();

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding binding) {
            return binding.documentIdentity().equals(DOCUMENT_ID)
                    && binding.modelIdentity().equals(MODEL_ID)
                    && binding.documentGeneration() == documentGeneration.get()
                    && binding.modelGeneration() == modelGeneration.get();
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    documentGeneration.get(),
                    revision.get(),
                    entries.size(),
                    List.copyOf(entries),
                    !entries.isEmpty(),
                    false);
        }

        @Override
        public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding binding, final String label) {
            pendingLabel = label;
            return new Object();
        }

        @Override
        public Optional<String> prepareHistoryMetadata(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final Object edit,
                final String transactionId,
                final HistoryEntryDetail detail,
                final Optional<HistoryAction> action) {
            pendingEntryId = ENTRY_ID_PREFIX + (entries.size() + 1);
            pendingTxId = transactionId;
            pendingDetail = detail;
            pendingAction = action;
            return Optional.of(pendingEntryId);
        }

        @Override
        public void endEdit(
                final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit, final boolean abort) {
            if (!abort && pendingEntryId != null) {
                entries.add(new HistoryEntry(
                        entries.size(),
                        pendingLabel,
                        true,
                        pendingAction,
                        Optional.of(new HistoryEntryId(pendingEntryId)),
                        Optional.of(pendingTxId),
                        pendingDetail));
                revision.incrementAndGet();
            }
            pendingEntryId = null;
        }

        @Override
        public void undoEditGroup(final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit) {}

        @Override
        public void refresh(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final Set<EditorRefreshRequirement> requirements) {}

        @Override
        public Optional<String> committedHistoryEntryId(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final HistorySnapshot before,
                final HistorySnapshot after,
                final String transactionId,
                final String label) {
            if (after.entries().isEmpty()) {
                return Optional.empty();
            }
            return after.entries().get(after.entries().size() - 1).entryId().map(HistoryEntryId::value);
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }
}
