package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.automesh.AutoMeshAlgorithmCatalog;
import dev.turboism.automesh.AutoMeshDispatch;
import dev.turboism.automesh.AutoMeshExecutor;
import dev.turboism.automesh.AutoMeshRequestBuilder;
import dev.turboism.automesh.AutoMeshSelectionState;
import dev.turboism.automesh.AutoMeshUndoGroupProbe;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
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
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.fake.FakeDrawable;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end routing of one native Auto Mesh Generate confirmation.
 *
 * <p>The guarded {@link FunnelFixture} is transformed by the real transformer, loaded from its own
 * bytes, and invoked through the real {@link NativeAutoMeshBridge}. The bridge therefore hands the
 * real {@link AutoMeshGuardHandler} the same argument array a Cubism funnel would, which is what
 * proves the routing rather than each collaborator in isolation.</p>
 */
class AutoMeshGuardFunnelRoutingTest {

    private static final String OWNER = "dev/turboism/automesh/host/FunnelFixture";
    private static final String OWNER_BINARY_NAME = OWNER.replace('/', '.');
    private static final String MODELING = "updateMeshFromInspector_inModelingMode";
    private static final String MESH_EDITOR = "updateMeshFromInspector_inMeshEditorMode";
    private static final String MODELING_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String MESH_EDITOR_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;)V";
    private static final Class<?>[] MODELING_PARAMETERS = {
        Object.class, Object.class, List.class, Object.class, Object.class
    };
    private static final Class<?>[] MESH_EDITOR_PARAMETERS = {Object.class, Object.class, List.class};
    private static final String PLUGIN_ID = "plugin.test";
    private static final long PLUGIN_GENERATION = 1L;
    private static final long AWAIT_SECONDS = 5L;

    private final AutoMeshAlgorithmCatalog session = new AutoMeshAlgorithmCatalog();
    private final CountingTransactions transactions = new CountingTransactions();
    private final List<AutoMeshNotice> notices = new CopyOnWriteArrayList<>();
    private final List<String> diagnostics = new CopyOnWriteArrayList<>();
    private final ExecutorService computeLane =
            Executors.newSingleThreadExecutor(runnable -> thread(runnable, "automesh-routing-compute"));
    private final ExecutorService uiThread =
            Executors.newSingleThreadExecutor(runnable -> thread(runnable, "automesh-routing-ui"));

    private Registration bridge;

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        computeLane.shutdownNow();
        uiThread.shutdownNow();
    }

    @Test
    void nativeSelectionRunsTheTransformedNativeBody() throws Exception {
        final Object fixture = guardedFixture(List.of(new StubDrawable("ArtMesh1")));

        invoke(fixture, MODELING, new Object[] {new Object(), new Object(), List.of(new Object()), null, null});

        assertEquals(1, counter(fixture, "modelingCalls"), "the native body must run");
        assertEquals(0, transactions.executions.get());
        assertTrue(notices.isEmpty());
    }

    @Test
    void customSelectionSkipsTheNativeBodyAndCommitsOneTransaction() throws Exception {
        final StubDrawable target = new StubDrawable("ArtMesh1");
        select((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok"));
        final Object fixture = guardedFixture(List.of(target));

        invoke(fixture, MODELING, new Object[] {new Object(), new Object(), List.of(new Object()), null, null});

        assertEquals(0, counter(fixture, "modelingCalls"), "a handled confirmation must not reach the native body");
        awaitNotice(AutoMeshNotice.Severity.INFO);
        assertEquals(1, transactions.executions.get());
        assertEquals(1, target.writes.size());
    }

    @Test
    void meshEditorFunnelRoutesThroughTheSameHandler() throws Exception {
        final StubDrawable target = new StubDrawable("ArtMesh1");
        select((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok"));
        final Object fixture = guardedFixture(List.of(target));

        invoke(fixture, MESH_EDITOR, new Object[] {new Object(), new Object(), List.of(new Object())});

        assertEquals(0, counter(fixture, "meshEditorCalls"));
        awaitNotice(AutoMeshNotice.Severity.INFO);
        assertEquals(1, transactions.executions.get());
    }

    @Test
    void unresolvableSelectionRunsTheNativeBodyAndReportsADiagnostic() throws Exception {
        select((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok"));
        final Object fixture = guardedFixture(Optional.empty());

        invoke(fixture, MODELING, new Object[] {new Object(), new Object(), List.of(new Object()), null, null});

        assertEquals(1, counter(fixture, "modelingCalls"), "an unresolvable selection keeps the native algorithm");
        assertEquals(0, transactions.executions.get());
        assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("unresolvable")),
                "the fallback must be diagnosable: " + diagnostics);
    }

    @Test
    void refusalIsHandledAndTheNativeBodyStaysUntouched() throws Exception {
        select((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok"));
        final Object fixture = guardedFixture(List.of());

        invoke(fixture, MODELING, new Object[] {new Object(), new Object(), List.of(), null, null});

        assertEquals(0, counter(fixture, "modelingCalls"));
        assertEquals(1, notices.size());
        assertEquals(AutoMeshNotice.Severity.WARNING, notices.get(0).severity());
        assertEquals(0, transactions.executions.get());
    }

    @Test
    void throwingHandlerRunsTheNativeBodyAndReportsADiagnostic() throws Exception {
        select((request, cancellation) -> new AutoMeshResult.Success(triangle(), "ok"));
        bridge = NativeAutoMeshBridge.install(
                (funnel, hostArguments) -> {
                    throw new IllegalStateException("policy failed");
                },
                diagnostics::add);
        final Object fixture = transformedFixture();

        invoke(fixture, MODELING, new Object[] {new Object(), new Object(), List.of(new Object()), null, null});

        assertEquals(1, counter(fixture, "modelingCalls"));
        assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("IllegalStateException")),
                diagnostics.toString());
    }

    // ------------------------------------------------------------------
    // Fixture plumbing
    // ------------------------------------------------------------------

    private Object guardedFixture(final List<Drawable> resolved) throws Exception {
        return guardedFixture(Optional.of(resolved));
    }

    private Object guardedFixture(final Optional<List<Drawable>> resolved) throws Exception {
        bridge = NativeAutoMeshBridge.install(handler(resolved), diagnostics::add);
        return transformedFixture();
    }

    private AutoMeshGuardHandler handler(final Optional<List<Drawable>> resolved) {
        final AutoMeshDispatch dispatch = new AutoMeshDispatch(
                new AutoMeshRequestBuilder(),
                new AutoMeshExecutor(computeLane),
                transactions,
                AutoMeshUndoGroupProbe.CLOSED,
                uiThread);
        return new AutoMeshGuardHandler(
                new AutoMeshSelectionState(session),
                (funnel, hostArguments) -> resolved,
                dispatch,
                notices::add,
                diagnostics::add,
                AutoMeshMessages.forLocale(null));
    }

    private void select(final dev.turboism.sdk.cubism.mesh.automesh.AutoMeshCompute compute) {
        final AutoMeshAlgorithm algorithm =
                new AutoMeshAlgorithm("mesh", "Mesh", "", List.of(), List.of(), false, compute);
        session.register(algorithm, PLUGIN_ID, PLUGIN_GENERATION, () -> true);
        session.select(algorithm.id());
    }

    private static Object transformedFixture() {
        final GuardedLoader loader = new GuardedLoader();
        final byte[] transformed = transformer(loader).transform(null, loader, OWNER, null, null, fixtureBytes());
        assertNotNull(transformed, "the fixture must transform");
        try {
            return loader.defineGuarded(OWNER_BINARY_NAME, transformed)
                    .getConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("the transformed fixture must load and link", failure);
        }
    }

    private static AutoMeshFunnelTransformer transformer(final ClassLoader loader) {
        return new AutoMeshFunnelTransformer(
                OWNER,
                List.of(
                        new AutoMeshFunnelTransformer.Site(
                                AutoMeshFunnel.MODELING_MODE, OWNER, MODELING, MODELING_DESCRIPTOR),
                        new AutoMeshFunnelTransformer.Site(
                                AutoMeshFunnel.MESH_EDITOR_MODE, OWNER, MESH_EDITOR, MESH_EDITOR_DESCRIPTOR)),
                loader,
                message -> {});
    }

    private static void invoke(final Object fixture, final String method, final Object[] arguments) {
        final Class<?>[] parameters = MODELING.equals(method) ? MODELING_PARAMETERS : MESH_EDITOR_PARAMETERS;
        try {
            fixture.getClass().getMethod(method, parameters).invoke(fixture, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("a guarded funnel must stay callable", failure);
        }
    }

    private static int counter(final Object fixture, final String field) {
        try {
            return fixture.getClass().getField(field).getInt(fixture);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static byte[] fixtureBytes() {
        try (InputStream input = FunnelFixture.class.getResourceAsStream("FunnelFixture.class")) {
            assertNotNull(input, "the fixture class file must be on the test classpath");
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
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

    /** Drawable double that records geometry writes and reports one stored form. */
    private static final class StubDrawable extends FakeDrawable {

        private final ArtMeshGeometry geometry = triangle();
        private final List<ArtMeshGeometry> writes = new CopyOnWriteArrayList<>();

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

    /** Transaction double that counts executions and commits what the write callback produced. */
    private static final class CountingTransactions implements AuthoringTransactionService {

        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public <T> AuthoringTransactionResult<T> execute(
                final AuthoringTransactionOptions options, final AuthoringTransactionWork<T> work) {
            executions.incrementAndGet();
            final T value;
            try {
                value = work.run();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            final HistorySnapshot history =
                    new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1L, 1L, 0, List.of(), false, false);
            return AuthoringTransactionResult.committed(
                    value,
                    new AuthoringTransactionReceipt(
                            "transaction-1", options.label(), history, history, Optional.of("entry-1")));
        }
    }

    /** Child-first loader: the guarded fixture is defined from the transformer's own output. */
    private static final class GuardedLoader extends ClassLoader {

        private final Map<String, byte[]> guarded = new HashMap<>();

        private GuardedLoader() {
            super(FunnelFixture.class.getClassLoader());
        }

        private Class<?> defineGuarded(final String name, final byte[] bytes) {
            guarded.put(name, bytes);
            try {
                return loadClass(name);
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!guarded.containsKey(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    final byte[] bytes = guarded.get(name);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
