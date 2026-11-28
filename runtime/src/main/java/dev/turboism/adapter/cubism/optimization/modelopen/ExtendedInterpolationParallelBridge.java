package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes independent {@code CExtendedInterpolationExtension.setupFormStructure(false, true)}
 * builds concurrently during first-time {@code CModelSource.setup()}.
 */
public final class ExtendedInterpolationParallelBridge {

    private static final int MIN_PARALLEL_EXTENSIONS = 2;
    private static final int MAX_POOL_THREADS = 16;
    private static final int WORK_QUEUE_CAPACITY = 4096;
    private static final int CALLER_THREAD_RESERVE = 1;
    private static final int IDENTITY_MAP_CAPACITY_FACTOR = 2;
    private static final long DEFAULT_EXTENSION_WEIGHT = 1L;
    private static final String WORKER_PREFIX = RuntimeThreads.THREAD_NAME_PREFIX + "-ext-interp-setup";
    private static final String FIELD_SETUP_FLAG = "setupFlag";
    private static final String FIELD_DRAWABLE_SET = "drawableSourceSet";
    private static final String FIELD_DEFORMER_SET = "deformerSourceSet";
    private static final String METHOD_GET_EXTENSIONS = "getExtensions";
    private static final String METHOD_GET_KEYFORMS = "getKeyforms";
    private static final String METHOD_GET_POSITIONS = "getPositions";
    private static final String METHOD_SETUP_FORM_STRUCTURE = "setupFormStructure";
    private static final String EXT_INTERP_CLASS =
            "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension";
    private static final String CONTROLLABLE_CLASS = "com.live2d.cubism.doc.model.ACParameterControllableSource";
    private static final String ART_MESH_FORM_CLASS = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";
    private static final String WARP_DEFORMER_FORM_CLASS =
            "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm";
    private static final Comparator<WeightedExtension> HEAVIEST_FIRST =
            (left, right) -> Long.compare(right.weight(), left.weight());

    private static final int POOL_THREADS = Math.max(
            MIN_PARALLEL_EXTENSIONS,
            Math.min(MAX_POOL_THREADS, Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService WORKER_POOL = createWorkerPool(POOL_THREADS);
    private static final ThreadLocal<Boolean> WORKER_ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Set<Object>> PRECOMPUTED_EXTENSIONS = new ThreadLocal<>();

    private static volatile Handles cachedHandles;

    private ExtendedInterpolationParallelBridge() {}

    /**
     * Returns whether the calling thread is currently running a parallel extended-interpolation task.
     *
     * @return {@code true} on an active worker thread so host debug stopwatches skip unsynchronized state
     */
    public static boolean isWorkerThread() {
        return Boolean.TRUE.equals(WORKER_ACTIVE.get());
    }

    /**
     * Consumes a precomputed completion marker for {@code extension} on the current setup thread.
     *
     * @param extension host {@code CExtendedInterpolationExtension} entering {@code setupFormStructure$default}
     * @return {@code true} if {@code extension} was already built during this setup pass
     */
    public static boolean consumePrecomputed(final Object extension) {
        final Set<Object> set = PRECOMPUTED_EXTENSIONS.get();
        if (set == null || extension == null) {
            return false;
        }
        final boolean removed = set.remove(extension);
        if (set.isEmpty()) {
            PRECOMPUTED_EXTENSIONS.remove();
        }
        return removed;
    }

    /**
     * Precomputes all extended-interpolation extensions on {@code modelSource} in parallel during
     * first-time {@code CModelSource.setup()} (when {@code setupFlag == false}).
     *
     * @param modelSource initializing {@code CModelSource} instance
     */
    public static void precomputeForFirstSetup(final Object modelSource) {
        if (modelSource == null || isWorkerThread()) {
            return;
        }
        PRECOMPUTED_EXTENSIONS.remove();
        try {
            final Handles h = resolveHandles(modelSource.getClass());
            if (h.setupFlag.getBoolean(modelSource)) {
                return;
            }
            final List<WeightedExtension> extensions = new ArrayList<>();
            collectExtensions(h.drawableSourceSet.get(modelSource), h, extensions);
            collectExtensions(h.deformerSourceSet.get(modelSource), h, extensions);
            if (extensions.size() < MIN_PARALLEL_EXTENSIONS) {
                return;
            }
            final Set<Object> completed =
                    executeParallel(extensions, ext -> h.setupFormStructure.invokeExact(ext, false, true));
            if (!completed.isEmpty()) {
                PRECOMPUTED_EXTENSIONS.set(completed);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            PRECOMPUTED_EXTENSIONS.remove();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            PRECOMPUTED_EXTENSIONS.remove();
        }
    }

    static Set<Object> executeParallel(final List<WeightedExtension> extensions, final ExtensionTask task)
            throws Exception {
        // Schedule heaviest meshes first to eliminate tail latency on high-vertex extensions.
        extensions.sort(HEAVIEST_FIRST);
        final int total = extensions.size();
        final Object[] completedSlots = new Object[total];
        final AtomicInteger nextIndex = new AtomicInteger();
        final int workerCount =
                Math.min(Math.max(0, POOL_THREADS - CALLER_THREAD_RESERVE), total - CALLER_THREAD_RESERVE);
        final List<Future<?>> futures = new ArrayList<>(workerCount);
        for (int worker = 0; worker < workerCount; worker++) {
            futures.add(WORKER_POOL.submit(() -> drainExtensions(extensions, nextIndex, completedSlots, task)));
        }
        // Let the caller thread drain work concurrently instead of idling on future.get().
        drainExtensions(extensions, nextIndex, completedSlots, task);
        for (final Future<?> future : futures) {
            future.get();
        }
        final Set<Object> completed =
                Collections.newSetFromMap(new IdentityHashMap<>(total * IDENTITY_MAP_CAPACITY_FACTOR));
        for (final Object done : completedSlots) {
            if (done != null) {
                completed.add(done);
            }
        }
        return completed;
    }

    private static void drainExtensions(
            final List<WeightedExtension> extensions,
            final AtomicInteger nextIndex,
            final Object[] completedSlots,
            final ExtensionTask task) {
        final int total = extensions.size();
        WORKER_ACTIVE.set(Boolean.TRUE);
        try {
            while (true) {
                final int index = nextIndex.getAndIncrement();
                if (index >= total) {
                    return;
                }
                final Object ext = extensions.get(index).extension();
                try {
                    task.run(ext);
                    completedSlots[index] = ext;
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                }
            }
        } finally {
            WORKER_ACTIVE.set(Boolean.FALSE);
        }
    }

    private static void collectExtensions(final Object sourceSet, final Handles h, final List<WeightedExtension> out)
            throws Throwable {
        if (!(sourceSet instanceof Iterable<?> iterable)) {
            return;
        }
        for (final Object element : iterable) {
            if (element == null || !h.controllableClass.isInstance(element)) {
                continue;
            }
            final Object extObj = h.getExtensions.invokeExact(element);
            if (!(extObj instanceof Iterable<?> extList)) {
                continue;
            }
            for (final Object ext : extList) {
                if (h.extInterpClass.isInstance(ext)) {
                    out.add(new WeightedExtension(ext, estimateWeight(element, h)));
                    break;
                }
            }
        }
    }

    private static long estimateWeight(final Object controllable, final Handles h) {
        if (h.getKeyforms == null) {
            return DEFAULT_EXTENSION_WEIGHT;
        }
        try {
            final Object keyformsObj = h.getKeyforms.invokeExact(controllable);
            if (!(keyformsObj instanceof List<?> keyforms) || keyforms.isEmpty()) {
                return DEFAULT_EXTENSION_WEIGHT;
            }
            final int keyformCount = keyforms.size();
            final Object firstForm = keyforms.get(0);
            int coordCount = 1;
            if (firstForm != null) {
                if (h.meshFormClass != null && h.meshFormClass.isInstance(firstForm)) {
                    final float[] positions = (float[]) h.meshGetPositions.invokeExact(firstForm);
                    if (positions != null && positions.length > 0) {
                        coordCount = positions.length;
                    }
                } else if (h.warpFormClass != null && h.warpFormClass.isInstance(firstForm)) {
                    final float[] positions = (float[]) h.warpGetPositions.invokeExact(firstForm);
                    if (positions != null && positions.length > 0) {
                        coordCount = positions.length;
                    }
                }
            }
            return (long) keyformCount * coordCount;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return DEFAULT_EXTENSION_WEIGHT;
        }
    }

    private static ExecutorService createWorkerPool(final int threads) {
        return RuntimeThreads.fixedLane(
                threads,
                WORK_QUEUE_CAPACITY,
                RuntimeThreads.daemonThreads(
                        WORKER_PREFIX, RuntimeThreads.contextClassLoader(ExtendedInterpolationParallelBridge.class)));
    }

    private static Handles resolveHandles(final Class<?> modelSourceClass) throws Exception {
        Handles cached = cachedHandles;
        if (cached != null && cached.modelSourceClass == modelSourceClass) {
            return cached;
        }
        synchronized (ExtendedInterpolationParallelBridge.class) {
            cached = cachedHandles;
            if (cached != null && cached.modelSourceClass == modelSourceClass) {
                return cached;
            }
            cached = new Handles(modelSourceClass);
            cachedHandles = cached;
            return cached;
        }
    }

    private static Field openField(final Class<?> owner, final String name) throws NoSuchFieldException {
        final Field field = owner.getDeclaredField(name);
        ReflectAccess.open(field);
        return field;
    }

    private static MethodHandle tryOptionalMethod(
            final Class<?> owner, final String name, final MethodType erasedType) {
        if (owner == null) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().unreflect(owner.getMethod(name)).asType(erasedType);
        } catch (final ReflectiveOperationException unavailable) {
            return null;
        }
    }

    private static Class<?> tryOptionalClass(final String name, final ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (final ClassNotFoundException unavailable) {
            return null;
        }
    }

    record WeightedExtension(Object extension, long weight) {}

    @FunctionalInterface
    interface ExtensionTask {
        void run(Object extension) throws Throwable;
    }

    private static final class Handles {
        private final Class<?> modelSourceClass;
        private final Field setupFlag;
        private final Field drawableSourceSet;
        private final Field deformerSourceSet;
        private final Class<?> controllableClass;
        private final MethodHandle getExtensions;
        private final MethodHandle getKeyforms;
        private final Class<?> meshFormClass;
        private final MethodHandle meshGetPositions;
        private final Class<?> warpFormClass;
        private final MethodHandle warpGetPositions;
        private final Class<?> extInterpClass;
        private final MethodHandle setupFormStructure;

        Handles(final Class<?> modelSourceClass)
                throws NoSuchFieldException, NoSuchMethodException, IllegalAccessException, ClassNotFoundException {
            this.modelSourceClass = modelSourceClass;
            final ClassLoader loader = modelSourceClass.getClassLoader();
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            this.setupFlag = openField(modelSourceClass, FIELD_SETUP_FLAG);
            this.drawableSourceSet = openField(modelSourceClass, FIELD_DRAWABLE_SET);
            this.deformerSourceSet = openField(modelSourceClass, FIELD_DEFORMER_SET);
            this.controllableClass = Class.forName(CONTROLLABLE_CLASS, false, loader);
            this.getExtensions = lookup.unreflect(this.controllableClass.getMethod(METHOD_GET_EXTENSIONS))
                    .asType(MethodType.methodType(Object.class, Object.class));
            this.getKeyforms = tryOptionalMethod(
                    this.controllableClass, METHOD_GET_KEYFORMS, MethodType.methodType(Object.class, Object.class));
            this.meshFormClass = tryOptionalClass(ART_MESH_FORM_CLASS, loader);
            this.meshGetPositions = tryOptionalMethod(
                    this.meshFormClass, METHOD_GET_POSITIONS, MethodType.methodType(float[].class, Object.class));
            this.warpFormClass = tryOptionalClass(WARP_DEFORMER_FORM_CLASS, loader);
            this.warpGetPositions = tryOptionalMethod(
                    this.warpFormClass, METHOD_GET_POSITIONS, MethodType.methodType(float[].class, Object.class));
            this.extInterpClass = Class.forName(EXT_INTERP_CLASS, false, loader);
            this.setupFormStructure = lookup.unreflect(
                            this.extInterpClass.getMethod(METHOD_SETUP_FORM_STRUCTURE, boolean.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class, boolean.class));
        }
    }
}
