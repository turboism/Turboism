package dev.turboism.adapter.cubism.textureatlas;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Shares detached native solvers within one atlas initialization. */
public final class TextureAtlasParallelMeshes implements AutoCloseable {
    private static final int MAX_WORKERS = 4;
    private static final int MIN_WORKERS = 2;
    private static final int MIN_POINTS = 128;
    private static final int MAX_PREPARED_POINTS = 256_000;
    private static final String WORKER_NAME = "turboism-texture-atlas-mesh";
    private static final String LOG_COMPONENT = "texture-atlas-mesh";
    private static final String PREPARATION_FAILED = "Could not prepare an atlas mesh for parallel triangulation";
    private static final String SOLVE_FAILED = "Parallel atlas triangulation failed, using the native solver";
    private static final String SOURCE_PACKAGE = "com.live2d.cubism.doc.model.";
    private static final String ART_MESH = SOURCE_PACKAGE + "drawable.artMesh.CArtMeshSource";
    private static final String MESH_EXTENSION = SOURCE_PACKAGE + "extension.editableMesh.CEditableMeshExtension";
    private static final String PROGRESS_DEFAULT = "b";
    private static final String MESH_OPS_SINGLETON = "a";
    private static final String DEFAULT_PROGRESS_METHOD = "a";
    private static final String APPLY_METHOD = "a";
    private static final String OUTLINES_METHOD = "b";
    private static final String EXTENSIONS_METHOD = "getExtensions";
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

    private static final String SOLVER_CONTEXT_METHOD = "a";
    private static final String CONTEXT_CONTOURS_METHOD = "c";

    private static final ClassValue<Optional<Host>> HOST = new ClassValue<>() {
        @Override
        protected Optional<Host> computeValue(final Class<?> meshOps) {
            try {
                meshOps.getDeclaredMethod(TextureAtlasMeshSolverTransformer.CAPTURE_MARKER);
                return Host.reviewed(meshOps);
            } catch (ReflectiveOperationException | IOException | LinkageError | SecurityException unsupported) {
                return Optional.empty();
            }
        }
    };
    private static final ThreadLocal<Boolean> WORKER = new ThreadLocal<>();

    private final IdentityHashMap<Object, Prepared> prepared = new IdentityHashMap<>();
    private ExecutorService executor;
    private Host host;
    private Work capture;
    private boolean capturing;

    static boolean onWorker() {
        return WORKER.get() != null;
    }

    /** Starts detached mesh solve tasks before the host builds its edit layers. */
    public static void preload(final Map<?, ?> drawables, final Class<?> meshOps) {
        final var batch = TextureAtlasLoading.meshBatch();
        if (batch == null || batch.host != null) return;
        try {
            HOST.get(meshOps).ifPresent(host -> batch.prepare(drawables, host));
        } catch (RuntimeException failure) {
            RuntimeDiagnostics.error(LOG_COMPONENT, PREPARATION_FAILED, failure);
        }
    }

    /** The reviewed caller can stop before it modifies the mesh once the solver owns its inputs. */
    public static boolean capture(final Object solver, final Object progress, final MethodHandle solve) {
        final var batch = TextureAtlasLoading.meshBatch();
        if (batch == null || !batch.capturing || progress != batch.host.progress()) return false;
        batch.capture = new Work(solver, progress, solve);
        return true;
    }

    /** Supplies a valid prepared solver or keeps the native solve on the caller thread. */
    public static Object solve(final Object solver, final Object progress, final Object mesh, final MethodHandle solve)
            throws Throwable {
        final var batch = TextureAtlasLoading.meshBatch();
        if (batch != null && batch.host != null && progress == batch.host.progress()) {
            final var result = batch.take(mesh, solver);
            if (result != null) return result;
        }
        solve.invoke(solver, progress);
        return solver;
    }

    private void prepare(final Map<?, ?> drawables, final Host admitted) {
        host = admitted;
        final int workerCount = Math.min(MAX_WORKERS, Runtime.getRuntime().availableProcessors());
        if (workerCount < MIN_WORKERS) return;
        final var meshes = new ArrayList<Candidate>();
        final var seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final var group : drawables.values()) {
            if (!(group instanceof Iterable<?> sources)) continue;
            for (final Object source : sources) {
                try {
                    final Object mesh = host.findMesh(source);
                    if (mesh == null || !seen.add(mesh) || !host.needsTriangulation(mesh)) continue;
                    final int pointCount = (int) host.pointCount().invoke(mesh);
                    if (pointCount >= MIN_POINTS) meshes.add(new Candidate(mesh, pointCount));
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    reportPreparationFailure(failure);
                }
            }
        }
        if (meshes.size() < MIN_WORKERS) return;
        meshes.sort(Comparator.comparingInt(Candidate::pointCount).reversed());
        executor = RuntimeThreads.fixedLane(
                Math.min(workerCount, meshes.size()),
                Math.min(meshes.size(), MAX_PREPARED_POINTS / MIN_POINTS),
                RuntimeThreads.daemonThreads(
                        WORKER_NAME, host.operations().getClass().getClassLoader()));
        // Limit the number of points that a single open can duplicate in native solver contexts.
        int availablePoints = MAX_PREPARED_POINTS;
        for (final Candidate candidate : meshes) {
            if (candidate.pointCount() > availablePoints) continue;
            try {
                if (stage(candidate.mesh())) availablePoints -= candidate.pointCount();
            } catch (ReflectiveOperationException | RuntimeException failure) {
                reportPreparationFailure(failure);
            }
        }
    }

    private boolean stage(final Object mesh) throws ReflectiveOperationException {
        final Revision revision = host.revision(mesh);
        @SuppressWarnings("unchecked")
        final List<int[]> contours = (List<int[]>) host.outlines().invoke(host.operations(), mesh);
        if (contours.isEmpty()) return false;
        capture = null;
        capturing = true;
        final Work work;
        try {
            host.apply().invoke(host.operations(), mesh, contours, true, host.progress());
            work = capture;
        } finally {
            capturing = false;
            capture = null;
        }
        if (work == null) return false;
        final var contourSnapshot = contours.stream().map(int[]::clone).toList();
        final var result = executor.submit(work::run);
        prepared.put(mesh, new Prepared(revision, contourSnapshot, result));
        return true;
    }

    private Object take(final Object mesh, final Object nativeSolver) {
        final var entry = prepared.remove(mesh);
        if (entry == null) return null;
        try {
            if (!entry.revision().equals(host.revision(mesh))
                    || !sameContours(entry.contours(), host.contours(nativeSolver))) {
                entry.result().cancel(false);
                return null;
            }
            return entry.result().get();
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            FatalErrors.rethrowIfFatal(cause);
            RuntimeDiagnostics.error(LOG_COMPONENT, SOLVE_FAILED, cause);
            return null;
        } catch (InterruptedException | ReflectiveOperationException | RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, SOLVE_FAILED, failure);
            return null;
        }
    }

    private static boolean sameContours(final List<int[]> first, final List<?> second) {
        if (second == null || first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            if (!Arrays.equals(first.get(index), (int[]) second.get(index))) return false;
        }
        return true;
    }

    @Override
    public void close() {
        if (executor == null) return;
        for (final Prepared entry : prepared.values()) {
            entry.result().cancel(false);
        }
        prepared.clear();
        executor.shutdown();
    }

    private static void reportPreparationFailure(final Throwable failure) {
        final Throwable cause =
                failure instanceof java.lang.reflect.InvocationTargetException wrapper ? wrapper.getCause() : failure;
        FatalErrors.rethrowIfFatal(cause);
        RuntimeDiagnostics.error(LOG_COMPONENT, PREPARATION_FAILED, cause);
    }

    private record Candidate(Object mesh, int pointCount) {}

    private record Revision(int edges, int positions) {}

    private record Prepared(Revision revision, List<int[]> contours, Future<Object> result) {}

    private record Work(Object solver, Object progress, MethodHandle solve) {
        Object run() {
            WORKER.set(Boolean.TRUE);
            try {
                solve.invoke(solver, progress);
                return solver;
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw FatalErrors.propagate(failure);
            } finally {
                WORKER.remove();
            }
        }
    }

    private record Host(
            Object operations,
            Object progress,
            Class<?> artMesh,
            Class<?> extension,
            Method extensions,
            Method editableMesh,
            Method delaunay,
            Method edgeRevision,
            Method positionRevision,
            Method cachedRevision,
            Method pointCount,
            Method outlines,
            Method apply,
            Method solverContext,
            Method contextContours) {
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
            final Class<?> art = loader.loadClass(ART_MESH);
            final Class<?> extension = loader.loadClass(MESH_EXTENSION);
            final Method solverContext = type(loader, TextureAtlasMeshSolverTransformer.TRIANGULATION + "a")
                    .getMethod(SOLVER_CONTEXT_METHOD);
            return Optional.of(new Host(
                    meshOps.getField(MESH_OPS_SINGLETON).get(null),
                    progress,
                    art,
                    extension,
                    art.getMethod(EXTENSIONS_METHOD),
                    extension.getMethod(EDITABLE_MESH_METHOD),
                    mesh.getMethod(DELAUNAY_METHOD),
                    mesh.getMethod(EDGE_REVISION_METHOD),
                    mesh.getMethod(POSITION_REVISION_METHOD),
                    mesh.getMethod(CACHED_REVISION_METHOD),
                    mesh.getMethod(POINT_COUNT_METHOD),
                    meshOps.getMethod(OUTLINES_METHOD, mesh),
                    apply,
                    solverContext,
                    solverContext.getReturnType().getMethod(CONTEXT_CONTOURS_METHOD)));
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

        List<?> contours(final Object solver) throws ReflectiveOperationException {
            return (List<?>) contextContours.invoke(solverContext.invoke(solver));
        }

        Object findMesh(final Object source) throws ReflectiveOperationException {
            if (!artMesh.isInstance(source)) return null;
            for (final Object candidate : (Iterable<?>) extensions.invoke(source)) {
                if (extension.isInstance(candidate)) return editableMesh.invoke(candidate);
            }
            return null;
        }

        boolean needsTriangulation(final Object mesh) throws ReflectiveOperationException {
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
