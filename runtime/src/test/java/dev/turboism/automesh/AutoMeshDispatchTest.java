package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshCompute;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshComputeException;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
import dev.turboism.test.fake.FakeDrawable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Contracts of the custom auto-mesh dispatch: one transaction, all-or-nothing writes, isolation. */
class AutoMeshDispatchTest {

    private static final String COMPUTE_THREAD = "automesh-test-compute";
    private static final String UI_THREAD = "automesh-test-ui";
    private static final String PLUGIN_ID = "plugin.test";
    private static final long PLUGIN_GENERATION = 1L;
    private static final long AWAIT_SECONDS = 5L;

    /** Algorithm sleep that outlasts the over-budget timeout of the matching test. */
    private static final Duration OVER_BUDGET_SLEEP = Duration.ofSeconds(5);

    private static final AlgorithmParameterDescriptor.DoubleParameter SCALE =
            new AlgorithmParameterDescriptor.DoubleParameter("scale", "Scale", "", 0.0d, 2.0d, 0.1d, 2, 1.0d);
    private static final AlgorithmPreset FINE = new AlgorithmPreset("Fine", Map.of("scale", "1.2"));

    private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
    private final CountingTransactions transactions = new CountingTransactions();
    private final ExecutorService computeLane =
            Executors.newSingleThreadExecutor(runnable -> thread(runnable, COMPUTE_THREAD));
    private final ExecutorService uiThread = Executors.newSingleThreadExecutor(runnable -> thread(runnable, UI_THREAD));

    private AutoMeshSelectionState selection;

    @BeforeEach
    void setUp() {
        selection = new AutoMeshSelectionState(catalog);
    }

    @AfterEach
    void tearDown() {
        computeLane.shutdownNow();
        uiThread.shutdownNow();
    }

    @Test
    void customRunCommitsOneTransactionAndReplacesEveryTarget() throws Exception {
        final List<AutoMeshRequest> received = new CopyOnWriteArrayList<>();
        select(algorithm((request, cancellation) -> {
            received.add(request);
            return new AutoMeshResult.Success(triangle().withVertexPosition(0, 2.0f, 0.0f), "one");
        }));
        selection.selectPreset(catalog.find("mesh").orElseThrow(), FINE.name());
        final StubDrawable first = new StubDrawable("ArtMesh1");
        final StubDrawable second = new StubDrawable("ArtMesh2");

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(first, second)));

        assertEquals(DispatchResult.Status.APPLIED, result.status());
        assertEquals(List.of(new ArtMeshId("ArtMesh1"), new ArtMeshId("ArtMesh2")), result.writtenTargets());
        assertEquals(1, transactions.executions.get());
        assertEquals(1, first.writes.size());
        assertEquals(1, second.writes.size());
        assertEquals(2, received.size());
        assertEquals(Map.of("scale", "1.2"), received.get(0).parameterValues());
    }

    @Test
    void rejectedResultWritesNothingForAnyTarget() throws Exception {
        select(algorithm(
                (request, cancellation) -> new AutoMeshResult.Rejected("no-fit", "The texture has no free area.")));
        final StubDrawable first = new StubDrawable("ArtMesh1");
        final StubDrawable second = new StubDrawable("ArtMesh2");

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(first, second)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertEquals("The texture has no free area.", result.message());
        assertTrue(result.writtenTargets().isEmpty());
        assertEquals(0, transactions.executions.get());
        assertTrue(first.writes.isEmpty());
        assertTrue(second.writes.isEmpty());
    }

    @Test
    void cancelledResultWritesNothingForAnyTarget() throws Exception {
        select(algorithm((request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE));
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(target)));

        assertEquals(DispatchResult.Status.CANCELLED, result.status());
        assertTrue(result.writtenTargets().isEmpty());
        assertEquals(0, transactions.executions.get());
        assertTrue(target.writes.isEmpty());
    }

    @Test
    void cancelledRunStopsBeforeAnyWrite() throws Exception {
        final CountDownLatch computing = new CountDownLatch(1);
        select(algorithm((request, cancellation) -> {
            computing.countDown();
            while (!cancellation.isCancellationRequested()) {
                Thread.onSpinWait();
            }
            throw new AutoMeshComputeException("stopped");
        }));
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchOutcome.Started started =
                assertInstanceOf(DispatchOutcome.Started.class, dispatch().dispatch(selection, List.of(target)));
        assertTrue(computing.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        started.run().cancel();

        assertEquals(DispatchResult.Status.CANCELLED, awaitOutcome(started).status());
        assertEquals(0, transactions.executions.get());
        assertTrue(target.writes.isEmpty());
    }

    @Test
    void multiKeyformTargetIsRefusedWithItsReasonCode() {
        final RecordingAlgorithm algorithm = new RecordingAlgorithm();
        select(algorithm(algorithm.compute(), false));
        final StubDrawable keyformed = new StubDrawable("ArtMesh1", 2);

        final DispatchOutcome.Refused refused =
                assertInstanceOf(DispatchOutcome.Refused.class, dispatch().dispatch(selection, List.of(keyformed)));

        assertEquals(AutoMeshDispatch.REASON_MULTI_KEYFORM, refused.reasonCode());
        assertEquals(0, algorithm.calls.get());
        assertTrue(keyformed.writes.isEmpty());
    }

    @Test
    void unreadableKeyformStateStillRefusesABoundTarget() {
        final RecordingAlgorithm algorithm = new RecordingAlgorithm();
        select(algorithm(algorithm.compute(), false));
        final StubDrawable bound = new StubDrawable("ArtMesh1");
        bound.authoringSnapshotUnavailable = true;
        bound.bindings.add(binding("ArtMesh1"));

        final DispatchOutcome.Refused refused =
                assertInstanceOf(DispatchOutcome.Refused.class, dispatch().dispatch(selection, List.of(bound)));

        assertEquals(AutoMeshDispatch.REASON_MULTI_KEYFORM, refused.reasonCode());
        assertEquals(0, algorithm.calls.get());
    }

    @Test
    void algorithmThatDeclaresKeyformSupportRunsOnAKeyformedTarget() throws Exception {
        select(algorithm(
                (request, cancellation) -> {
                    assertEquals(2, request.keyformCount());
                    return new AutoMeshResult.Success(triangle(), "");
                },
                true));
        final StubDrawable keyformed = new StubDrawable("ArtMesh1", 2);

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(keyformed)));

        assertEquals(DispatchResult.Status.APPLIED, result.status());
        assertEquals(1, keyformed.writes.size());
    }

    @Test
    void throwingAlgorithmIsIsolatedAsARejection() throws Exception {
        select(algorithm((request, cancellation) -> {
            throw new IllegalStateException("boom");
        }));
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(target)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("boom"));
        assertEquals(0, transactions.executions.get());
        assertTrue(target.writes.isEmpty());
    }

    @Test
    void computeRunsOffTheUiThreadAndTheWriteRunsOnIt() throws Exception {
        final List<String> computeThreads = new CopyOnWriteArrayList<>();
        select(algorithm((request, cancellation) -> {
            computeThreads.add(Thread.currentThread().getName());
            return new AutoMeshResult.Success(triangle(), "");
        }));
        final StubDrawable target = new StubDrawable("ArtMesh1");

        assertEquals(
                DispatchResult.Status.APPLIED,
                await(dispatch().dispatch(selection, List.of(target))).status());

        assertEquals(List.of(COMPUTE_THREAD), computeThreads);
        assertEquals(List.of(UI_THREAD), transactions.threads);
    }

    @Test
    void openNativeUndoGroupRefusesTheRunBeforeCompute() {
        final RecordingAlgorithm algorithm = new RecordingAlgorithm();
        select(algorithm(algorithm.compute(), false));
        final AtomicInteger probeCalls = new AtomicInteger();
        final AutoMeshDispatch dispatch = dispatch(() -> {
            probeCalls.incrementAndGet();
            return true;
        });
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchOutcome.Refused refused =
                assertInstanceOf(DispatchOutcome.Refused.class, dispatch.dispatch(selection, List.of(target)));

        assertEquals(AutoMeshDispatch.REASON_NATIVE_UNDO_OPEN, refused.reasonCode());
        assertEquals(1, probeCalls.get());
        assertEquals(0, algorithm.calls.get());
        assertEquals(0, transactions.executions.get());
    }

    @Test
    void overBudgetAlgorithmIsReportedAsARejectionWithoutWrittenTargets() throws Exception {
        select(algorithm((request, cancellation) -> {
            try {
                Thread.sleep(OVER_BUDGET_SLEEP.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new AutoMeshResult.Success(triangle(), "");
        }));
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch(Duration.ofMillis(50)).dispatch(selection, List.of(target)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertEquals(0, transactions.executions.get());
        assertTrue(target.writes.isEmpty());
    }

    @Test
    void refusedTransactionReportsTheEditorOutcome() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "")));
        transactions.refuse = true;
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(target)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("ROLLED_BACK"));
        assertTrue(result.writtenTargets().isEmpty());
    }

    private void select(final AutoMeshAlgorithm algorithm) {
        catalog.register(algorithm, PLUGIN_ID, PLUGIN_GENERATION, () -> true);
        catalog.select(algorithm.id());
    }

    private AutoMeshDispatch dispatch() {
        return dispatch(Duration.ofSeconds(AWAIT_SECONDS));
    }

    private AutoMeshDispatch dispatch(final Duration timeout) {
        return dispatch(AutoMeshUndoGroupProbe.CLOSED, timeout);
    }

    private AutoMeshDispatch dispatch(final AutoMeshUndoGroupProbe probe) {
        return dispatch(probe, Duration.ofSeconds(AWAIT_SECONDS));
    }

    private AutoMeshDispatch dispatch(final AutoMeshUndoGroupProbe probe, final Duration timeout) {
        return new AutoMeshDispatch(
                new AutoMeshRequestBuilder(),
                new AutoMeshExecutor(computeLane, timeout),
                transactions,
                probe,
                uiThread);
    }

    private static DispatchResult await(final DispatchOutcome outcome) throws Exception {
        return awaitOutcome(assertInstanceOf(DispatchOutcome.Started.class, outcome));
    }

    private static DispatchResult awaitOutcome(final DispatchOutcome.Started started) throws Exception {
        return started.run().completion().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static AutoMeshAlgorithm algorithm(final AutoMeshCompute compute) {
        return algorithm(compute, false);
    }

    private static AutoMeshAlgorithm algorithm(final AutoMeshCompute compute, final boolean supportsMultiKeyform) {
        return new AutoMeshAlgorithm("mesh", "Mesh", "", List.of(SCALE), List.of(FINE), supportsMultiKeyform, compute);
    }

    private static ArtMeshGeometry triangle() {
        return new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2));
    }

    private static ParameterBinding binding(final String artMeshId) {
        return new ParameterBinding(
                ParameterBindingTarget.artMesh(new ArtMeshId(artMeshId)),
                new ParameterId("ParamAngleX"),
                ParameterBindingFamily.KEYFORM_GRID,
                List.of(new ParameterBindingPoint(new ParameterBindingPointId("point-1"), 0.0f)));
    }

    private static Thread thread(final Runnable runnable, final String name) {
        final Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Algorithm double that records how often its compute function ran. */
    private static final class RecordingAlgorithm {

        private final AtomicInteger calls = new AtomicInteger();

        private AutoMeshCompute compute() {
            return (request, cancellation) -> {
                calls.incrementAndGet();
                return new AutoMeshResult.Success(triangle(), "");
            };
        }
    }

    /**
     * Drawable double that records geometry writes and reports its keyform count from a synthetic
     * stored-form snapshot, so the multi-keyform guard is exercised through the real read path.
     */
    private static final class StubDrawable extends FakeDrawable {

        private final ArtMeshGeometry geometry = triangle();
        private final int keyformCount;
        private final List<ArtMeshGeometry> writes = new CopyOnWriteArrayList<>();
        private boolean authoringSnapshotUnavailable;

        private StubDrawable(final String id) {
            this(id, 1);
        }

        private StubDrawable(final String id, final int keyformCount) {
            super(id);
            this.keyformCount = keyformCount;
        }

        @Override
        public ArtMeshGeometry geometry() {
            return geometry;
        }

        @Override
        public Optional<TexturePixels> texturePixels() {
            return Optional.empty();
        }

        @Override
        public ArtMeshAuthoringSnapshot authoringSnapshot() {
            if (authoringSnapshotUnavailable) {
                throw new UnsupportedOperationException("stored authoring read unavailable");
            }
            final List<ArtMeshAuthoringSnapshot.Form> forms = new ArrayList<>(keyformCount);
            for (int index = 0; index < keyformCount; index++) {
                forms.add(new ArtMeshAuthoringSnapshot.Form("form-" + index, geometry.positions(), 0));
            }
            return new ArtMeshAuthoringSnapshot(id(), "source-guid", geometry, List.of(), List.of(), forms, List.of());
        }

        @Override
        public void replaceGeometry(final ArtMeshGeometry replaced) {
            writes.add(replaced);
        }
    }

    /** Transaction double that counts executions and records the thread each callback ran on. */
    private static final class CountingTransactions implements AuthoringTransactionService {

        private final AtomicInteger executions = new AtomicInteger();
        private final List<String> threads = new CopyOnWriteArrayList<>();
        private volatile boolean refuse;

        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            executions.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            final T value;
            try {
                value = work.run();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return refuse
                    ? AuthoringTransactionResult.rolledBack(receipt(options, false), "automesh-test-rolled-back")
                    : AuthoringTransactionResult.committed(value, receipt(options, true));
        }

        private static AuthoringTransactionReceipt receipt(
                final AuthoringTransactionOptions options, final boolean committed) {
            final HistorySnapshot history =
                    new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1L, 1L, 0, List.of(), false, false);
            return new AuthoringTransactionReceipt(
                    "transaction-1",
                    options.label(),
                    history,
                    history,
                    committed ? Optional.of("entry-1") : Optional.empty());
        }
    }
}
