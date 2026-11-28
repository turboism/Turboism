package dev.turboism.adapter.cubism.optimization.atlas;

import dev.turboism.adapter.cubism.optimization.atlas.cache.AtlasCacheReuseDelegate;
import dev.turboism.adapter.cubism.optimization.atlas.cache.AtlasCacheReusePatcher;
import dev.turboism.adapter.cubism.optimization.atlas.cache.AtlasPageScheduler;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Coordinates safe 3-phase multi-page atlas rebuilds across {@code TextureManagerHandler} and
 * {@code TAE_DataModel} confirm flows: owner-thread prewarm, bounded parallel CPU rasterization
 * via {@link AtlasPageScheduler#parallel()}, and owner-thread resource/progress commit.
 */
public final class AtlasPageRebuildCoordinator {

    private static final int MIN_PARALLEL_PAGES = 2;
    private static final int PROGRESS_UNIT = 1;
    private static final String CLASS_TEXTURE_ATLAS = "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas";
    private static final String CLASS_TAE_DATA_MODEL =
            "com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.TAE_DataModel";
    private static final String CLASS_PROGRESS = "com.live2d.util.a.a";
    private static final String CLASS_FUNCTION1 = "kotlin.jvm.functions.Function1";
    private static final String CLASS_UNIT = "kotlin.Unit";

    private static final ThreadLocal<List<ConfirmEntry>> CONFIRM_QUEUE = new ThreadLocal<>();

    private static final ClassValue<Optional<HostCommitHandles>> COMMIT_HANDLES = new ClassValue<>() {
        @Override
        protected Optional<HostCommitHandles> computeValue(final Class<?> callerClass) {
            return Optional.ofNullable(HostCommitHandles.resolve(callerClass));
        }
    };

    private static final ClassValue<Optional<ConfirmTaskHandles>> CONFIRM_HANDLES = new ClassValue<>() {
        @Override
        protected Optional<ConfirmTaskHandles> computeValue(final Class<?> taskClass) {
            return Optional.ofNullable(ConfirmTaskHandles.resolve(taskClass));
        }
    };

    private AtlasPageRebuildCoordinator() {}

    /**
     * Runs multi-page rebuilds for {@code TextureManagerHandler} ({@code doc.model.texture.G}) using
     * bounded parallel CPU rasterization and strictly owner-thread resource/cache commits.
     *
     * @param atlases ordered list of {@code CTextureAtlas} pages
     * @param progress host {@code com.live2d.util.a.a} progress reporter, possibly {@code null}
     * @return {@code true} if handled; {@code false} for serial single-page fallback
     */
    public static boolean runParallelTextureUpdates(final List<?> atlases, final Object progress) {
        if (atlases == null || atlases.size() < MIN_PARALLEL_PAGES) {
            return false;
        }
        final Object firstAtlas = atlases.get(0);
        if (firstAtlas == null) {
            return false;
        }
        final Class<?> atlasClass = firstAtlas.getClass();
        final HostCommitHandles h = COMMIT_HANDLES.get(atlasClass).orElse(null);
        if (h == null) {
            return false;
        }
        return executeThreePhaseRebuild(atlasClass, h, buildTextureUpdateEntries(atlases), progress);
    }

    /**
     * Captures a queued confirm rebuild step from patched {@code atlasEditor.impl.c} when a batch
     * scope is active, or falls back directly to {@code progress.a(steps, fn)}.
     */
    public static void enqueueConfirmPage(final Object progress, final int steps, final Object function1) {
        final List<ConfirmEntry> queue = CONFIRM_QUEUE.get();
        if (queue != null && function1 != null) {
            queue.add(new ConfirmEntry(progress, steps, function1));
            return;
        }
        if (progress != null && function1 != null) {
            final HostCommitHandles h = COMMIT_HANDLES.get(progress.getClass()).orElse(null);
            if (h != null) {
                h.invokeProgressSubTask(progress, steps, function1);
            }
        }
    }

    /**
     * Wraps {@code atlasEditor.impl.c.a(progress)} so serial page/layer model updates complete first
     * and unlocked pages bake their CPU rasters in parallel before sequential owner-thread commits.
     */
    public static void runConfirmBatch(
            final Class<?> callerClass, final Object cInstance, final Object progress, final MethodHandle origLoop)
            throws Throwable {
        final List<ConfirmEntry> queue = new ArrayList<>();
        CONFIRM_QUEUE.set(queue);
        try {
            origLoop.invoke(cInstance, progress);
        } finally {
            CONFIRM_QUEUE.remove();
        }
        if (queue.isEmpty()) {
            return;
        }
        final HostCommitHandles h = COMMIT_HANDLES.get(callerClass).orElse(null);
        if (h == null || queue.size() < MIN_PARALLEL_PAGES) {
            runSerialConfirmQueue(h, queue);
            return;
        }

        final List<PageWork> works = new ArrayList<>(queue.size());
        for (final ConfirmEntry entry : queue) {
            final ConfirmTaskHandles th =
                    CONFIRM_HANDLES.get(entry.function1.getClass()).orElse(null);
            if (th == null) {
                runSerialConfirmQueue(h, queue);
                return;
            }
            final Object atlas = th.getAtlas(entry.function1);
            final Object taeModel = th.getTaeModel(entry.function1);
            if (atlas == null || taeModel == null) {
                runSerialConfirmQueue(h, queue);
                return;
            }
            final boolean edgeFill = th.getEdgeFill(taeModel);
            works.add(new PageWork(atlas, edgeFill, entry.progress, entry.steps, entry.function1));
        }

        final Class<?> atlasClass = works.get(0).atlas.getClass();
        if (!executeThreePhaseRebuild(atlasClass, h, works, progress)) {
            runSerialConfirmQueue(h, queue);
        }
    }

    private static List<PageWork> buildTextureUpdateEntries(final List<?> atlases) {
        final List<PageWork> works = new ArrayList<>(atlases.size());
        for (final Object atlas : atlases) {
            if (atlas != null) {
                works.add(new PageWork(atlas, true, null, PROGRESS_UNIT, null));
            }
        }
        return works;
    }

    private static boolean executeThreePhaseRebuild(
            final Class<?> atlasClass,
            final HostCommitHandles h,
            final List<PageWork> pages,
            final Object outerProgress) {
        if (pages.size() < MIN_PARALLEL_PAGES) {
            return false;
        }
        if (h.isCanceled(outerProgress)) {
            return true;
        }

        // Phase 1: Lightweight owner-thread input prewarm without SHA-256 hashing or pixel blits.
        final Object[] prewarmedPages = new Object[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            final PageWork page = pages.get(i);
            prewarmedPages[i] = AtlasTilePageCompositor.prewarmAtlasInputs(atlasClass, page.atlas, page.edgeFill);
        }

        final boolean cacheReuseActive = AtlasCacheReusePatcher.isPatched();
        final AtlasCacheReuseDelegate.BatchDigestCache digestCache =
                cacheReuseActive ? AtlasCacheReuseDelegate.newBatchDigestCache() : null;
        final AtlasCacheReuseDelegate.PreparedPageReuse[] preparedReuses =
                new AtlasCacheReuseDelegate.PreparedPageReuse[pages.size()];
        final Object[] bakedBuffers = new Object[pages.size()];
        try {
            // Phase 2: Bounded parallel CPU reuse checks, image copies, tile compositing, and output digests.
            final List<Runnable> workerTasks = new ArrayList<>(pages.size());
            for (int i = 0; i < pages.size(); i++) {
                final int idx = i;
                final PageWork page = pages.get(idx);
                final Object prewarmed = prewarmedPages[idx];
                workerTasks.add(() -> {
                    if (h.isCanceled(outerProgress)) {
                        return;
                    }
                    AtlasCacheReuseDelegate.PreparedPageReuse decision = null;
                    if (cacheReuseActive) {
                        decision = AtlasCacheReuseDelegate.prepareReuseOffEdt(
                                atlasClass, page.atlas, page.edgeFill, digestCache);
                        preparedReuses[idx] = decision;
                        if (decision != null && decision.isReuse()) {
                            return;
                        }
                    }
                    AtlasTilePageCompositor.enterMultiPageWorker();
                    try {
                        final Object baked = prewarmed != null
                                ? AtlasTilePageCompositor.bakePrewarmedWritableImage(atlasClass, prewarmed, false)
                                : AtlasTilePageCompositor.bakeAtlasWritableImage(
                                        atlasClass, page.atlas, page.edgeFill, null, false);
                        bakedBuffers[idx] = baked;
                        if (cacheReuseActive && decision != null && baked != null) {
                            AtlasCacheReuseDelegate.prepareBakedOutputOffEdt(atlasClass, decision, baked);
                        }
                    } finally {
                        AtlasTilePageCompositor.exitMultiPageWorker();
                    }
                });
            }
            AtlasPageScheduler.forPolicy(AtlasPageScheduler.Policy.PARALLEL_UNIFORM)
                    .runPages(workerTasks);

            // Phase 3: Sequential owner-thread commit of resources, cache manager, and Swing progress.
            for (int i = 0; i < pages.size(); i++) {
                if (h.isCanceled(outerProgress)) {
                    break;
                }
                final PageWork page = pages.get(i);
                final Object targetProgress = page.progress != null ? page.progress : outerProgress;
                final AtlasCacheReuseDelegate.PreparedPageReuse prep = preparedReuses[i];
                final boolean reused = prep != null && prep.isReuse();
                final Object baked = bakedBuffers[i];
                bakedBuffers[i] = null;
                if (prep != null) {
                    AtlasCacheReuseDelegate.beginPreparedCommit(prep);
                }
                try {
                    if (baked != null && !reused) {
                        AtlasTilePageCompositor.putPrebakedImage(page.atlas, baked);
                    }
                    try {
                        if (page.confirmLambda != null && targetProgress != null) {
                            h.invokeProgressSubTask(targetProgress, page.steps, page.confirmLambda);
                        } else {
                            commitTexturePageWithProgress(h, page.atlas, page.edgeFill, targetProgress);
                        }
                    } finally {
                        if (baked != null) {
                            AtlasTilePageCompositor.consumePrebakedImage(page.atlas);
                        }
                    }
                } finally {
                    if (prep != null) {
                        AtlasCacheReuseDelegate.endPreparedCommit();
                    }
                }
            }
            return true;
        } finally {
            for (final Object leftover : bakedBuffers) {
                if (leftover != null) {
                    AtlasTilePageCompositor.flushWritableImage(atlasClass, leftover);
                }
            }
            for (final AtlasCacheReuseDelegate.PreparedPageReuse leftoverReuse : preparedReuses) {
                if (leftoverReuse != null) {
                    final Object unusedCopy = leftoverReuse.takeSuppliedWritable();
                    if (unusedCopy != null) {
                        AtlasTilePageCompositor.flushWritableImage(atlasClass, unusedCopy);
                    }
                }
            }
            AtlasTilePageCompositor.clearPrebakedImages(atlasClass);
        }
    }

    private static void commitTexturePageWithProgress(
            final HostCommitHandles h, final Object atlas, final boolean edgeFill, final Object progress) {
        if (progress == null) {
            h.updateTexture(atlas, true, edgeFill, null);
            return;
        }
        final Object fn = h.createUpdateSubTask(atlas, edgeFill, progress);
        if (fn != null) {
            h.invokeProgressSubTask(progress, PROGRESS_UNIT, fn);
        } else {
            h.updateTexture(atlas, true, edgeFill, progress);
        }
    }

    private static void runSerialConfirmQueue(final HostCommitHandles h, final List<ConfirmEntry> queue) {
        if (h == null) {
            return;
        }
        for (final ConfirmEntry entry : queue) {
            h.invokeProgressSubTask(entry.progress, entry.steps, entry.function1);
        }
    }

    private record ConfirmEntry(Object progress, int steps, Object function1) {}

    private record PageWork(Object atlas, boolean edgeFill, Object progress, int steps, Object confirmLambda) {}

    private static final class ConfirmTaskHandles {
        private final Field atlasField;
        private final Field taeModelField;
        private final MethodHandle getEdgeFill;

        private ConfirmTaskHandles(final Field atlasField, final Field taeModelField, final MethodHandle getEdgeFill) {
            this.atlasField = atlasField;
            this.taeModelField = taeModelField;
            this.getEdgeFill = getEdgeFill;
        }

        static ConfirmTaskHandles resolve(final Class<?> taskClass) {
            try {
                final ClassLoader loader = taskClass.getClassLoader();
                final Class<?> atlasClass = Class.forName(CLASS_TEXTURE_ATLAS, false, loader);
                final Class<?> taeClass = Class.forName(CLASS_TAE_DATA_MODEL, false, loader);
                Field foundAtlas = null;
                Field foundModel = null;
                for (final Field field : taskClass.getDeclaredFields()) {
                    if (atlasClass.equals(field.getType())) {
                        field.setAccessible(true);
                        foundAtlas = field;
                    } else if (taeClass.equals(field.getType())) {
                        field.setAccessible(true);
                        foundModel = field;
                    }
                }
                if (foundAtlas == null || foundModel == null) {
                    return null;
                }
                final Method gMethod = taeClass.getMethod("g");
                return new ConfirmTaskHandles(
                        foundAtlas, foundModel, MethodHandles.lookup().unreflect(gMethod));
            } catch (final ReflectiveOperationException | LinkageError failure) {
                return null;
            }
        }

        Object getAtlas(final Object task) {
            try {
                return atlasField.get(task);
            } catch (final IllegalAccessException failure) {
                return null;
            }
        }

        Object getTaeModel(final Object task) {
            try {
                return taeModelField.get(task);
            } catch (final IllegalAccessException failure) {
                return null;
            }
        }

        boolean getEdgeFill(final Object taeModel) {
            try {
                return (boolean) getEdgeFill.invoke(taeModel);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return true;
            }
        }
    }

    private static final class HostCommitHandles {
        private final ClassLoader loader;
        private final MethodHandle updateTexture;
        private final MethodHandle isCanceled;
        private final MethodHandle subTaskProgress;
        private final Class<?> function1Class;
        private final Object unitInstance;

        private HostCommitHandles(
                final ClassLoader loader,
                final MethodHandle updateTexture,
                final MethodHandle isCanceled,
                final MethodHandle subTaskProgress,
                final Class<?> function1Class,
                final Object unitInstance) {
            this.loader = loader;
            this.updateTexture = updateTexture;
            this.isCanceled = isCanceled;
            this.subTaskProgress = subTaskProgress;
            this.function1Class = function1Class;
            this.unitInstance = unitInstance;
        }

        static HostCommitHandles resolve(final Class<?> callerClass) {
            try {
                final ClassLoader loader = callerClass.getClassLoader();
                final MethodHandles.Lookup lookup = MethodHandles.lookup();
                final Class<?> atlasClass = Class.forName(CLASS_TEXTURE_ATLAS, false, loader);
                final Class<?> progressClass = Class.forName(CLASS_PROGRESS, false, loader);
                final Class<?> function1Class = Class.forName(CLASS_FUNCTION1, false, loader);
                final Class<?> unitClass = Class.forName(CLASS_UNIT, false, loader);
                final Object unitInstance = unitClass.getField("INSTANCE").get(null);

                final Method updateTexM =
                        atlasClass.getMethod("updateTexture", boolean.class, boolean.class, progressClass);
                final Method cancelM = progressClass.getMethod("c");
                final Method subTaskM = progressClass.getMethod("a", int.class, function1Class);

                return new HostCommitHandles(
                        loader,
                        lookup.unreflect(updateTexM),
                        lookup.unreflect(cancelM),
                        lookup.unreflect(subTaskM),
                        function1Class,
                        unitInstance);
            } catch (final ReflectiveOperationException | LinkageError failure) {
                return null;
            }
        }

        boolean isCanceled(final Object progress) {
            if (progress == null) {
                return false;
            }
            try {
                return (boolean) isCanceled.invoke(progress);
            } catch (final Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                return false;
            }
        }

        void invokeProgressSubTask(final Object progress, final int steps, final Object function1) {
            try {
                subTaskProgress.invoke(progress, steps, function1);
            } catch (final RuntimeException | Error e) {
                throw e;
            } catch (final Throwable t) {
                FatalErrors.rethrowIfFatal(t);
                throw new IllegalStateException("Failed progress subtask", t);
            }
        }

        Object createUpdateSubTask(final Object atlas, final boolean edgeFill, final Object progress) {
            return Proxy.newProxyInstance(loader, new Class<?>[] {function1Class}, (proxy, method, args) -> {
                updateTexture(atlas, true, edgeFill, progress);
                return unitInstance;
            });
        }

        void updateTexture(final Object atlas, final boolean rebuild, final boolean edgeFill, final Object progress) {
            try {
                updateTexture.invoke(atlas, rebuild, edgeFill, progress);
            } catch (final RuntimeException | Error e) {
                throw e;
            } catch (final Throwable t) {
                FatalErrors.rethrowIfFatal(t);
                throw new IllegalStateException("Atlas texture update failed", t);
            }
        }
    }
}
