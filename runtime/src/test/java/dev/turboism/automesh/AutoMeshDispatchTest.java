package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder;
import dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions;
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
import java.util.LinkedHashMap;
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

    /** Side of the square every stored bank form covers in its own position space. */
    private static final float FORM_SIZE = 100.0f;

    /** Offset one stored form shifts per unit of its parameter key. */
    private static final float FORM_OFFSET_PER_KEY = 50.0f;

    /** Bounds tolerance far below one drawn pixel; stored positions are 32-bit floats. */
    private static final float BOUNDS_TOLERANCE = 1.0e-3f;

    /** Areas at or below this value mean all three vertices share one line. */
    private static final float MINIMUM_TRIANGLE_AREA = 0.0f;

    /** Vertices one triangle names; every triangle block consumes this many indices. */
    private static final int TRIANGLE_VERTICES = 3;

    /** Draw order the Editor adds to every stored form when it rebuilds the source topology. */
    private static final int TOPOLOGY_DRAW_ORDER_SHIFT = 7;

    /** Vertex count of the plugin layout; it differs from the source quad on purpose. */
    private static final int LAYOUT_VERTEX_COUNT = 9;

    private static final List<Float> BANK_KEYS = List.of(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f);
    private static final String PARAMETER_GUID = "guid-angle-x";
    private static final String DEFAULT_FORM_GUID = "form-2";

    private static final List<Point2> LAYOUT_UVS = List.of(
            new Point2(0.0f, 0.0f),
            new Point2(0.5f, 0.0f),
            new Point2(1.0f, 0.0f),
            new Point2(0.0f, 0.5f),
            new Point2(0.5f, 0.5f),
            new Point2(1.0f, 0.5f),
            new Point2(0.0f, 1.0f),
            new Point2(0.5f, 1.0f),
            new Point2(1.0f, 1.0f));
    private static final List<Integer> LAYOUT_INDICES =
            List.of(0, 1, 4, 0, 4, 3, 1, 2, 5, 1, 5, 4, 3, 4, 7, 3, 7, 6, 4, 5, 8, 4, 8, 7);

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
    void keyformedRunWritesEveryOrdinaryFormAndOneUndoRevertsThem() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(refinedLayout(), "one")));
        final BankDrawable target = keyformBank("ArtMesh1");
        transactions.participants.add(target);
        final Map<String, List<Point2>> original = target.formPositions();

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(target)));

        assertEquals(DispatchResult.Status.APPLIED, result.status());
        assertEquals(List.of(new ArtMeshId("ArtMesh1")), result.writtenTargets());
        assertEquals(1, transactions.executions.get());
        assertEquals(1, target.writes.size());
        assertEquals(1, target.keyformBatches.size());
        assertEquals(BANK_KEYS.size() - 1, target.keyformBatches.get(0).size());
        assertEquals(BANK_KEYS.size(), target.formPositions().size());
        target.formPositions().forEach((formGuid, positions) -> {
            assertEquals(LAYOUT_VERTEX_COUNT, positions.size(), formGuid + " keeps every layout vertex");
            assertInsideBounds(positions, original.get(formGuid), formGuid);
        });

        transactions.undo();

        assertEquals(original, target.formPositions(), "one Undo restores every stored form");
        assertEquals(quad(), target.geometry(), "one Undo restores the previous geometry");
    }

    @Test
    void keyformedBatchIsDerivedAfterTheTopologyWriteThatReshapesEveryForm() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(refinedLayout(), "one")));
        final BankDrawable target = keyformBank("ArtMesh1");
        transactions.participants.add(target);
        final Map<String, List<Point2>> original = target.formPositions();

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(target)));

        assertEquals(DispatchResult.Status.APPLIED, result.status());
        assertEquals(1, target.keyformBatches.size());
        assertFalse(target.batchUsedPreWriteSnapshot, "the batch targets the bank the topology write leaves");
        assertEquals(target.postWriteOrders(), target.formOrders(), "the batch keeps the stored draw orders");
        target.formPositions().forEach((formGuid, positions) -> {
            assertEquals(LAYOUT_VERTEX_COUNT, positions.size(), formGuid + " keeps every layout vertex");
            assertNoCollapsedTriangle(positions, formGuid);
        });

        transactions.undo();

        assertEquals(original, target.formPositions(), "one Undo restores every stored form");
        assertEquals(quad(), target.geometry(), "one Undo restores the previous geometry");
    }

    @Test
    void keyformBankTheTopologyWriteDoesNotKeepIsRefusedAndRolledBack() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(refinedLayout(), "")));
        final BankDrawable target = keyformBank("ArtMesh1");
        target.keepFormVertexCounts = false;
        transactions.participants.add(target);
        final Map<String, List<Point2>> original = target.formPositions();

        final DispatchResult result = await(dispatch().dispatch(selection, List.of(target)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("did not keep every keyform"), result.message());
        assertTrue(result.message().contains("ArtMesh1"), result.message());
        assertTrue(result.writtenTargets().isEmpty());
        assertEquals(1, transactions.executions.get());
        assertTrue(target.keyformBatches.isEmpty(), "no partial batch reaches the forms");
        assertEquals(original, target.formPositions());
        assertEquals(quad(), target.geometry());
    }

    @Test
    void unreadableKeyformStateStillRefusesABoundTarget() {
        final RecordingAlgorithm algorithm = new RecordingAlgorithm();
        select(algorithm(algorithm.compute()));
        final StubDrawable bound = new StubDrawable("ArtMesh1");
        bound.authoringSnapshotUnavailable = true;
        bound.bindings.add(binding("ArtMesh1"));

        final DispatchOutcome.Refused refused =
                assertInstanceOf(DispatchOutcome.Refused.class, dispatch().dispatch(selection, List.of(bound)));

        assertEquals(AutoMeshDispatch.REASON_KEYFORM_UNREADABLE, refused.reasonCode());
        assertEquals(0, algorithm.calls.get());
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
        select(algorithm(algorithm.compute()));
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
        return new AutoMeshAlgorithm("mesh", "Mesh", "", List.of(SCALE), List.of(FINE), false, compute);
    }

    private static ArtMeshGeometry triangle() {
        return new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2));
    }

    /** Default stored form of every bank: one quad over the layout uv square. */
    private static ArtMeshGeometry quad() {
        return new ArtMeshGeometry(square(0.0f), unitSquareUvs(), List.of(0, 1, 2, 0, 2, 3));
    }

    /** Plugin layout: a 3x3 grid inside the source uv square, in the default form's position space. */
    private static ArtMeshGeometry refinedLayout() {
        final List<Point2> positions = LAYOUT_UVS.stream()
                .map(uv -> new Point2(uv.x() * FORM_SIZE, uv.y() * FORM_SIZE))
                .toList();
        return new ArtMeshGeometry(positions, LAYOUT_UVS, LAYOUT_INDICES);
    }

    /** Ordinary keyform bank of five squares, one default form and four deformed forms. */
    private static BankDrawable keyformBank(final String id) {
        final var axes = List.of(new ArtMeshAuthoringSnapshot.Axis(
                PARAMETER_GUID, new ParameterId("ParamAngleX"), -1.0f, 0.0f, 1.0f, BANK_KEYS));
        final List<ArtMeshAuthoringSnapshot.Cell> cells = new ArrayList<>(BANK_KEYS.size());
        final Map<String, List<Point2>> forms = new LinkedHashMap<>();
        final Map<String, Integer> orders = new LinkedHashMap<>();
        for (int index = 0; index < BANK_KEYS.size(); index++) {
            final String formGuid = "form-" + index;
            cells.add(new ArtMeshAuthoringSnapshot.Cell(
                    formGuid, List.of(new ArtMeshAuthoringSnapshot.Coordinate(PARAMETER_GUID, BANK_KEYS.get(index)))));
            forms.put(formGuid, square(BANK_KEYS.get(index)));
            orders.put(formGuid, 500 + index);
        }
        return new BankDrawable(id, axes, cells, forms, orders);
    }

    private static List<Point2> square(final float key) {
        final float offset = key * FORM_OFFSET_PER_KEY;
        return List.of(
                new Point2(offset, offset),
                new Point2(offset + FORM_SIZE, offset),
                new Point2(offset + FORM_SIZE, offset + FORM_SIZE),
                new Point2(offset, offset + FORM_SIZE));
    }

    private static List<Point2> unitSquareUvs() {
        return List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(1.0f, 1.0f), new Point2(0.0f, 1.0f));
    }

    private static void assertInsideBounds(
            final List<Point2> positions, final List<Point2> bounds, final String formGuid) {
        float minimumX = Float.MAX_VALUE;
        float maximumX = -Float.MAX_VALUE;
        float minimumY = Float.MAX_VALUE;
        float maximumY = -Float.MAX_VALUE;
        for (final Point2 point : bounds) {
            minimumX = Math.min(minimumX, point.x());
            maximumX = Math.max(maximumX, point.x());
            minimumY = Math.min(minimumY, point.y());
            maximumY = Math.max(maximumY, point.y());
        }
        for (final Point2 point : positions) {
            assertTrue(
                    point.x() >= minimumX - BOUNDS_TOLERANCE && point.x() <= maximumX + BOUNDS_TOLERANCE,
                    formGuid + " x stays inside its own bounds: " + point);
            assertTrue(
                    point.y() >= minimumY - BOUNDS_TOLERANCE && point.y() <= maximumY + BOUNDS_TOLERANCE,
                    formGuid + " y stays inside its own bounds: " + point);
        }
    }

    /** Asserts that every triangle of the installed layout keeps a non-zero area in one form. */
    private static void assertNoCollapsedTriangle(final List<Point2> positions, final String formGuid) {
        for (int block = 0; block + TRIANGLE_VERTICES <= LAYOUT_INDICES.size(); block += TRIANGLE_VERTICES) {
            final Point2 first = positions.get(LAYOUT_INDICES.get(block));
            final Point2 second = positions.get(LAYOUT_INDICES.get(block + 1));
            final Point2 third = positions.get(LAYOUT_INDICES.get(block + 2));
            final float doubleArea = (second.x() - first.x()) * (third.y() - first.y())
                    - (third.x() - first.x()) * (second.y() - first.y());
            assertTrue(
                    Math.abs(doubleArea) > MINIMUM_TRIANGLE_AREA,
                    formGuid + " triangle " + block / TRIANGLE_VERTICES + " keeps its area: " + doubleArea);
        }
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
     * Drawable double that records geometry writes and reports one stored form, so a plain target
     * keeps the single-form write path and an unreadable snapshot exercises the refusal.
     */
    private static final class StubDrawable extends FakeDrawable {

        private final ArtMeshGeometry geometry = triangle();
        private final List<ArtMeshGeometry> writes = new CopyOnWriteArrayList<>();
        private boolean authoringSnapshotUnavailable;

        private StubDrawable(final String id) {
            super(id);
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
            return new ArtMeshAuthoringSnapshot(
                    id(),
                    "source-guid",
                    geometry,
                    List.of(),
                    List.of(),
                    List.of(new ArtMeshAuthoringSnapshot.Form("form-0", geometry.positions(), 0)),
                    List.of());
        }

        @Override
        public void replaceGeometry(final ArtMeshGeometry replaced) {
            writes.add(replaced);
        }
    }

    /**
     * Drawable double that stores an ordinary keyform bank the way the Editor does: one source
     * topology, one stored position array per form, and a compare-and-set batch write.
     */
    private static final class BankDrawable extends FakeDrawable implements Undoable {

        private final List<ArtMeshAuthoringSnapshot.Axis> axes;
        private final List<ArtMeshAuthoringSnapshot.Cell> cells;
        private final Map<String, List<Point2>> forms = new LinkedHashMap<>();
        private final Map<String, Integer> orders = new LinkedHashMap<>();
        private final List<ArtMeshGeometry> writes = new CopyOnWriteArrayList<>();
        private final List<List<ArtMeshKeyformPositions>> keyformBatches = new CopyOnWriteArrayList<>();
        private List<Point2> sourcePositions = square(0.0f);
        private List<Point2> sourceUvs = unitSquareUvs();
        private List<Integer> sourceIndices = List.of(0, 1, 2, 0, 2, 3);
        private boolean keepFormVertexCounts = true;
        private Map<String, List<Point2>> capturedForms = Map.of();
        private Map<String, Integer> capturedOrders = Map.of();
        private ArtMeshGeometry capturedGeometry;
        private volatile ArtMeshAuthoringSnapshot lastSnapshot;
        private volatile ArtMeshAuthoringSnapshot preWriteSnapshot;
        private volatile Map<String, Integer> postWriteOrders = Map.of();
        private volatile boolean batchUsedPreWriteSnapshot;

        private BankDrawable(
                final String id,
                final List<ArtMeshAuthoringSnapshot.Axis> axes,
                final List<ArtMeshAuthoringSnapshot.Cell> cells,
                final Map<String, List<Point2>> forms,
                final Map<String, Integer> orders) {
            super(id);
            this.axes = axes;
            this.cells = cells;
            this.forms.putAll(forms);
            this.orders.putAll(orders);
        }

        @Override
        public ArtMeshGeometry geometry() {
            return new ArtMeshGeometry(forms.get(DEFAULT_FORM_GUID), sourceUvs, sourceIndices);
        }

        @Override
        public Optional<TexturePixels> texturePixels() {
            return Optional.empty();
        }

        @Override
        public ArtMeshAuthoringSnapshot authoringSnapshot() {
            final ArtMeshAuthoringSnapshot snapshot = new ArtMeshAuthoringSnapshot(
                    id(),
                    "source-guid",
                    new ArtMeshGeometry(sourcePositions, sourceUvs, sourceIndices),
                    axes,
                    cells,
                    forms.entrySet().stream()
                            .map(entry -> new ArtMeshAuthoringSnapshot.Form(
                                    entry.getKey(), entry.getValue(), orders.get(entry.getKey())))
                            .toList(),
                    List.of());
            lastSnapshot = snapshot;
            return snapshot;
        }

        /**
         * Applies a topology write. The Editor replaces the source arrays and the evaluated form,
         * resizes every stored form to the new vertex count, and rebuilds the stored draw orders.
         * Only the state after this write can describe the bank the batch must address, so
         * {@code keepFormVertexCounts = false} models a host that resizes none of the forms.
         */
        @Override
        public void replaceGeometry(final ArtMeshGeometry replaced) {
            writes.add(replaced);
            preWriteSnapshot = lastSnapshot;
            sourcePositions = replaced.positions();
            sourceUvs = replaced.uvs();
            sourceIndices = replaced.triangleIndices();
            forms.put(DEFAULT_FORM_GUID, replaced.positions());
            if (keepFormVertexCounts) {
                forms.replaceAll((formGuid, stored) ->
                        resizedTo(stored, replaced.positions().size()));
            }
            orders.replaceAll((formGuid, order) -> order + TOPOLOGY_DRAW_ORDER_SHIFT);
            postWriteOrders = Map.copyOf(orders);
        }

        /** Stored array the Editor resizes; the added vertices start on one stored point. */
        private static List<Point2> resizedTo(final List<Point2> stored, final int vertexCount) {
            final List<Point2> resized = new ArrayList<>(vertexCount);
            for (int index = 0; index < vertexCount; index++) {
                resized.add(stored.get(Math.min(index, stored.size() - 1)));
            }
            return List.copyOf(resized);
        }

        @Override
        public void setOrdinaryKeyforms(
                final ArtMeshAuthoringSnapshot expected,
                final List<ArtMeshKeyformPositions> positions,
                final List<ArtMeshKeyformDrawOrder> drawOrders) {
            batchUsedPreWriteSnapshot = expected.equals(preWriteSnapshot);
            if (!expected.equals(authoringSnapshot())) {
                throw new IllegalStateException("stale keyform bank");
            }
            keyformBatches.add(List.copyOf(positions));
            for (final ArtMeshKeyformPositions edit : positions) {
                if (edit.formGuid().equals(DEFAULT_FORM_GUID)) {
                    throw new UnsupportedOperationException("default form positions are not editable");
                }
                if (!edit.coordinates().equals(coordinatesOf(edit.formGuid()))) {
                    throw new IllegalArgumentException("keyform tuple mismatch");
                }
                if (edit.positions().size() != sourcePositions.size()) {
                    throw new IllegalArgumentException("keyform vertex count mismatch");
                }
                forms.put(edit.formGuid(), edit.positions());
            }
            for (final ArtMeshKeyformDrawOrder edit : drawOrders) {
                orders.put(edit.formGuid(), edit.drawOrder());
            }
        }

        private List<ArtMeshAuthoringSnapshot.Coordinate> coordinatesOf(final String formGuid) {
            return cells.stream()
                    .filter(cell -> cell.formGuid().equals(formGuid))
                    .findFirst()
                    .orElseThrow()
                    .coordinates();
        }

        private Map<String, List<Point2>> formPositions() {
            return Map.copyOf(forms);
        }

        private Map<String, Integer> formOrders() {
            return Map.copyOf(orders);
        }

        private Map<String, Integer> postWriteOrders() {
            return postWriteOrders;
        }

        @Override
        public void capture() {
            capturedForms = Map.copyOf(forms);
            capturedOrders = Map.copyOf(orders);
            capturedGeometry = geometry();
        }

        @Override
        public void restore() {
            forms.clear();
            forms.putAll(capturedForms);
            orders.clear();
            orders.putAll(capturedOrders);
            final ArtMeshGeometry restored = capturedGeometry;
            sourcePositions = restored.positions();
            sourceUvs = restored.uvs();
            sourceIndices = restored.triangleIndices();
        }
    }

    /** One Editor participant of a transaction: a single Undo entry reverts every captured write. */
    private interface Undoable {

        /** Records the state the one Undo entry must restore. */
        void capture();

        /** Restores the state the last capture recorded. */
        void restore();
    }

    /** Transaction double that counts executions and records the thread each callback ran on. */
    private static final class CountingTransactions implements AuthoringTransactionService {

        private final AtomicInteger executions = new AtomicInteger();
        private final List<String> threads = new CopyOnWriteArrayList<>();
        private final List<Undoable> participants = new CopyOnWriteArrayList<>();
        private volatile boolean refuse;

        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            executions.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            participants.forEach(Undoable::capture);
            final T value;
            try {
                value = work.run();
            } catch (Exception failure) {
                participants.forEach(Undoable::restore);
                throw new IllegalStateException(failure);
            }
            return refuse
                    ? AuthoringTransactionResult.rolledBack(receipt(options, false), "automesh-test-rolled-back")
                    : AuthoringTransactionResult.committed(value, receipt(options, true));
        }

        private void undo() {
            participants.forEach(Undoable::restore);
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
