package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.automesh.AutoMeshAlgorithmCatalog;
import dev.turboism.automesh.AutoMeshDispatch;
import dev.turboism.automesh.AutoMeshRequestBuilder;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.AutoMeshUndoGroupProbe;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionReceipt;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionWork;
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
    private final CountingTransactions transactions = new CountingTransactions();
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
        assertEquals(NO_ACCESS, transactions.executions.get());
    }

    @Test
    void customRunIsHandledAndCommitsExactlyOneTransaction() throws Exception {
        final StubDrawable target = new StubDrawable("ArtMesh1");
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));

        final Boolean handled =
                handler(new CountingTargets(List.of(target))).handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.TRUE, handled, "a custom run owns the confirmation");
        awaitNotice(AutoMeshNotice.Severity.INFO);
        assertEquals(ONE_ACCESS, transactions.executions.get(), "one Undo entry per confirmation");
        assertEquals(1, target.writes.size());
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
        assertEquals(NO_ACCESS, transactions.executions.get());
    }

    @Test
    void multiKeyformRefusalIsHandledAndReported() {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));

        final Boolean handled = handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1", 2))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.TRUE, handled);
        assertEquals(AutoMeshNotice.Severity.WARNING, notices.get(0).severity());
        assertTrue(notices.get(0).message().contains("ArtMesh1"), notices.get(0).message());
        assertEquals(NO_ACCESS, transactions.executions.get());
    }

    @Test
    void unresolvableElementIsNotHandled() {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));

        final Boolean handled =
                handler(new CountingTargets(Optional.empty())).handle(AutoMeshFunnel.MODELING_MODE, arguments());

        assertEquals(Boolean.FALSE, handled, "an unresolvable selection keeps the native algorithm reachable");
        assertEquals(NO_ACCESS, transactions.executions.get());
        assertTrue(notices.isEmpty());
        assertEquals(ONE_ACCESS, diagnostics.size());
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
        assertEquals(NO_ACCESS, transactions.executions.get());
    }

    @Test
    void throwingStatusSinkDoesNotChangeTheAnswer() {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        final AutoMeshDispatch dispatch = dispatch(new CountingTargets(List.of()));
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
        assertEquals(NO_ACCESS, transactions.executions.get());
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
        assertEquals(NO_ACCESS, transactions.executions.get());
    }

    @Test
    void cancelledRunIsReportedAsAnInfoNotice() throws Exception {
        select(algorithm((request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE));

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        awaitNotice(AutoMeshNotice.Severity.INFO);
        assertEquals(NO_ACCESS, transactions.executions.get());
    }

    @Test
    void refusedTransactionIsReportedAsARejection() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        transactions.refuse = true;

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        awaitNotice(AutoMeshNotice.Severity.WARNING);
        assertEquals(ONE_ACCESS, transactions.executions.get());
    }

    @Test
    void throwingTransactionIsReportedAsAnError() throws Exception {
        select(algorithm((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok")));
        transactions.fail = true;

        handler(new CountingTargets(List.of(new StubDrawable("ArtMesh1"))))
                .handle(AutoMeshFunnel.MODELING_MODE, arguments());

        awaitNotice(AutoMeshNotice.Severity.ERROR);
        assertEquals(ONE_ACCESS, transactions.executions.get());
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

    private AutoMeshDispatch dispatch(final AutoMeshTargetResolver targets) {
        return new AutoMeshDispatch(
                new AutoMeshRequestBuilder(),
                new dev.turboism.automesh.AutoMeshExecutor(computeLane),
                transactions,
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

        private final Optional<List<Drawable>> resolved;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger drawableReads = new AtomicInteger();

        private CountingTargets(final List<Drawable> resolved) {
            this(Optional.of(List.copyOf(resolved)));
        }

        private CountingTargets(final Optional<List<Drawable>> resolved) {
            this.resolved = resolved;
        }

        @Override
        public Optional<List<Drawable>> resolve(final AutoMeshFunnel funnel, final Object[] hostArguments) {
            calls.incrementAndGet();
            resolved.ifPresent(values -> values.forEach(Drawable::id));
            drawableReads.addAndGet(resolved.map(List::size).orElse(0));
            return resolved;
        }
    }

    /** Drawable double that records geometry writes and reports a synthetic stored-form count. */
    private static final class StubDrawable extends FakeDrawable {

        private final ArtMeshGeometry geometry = triangle();
        private final int keyformCount;
        private final List<ArtMeshGeometry> writes = new CopyOnWriteArrayList<>();

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
        private volatile boolean fail;

        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            executions.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            if (fail) {
                throw new IllegalStateException("transaction host unavailable");
            }
            final T value;
            try {
                value = work.run();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            if (refuse) {
                final HistorySnapshot history =
                        new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1L, 1L, 0, List.of(), false, false);
                return AuthoringTransactionResult.rolledBack(
                        new AuthoringTransactionReceipt(
                                "transaction-1", options.label(), history, history, Optional.empty()),
                        "automesh-test-rolled-back");
            }
            return AuthoringTransactionResult.committed(value, receipt(options));
        }

        private static AuthoringTransactionReceipt receipt(final AuthoringTransactionOptions options) {
            final HistorySnapshot history =
                    new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1L, 1L, 0, List.of(), false, false);
            return new AuthoringTransactionReceipt(
                    "transaction-1", options.label(), history, history, Optional.of("entry-1"));
        }
    }
}
