package dev.turboism.adapter.cubism.modelopen;

import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Executes independent {@code CExtendedInterpolationExtension.setupFormStructure(false, true)}
 * builds concurrently during first-time {@code CModelSource.setup()}.
 */
public final class ExtendedInterpolationParallelBridge {

    private static final int MIN_PARALLEL_EXTENSIONS = 2;
    private static final int MAX_POOL_THREADS = 8;
    private static final int WORK_QUEUE_CAPACITY = 4096;
    private static final String WORKER_PREFIX = RuntimeThreads.THREAD_NAME_PREFIX + "-ext-interp-setup";
    private static final String FIELD_SETUP_FLAG = "setupFlag";
    private static final String FIELD_DRAWABLE_SET = "drawableSourceSet";
    private static final String FIELD_DEFORMER_SET = "deformerSourceSet";
    private static final String METHOD_GET_EXTENSIONS = "getExtensions";
    private static final String METHOD_SETUP_FORM_STRUCTURE = "setupFormStructure";
    private static final String EXT_INTERP_CLASS =
            "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension";
    private static final String CONTROLLABLE_CLASS = "com.live2d.cubism.doc.model.ACParameterControllableSource";

    private static final ExecutorService WORKER_POOL = createWorkerPool();
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
            final List<Object> extensions = new ArrayList<>();
            collectExtensions(h.drawableSourceSet.get(modelSource), h, extensions);
            collectExtensions(h.deformerSourceSet.get(modelSource), h, extensions);
            if (extensions.size() < MIN_PARALLEL_EXTENSIONS) {
                return;
            }

            final Set<Object> completed = Collections.newSetFromMap(new IdentityHashMap<>(extensions.size() * 2));
            final List<Future<Object>> futures = new ArrayList<>(extensions.size());
            for (final Object ext : extensions) {
                futures.add(WORKER_POOL.submit(() -> {
                    WORKER_ACTIVE.set(Boolean.TRUE);
                    try {
                        h.setupFormStructure.invoke(ext, Boolean.FALSE, Boolean.TRUE);
                        return ext;
                    } catch (final Throwable failure) {
                        FatalErrors.rethrowIfFatal(failure);
                        return null;
                    } finally {
                        WORKER_ACTIVE.set(Boolean.FALSE);
                    }
                }));
            }
            for (final Future<Object> future : futures) {
                final Object done = future.get();
                if (done != null) {
                    completed.add(done);
                }
            }
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

    private static void collectExtensions(final Object sourceSet, final Handles h, final List<Object> out)
            throws Exception {
        if (!(sourceSet instanceof Iterable<?> iterable)) {
            return;
        }
        for (final Object element : iterable) {
            if (element == null || !h.controllableClass.isInstance(element)) {
                continue;
            }
            final Object extObj = h.getExtensions.invoke(element);
            if (!(extObj instanceof Iterable<?> extList)) {
                continue;
            }
            for (final Object ext : extList) {
                if (h.extInterpClass.isInstance(ext)) {
                    out.add(ext);
                    break;
                }
            }
        }
    }

    private static ExecutorService createWorkerPool() {
        final int threads = Math.max(
                MIN_PARALLEL_EXTENSIONS,
                Math.min(MAX_POOL_THREADS, Runtime.getRuntime().availableProcessors()));
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

    private static final class Handles {
        private final Class<?> modelSourceClass;
        private final Field setupFlag;
        private final Field drawableSourceSet;
        private final Field deformerSourceSet;
        private final Class<?> controllableClass;
        private final Method getExtensions;
        private final Class<?> extInterpClass;
        private final Method setupFormStructure;

        Handles(final Class<?> modelSourceClass)
                throws NoSuchFieldException, NoSuchMethodException, ClassNotFoundException {
            this.modelSourceClass = modelSourceClass;
            final ClassLoader loader = modelSourceClass.getClassLoader();
            this.setupFlag = openField(modelSourceClass, FIELD_SETUP_FLAG);
            this.drawableSourceSet = openField(modelSourceClass, FIELD_DRAWABLE_SET);
            this.deformerSourceSet = openField(modelSourceClass, FIELD_DEFORMER_SET);
            this.controllableClass = Class.forName(CONTROLLABLE_CLASS, false, loader);
            this.getExtensions = this.controllableClass.getMethod(METHOD_GET_EXTENSIONS);
            this.extInterpClass = Class.forName(EXT_INTERP_CLASS, false, loader);
            this.setupFormStructure =
                    this.extInterpClass.getMethod(METHOD_SETUP_FORM_STRUCTURE, boolean.class, boolean.class);
        }
    }
}
