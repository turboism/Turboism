package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.automesh.host.AutoMeshFunnel;
import dev.turboism.automesh.host.AutoMeshResolvedTarget;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.test.fake.FakeDrawable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression guard of the native hand-off: the Editor's own generator must run with an untouched
 * model, so the dispatcher resolves no algorithm and issues no SDK read or write.
 */
class AutoMeshDispatchNativePathTest {

    private static final long PLUGIN_GENERATION = 1L;
    private static final String PLUGIN_ID = "plugin.test";

    private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
    private final CountingSession session = new CountingSession(catalog);
    private final FakeAutoMeshWrite write = new FakeAutoMeshWrite();
    private final AtomicInteger probeCalls = new AtomicInteger();
    private final Executor forbiddenLane = runnable -> {
        throw new AssertionError("the native path must not start a compute run");
    };
    private final Executor forbiddenUiThread = runnable -> {
        throw new AssertionError("the native path must not post UI work");
    };

    @Test
    void unselectedSessionRunsTheNativeBodyWithZeroReadsAndZeroLookups() {
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchOutcome outcome = dispatch().dispatch(new AutoMeshSelectionState(session), targetsOf(target));

        assertSame(DispatchOutcome.UNHANDLED, outcome);
        assertEquals(0, session.lookups.get());
        assertEquals(1, session.selectionChecks.get());
        assertEquals(0, target.geometryReads.get());
        assertEquals(0, write.calls());
        assertEquals(0, probeCalls.get());
    }

    @Test
    void explicitNativeSelectionNeverResolvesARegisteredAlgorithm() {
        registerAlgorithm();
        catalog.select(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID);
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchOutcome outcome = dispatch().dispatch(new AutoMeshSelectionState(session), targetsOf(target));

        assertSame(DispatchOutcome.UNHANDLED, outcome);
        assertEquals(0, session.lookups.get());
        assertEquals(0, target.geometryReads.get());
        assertEquals(0, write.calls());
    }

    @Test
    void registeredButUnselectedAlgorithmLeavesTheNativeBodyUntouched() {
        registerAlgorithm();
        final StubDrawable target = new StubDrawable("ArtMesh1");

        final DispatchOutcome outcome = dispatch().dispatch(new AutoMeshSelectionState(session), targetsOf(target));

        assertSame(DispatchOutcome.UNHANDLED, outcome);
        assertTrue(catalog.selectedAlgorithmId().isEmpty());
        assertEquals(0, session.lookups.get());
        assertEquals(0, write.calls());
    }

    private void registerAlgorithm() {
        catalog.register(
                new AutoMeshAlgorithm(
                        "mesh",
                        "Mesh",
                        "",
                        List.of(new AlgorithmParameterDescriptor.DoubleParameter(
                                "scale", "Scale", "", 0.0d, 2.0d, 0.1d, 2, 1.0d)),
                        List.of(),
                        false,
                        (request, cancellation) -> new AutoMeshResult.Success(triangle(), "")),
                PLUGIN_ID,
                PLUGIN_GENERATION,
                () -> true);
    }

    private AutoMeshDispatch dispatch() {
        return new AutoMeshDispatch(
                new AutoMeshRequestBuilder(),
                new AutoMeshExecutor(forbiddenLane),
                write,
                () -> {
                    probeCalls.incrementAndGet();
                    return false;
                },
                forbiddenUiThread);
    }

    private static ArtMeshGeometry triangle() {
        return new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2));
    }

    /** Session view that counts the selection checks and the algorithm lookups it serves. */
    private static final class CountingSession implements AutoMeshSessionAlgorithms {

        private final AutoMeshSessionAlgorithms delegate;
        private final AtomicInteger selectionChecks = new AtomicInteger();
        private final AtomicInteger lookups = new AtomicInteger();

        private CountingSession(final AutoMeshSessionAlgorithms delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<String> selectedAlgorithmId() {
            selectionChecks.incrementAndGet();
            return delegate.selectedAlgorithmId();
        }

        @Override
        public Optional<AutoMeshAlgorithm> find(final String algorithmId) {
            lookups.incrementAndGet();
            return delegate.find(algorithmId);
        }

        @Override
        public void select(final String algorithmId) {
            delegate.select(algorithmId);
        }
    }

    private static List<AutoMeshResolvedTarget> targetsOf(final StubDrawable target) {
        return List.of(FakeAutoMeshWrite.target(AutoMeshFunnel.MODELING_MODE, target));
    }

    /** Drawable double that fails the test when the native path reads or writes it. */
    private static final class StubDrawable extends FakeDrawable {

        private final ArtMeshGeometry source = triangle();
        private final AtomicInteger geometryReads = new AtomicInteger();

        private StubDrawable(final String id) {
            super(id);
        }

        @Override
        public ArtMeshGeometry geometry() {
            geometryReads.incrementAndGet();
            return source;
        }

        @Override
        public void replaceGeometry(final ArtMeshGeometry replaced) {
            throw new AssertionError("the native path must not write geometry");
        }
    }
}
