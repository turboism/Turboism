package dev.turboism.adapter.cubism.optimization.atlas;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;

/**
 * Orchestrates fast direct-{@code int[]} tile compositing and conflict-free wave parallelization
 * for {@code CTextureAtlas.setupCacheImage$cubism} without static references to host Cubism types.
 */
public final class AtlasTilePageCompositor {

    private static final double CUBISM_DOWNSCALE_THRESHOLD = 0.45;
    private static final int EDGE_FILL_DISTANCE = 3;
    private static final int EXACT_GRID_PAD_PX = 0;
    private static final int FILTERED_EDGE_PAD_PX = 3;
    private static final int UNFILTERED_PAD_PX = 1;
    private static final int MIN_PARALLEL_WAVE_TILES = 2;
    private static final long PARALLEL_WAVE_AREA_THRESHOLD = 262_144L;
    private static final int MATRIX_SIZE = 6;
    private static final double MATRIX_EPSILON = 1e-6;
    private static final String CLASS_WRITABLE_IMAGE = "com.live2d.graphics.CWritableImage";
    private static final String CLASS_COLOR_TYPE = "com.live2d.graphics.n";
    private static final String FIELD_ARGB_TYPE = "c";
    private static final String CLASS_PROGRESS = "com.live2d.util.a.a";
    private static final String CLASS_EDGE_HELPER_50 = "com.live2d.util.C";
    private static final String CLASS_EDGE_HELPER_52 = "com.live2d.util.D";
    private static final String CLASS_GRAPHICS_HINTS = "jp.noids.graphics.i";

    private static final ThreadLocal<IdentityHashMap<Object, Object>> PREBAKED_IMAGES = new ThreadLocal<>();
    private static final ThreadLocal<Integer> MULTI_PAGE_WORKER_DEPTH = ThreadLocal.withInitial(() -> 0);

    private static final ClassValue<Optional<AtlasHostHandles>> ATLAS_HANDLES = new ClassValue<>() {
        @Override
        protected Optional<AtlasHostHandles> computeValue(final Class<?> atlasClass) {
            return Optional.ofNullable(AtlasHostHandles.resolve(atlasClass));
        }
    };

    private static final ClassValue<Optional<MethodHandle>> HINT_BICUBIC_HANDLE = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(final Class<?> hostClass) {
            return Optional.ofNullable(resolveBicubicHintHandle(hostClass.getClassLoader()));
        }
    };

    private AtlasTilePageCompositor() {}

    /** Marks the current thread as inside an outer multi-page worker so inner tile waves stay serial. */
    public static void enterMultiPageWorker() {
        MULTI_PAGE_WORKER_DEPTH.set(MULTI_PAGE_WORKER_DEPTH.get() + 1);
    }

    /** Clears the outer multi-page worker marker on completion. */
    public static void exitMultiPageWorker() {
        final int depth = MULTI_PAGE_WORKER_DEPTH.get();
        if (depth <= 1) {
            MULTI_PAGE_WORKER_DEPTH.remove();
        } else {
            MULTI_PAGE_WORKER_DEPTH.set(depth - 1);
        }
    }

    static boolean inMultiPageWorker() {
        return MULTI_PAGE_WORKER_DEPTH.get() > 0;
    }

    /** Stores a pre-baked {@code CWritableImage} for {@code atlas} until owner commit consumes it. */
    public static void putPrebakedImage(final Object atlas, final Object writableImage) {
        if (atlas == null || writableImage == null) {
            return;
        }
        IdentityHashMap<Object, Object> map = PREBAKED_IMAGES.get();
        if (map == null) {
            map = new IdentityHashMap<>();
            PREBAKED_IMAGES.set(map);
        }
        map.put(atlas, writableImage);
    }

    /** Consumes and returns a previously pre-baked {@code CWritableImage} for {@code atlas}, or {@code null}. */
    public static Object consumePrebakedImage(final Object atlas) {
        final IdentityHashMap<Object, Object> map = PREBAKED_IMAGES.get();
        if (map == null || atlas == null) {
            return null;
        }
        final Object img = map.remove(atlas);
        if (map.isEmpty()) {
            PREBAKED_IMAGES.remove();
        }
        return img;
    }

    /** Flushes and clears any unconsumed pre-baked images when aborting or canceling a batch. */
    public static void clearPrebakedImages(final Class<?> hostClass) {
        final IdentityHashMap<Object, Object> map = PREBAKED_IMAGES.get();
        if (map == null) {
            return;
        }
        PREBAKED_IMAGES.remove();
        final AtlasHostHandles h =
                hostClass != null ? ATLAS_HANDLES.get(hostClass).orElse(null) : null;
        if (h == null) {
            return;
        }
        for (final Object writable : map.values()) {
            h.flushSafely(writable);
        }
    }

    /** Flushes one {@code CWritableImage} safely on cancellation or fallback. */
    public static void flushWritableImage(final Class<?> hostClass, final Object writableImage) {
        if (hostClass == null || writableImage == null) {
            return;
        }
        final AtlasHostHandles h = ATLAS_HANDLES.get(hostClass).orElse(null);
        if (h != null) {
            h.flushSafely(writableImage);
        }
    }

    /**
     * Intercepts {@code CTextureAtlas.setupCacheImage$cubism(boolean, progress)}, consuming any
     * worker-prebaked page buffer or compositing tiles directly before committing on the caller.
     */
    public static void setupCacheImage(
            final Class<?> atlasClass,
            final Object atlas,
            final boolean edgeFill,
            final Object progress,
            final MethodHandle commitHandle,
            final MethodHandle origHandle)
            throws Throwable {
        final AtlasHostHandles h = ATLAS_HANDLES.get(atlasClass).orElse(null);
        if (h == null) {
            origHandle.invoke(atlas, edgeFill, progress);
            return;
        }

        final Object prebaked = consumePrebakedImage(atlas);
        if (prebaked != null) {
            final Object activeProgress = h.unwrapProgress(progress);
            final List<?> entries = h.getModelImages(atlas);
            final int total = entries != null ? entries.size() : 0;
            final String atlasName = h.getAtlasName(atlas);
            h.progressStart(activeProgress, total, atlasName + " Setup Image...");
            if (total > 0) {
                h.progressStep(activeProgress, total);
            }
            commitHandle.invoke(atlas, prebaked, activeProgress);
            return;
        }

        final Object activeProgress = h.unwrapProgress(progress);
        final Object baked = bakeAtlasWritableImage(atlasClass, atlas, edgeFill, activeProgress, true);
        if (baked == null) {
            origHandle.invoke(atlas, edgeFill, progress);
            return;
        }
        commitHandle.invoke(atlas, baked, activeProgress);
    }

    /**
     * Fast path for {@code com.live2d.util.{d,e,f}/g.a(BufferedImage, Graphics, BufferedImage,
     * int, int, double, boolean)} when an exact grid blit bypasses Java2D {@code UtCache} scratch
     * buffers cleanly.
     */
    public static void drawTileWithEdgeFillOrFallback(
            final Class<?> helperClass,
            final Object helperInstance,
            final BufferedImage dst,
            final Graphics pageGraphics,
            final BufferedImage src,
            final int x,
            final int y,
            final double threshold,
            final boolean edgeFill,
            final MethodHandle origDraw)
            throws Throwable {
        if (pageGraphics instanceof Graphics2D g2d && src != null && dst != null) {
            final AffineTransform current = g2d.getTransform();
            final double[] m = new double[MATRIX_SIZE];
            current.getMatrix(m);
            final double scaleX = Math.hypot(m[0], m[1]);
            final double scaleY = scaleX > MATRIX_EPSILON ? Math.abs(m[0] * m[3] - m[1] * m[2]) / scaleX : 0.0;
            final double minScale = Math.min(scaleX, scaleY);
            if (minScale > threshold
                    && DirectArgbTileBlitter.isExactGridBlit(current, x, y)
                    && isSrcOverComposite(g2d)) {
                if (edgeFill) {
                    synchronized (src) {
                        if (!DirectEdgeFillOp.tryEdgeFill(src, EDGE_FILL_DISTANCE)) {
                            synchronized (dst) {
                                origDraw.invoke(helperInstance, dst, pageGraphics, src, x, y, threshold, true);
                            }
                            return;
                        }
                    }
                }
                if (DirectArgbTileBlitter.tryBlit(dst, g2d, src, x, y)) {
                    return;
                }
            }
        }
        origDraw.invoke(helperInstance, dst, pageGraphics, src, x, y, threshold, edgeFill);
    }

    /**
     * Bakes a complete {@code CWritableImage} for {@code atlas} purely in CPU memory without
     * mutating {@code CTextureAtlas.cachedAtlasImage}, {@code CImageResource.cacheList}, or
     * {@code CCachedImageManager}. Returns {@code null} only if handles cannot resolve.
     */
    public static Object bakeAtlasWritableImage(
            final Class<?> atlasClass,
            final Object atlas,
            final boolean edgeFill,
            final Object unwrappedProgress,
            final boolean allowParallelTiles) {
        final AtlasHostHandles h = ATLAS_HANDLES.get(atlasClass).orElse(null);
        if (h == null || atlas == null) {
            return null;
        }
        final PrewarmedPage prepared = resolveAndPrewarmPage(h, atlas, edgeFill, false);
        if (prepared == null) {
            return null;
        }
        return bakePreparedPage(atlasClass, h, prepared, edgeFill, unwrappedProgress, allowParallelTiles);
    }

    /**
     * Bakes a previously owner-thread-prewarmed page purely in CPU memory so worker threads never
     * call {@code CModelImage.getFilteredImage()} or {@code ModelImageEntry.calcModelImageLocalToAtlasTransform()}.
     */
    public static Object bakePrewarmedWritableImage(
            final Class<?> atlasClass, final Object prewarmedPage, final boolean allowParallelTiles) {
        if (!(prewarmedPage instanceof PrewarmedPage page)) {
            return null;
        }
        final AtlasHostHandles h = ATLAS_HANDLES.get(atlasClass).orElse(null);
        if (h == null) {
            return null;
        }
        return bakePreparedPage(atlasClass, h, page, page.edgeFill, null, allowParallelTiles);
    }

    /** Pre-warms every model image, filtered buffer, and affine transform referenced by {@code atlas}. */
    public static Object prewarmAtlasInputs(final Class<?> atlasClass, final Object atlas, final boolean edgeFill) {
        final AtlasHostHandles h = ATLAS_HANDLES.get(atlasClass).orElse(null);
        if (h == null || atlas == null) {
            return null;
        }
        return resolveAndPrewarmPage(h, atlas, edgeFill, false);
    }

    private static PrewarmedPage resolveAndPrewarmPage(
            final AtlasHostHandles h, final Object atlas, final boolean edgeFill, final boolean applyEdgeFillNow) {
        try {
            final int pageW = h.getWidth(atlas);
            final int pageH = h.getHeight(atlas);
            final String atlasName = h.getAtlasName(atlas);
            final Object modelSource = h.getModelSource(atlas);
            final Object textureManager = h.getTextureManager(modelSource);
            final List<?> entries = h.getModelImages(atlas);
            final List<TileWork> tiles = new ArrayList<>(entries.size());
            final Map<BufferedImage, Boolean> visited = applyEdgeFillNow ? new IdentityHashMap<>() : null;
            int totalEntryCount = entries.size();
            for (final Object entry : entries) {
                final TileWork work = resolveTileWork(h, textureManager, entry, pageW, pageH, edgeFill, null);
                if (work == null) {
                    continue;
                }
                if (applyEdgeFillNow && edgeFill && !work.downscales && visited.put(work.srcBi, Boolean.TRUE) == null) {
                    synchronized (work.srcBi) {
                        if (!DirectEdgeFillOp.tryEdgeFill(work.srcBi, EDGE_FILL_DISTANCE)) {
                            h.invokeHostEdgeFill(work.srcBi, EDGE_FILL_DISTANCE);
                        }
                    }
                }
                tiles.add(work);
            }
            return new PrewarmedPage(pageW, pageH, atlasName, totalEntryCount, edgeFill, tiles);
        } catch (final Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static Object bakePreparedPage(
            final Class<?> atlasClass,
            final AtlasHostHandles h,
            final PrewarmedPage page,
            final boolean edgeFill,
            final Object unwrappedProgress,
            final boolean allowParallelTiles) {
        try {
            final int pageW = page.pageW;
            final int pageH = page.pageH;
            final List<TileWork> tiles = page.tiles;
            if (unwrappedProgress != null) {
                h.progressStart(unwrappedProgress, page.totalEntryCount, page.atlasName + " Setup Image...");
                final int skipped = page.totalEntryCount - tiles.size();
                if (skipped > 0) {
                    h.progressStep(unwrappedProgress, skipped);
                }
            }

            final Object targetWritable = h.newWritableImage(pageW, pageH);
            h.fillWritableImage(targetWritable, 0);
            final BufferedImage dstBi = h.getJBufferedImage(targetWritable);
            if (dstBi == null) {
                h.flushSafely(targetWritable);
                return null;
            }

            final Map<BufferedImage, Boolean> edgeFilledSources = new IdentityHashMap<>();
            for (final TileWork work : tiles) {
                if (edgeFill && !work.downscales) {
                    edgeFilledSources.putIfAbsent(work.srcBi, Boolean.TRUE);
                }
            }

            // Pre-apply idempotent edge-fill once per unique source image before any parallel wave reads it.
            for (final BufferedImage srcBi : edgeFilledSources.keySet()) {
                synchronized (srcBi) {
                    if (!DirectEdgeFillOp.tryEdgeFill(srcBi, EDGE_FILL_DISTANCE)) {
                        h.invokeHostEdgeFill(srcBi, EDGE_FILL_DISTANCE);
                    }
                }
            }

            final List<DisjointTileWavePlanner.TileBounds> bounds = new ArrayList<>(tiles.size());
            for (final TileWork work : tiles) {
                bounds.add(work.bounds);
            }
            final List<int[]> waves = DisjointTileWavePlanner.planWaves(bounds);
            final MethodHandle bicubicHint = HINT_BICUBIC_HANDLE.get(atlasClass).orElse(null);

            for (final int[] wave : waves) {
                if (shouldParallelizeWave(allowParallelTiles, tiles, wave)) {
                    executeWaveParallel(h, targetWritable, dstBi, tiles, wave, edgeFill, bicubicHint);
                } else {
                    executeWaveSerial(h, targetWritable, dstBi, tiles, wave, edgeFill, bicubicHint);
                }
                if (unwrappedProgress != null) {
                    for (final int idx : wave) {
                        final String imgName = tiles.get(idx).modelImageName;
                        if (imgName != null) {
                            h.progressText(unwrappedProgress, "draw " + imgName);
                        }
                        h.progressStep(unwrappedProgress, 1);
                    }
                }
            }

            if (edgeFill) {
                if (!DirectEdgeFillOp.tryEdgeFill(dstBi, EDGE_FILL_DISTANCE)) {
                    h.invokeHostEdgeFill(dstBi, EDGE_FILL_DISTANCE);
                }
            }
            return targetWritable;
        } catch (final RuntimeException | Error failure) {
            throw failure;
        } catch (final Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private record PrewarmedPage(
            int pageW, int pageH, String atlasName, int totalEntryCount, boolean edgeFill, List<TileWork> tiles) {}

    private static TileWork resolveTileWork(
            final AtlasHostHandles h,
            final Object textureManager,
            final Object entry,
            final int pageW,
            final int pageH,
            final boolean edgeFill,
            final Object progress)
            throws Throwable {
        final Object guid = h.getEntryGuid(entry);
        final Object modelImage = h.getModelImage(textureManager, guid);
        if (modelImage == null) {
            return null;
        }
        final String modelImageName = h.getModelImageName(modelImage);
        final Object filtered = h.getFilteredImage(modelImage);
        final Object srcWritable = h.getResourceImage(filtered);
        final BufferedImage srcBi = h.getJBufferedImage(srcWritable);
        if (srcBi == null) {
            return null;
        }
        final Object cAffine = h.calcTransform(entry);
        if (!(cAffine instanceof AffineTransform transform)) {
            return null;
        }

        final double[] m = new double[MATRIX_SIZE];
        transform.getMatrix(m);
        final double scaleX = Math.hypot(m[0], m[1]);
        final double scaleY = scaleX > MATRIX_EPSILON ? Math.abs(m[0] * m[3] - m[1] * m[2]) / scaleX : 0.0;
        final double minScale = Math.min(scaleX, scaleY);
        final boolean downscales = edgeFill && minScale <= CUBISM_DOWNSCALE_THRESHOLD;
        final boolean exactGrid = DirectArgbTileBlitter.isExactGridBlit(transform, 0, 0);
        final int padPx = exactGrid ? EXACT_GRID_PAD_PX : (edgeFill ? FILTERED_EDGE_PAD_PX : UNFILTERED_PAD_PX);
        final DisjointTileWavePlanner.TileBounds box = DisjointTileWavePlanner.computeClippedBounds(
                srcBi.getWidth(), srcBi.getHeight(), transform, padPx, pageW, pageH);
        if (box.isEmpty()) {
            return null;
        }
        return new TileWork(modelImageName, srcWritable, srcBi, cAffine, transform, box, exactGrid, downscales);
    }

    private static boolean shouldParallelizeWave(
            final boolean allowParallelTiles, final List<TileWork> tiles, final int[] wave) {
        if (!allowParallelTiles || inMultiPageWorker() || wave.length < MIN_PARALLEL_WAVE_TILES) {
            return false;
        }
        long area = 0L;
        for (final int idx : wave) {
            area += tiles.get(idx).bounds.areaPx();
        }
        if (area < PARALLEL_WAVE_AREA_THRESHOLD) {
            return false;
        }
        // Only run parallel when tiles do not share mutable source buffers during fallback draw.
        final IdentityHashMap<BufferedImage, Boolean> seen = new IdentityHashMap<>(wave.length);
        for (final int idx : wave) {
            if (seen.put(tiles.get(idx).srcBi, Boolean.TRUE) != null) {
                return false;
            }
        }
        return true;
    }

    private static void executeWaveSerial(
            final AtlasHostHandles h,
            final Object targetWritable,
            final BufferedImage dstBi,
            final List<TileWork> tiles,
            final int[] wave,
            final boolean edgeFill,
            final MethodHandle bicubicHint)
            throws Throwable {
        final Object uiG = h.getWritableGraphics(targetWritable);
        final Graphics2D g2d = h.unwrapGraphics2D(uiG);
        try {
            h.enableBicubic(uiG);
            for (final int idx : wave) {
                drawSingleTile(h, targetWritable, uiG, g2d, dstBi, tiles.get(idx), edgeFill, false, bicubicHint);
            }
        } finally {
            h.disposeUiG(uiG);
        }
    }

    private static void executeWaveParallel(
            final AtlasHostHandles h,
            final Object targetWritable,
            final BufferedImage dstBi,
            final List<TileWork> tiles,
            final int[] wave,
            final boolean edgeFill,
            final MethodHandle bicubicHint) {
        final List<ForkJoinTask<Void>> tasks = new ArrayList<>(wave.length);
        for (final int idx : wave) {
            final TileWork work = tiles.get(idx);
            tasks.add(new RecursiveAction() {
                @Override
                protected void compute() {
                    final Graphics2D tileG = dstBi.createGraphics();
                    try {
                        applyBicubicHint(tileG, bicubicHint);
                        final DisjointTileWavePlanner.TileBounds b = work.bounds;
                        tileG.setClip(new Rectangle(b.x0(), b.y0(), b.x1() - b.x0(), b.y1() - b.y0()));
                        tileG.setTransform(work.transform);
                        if (DirectArgbTileBlitter.tryBlit(dstBi, tileG, work.srcBi, 0, 0)) {
                            return;
                        }
                        if (edgeFill) {
                            h.invokeHostDrawViaG(dstBi, tileG, work.srcBi, CUBISM_DOWNSCALE_THRESHOLD, true);
                        } else {
                            tileG.drawImage(work.srcBi, 0, 0, null);
                        }
                    } catch (final Throwable t) {
                        FatalErrors.rethrowIfFatal(t);
                        rethrowUnchecked(t);
                    } finally {
                        tileG.dispose();
                    }
                }
            });
        }
        ForkJoinTask.invokeAll(tasks);
    }

    private static void drawSingleTile(
            final AtlasHostHandles h,
            final Object targetWritable,
            final Object uiG,
            final Graphics2D g2d,
            final BufferedImage dstBi,
            final TileWork work,
            final boolean edgeFill,
            final boolean clipToBounds,
            final MethodHandle bicubicHint)
            throws Throwable {
        if (work.exactGrid) {
            g2d.setTransform(work.transform);
            if (clipToBounds) {
                final DisjointTileWavePlanner.TileBounds b = work.bounds;
                g2d.setClip(new Rectangle(b.x0(), b.y0(), b.x1() - b.x0(), b.y1() - b.y0()));
            }
            if (DirectArgbTileBlitter.tryBlit(dstBi, g2d, work.srcBi, 0, 0)) {
                return;
            }
        }
        h.setUiGTransform(uiG, work.cAffine);
        if (edgeFill) {
            h.invokeHostHelperDraw(targetWritable, uiG, work.srcWritable);
        } else {
            if (!DirectArgbTileBlitter.tryBlit(dstBi, g2d, work.srcBi, 0, 0)) {
                h.invokeUiGDrawImage(uiG, work.srcWritable);
            }
        }
    }

    private static void applyBicubicHint(final Graphics2D g2d, final MethodHandle hintHandle) {
        if (hintHandle != null) {
            try {
                hintHandle.invokeExact((Graphics) g2d);
                return;
            } catch (final Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                // Fall through to explicit bicubic hint.
            }
        }
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private static MethodHandle resolveBicubicHintHandle(final ClassLoader loader) {
        try {
            final Class<?> hintsClass = Class.forName(CLASS_GRAPHICS_HINTS, false, loader);
            final Method m = hintsClass.getMethod("a", Graphics.class);
            return MethodHandles.lookup().unreflect(m);
        } catch (final ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean isSrcOverComposite(final Graphics2D g2d) {
        return g2d.getComposite() instanceof AlphaComposite ac && ac.getRule() == AlphaComposite.SRC_OVER;
    }

    private static void rethrowUnchecked(final Throwable t) {
        if (t instanceof RuntimeException rt) {
            throw rt;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new IllegalStateException(t);
    }

    private record TileWork(
            String modelImageName,
            Object srcWritable,
            BufferedImage srcBi,
            Object cAffine,
            AffineTransform transform,
            DisjointTileWavePlanner.TileBounds bounds,
            boolean exactGrid,
            boolean downscales) {}

    private static final class AtlasHostHandles {
        private final MethodHandle getWidth;
        private final MethodHandle getHeight;
        private final MethodHandle getModelImages;
        private final MethodHandle getName;
        private final MethodHandle getModelSource;
        private final MethodHandle getTextureManager;
        private final MethodHandle getModelImage;
        private final MethodHandle getEntryGuid;
        private final MethodHandle calcTransform;
        private final MethodHandle getFilteredImage;
        private final MethodHandle getModelImageName;
        private final MethodHandle getResourceImage;
        private final MethodHandle getJBufferedImage;
        private final MethodHandle newWritableImage;
        private final Object argbType;
        private final MethodHandle fillWritable;
        private final MethodHandle getGraphics;
        private final MethodHandle unwrapGraphics2D;
        private final MethodHandle enableBicubic;
        private final MethodHandle setUiGTransform;
        private final MethodHandle drawUiGImage;
        private final MethodHandle disposeUiG;
        private final MethodHandle flushWritable;
        private final MethodHandle unwrapProgress;
        private final MethodHandle progressStart;
        private final MethodHandle progressText;
        private final MethodHandle progressStep;
        private final Object helperInstance;
        private final MethodHandle helperDrawTile;
        private final MethodHandle helperEdgeFill;
        private final Object gInstance;
        private final MethodHandle gDrawBuffered;

        private AtlasHostHandles(
                final MethodHandle getWidth,
                final MethodHandle getHeight,
                final MethodHandle getModelImages,
                final MethodHandle getName,
                final MethodHandle getModelSource,
                final MethodHandle getTextureManager,
                final MethodHandle getModelImage,
                final MethodHandle getEntryGuid,
                final MethodHandle calcTransform,
                final MethodHandle getFilteredImage,
                final MethodHandle getModelImageName,
                final MethodHandle getResourceImage,
                final MethodHandle getJBufferedImage,
                final MethodHandle newWritableImage,
                final Object argbType,
                final MethodHandle fillWritable,
                final MethodHandle getGraphics,
                final MethodHandle unwrapGraphics2D,
                final MethodHandle enableBicubic,
                final MethodHandle setUiGTransform,
                final MethodHandle drawUiGImage,
                final MethodHandle disposeUiG,
                final MethodHandle flushWritable,
                final MethodHandle unwrapProgress,
                final MethodHandle progressStart,
                final MethodHandle progressText,
                final MethodHandle progressStep,
                final Object helperInstance,
                final MethodHandle helperDrawTile,
                final MethodHandle helperEdgeFill,
                final Object gInstance,
                final MethodHandle gDrawBuffered) {
            this.getWidth = getWidth;
            this.getHeight = getHeight;
            this.getModelImages = getModelImages;
            this.getName = getName;
            this.getModelSource = getModelSource;
            this.getTextureManager = getTextureManager;
            this.getModelImage = getModelImage;
            this.getEntryGuid = getEntryGuid;
            this.calcTransform = calcTransform;
            this.getFilteredImage = getFilteredImage;
            this.getModelImageName = getModelImageName;
            this.getResourceImage = getResourceImage;
            this.getJBufferedImage = getJBufferedImage;
            this.newWritableImage = newWritableImage;
            this.argbType = argbType;
            this.fillWritable = fillWritable;
            this.getGraphics = getGraphics;
            this.unwrapGraphics2D = unwrapGraphics2D;
            this.enableBicubic = enableBicubic;
            this.setUiGTransform = setUiGTransform;
            this.drawUiGImage = drawUiGImage;
            this.disposeUiG = disposeUiG;
            this.flushWritable = flushWritable;
            this.unwrapProgress = unwrapProgress;
            this.progressStart = progressStart;
            this.progressText = progressText;
            this.progressStep = progressStep;
            this.helperInstance = helperInstance;
            this.helperDrawTile = helperDrawTile;
            this.helperEdgeFill = helperEdgeFill;
            this.gInstance = gInstance;
            this.gDrawBuffered = gDrawBuffered;
        }

        static AtlasHostHandles resolve(final Class<?> atlasClass) {
            try {
                final ClassLoader loader = atlasClass.getClassLoader();
                final MethodHandles.Lookup lookup = MethodHandles.lookup();
                final Class<?> writableClass = Class.forName(CLASS_WRITABLE_IMAGE, false, loader);
                final Class<?> colorTypeClass = Class.forName(CLASS_COLOR_TYPE, false, loader);
                final Field argbField = colorTypeClass.getField(FIELD_ARGB_TYPE);
                final Object argbType = argbField.get(null);
                final Constructor<?> wiCtor = writableClass.getConstructor(int.class, int.class, colorTypeClass);

                final Method getModelImagesM = atlasClass.getMethod("getModelImages");
                final Method getModelSourceM = atlasClass.getMethod("getModelSource");
                final Class<?> modelSourceClass = getModelSourceM.getReturnType();
                final Method getTexMgrM = modelSourceClass.getMethod("getTextureManager");
                final Class<?> texMgrClass = getTexMgrM.getReturnType();

                final Class<?> entryClass = Class.forName(atlasClass.getName() + "$ModelImageEntry", false, loader);
                final Method getEntryGuidM = entryClass.getMethod("getModelImageGuid");
                final Method calcTransformM = entryClass.getMethod("calcModelImageLocalToAtlasTransform");
                final Method getModelImageM = texMgrClass.getMethod("getModelImage", getEntryGuidM.getReturnType());
                final Class<?> modelImageClass = getModelImageM.getReturnType();
                final Method getFilteredM = modelImageClass.getMethod("getFilteredImage");
                final Class<?> imgResClass = getFilteredM.getReturnType();
                final Method getResImgM = imgResClass.getMethod("getImage");
                final Method getJBufM = writableClass.getMethod("getJBufferedImage");
                final Method getGraphicsM = writableClass.getMethod("getGraphics");
                final Class<?> uiGClass = getGraphicsM.getReturnType();
                final Class<?> cImageClass = writableClass.getSuperclass();

                final Class<?> progressClass = Class.forName(CLASS_PROGRESS, false, loader);
                final Object progressCompanion = progressClass.getField("a").get(null);
                final Method unwrapM = progressCompanion.getClass().getMethod("a", progressClass);

                Class<?> edgeHelperClass;
                try {
                    edgeHelperClass = Class.forName(CLASS_EDGE_HELPER_52, false, loader);
                    edgeHelperClass.getMethod(
                            "a", writableClass, uiGClass, writableClass, int.class, int.class, boolean.class);
                } catch (final ClassNotFoundException | NoSuchMethodException fallback50) {
                    edgeHelperClass = Class.forName(CLASS_EDGE_HELPER_50, false, loader);
                }
                final Object helperInstance = edgeHelperClass.getField("a").get(null);
                final Method helperDrawM = edgeHelperClass.getMethod(
                        "a", writableClass, uiGClass, writableClass, int.class, int.class, boolean.class);
                final Method helperEdgeM = edgeHelperClass.getMethod("a", BufferedImage.class, int.class);

                Class<?> gClass = null;
                for (final String candidate :
                        List.of("com.live2d.util.f.g", "com.live2d.util.e.g", "com.live2d.util.d.g")) {
                    try {
                        final Class<?> c = Class.forName(candidate, false, loader);
                        c.getMethod(
                                "a",
                                BufferedImage.class,
                                Graphics.class,
                                BufferedImage.class,
                                int.class,
                                int.class,
                                double.class,
                                boolean.class);
                        gClass = c;
                        break;
                    } catch (final ClassNotFoundException | NoSuchMethodException ignored) {
                        // Try next version candidate.
                    }
                }
                final Object gInstance = gClass != null ? gClass.getField("a").get(null) : null;
                final Method gDrawM = gClass != null
                        ? gClass.getMethod(
                                "a",
                                BufferedImage.class,
                                Graphics.class,
                                BufferedImage.class,
                                int.class,
                                int.class,
                                double.class,
                                boolean.class)
                        : null;

                return new AtlasHostHandles(
                        lookup.unreflect(atlasClass.getMethod("getWidth")),
                        lookup.unreflect(atlasClass.getMethod("getHeight")),
                        lookup.unreflect(getModelImagesM),
                        lookup.unreflect(atlasClass.getMethod("getName")),
                        lookup.unreflect(getModelSourceM),
                        lookup.unreflect(getTexMgrM),
                        lookup.unreflect(getModelImageM),
                        lookup.unreflect(getEntryGuidM),
                        lookup.unreflect(calcTransformM),
                        lookup.unreflect(getFilteredM),
                        lookup.unreflect(modelImageClass.getMethod("getName")),
                        lookup.unreflect(getResImgM),
                        lookup.unreflect(getJBufM),
                        lookup.unreflectConstructor(wiCtor),
                        argbType,
                        lookup.unreflect(writableClass.getMethod("fill", int.class)),
                        lookup.unreflect(getGraphicsM),
                        lookup.unreflect(uiGClass.getMethod("a")),
                        lookup.unreflect(uiGClass.getMethod("e")),
                        lookup.unreflect(uiGClass.getMethod("a", calcTransformM.getReturnType())),
                        lookup.unreflect(uiGClass.getMethod("a", cImageClass, int.class, int.class)),
                        lookup.unreflect(uiGClass.getMethod("d")),
                        lookup.unreflect(writableClass.getMethod("flush")),
                        lookup.unreflect(unwrapM).bindTo(progressCompanion),
                        lookup.unreflect(progressClass.getMethod("a", int.class, String.class, boolean.class)),
                        lookup.unreflect(progressClass.getMethod("a", String.class)),
                        lookup.unreflect(progressClass.getMethod("b", int.class)),
                        helperInstance,
                        lookup.unreflect(helperDrawM),
                        lookup.unreflect(helperEdgeM),
                        gInstance,
                        gDrawM != null ? lookup.unreflect(gDrawM) : null);
            } catch (final ReflectiveOperationException | LinkageError failure) {
                return null;
            }
        }

        int getWidth(final Object atlas) throws Throwable {
            return (int) getWidth.invoke(atlas);
        }

        int getHeight(final Object atlas) throws Throwable {
            return (int) getHeight.invoke(atlas);
        }

        List<?> getModelImages(final Object atlas) throws Throwable {
            final Object list = getModelImages.invoke(atlas);
            return list instanceof List<?> raw ? raw : Collections.emptyList();
        }

        String getAtlasName(final Object atlas) throws Throwable {
            return (String) getName.invoke(atlas);
        }

        Object getModelSource(final Object atlas) throws Throwable {
            return getModelSource.invoke(atlas);
        }

        Object getTextureManager(final Object modelSource) throws Throwable {
            return getTextureManager.invoke(modelSource);
        }

        Object getEntryGuid(final Object entry) throws Throwable {
            return getEntryGuid.invoke(entry);
        }

        Object getModelImage(final Object textureManager, final Object guid) throws Throwable {
            return getModelImage.invoke(textureManager, guid);
        }

        String getModelImageName(final Object modelImage) throws Throwable {
            return (String) getModelImageName.invoke(modelImage);
        }

        Object calcTransform(final Object entry) throws Throwable {
            return calcTransform.invoke(entry);
        }

        Object getFilteredImage(final Object modelImage) throws Throwable {
            return getFilteredImage.invoke(modelImage);
        }

        Object getResourceImage(final Object imageResource) throws Throwable {
            return getResourceImage.invoke(imageResource);
        }

        BufferedImage getJBufferedImage(final Object writable) throws Throwable {
            return writable == null ? null : (BufferedImage) getJBufferedImage.invoke(writable);
        }

        Object newWritableImage(final int w, final int h) throws Throwable {
            return newWritableImage.invoke(w, h, argbType);
        }

        void fillWritableImage(final Object writable, final int color) throws Throwable {
            fillWritable.invoke(writable, color);
        }

        Object getWritableGraphics(final Object writable) throws Throwable {
            return getGraphics.invoke(writable);
        }

        Graphics2D unwrapGraphics2D(final Object uiG) throws Throwable {
            return (Graphics2D) unwrapGraphics2D.invoke(uiG);
        }

        void enableBicubic(final Object uiG) throws Throwable {
            enableBicubic.invoke(uiG);
        }

        void setUiGTransform(final Object uiG, final Object cAffine) throws Throwable {
            setUiGTransform.invoke(uiG, cAffine);
        }

        void invokeUiGDrawImage(final Object uiG, final Object writable) throws Throwable {
            drawUiGImage.invoke(uiG, writable, 0, 0);
        }

        void disposeUiG(final Object uiG) {
            try {
                disposeUiG.invoke(uiG);
            } catch (final Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                // Best-effort graphics disposal.
            }
        }

        void flushSafely(final Object writable) {
            if (writable == null) {
                return;
            }
            try {
                flushWritable.invoke(writable);
            } catch (final Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                // Best-effort cleanup on abort.
            }
        }

        Object unwrapProgress(final Object progress) throws Throwable {
            return unwrapProgress.invoke(progress);
        }

        void progressStart(final Object progress, final int max, final String title) throws Throwable {
            progressStart.invoke(progress, max, title, false);
        }

        void progressText(final Object progress, final String text) throws Throwable {
            progressText.invoke(progress, text);
        }

        void progressStep(final Object progress, final int steps) throws Throwable {
            progressStep.invoke(progress, steps);
        }

        void invokeHostHelperDraw(final Object dstWi, final Object uiG, final Object srcWi) throws Throwable {
            helperDrawTile.invoke(helperInstance, dstWi, uiG, srcWi, 0, 0, true);
        }

        void invokeHostEdgeFill(final BufferedImage img, final int dist) throws Throwable {
            helperEdgeFill.invoke(helperInstance, img, dist);
        }

        void invokeHostDrawViaG(
                final BufferedImage dst,
                final Graphics2D g2d,
                final BufferedImage src,
                final double threshold,
                final boolean edgeFill)
                throws Throwable {
            if (gDrawBuffered != null && gInstance != null) {
                gDrawBuffered.invoke(gInstance, dst, (Graphics) g2d, src, 0, 0, threshold, edgeFill);
            } else {
                g2d.drawImage(src, 0, 0, null);
            }
        }
    }
}
