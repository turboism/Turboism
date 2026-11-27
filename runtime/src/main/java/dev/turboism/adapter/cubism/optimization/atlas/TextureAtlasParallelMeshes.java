package dev.turboism.adapter.cubism.optimization.atlas;

import dev.turboism.core.reflect.CodeSources;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.runtime.exec.RuntimeThreads;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Runs full Delaunay triangulation off EDT and returns compact apply-ready edge/index arrays. */
public final class TextureAtlasParallelMeshes implements AutoCloseable {

    private static final int MAX_WORKERS_CAP = 12;
    private static final int MIN_WORKERS = 2;
    private static final int MIN_POINTS = 48;
    private static final int MAX_IN_FLIGHT_POINTS = 256_000;
    private static final int MAX_TOTAL_PREPARED_POINTS = 4_000_000;
    private static final int TRIANGLE_EDGES = 3;
    private static final long CLOSE_WAIT_MILLIS = 2_000L;
    private static final String WORKER_NAME = "turboism-texture-atlas-mesh";
    private static final String LOG_COMPONENT = "texture-atlas-mesh";
    private static final String PREPARATION_FAILED = "Could not prepare an atlas mesh for parallel triangulation";
    private static final String SOLVE_FAILED = "Parallel atlas triangulation failed, using the native solver";
    private static final String SOURCE_PACKAGE = "com.live2d.cubism.doc.model.";
    private static final String ART_MESH = SOURCE_PACKAGE + "drawable.artMesh.CArtMeshSource";
    private static final String ART_PATH = SOURCE_PACKAGE + "drawable.artPath.CArtPathSource";
    private static final String PROGRESS_DEFAULT = "b";
    private static final String DEFAULT_PROGRESS_METHOD = "a";
    private static final String DELAUNAY_SOLVER_SUFFIX = "h";
    private static final String BRUSH_METHOD = "getBrush";
    private static final String TEXTURE_INPUT_METHOD = "getTextureInput";
    private static final String EDITABLE_MESH_METHOD = "getEditableMesh";
    private static final String DELAUNAY_METHOD = "getUseDelaunayTriangulation";
    private static final String EDGE_REVISION_METHOD = "get_edge_edit_version";
    private static final String POSITION_REVISION_METHOD = "get_postion_edit_version";
    private static final String CACHED_REVISION_METHOD = "getCache_version_gl_indices$core";
    private static final String POINT_COUNT_METHOD = "getPointCount";
    private static final String PROGRESS_PACKAGE_PREFIX =
            TextureAtlasMeshSolverTransformer.PROGRESS_PACKAGE_PREFIX.replace('/', '.');
    private static final int APPLY_PARAMETER_COUNT = 4;
    private static final int PROGRESS_PARAMETER_INDEX = 3;
    private static final String APPLY_METHOD = "a";
    private static final String POINTS_METHOD = "getPoints";
    private static final String EDGES_METHOD = "getEdges";
    private static final String EDGE_INDEX1_METHOD = "getIndex1";
    private static final String EDGE_INDEX2_METHOD = "getIndex2";
    private static final String EDGE_TYPE_METHOD = "getType";
    private static final String AUTO_TRIANGULATION_NAME = "AUTO_TRIANGULATION";
    private static final String SOLVER_CONTEXT_METHOD = "a";
    private static final String SOLVER_SOLVE_METHOD = "a";
    private static final String CONTEXT_SET_POINTS_METHOD = "a";
    private static final String CONTEXT_ADD_EDGE_METHOD = "a";
    private static final String CONTEXT_SET_CONTOURS_METHOD = "b";
    private static final String CONTEXT_TRIANGLES_METHOD = "f";

    private static final ClassValue<Optional<Host>> HOST = new ClassValue<>() {
        @Override
        protected Optional<Host> computeValue(final Class<?> meshOps) {
            try {
                return Host.reviewed(meshOps);
            } catch (ReflectiveOperationException | IOException | LinkageError | SecurityException unsupported) {
                return Optional.empty();
            }
        }
    };
    private static final ThreadLocal<Boolean> WORKER = new ThreadLocal<>();

    private final IdentityHashMap<Object, PreparedTask> prepared = new IdentityHashMap<>();
    private final PointBudget inFlightBudget = new PointBudget(MAX_IN_FLIGHT_POINTS);
    private ExecutorService executor;
    private Host host;

    static boolean onWorker() {
        return WORKER.get() != null;
    }

    /** Starts full off-EDT mesh triangulation tasks before the host builds its edit layers. */
    public static void preload(final Map<?, ?> drawables, final Class<?> meshOps) {
        final TextureAtlasParallelMeshes batch = TextureAtlasLoading.meshBatch();
        if (batch == null || batch.host != null || drawables == null) return;
        try {
            HOST.get(meshOps).ifPresent(admitted -> batch.prepare(drawables, admitted));
        } catch (RuntimeException failure) {
            RuntimeDiagnostics.error(LOG_COMPONENT, PREPARATION_FAILED, failure);
        }
    }

    /** Applies a finished off-EDT result directly in {@code GEditableMesh2.updateIndices}, skipping native solve. */
    public static boolean tryApplyPrepared(final Object mesh) {
        final TextureAtlasParallelMeshes batch = TextureAtlasLoading.meshBatch();
        if (batch == null || batch.host == null || mesh == null) return false;
        return batch.applyPrepared(mesh);
    }

    boolean isPrepared(final Object mesh) {
        return prepared.containsKey(mesh);
    }

    /** Backward-compatible capture entrypoint retained for callers outside the full-apply pipeline. */
    public static boolean capture(final Object solver, final Object progress, final MethodHandle solve) {
        return false;
    }

    /** Backward-compatible solve entrypoint retained for callers outside the full-apply pipeline. */
    public static Object solve(final Object solver, final Object progress, final Object mesh, final MethodHandle solve)
            throws Throwable {
        solve.invoke(solver, progress);
        return solver;
    }

    private void prepare(final Map<?, ?> drawables, final Host admitted) {
        host = admitted;
        final int cpus = Runtime.getRuntime().availableProcessors();
        final int workerCount = Math.min(MAX_WORKERS_CAP, Math.max(MIN_WORKERS, cpus - 1));
        if (cpus < MIN_WORKERS) return;

        final List<Candidate> meshes = new ArrayList<>();
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Object group : drawables.values()) {
            if (!(group instanceof Iterable<?> sources)) continue;
            for (final Object source : sources) {
                try {
                    final Object mesh = admitted.findMesh(source);
                    if (mesh == null || !seen.add(mesh) || !admitted.needsDelaunayTriangulation(mesh)) continue;
                    final int pointCount = (int) admitted.pointCount().invoke(mesh);
                    if (pointCount >= MIN_POINTS) {
                        meshes.add(new Candidate(mesh, pointCount, admitted.revision(mesh)));
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    reportPreparationFailure(failure);
                }
            }
        }
        if (meshes.size() < MIN_WORKERS) return;

        meshes.sort(Comparator.comparingInt(Candidate::pointCount).reversed());
        final List<Candidate> scheduled = new ArrayList<>(meshes.size());
        long remainingPreparedBudget = MAX_TOTAL_PREPARED_POINTS;
        for (final Candidate candidate : meshes) {
            if (candidate.pointCount() > remainingPreparedBudget) continue;
            remainingPreparedBudget -= candidate.pointCount();
            scheduled.add(candidate);
        }
        if (scheduled.size() < MIN_WORKERS) return;

        executor = RuntimeThreads.fixedLane(
                Math.min(workerCount, scheduled.size()),
                scheduled.size(),
                RuntimeThreads.daemonThreads(WORKER_NAME, admitted.loader()));

        for (final Candidate candidate : scheduled) {
            final Object mesh = candidate.mesh();
            final int points = candidate.pointCount();
            final Revision revision = candidate.revision();
            final Future<PreparedResult> future = executor.submit(() -> solveOffEdt(admitted, mesh, points, revision));
            prepared.put(mesh, new PreparedTask(revision, future));
        }
    }

    private PreparedResult solveOffEdt(
            final Host admitted, final Object mesh, final int pointCount, final Revision expectedRevision)
            throws Exception {
        final int permitCost = Math.min(pointCount, MAX_IN_FLIGHT_POINTS);
        inFlightBudget.acquire(permitCost);
        WORKER.set(Boolean.TRUE);
        Object solver = null;
        try {
            if (!expectedRevision.equals(admitted.revision(mesh))) {
                return PreparedResult.STALE;
            }
            final TextureAtlasMeshFastOps.OutlineSnapshot snapshot =
                    TextureAtlasMeshFastOps.extractOutlinesAndPoints(mesh);
            final List<int[]> contours = snapshot.contours();
            if (contours.isEmpty()) {
                return PreparedResult.EMPTY_CONTOURS;
            }

            solver = admitted.newSolver();
            final Object ctx = admitted.solverContext().invoke(solver);
            admitted.contextSetPoints().invoke(ctx, admitted.points().invoke(mesh));
            for (final Object edge : (List<?>) admitted.edges().invoke(mesh)) {
                if (admitted.edgeType().invoke(edge) != admitted.autoTriangulation()) {
                    admitted.contextAddEdge()
                            .invoke(ctx, (int) admitted.edgeIndex1().invoke(edge), (int)
                                    admitted.edgeIndex2().invoke(edge));
                }
            }
            admitted.contextSetContours().invoke(ctx, contours);
            admitted.solverSolve().invoke(solver, admitted.progress());

            final int[][] triangles = (int[][]) admitted.contextTriangles().invoke(ctx);
            solver = null;
            final boolean cwFlag = TextureAtlasMeshFastOps.queryCwPatchFlag(mesh);
            final int[] cachedIndices =
                    TextureAtlasMeshFastOps.orientTriangleIndices(triangles, snapshot.positionsXY(), cwFlag);
            final int[] uniqueEdgePairs = TextureAtlasMeshFastOps.deduplicateTriangleEdges(triangles);
            return new PreparedResult(false, false, cachedIndices, uniqueEdgePairs, triangles.length * TRIANGLE_EDGES);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw FatalErrors.propagate(failure);
        } finally {
            solver = null;
            WORKER.remove();
            inFlightBudget.release(permitCost);
        }
    }

    private boolean applyPrepared(final Object mesh) {
        final PreparedTask entry = prepared.remove(mesh);
        if (entry == null) return false;
        try {
            if (!entry.revision().equals(host.revision(mesh))) {
                entry.future().cancel(true);
                return false;
            }
            final PreparedResult result = entry.future().get();
            if (result == null || result.stale() || result.emptyContours()) {
                return false;
            }
            if (!entry.revision().equals(host.revision(mesh))) {
                return false;
            }
            return TextureAtlasMeshFastOps.applyPrepared(
                    mesh, result.cachedIndices(), result.uniqueEdgePairs(), result.triEdgeCallCount());
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            FatalErrors.rethrowIfFatal(cause);
            RuntimeDiagnostics.error(LOG_COMPONENT, SOLVE_FAILED, cause);
            return false;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            RuntimeDiagnostics.error(LOG_COMPONENT, SOLVE_FAILED, failure);
            return false;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, SOLVE_FAILED, failure);
            return false;
        }
    }

    @Override
    public void close() {
        for (final PreparedTask entry : prepared.values()) {
            entry.future().cancel(true);
        }
        prepared.clear();
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void reportPreparationFailure(final Throwable failure) {
        final Throwable cause =
                failure instanceof java.lang.reflect.InvocationTargetException wrapper ? wrapper.getCause() : failure;
        FatalErrors.rethrowIfFatal(cause);
        RuntimeDiagnostics.error(LOG_COMPONENT, PREPARATION_FAILED, cause);
    }

    private static final class PointBudget {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition released = lock.newCondition();
        private int available;

        PointBudget(final int maxPoints) {
            this.available = maxPoints;
        }

        void acquire(final int cost) throws InterruptedException {
            lock.lock();
            try {
                while (available < cost) {
                    released.await();
                }
                available -= cost;
            } finally {
                lock.unlock();
            }
        }

        void release(final int cost) {
            lock.lock();
            try {
                available += cost;
                released.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private record Candidate(Object mesh, int pointCount, Revision revision) {}

    private record Revision(int edges, int positions) {}

    private record PreparedTask(Revision revision, Future<PreparedResult> future) {}

    private record PreparedResult(
            boolean stale, boolean emptyContours, int[] cachedIndices, int[] uniqueEdgePairs, int triEdgeCallCount) {
        static final PreparedResult STALE = new PreparedResult(true, false, null, null, 0);
        static final PreparedResult EMPTY_CONTOURS = new PreparedResult(false, true, null, null, 0);
    }

    private record Host(
            ClassLoader loader,
            Object progress,
            Class<?> solverClass,
            Class<?> artMesh,
            Class<?> artPath,
            Method artMeshEditableMesh,
            Method brush,
            Method textureInput,
            Method pathEditableMesh,
            Method delaunay,
            Method edgeRevision,
            Method positionRevision,
            Method cachedRevision,
            Method pointCount,
            Method points,
            Method edges,
            Method edgeIndex1,
            Method edgeIndex2,
            Method edgeType,
            Object autoTriangulation,
            Method solverContext,
            Method solverSolve,
            Method contextSetPoints,
            Method contextAddEdge,
            Method contextSetContours,
            Method contextTriangles) {
        static Optional<Host> reviewed(final Class<?> meshOps) throws ReflectiveOperationException, IOException {
            final ClassLoader loader = meshOps.getClassLoader();
            final Path artifact = CodeSources.pathOf(meshOps.getProtectionDomain());
            if (loader == null
                    || artifact == null
                    || !ReviewedHostArtifacts.isReviewed(HostArtifactDigest.cached(artifact))) {
                return Optional.empty();
            }
            final Class<?> mesh = type(loader, TextureAtlasMeshSolverTransformer.MESH);
            final Method apply = findApplyMethod(meshOps, mesh);
            if (apply == null) return Optional.empty();
            final Class<?> progressType = apply.getParameterTypes()[PROGRESS_PARAMETER_INDEX];
            final Object progressProvider =
                    progressType.getField(PROGRESS_DEFAULT).get(null);
            final Object progress = progressProvider
                    .getClass()
                    .getMethod(DEFAULT_PROGRESS_METHOD)
                    .invoke(progressProvider);
            final Class<?> solverBase = type(loader, TextureAtlasMeshSolverTransformer.TRIANGULATION + "a");
            final Class<?> solverClass =
                    type(loader, TextureAtlasMeshSolverTransformer.TRIANGULATION + DELAUNAY_SOLVER_SUFFIX);
            final Method solverContext = solverBase.getMethod(SOLVER_CONTEXT_METHOD);
            final Class<?> ctxClass = solverContext.getReturnType();
            final Class<?> edgeClass = type(loader, TextureAtlasMeshSolverTransformer.MESH_PACKAGE + "MEdge");
            final Class<?> edgeTypeClass =
                    type(loader, TextureAtlasMeshSolverTransformer.MESH_PACKAGE + "MEdge$EdgeType");
            final Class<?> artMesh = loader.loadClass(ART_MESH);
            final Class<?> artPath = loader.loadClass(ART_PATH);
            final Class<?> brushClass = artPath.getMethod(BRUSH_METHOD).getReturnType();
            final Method textureInputMethod = brushClass.getMethod(TEXTURE_INPUT_METHOD);
            final Method pathEditableMesh = textureInputMethod.getReturnType().getMethod(EDITABLE_MESH_METHOD);
            return Optional.of(new Host(
                    loader,
                    progress,
                    solverClass,
                    artMesh,
                    artPath,
                    artMesh.getMethod(EDITABLE_MESH_METHOD),
                    artPath.getMethod(BRUSH_METHOD),
                    textureInputMethod,
                    pathEditableMesh,
                    mesh.getMethod(DELAUNAY_METHOD),
                    mesh.getMethod(EDGE_REVISION_METHOD),
                    mesh.getMethod(POSITION_REVISION_METHOD),
                    mesh.getMethod(CACHED_REVISION_METHOD),
                    mesh.getMethod(POINT_COUNT_METHOD),
                    mesh.getMethod(POINTS_METHOD),
                    mesh.getMethod(EDGES_METHOD),
                    edgeClass.getMethod(EDGE_INDEX1_METHOD),
                    edgeClass.getMethod(EDGE_INDEX2_METHOD),
                    edgeClass.getMethod(EDGE_TYPE_METHOD),
                    edgeTypeClass.getField(AUTO_TRIANGULATION_NAME).get(null),
                    solverContext,
                    solverBase.getMethod(SOLVER_SOLVE_METHOD, progressType),
                    ctxClass.getMethod(CONTEXT_SET_POINTS_METHOD, List.class),
                    ctxClass.getMethod(CONTEXT_ADD_EDGE_METHOD, int.class, int.class),
                    ctxClass.getMethod(CONTEXT_SET_CONTOURS_METHOD, List.class),
                    ctxClass.getMethod(CONTEXT_TRIANGLES_METHOD)));
        }

        private static Method findApplyMethod(final Class<?> meshOps, final Class<?> mesh) {
            Method matched = null;
            for (final Method candidate : meshOps.getDeclaredMethods()) {
                final int modifiers = candidate.getModifiers();
                if (!Modifier.isPublic(modifiers)
                        || Modifier.isStatic(modifiers)
                        || !APPLY_METHOD.equals(candidate.getName())
                        || candidate.getReturnType() != void.class) {
                    continue;
                }
                final Class<?>[] params = candidate.getParameterTypes();
                if (params.length != APPLY_PARAMETER_COUNT
                        || params[0] != mesh
                        || params[1] != List.class
                        || params[2] != boolean.class
                        || params[PROGRESS_PARAMETER_INDEX].isPrimitive()
                        || !params[PROGRESS_PARAMETER_INDEX].getName().startsWith(PROGRESS_PACKAGE_PREFIX)) {
                    continue;
                }
                if (matched != null) return null;
                matched = candidate;
            }
            return matched;
        }

        Object newSolver() throws ReflectiveOperationException {
            return solverClass.getConstructor().newInstance();
        }

        Object findMesh(final Object source) throws ReflectiveOperationException {
            if (artMesh.isInstance(source)) {
                return artMeshEditableMesh.invoke(source);
            }
            if (artPath.isInstance(source)) {
                final Object brushObj = brush.invoke(source);
                if (brushObj == null) return null;
                final Object inputObj = textureInput.invoke(brushObj);
                if (inputObj == null) return null;
                return pathEditableMesh.invoke(inputObj);
            }
            return null;
        }

        boolean needsDelaunayTriangulation(final Object mesh) throws ReflectiveOperationException {
            return (boolean) delaunay.invoke(mesh)
                    && (int) edgeRevision.invoke(mesh) != (int) cachedRevision.invoke(mesh);
        }

        Revision revision(final Object mesh) throws ReflectiveOperationException {
            return new Revision((int) edgeRevision.invoke(mesh), (int) positionRevision.invoke(mesh));
        }

        private static Class<?> type(final ClassLoader loader, final String internalName)
                throws ClassNotFoundException {
            return loader.loadClass(internalName.replace('/', '.'));
        }
    }
}
