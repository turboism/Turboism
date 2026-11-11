package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.automesh.host.AutoMeshFunnel;
import dev.turboism.automesh.host.AutoMeshResolvedTarget;
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
import dev.turboism.test.fake.FakeDrawable;
import java.time.Duration;
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

/**
 * Contracts of the custom auto-mesh dispatch: one native-shaped write, all-or-nothing, isolation.
 *
 * <p>The write port is the Editor contract, so every case asserts what the dispatcher asked the
 * Editor to replace instead of how the Editor replaces it. The adapter's own suite proves the
 * editable-mesh sequence.</p>
 */
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

    /** Raster width the texture double publishes. */
    private static final int TEXTURE_WIDTH = 64;

    /** Raster height the texture double publishes. */
    private static final int TEXTURE_HEIGHT = 32;

    /** uv-to-pixel scale the texture double publishes, so the pixels differ from the uvs. */
    private static final double TEXTURE_SCALE = 2.0d;

    private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
    private final FakeAutoMeshWrite write = new FakeAutoMeshWrite();
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
    void customRunWritesEveryTargetAsOneEditorOperation() throws Exception {
        final List<AutoMeshRequest> received = new CopyOnWriteArrayList<>();
        select(algorithm((request, cancellation) -> {
            received.add(request);
            return new AutoMeshResult.Success(triangle().withVertexPosition(0, 2.0f, 0.0f), "one");
        }));
        selection.selectPreset(catalog.find("mesh").orElseThrow(), FINE.name());
        final StubDrawable first = new StubDrawable("ArtMesh1");
        final StubDrawable second = new StubDrawable("ArtMesh2");

        final DispatchResult result = await(dispatch().dispatch(selection, targetsOf(first, second)));

        assertEquals(DispatchResult.Status.APPLIED, result.status());
        assertEquals(List.of(new ArtMeshId("ArtMesh1"), new ArtMeshId("ArtMesh2")), result.writtenTargets());
        assertEquals(1, write.calls(), "one selection is one Editor operation");
        assertEquals(2, received.size());
        assertEquals(Map.of("scale", "1.2"), received.get(0).parameterValues());
    }

    @Test
    void theWriteReceivesTheResolvedTargetsAndEveryLayoutInOrder() throws Exception {
        select(algorithm((request, cancellation) ->
                new AutoMeshResult.Success(request.geometry().withVertexPosition(0, 3.0f, 4.0f), "one")));
        final StubDrawable first = new StubDrawable("ArtMesh1");
        final StubDrawable second = new StubDrawable("ArtMesh2");
        final List<AutoMeshResolvedTarget> targets = targetsOf(first, second);

        await(dispatch().dispatch(selection, targets));

        final AutoMeshWrite<AutoMeshResolvedTarget> requested = write.writes().get(0);
        assertEquals(AutoMeshDispatch.UNDO_LABEL, requested.undoLabel());
        assertEquals(2, requested.targets().size());
        assertSame(targets.get(0), requested.targets().get(0), "the resolved handles reach the write unchanged");
        assertSame(targets.get(1), requested.targets().get(1));
        assertEquals(
                new Point2(3.0f, 4.0f),
                requested.layouts().get(0).geometry().positions().get(0));
        assertEquals(
                new Point2(3.0f, 4.0f),
                requested.layouts().get(1).geometry().positions().get(0));
    }

    @Test
    void everyLayoutCarriesTheTextureItsOwnRequestReceived() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(request.geometry(), "")));
        final StubDrawable textured = new StubDrawable("ArtMesh1");
        textured.texture = Optional.of(texture());
        final StubDrawable plain = new StubDrawable("ArtMesh2");

        await(dispatch().dispatch(selection, targetsOf(textured, plain)));

        final AutoMeshWrite<AutoMeshResolvedTarget> requested = write.writes().get(0);
        final TexturePixels written = requested.layouts().get(0).texture().orElseThrow();
        assertEquals(TEXTURE_WIDTH, written.width());
        assertEquals(TEXTURE_HEIGHT, written.height());
        assertEquals(TEXTURE_SCALE, written.uvToPixel()[0]);
        assertEquals(Optional.empty(), requested.layouts().get(1).texture(), "a geometry-only run carries no texture");
    }

    @Test
    void rejectedResultWritesNothingForAnyTarget() throws Exception {
        select(algorithm(
                (request, cancellation) -> new AutoMeshResult.Rejected("no-fit", "The texture has no free area.")));
        final StubDrawable first = new StubDrawable("ArtMesh1");
        final StubDrawable second = new StubDrawable("ArtMesh2");

        final DispatchResult result = await(dispatch().dispatch(selection, targetsOf(first, second)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertEquals("The texture has no free area.", result.message());
        assertTrue(result.writtenTargets().isEmpty());
        assertEquals(0, write.calls());
    }

    @Test
    void cancelledResultWritesNothingForAnyTarget() throws Exception {
        select(algorithm((request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE));
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch().dispatch(selection, targetsOf(target)));

        assertEquals(DispatchResult.Status.CANCELLED, result.status());
        assertTrue(result.writtenTargets().isEmpty());
        assertEquals(0, write.calls());
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
                assertInstanceOf(DispatchOutcome.Started.class, dispatch().dispatch(selection, targetsOf(target)));
        assertTrue(computing.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        started.run().cancel();

        assertEquals(DispatchResult.Status.CANCELLED, awaitOutcome(started).status());
        assertEquals(0, write.calls());
    }

    @Test
    void refusedWriteReportsTheLocalizedReasonAndWritesNothing() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "")));
        write.refuse = true;
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch().dispatch(selection, targetsOf(target)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertEquals("The Editor refused the write.", result.message());
        assertTrue(result.writtenTargets().isEmpty());
    }

    @Test
    void failingWriteIsReportedAsAFailedRun() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "")));
        write.fail = true;
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch().dispatch(selection, targetsOf(target)));

        assertEquals(DispatchResult.Status.FAILED, result.status());
        assertTrue(result.message().contains("The Editor refused the write."), result.message());
        assertTrue(result.writtenTargets().isEmpty());
    }

    @Test
    void unreadableKeyformStateStillRefusesABoundTarget() {
        final RecordingAlgorithm algorithm = new RecordingAlgorithm();
        select(algorithm(algorithm.compute()));
        final StubDrawable bound = new StubDrawable("ArtMesh1");
        bound.authoringSnapshotUnavailable = true;
        bound.bindings.add(binding("ArtMesh1"));

        final DispatchOutcome.Refused refused =
                assertInstanceOf(DispatchOutcome.Refused.class, dispatch().dispatch(selection, targetsOf(bound)));

        assertEquals(AutoMeshDispatch.REASON_KEYFORM_UNREADABLE, refused.reasonCode());
        assertEquals(0, algorithm.calls.get());
    }

    @Test
    void throwingAlgorithmIsIsolatedAsARejection() throws Exception {
        select(algorithm((request, cancellation) -> {
            throw new IllegalStateException("boom");
        }));
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchResult result = await(dispatch().dispatch(selection, targetsOf(target)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertTrue(result.message().contains("boom"));
        assertEquals(0, write.calls());
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
                await(dispatch().dispatch(selection, targetsOf(target))).status());

        assertEquals(List.of(COMPUTE_THREAD), computeThreads);
        assertEquals(List.of(UI_THREAD), write.threads(), "the write belongs to the Editor UI thread");
    }

    @Test
    void openNativeUndoGroupRefusesTheRunBeforeCompute() {
        final RecordingAlgorithm algorithm = new RecordingAlgorithm();
        select(algorithm(algorithm.compute()));
        final AtomicInteger probeCalls = new AtomicInteger();
        final AutoMeshDispatch<AutoMeshResolvedTarget> dispatch = dispatch(() -> {
            probeCalls.incrementAndGet();
            return true;
        });
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchOutcome.Refused refused =
                assertInstanceOf(DispatchOutcome.Refused.class, dispatch.dispatch(selection, targetsOf(target)));

        assertEquals(AutoMeshDispatch.REASON_NATIVE_UNDO_OPEN, refused.reasonCode());
        assertEquals(1, probeCalls.get());
        assertEquals(0, algorithm.calls.get());
        assertEquals(0, write.calls());
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

        final DispatchResult result = await(dispatch(Duration.ofMillis(50)).dispatch(selection, targetsOf(target)));

        assertEquals(DispatchResult.Status.REJECTED, result.status());
        assertEquals(0, write.calls());
    }

    private void select(final AutoMeshAlgorithm algorithm) {
        catalog.register(algorithm, PLUGIN_ID, PLUGIN_GENERATION, () -> true);
        catalog.select(algorithm.id());
    }

    private AutoMeshDispatch<AutoMeshResolvedTarget> dispatch() {
        return dispatch(Duration.ofSeconds(AWAIT_SECONDS));
    }

    private AutoMeshDispatch<AutoMeshResolvedTarget> dispatch(final Duration timeout) {
        return dispatch(AutoMeshUndoGroupProbe.CLOSED, timeout);
    }

    private AutoMeshDispatch<AutoMeshResolvedTarget> dispatch(final AutoMeshUndoGroupProbe probe) {
        return dispatch(probe, Duration.ofSeconds(AWAIT_SECONDS));
    }

    private AutoMeshDispatch<AutoMeshResolvedTarget> dispatch(
            final AutoMeshUndoGroupProbe probe, final Duration timeout) {
        return new AutoMeshDispatch<AutoMeshResolvedTarget>(
                new AutoMeshRequestBuilder(), new AutoMeshExecutor(computeLane, timeout), write, probe, uiThread);
    }

    private static List<AutoMeshResolvedTarget> targetsOf(final StubDrawable... drawables) {
        return java.util.Arrays.stream(drawables)
                .map(drawable -> FakeAutoMeshWrite.target(AutoMeshFunnel.MESH_EDITOR_MODE, drawable))
                .toList();
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

    /** Texture double whose uv-to-pixel affine scales the uvs, so pixels differ from uvs. */
    private static TexturePixels texture() {
        final double[] uvToPixel = {TEXTURE_SCALE, 0.0d, 0.0d, 0.0d, TEXTURE_SCALE, 0.0d};
        return new TexturePixels(TEXTURE_WIDTH, TEXTURE_HEIGHT, new int[TEXTURE_WIDTH * TEXTURE_HEIGHT], uvToPixel);
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
     * Drawable double that reports one stored form, so a plain target stays single-form and an
     * unreadable snapshot exercises the refusal.
     */
    private static final class StubDrawable extends FakeDrawable {

        private final ArtMeshGeometry geometry = triangle();
        private boolean authoringSnapshotUnavailable;
        private Optional<TexturePixels> texture = Optional.empty();

        private StubDrawable(final String id) {
            super(id);
        }

        @Override
        public ArtMeshGeometry geometry() {
            return geometry;
        }

        @Override
        public Optional<TexturePixels> texturePixels() {
            return texture;
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
    }
}
