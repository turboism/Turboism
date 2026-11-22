package dev.turboism.adapter.cubism.exportperf;

import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Parallelizes multi-page {@code CTextureAtlas.updateTexture} rebuilds with safe source warm-up.
 *
 * <p>Pre-decodes source model images sequentially before fanning out independent atlas pages so
 * lazy {@code CModelImage.getFilteredImage} initialization never races across workers.</p>
 */
public final class AtlasRebuildParallelizer {

    private static final int MAX_POOL_THREADS = 8;
    private static final int MIN_PARALLEL_PAGES = 2;
    private static final int WORK_QUEUE_CAPACITY = 256;
    private static final String WORKER_PREFIX = RuntimeThreads.THREAD_NAME_PREFIX + "-atlas-rebuild";
    private static final String FIELD_CACHED_ATLAS_IMAGE = "cachedAtlasImage";
    private static final String FIELD_LOCK_CACHED_ATLAS_IMAGE = "lockCachedAtlasImage";
    private static final String FIELD_IS_DIRTY_CACHED_ATLAS_IMAGE = "isDirty_cachedAtlasImage";
    private static final String METHOD_GET_TEXTURE_ATLASES = "getTextureAtlases";

    private static final ExecutorService WORKER_POOL = createWorkerPool();
    private static volatile Handles handles;
    private static volatile TextureManagerHandles managerHandles;

    private AtlasRebuildParallelizer() {}

    /**
     * Rebuilds dirty atlas pages on {@code textureManager} concurrently before serial ArtMesh
     * switching calls {@code CTextureAtlas.getCachedImage()} one page at a time.
     *
     * @param textureManager host {@code CTextureManager} switching to atlas input mode
     */
    public static void prewarmDirtyAtlases(final Object textureManager) {
        if (textureManager == null) {
            return;
        }
        try {
            final TextureManagerHandles mh = resolveManagerHandles(textureManager.getClass());
            final Object atlasesObj = mh.getTextureAtlases.invoke(textureManager);
            if (!(atlasesObj instanceof List<?> allAtlases) || allAtlases.size() < MIN_PARALLEL_PAGES) {
                return;
            }
            final List<Object> dirtyAtlases = new ArrayList<>(allAtlases.size());
            final Handles h = resolveHandles(allAtlases.get(0).getClass());
            for (final Object atlas : allAtlases) {
                if (atlas == null) {
                    continue;
                }
                final Object cached = h.cachedAtlasImage.get(atlas);
                final boolean locked = h.lockCachedAtlasImage.getBoolean(atlas);
                final boolean dirty = h.isDirty.getBoolean(atlas);
                if (cached == null || (!locked && dirty)) {
                    dirtyAtlases.add(atlas);
                }
            }
            if (dirtyAtlases.size() >= MIN_PARALLEL_PAGES) {
                runParallelUpdate(dirtyAtlases, null);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Rebuilds all atlas pages in {@code atlases}, running concurrently when multiple pages exist.
     *
     * @param atlases list of {@code CTextureAtlas} pages queued for update
     * @param progress host progress reporter instance
     * @return {@code true} if handled here, {@code false} to fall back to the stock loop
     */
    public static boolean runParallelUpdate(final List<?> atlases, final Object progress) {
        if (atlases == null || atlases.size() < MIN_PARALLEL_PAGES) {
            return false;
        }
        try {
            final Handles h = resolveHandles(atlases.get(0).getClass());
            prewarmFilteredImages(atlases, h);

            final List<Future<?>> futures = new ArrayList<>(atlases.size());
            for (final Object atlas : atlases) {
                final Callable<Void> task = () -> {
                    try {
                        h.updateTexture.invoke(atlas, Boolean.TRUE, Boolean.TRUE, null);
                    } catch (Throwable failure) {
                        FatalErrors.rethrowIfFatal(failure);
                        failure.printStackTrace();
                    }
                    return null;
                };
                futures.add(WORKER_POOL.submit(task));
            }

            Throwable firstError = null;
            for (final Future<?> future : futures) {
                try {
                    future.get();
                    if (progress != null) {
                        h.progressStep.invoke(progress, 1);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    cancelRemaining(futures);
                    throw new RuntimeException(interrupted);
                } catch (ExecutionException exec) {
                    if (firstError == null) {
                        firstError = exec.getCause();
                    }
                }
            }
            if (firstError != null) {
                if (firstError instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (firstError instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(firstError);
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private static void prewarmFilteredImages(final List<?> atlases, final Handles h) throws Exception {
        for (final Object atlas : atlases) {
            if (atlas == null) {
                continue;
            }
            final Object entries = h.getModelImages.invoke(atlas);
            if (!(entries instanceof Iterable<?> entryList)) {
                continue;
            }
            for (final Object entry : entryList) {
                if (entry == null) {
                    continue;
                }
                final Object modelImage = h.getModelImage.invoke(entry);
                if (modelImage != null) {
                    final Object res = h.getFilteredImage.invoke(modelImage);
                    if (res != null) {
                        h.getResImage.invoke(res);
                    }
                }
            }
        }
    }

    private static void cancelRemaining(final List<Future<?>> futures) {
        for (final Future<?> future : futures) {
            future.cancel(true);
        }
    }

    private static ExecutorService createWorkerPool() {
        final int threads = Math.max(
                MIN_PARALLEL_PAGES,
                Math.min(MAX_POOL_THREADS, Runtime.getRuntime().availableProcessors()));
        return RuntimeThreads.fixedLane(
                threads,
                WORK_QUEUE_CAPACITY,
                RuntimeThreads.daemonThreads(
                        WORKER_PREFIX, RuntimeThreads.contextClassLoader(AtlasRebuildParallelizer.class)));
    }

    private static Handles resolveHandles(final Class<?> atlasClass) throws Exception {
        Handles cached = handles;
        if (cached != null && cached.atlasClass == atlasClass) {
            return cached;
        }
        synchronized (AtlasRebuildParallelizer.class) {
            cached = handles;
            if (cached != null && cached.atlasClass == atlasClass) {
                return cached;
            }
            cached = new Handles(atlasClass);
            handles = cached;
            return cached;
        }
    }

    private static TextureManagerHandles resolveManagerHandles(final Class<?> managerClass) throws Exception {
        TextureManagerHandles cached = managerHandles;
        if (cached != null && cached.managerClass == managerClass) {
            return cached;
        }
        synchronized (AtlasRebuildParallelizer.class) {
            cached = managerHandles;
            if (cached != null && cached.managerClass == managerClass) {
                return cached;
            }
            cached = new TextureManagerHandles(managerClass);
            managerHandles = cached;
            return cached;
        }
    }

    private static Field openField(final Class<?> owner, final String name) throws NoSuchFieldException {
        final Field field = owner.getDeclaredField(name);
        ReflectAccess.open(field);
        return field;
    }

    private static final class TextureManagerHandles {
        private final Class<?> managerClass;
        private final Method getTextureAtlases;

        TextureManagerHandles(final Class<?> managerClass) throws NoSuchMethodException {
            this.managerClass = managerClass;
            this.getTextureAtlases = managerClass.getMethod(METHOD_GET_TEXTURE_ATLASES);
        }
    }

    private static final class Handles {
        private final Class<?> atlasClass;
        private final Method updateTexture;
        private final Method progressStep;
        private final Method getModelImages;
        private final Method getModelImage;
        private final Method getFilteredImage;
        private final Method getResImage;
        private final Field cachedAtlasImage;
        private final Field lockCachedAtlasImage;
        private final Field isDirty;

        Handles(final Class<?> atlasClass) throws NoSuchMethodException, NoSuchFieldException, ClassNotFoundException {
            this.atlasClass = atlasClass;
            final ClassLoader loader = atlasClass.getClassLoader();
            final Class<?> progressClass = Class.forName("com.live2d.util.a.a", false, loader);
            this.updateTexture = atlasClass.getMethod("updateTexture", boolean.class, boolean.class, progressClass);
            this.progressStep = progressClass.getMethod("b", int.class);
            this.getModelImages = atlasClass.getMethod("getModelImages");
            this.cachedAtlasImage = openField(atlasClass, FIELD_CACHED_ATLAS_IMAGE);
            this.lockCachedAtlasImage = openField(atlasClass, FIELD_LOCK_CACHED_ATLAS_IMAGE);
            this.isDirty = openField(atlasClass, FIELD_IS_DIRTY_CACHED_ATLAS_IMAGE);

            final Class<?> entryClass = Class.forName(
                    "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas$ModelImageEntry", false, loader);
            this.getModelImage = entryClass.getMethod("getModelImage");

            final Class<?> miClass =
                    Class.forName("com.live2d.cubism.doc.model.texture.modelImage.CModelImage", false, loader);
            this.getFilteredImage = miClass.getMethod("getFilteredImage");

            final Class<?> resClass = Class.forName("com.live2d.graphics.CImageResource", false, loader);
            this.getResImage = resClass.getMethod("getImage");
        }
    }
}
