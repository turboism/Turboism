package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.automesh.AutoMeshAlgorithmCatalog;
import dev.turboism.automesh.AutoMeshDispatch;
import dev.turboism.automesh.AutoMeshRequestBuilder;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.AutoMeshUndoGroupProbe;
import dev.turboism.automesh.FakeAutoMeshWrite;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import dev.turboism.test.fake.FakeDrawable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contracts of the guard policy the transformed funnels report to.
 *
 * <p>Every case pins one answer to the host: "not handled" keeps the Editor's own generator
 * reachable, and "handled" must be justified by a custom run the user actually asked for.</p>
 */
class AutoMeshGuardHandlerTest {

    private static final String COMPUTE_THREAD = "automesh-guard-compute";
    private static final String UI_THREAD = "automesh-guard-ui";
    private static final String PLUGIN_ID = "plugin.test";
    private static final long PLUGIN_GENERATION = 1L;
    private static final long AWAIT_SECONDS = 5L;
    private static final int NO_ACCESS = 0;
    private static final int ONE_ACCESS = 1;

    private static final AlgorithmParameterDescriptor.DoubleParameter SCALE =
            new AlgorithmParameterDescriptor.DoubleParameter("scale", "Scale", "", 0.0d, 2.0d, 0.1d, 2, 1.0d);

    private final AutoMeshAlgorithmCatalog session = new AutoMeshAlgorithmCatalog();
    private final FakeAutoMeshWrite write = new FakeAutoMeshWrite();
    private final List<AutoMeshNotice> notices = new CopyOnWriteArrayList<>();
    private final List<String> diagnostics = new CopyOnWriteArrayList<>();
    private final ExecutorService computeLane =
            Executors.newSingleThreadExecutor(runnable -> thread(runnable, COMPUTE_THREAD));
    private final ExecutorService uiThread = Executors.newSingleThreadExecutor(runnable -> thread(runnable, UI_THREAD));

    @AfterEach
    void tearDown() {
        computeLane.shutdownNow();
        uiThread.shutdownNow();
    }

    @Test
    void nativeSelectionIsNotHandledWithoutTouchingTheResolver() {
        final CountingTargets targets = new CountingTargets(List.of(new StubDrawable("ArtMesh1")));

        assertEquals(Boolean.FALSE, handler(targets).handle(AutoMeshFunnel.MODELING_MODE, arguments()));

        assertEquals(NO_ACCESS, targets.calls.get(), "the native path must resolve no host element");
        assertEquals(NO_ACCESS, targets.drawableReads.get(), "the native path must read no ArtMesh");
        assertTrue(notices.isEmpty(), "the native path reports nothing");
        assertEquals(NO_ACCESS, write.calls());
    }

    @Test
    void customRunIsHandledAndCommitsExactlyOneTransaction() throws Exception {
        final StubDrawable target = new StubDrawable("ArtMesh1");
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));

        final Boolean handled =
                handler(new CountingTargets(List.of(target))).handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.TRUE, handled, "a custom run owns the confirmation");
        awaitNotice(AutoMeshNotice.Severity.INFO);
        assertEquals(ONE_ACCESS, write.calls(), "one Undo entry per confirmation");
        assertTrue(notices.get(0).message().contains("1"), notices.get(0).message());
    }

    @Test
    void refusalIsHandledAndReportedWithoutStartingARun() {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));

        final Boolean handled =
                handler(new CountingTargets(List.of())).handle(AutoMeshFunnel.MESH_EDITOR_MODE, arguments());

        assertEquals(Boolean.TRUE, handled, "the user asked for a custom algorithm, so the native one must not run");
        assertEquals(ONE_ACCESS, notices.size());
        assertEquals(AutoMeshNotice.Severity.WARNING, notices.get(0).severity());
        assertEquals(NO_ACCESS, write.calls());
    }

    @Test
    void keyformedTargetIsWrittenByTheEditorLikeItsOwnRun() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));

        final Boolean handled = handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1", 2))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.TRUE, handled, "the custom algorithm owns the confirmation");
        awaitNotice(AutoMeshNotice.Severity.INFO);
        assertEquals(ONE_ACCESS, write.calls(), "the Editor derives every stored form from the editable mesh");
    }

    @Test
    void keyformBankTheHostCannotReadIsReportedAsAWarning() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        final StubDrawable unreadable = new StubDrawable("ArtMesh1", 2);
        unreadable.unreadableSnapshot = true;
        unreadable.bindings.add(binding("ArtMesh1"));

        final Boolean handled =
                handler(new CountingTargets(List.of(unreadable))).handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.TRUE, handled, "the user asked for a custom algorithm, so the native one must not run");
        awaitNotice(AutoMeshNotice.Severity.WARNING);
        assertTrue(notices.get(0).message().contains("ArtMesh1"), notices.get(0).message());
        assertEquals(NO_ACCESS, write.calls(), "a refused run writes nothing");
    }

    @Test
    void unresolvableElementIsNotHandled() {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));

        final Boolean handled =
                handler(new CountingTargets(Optional.empty())).handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.FALSE, handled, "an unresolvable selection keeps the native algorithm reachable");
        assertEquals(NO_ACCESS, write.calls());
        assertTrue(notices.isEmpty());
        assertEquals(ONE_ACCESS, diagnostics.size());
    }

    @Test
    void targetWithoutEditableMeshKeepsTheNativeAlgorithmReachable() {
        final AtomicInteger computations = new AtomicInteger();
        select(algorithm((request, cancellation) -> {
            computations.incrementAndGet();
            return new AutoMeshResult.Success(triangle(), "ok");
        }));
        final StubDrawable target = new StubDrawable("ArtMesh1");
        final AutoMeshTargetResolver withoutMesh =
                (funnel, hostArguments) -> Optional.of(List.of(FakeAutoMeshWrite.withoutEditableMesh(funnel, target)));

        assertEquals(Boolean.FALSE, handler(withoutMesh).handle(AutoMeshFunnel.MODELING_MODE, arguments()));

        assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("no-editable-mesh")),
                "the fallback must be diagnosable: " + diagnostics);
        assertEquals(0, computations.get(), "the custom path must not start a run");
        assertTrue(notices.isEmpty(), "a structural fallback is not a user-facing refusal");
    }

    @Test
    void throwingResolverIsNotHandledAndReported() {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        final AutoMeshTargetResolver throwing = (funnel, hostArguments) -> {
            throw new IllegalStateException("resolver failed");
        };

        final Boolean handled = handler(throwing).handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.FALSE, handled);
        assertEquals(ONE_ACCESS, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("IllegalStateException"), diagnostics.get(0));
        assertEquals(NO_ACCESS, write.calls());
    }

    @Test
    void throwingStatusSinkDoesNotChangeTheAnswer() {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        final AutoMeshDispatch<AutoMeshResolvedTarget> dispatch = dispatch(new CountingTargets(List.of()));
        final AutoMeshGuardHandler failing = new AutoMeshGuardHandler(
                new AutoMeshSelectionState(session),
                new CountingTargets(List.of()),
                dispatch,
                notice -> {
                    throw new IllegalStateException("status surface unavailable");
                },
                diagnostics::add,
                AutoMeshMessages.forLocale(null));

        final Boolean handled = failing.handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.TRUE, handled, "a missing status surface must not revive the native algorithm");
    }

    @Test
    void rejectedRunIsReportedAsAWarning() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Rejected("no-fit", "The texture is full.")));

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        awaitNotice(AutoMeshNotice.Severity.WARNING);
        assertTrue(
                notices.get(0).message().contains("The texture is full."),
                notices.get(0).message());
        assertEquals(NO_ACCESS, write.calls());
    }

    @Test
    void throwingAlgorithmIsReportedAsARejection() throws Exception {
        select(algorithm((request, cancellation) -> {
            throw new IllegalStateException("boom");
        }));

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        // The executor contains an algorithm fault as a rejection, so the user sees a warning and
        // the Editor keeps the model untouched.
        awaitNotice(AutoMeshNotice.Severity.WARNING);
        assertTrue(notices.get(0).message().contains("boom"), notices.get(0).message());
        assertEquals(NO_ACCESS, write.calls());
    }

    @Test
    void cancelledRunIsReportedAsAnInfoNotice() throws Exception {
        select(algorithm((request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE));

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        awaitNotice(AutoMeshNotice.Severity.INFO);
        assertEquals(NO_ACCESS, write.calls());
    }

    @Test
    void refusedWriteIsReportedAsARejection() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        write.refuse = true;

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        awaitNotice(AutoMeshNotice.Severity.WARNING);
        assertEquals(ONE_ACCESS, write.calls());
    }

    @Test
    void throwingWriteIsReportedAsAnError() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        write.fail = true;

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        awaitNotice(AutoMeshNotice.Severity.ERROR);
        assertEquals(ONE_ACCESS, write.calls());
    }

    // ------------------------------------------------------------------
    // Fixture plumbing
    // ------------------------------------------------------------------

    private AutoMeshGuardHandler handler(final AutoMeshTargetResolver targets) {
        return new AutoMeshGuardHandler(
                new AutoMeshSelectionState(session),
                targets,
                dispatch(targets),
                notices::add,
                diagnostics::add,
                AutoMeshMessages.forLocale(null));
    }

    private AutoMeshDispatch<AutoMeshResolvedTarget> dispatch(final AutoMeshTargetResolver targets) {
        return new AutoMeshDispatch<AutoMeshResolvedTarget>(
                new AutoMeshRequestBuilder(),
                new dev.turboism.automesh.AutoMeshExecutor(computeLane),
                write,
                AutoMeshUndoGroupProbe.CLOSED,
                uiThread);
    }

    private static Object[] arguments() {
        return new Object[] {AutoMeshFunnel.MODELING_MODE.token(), new Object(), new Object(), List.of(), null, null};
    }

    private void select(final AutoMeshAlgorithm algorithm) {
        session.register(algorithm, PLUGIN_ID, PLUGIN_GENERATION, () -> true);
        session.select(algorithm.id());
    }

    private void awaitNotice(final AutoMeshNotice.Severity severity) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            for (final AutoMeshNotice notice : notices) {
                if (notice.severity() == severity) {
                    return;
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("no " + severity + " notice arrived; notices=" + notices);
    }

    private static AutoMeshAlgorithm algorithm(final dev.turboism.sdk.cubism.mesh.automesh.AutoMeshCompute compute) {
        return new AutoMeshAlgorithm("mesh", "Mesh", "", List.of(SCALE), List.of(), false, compute);
    }

    private static ParameterBinding binding(final String artMeshId) {
        return new ParameterBinding(
                ParameterBindingTarget.artMesh(new ArtMeshId(artMeshId)),
                new ParameterId("ParamAngleX"),
                ParameterBindingFamily.KEYFORM_GRID,
                List.of(new ParameterBindingPoint(new ParameterBindingPointId("point-1"), 0.0f)));
    }

    private static ArtMeshGeometry triangle() {
        return new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2));
    }

    private static Thread thread(final Runnable runnable, final String name) {
        final Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Target resolver double that counts how often the guard asks it for the selection. */
    private static final class CountingTargets implements AutoMeshTargetResolver {

        private final Optional<List<AutoMeshResolvedTarget>> resolved;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger drawableReads = new AtomicInteger();

        private CountingTargets(final List<Drawable> resolved) {
            this(Optional.of(resolved.stream()
                    .map(drawable -> FakeAutoMeshWrite.target(AutoMeshFunnel.MODELING_MODE, drawable))
                    .toList()));
        }

        private CountingTargets(final Optional<List<AutoMeshResolvedTarget>> resolved) {
            this.resolved = resolved;
        }

        @Override
        public Optional<List<AutoMeshResolvedTarget>> resolve(
                final AutoMeshFunnel funnel, final Object[] hostArguments) {
            calls.incrementAndGet();
            resolved.ifPresent(
                    values -> values.forEach(target -> target.drawable().id()));
            drawableReads.addAndGet(resolved.map(List::size).orElse(0));
            return resolved;
        }
    }

    /** Drawable double that records geometry writes and reports a synthetic stored-form count. */
    private static final class StubDrawable extends FakeDrawable {

        private final ArtMeshGeometry geometry = triangle();
        private final int keyformCount;
        private boolean unreadableSnapshot;

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
            if (unreadableSnapshot) {
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
            throw new AssertionError("the write port owns the replacement");
        }
    }
}
