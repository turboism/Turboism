package dev.turboism.adapter.cubism.exportperf;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Coordinates parallel per-page atlas downscaling, edge processing, and lossless PNG encoding.
 *
 * <p>Pre-computes PNG payloads concurrently for all atlas pages in {@code exporter.b$a.a} while
 * leaving path formatting, JSON metadata, and ordering entirely in the host's control.</p>
 */
public final class FastPngExporter {

    private static final int EDGE_CLEAN_RADIUS = 3;
    private static final int EDGE_EXTEND_THRESHOLD = 8;
    private static final int MAX_POOL_THREADS = 8;
    private static final int MIN_PARALLEL_PAGES = 2;
    private static final int WORK_QUEUE_CAPACITY = 256;
    private static final String WORKER_PREFIX = RuntimeThreads.THREAD_NAME_PREFIX + "-png-export";
    private static final ExecutorService WORKER_POOL = createWorkerPool();

    private static final ThreadLocal<PreparedBatch> ACTIVE_BATCH = new ThreadLocal<>();

    private static volatile ExportHandles cachedHandles;

    private FastPngExporter() {}

    /**
     * Pre-decodes and concurrently renders/encodes PNG payloads for all atlas pages if eligible.
     *
     * @param exportPack host export bundle containing textures and {@code CMocExportSetting}
     * @param scale integer downscale factor ({@code >= 1})
     * @param writeTextures whether the current export call emits texture PNGs
     */
    public static void enterAndPrepare(final Object exportPack, final int scale, final boolean writeTextures) {
        if (ACTIVE_BATCH.get() != null) {
            return;
        }
        final PreparedBatch batch = new PreparedBatch();
        ACTIVE_BATCH.set(batch);
        if (!writeTextures || exportPack == null || scale <= 0) {
            return;
        }
        try {
            final ExportHandles h = resolveHandles(exportPack.getClass());
            final Object listObj = h.getTextureResources.invoke(exportPack);
            if (!(listObj instanceof List<?> resources) || resources.isEmpty()) {
                return;
            }
            final Object setting = h.getSetting.invoke(exportPack);
            final boolean extendEdgeColor = (boolean) h.getEnableExtendEdgeColor.invoke(setting);
            final int count = resources.size();

            // Resolve all source WritableImages sequentially because getImage() synchronizes decode.
            final BufferedImage[] sources = new BufferedImage[count];
            for (int i = 0; i < count; i++) {
                final Object res = resources.get(i);
                final Object wi = res == null ? null : h.resGetImage.invoke(res);
                final Object buf = wi == null ? null : h.wiGetBufferedImage.invoke(wi);
                sources[i] = buf instanceof BufferedImage bi ? bi : null;
            }

            final byte[][] pngs = new byte[count][];
            if (count < MIN_PARALLEL_PAGES) {
                pngs[0] = renderAndEncodePage(sources[0], scale, extendEdgeColor);
            } else {
                final List<Future<byte[]>> futures = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    final BufferedImage pageBuf = sources[i];
                    futures.add(WORKER_POOL.submit(() -> renderAndEncodePage(pageBuf, scale, extendEdgeColor)));
                }
                for (int i = 0; i < count; i++) {
                    pngs[i] = futures.get(i).get();
                }
            }
            batch.pages = pngs;
            batch.cursor = 0;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            batch.pages = null;
        }
    }

    /**
     * Releases any prepared page payloads held for the current thread's export invocation.
     */
    public static void exit() {
        ACTIVE_BATCH.remove();
    }

    /**
     * Returns whether the current export page slot has a pre-encoded PNG ready on this thread.
     *
     * @return {@code true} when intermediate downscale/edge work should be skipped for this page
     */
    public static boolean isCurrentPagePrepared() {
        final PreparedBatch batch = ACTIVE_BATCH.get();
        return batch != null
                && batch.pages != null
                && batch.cursor < batch.pages.length
                && batch.pages[batch.cursor] != null;
    }

    /**
     * Writes the pre-encoded PNG payload for the active page slot if available.
     *
     * @param output target stream from {@code CWritableImage.writeImageAsPng}
     * @return {@code true} if the prepared payload was written, {@code false} for fallback
     * @throws IOException if writing to {@code output} fails
     */
    public static boolean tryConsumePreparedPng(final OutputStream output) throws IOException {
        final PreparedBatch batch = ACTIVE_BATCH.get();
        if (batch == null || batch.pages == null || batch.cursor >= batch.pages.length) {
            return false;
        }
        final byte[] encoded = batch.pages[batch.cursor];
        batch.pages[batch.cursor] = null;
        batch.cursor++;
        if (encoded == null) {
            return false;
        }
        output.write(encoded);
        return true;
    }

    private static byte[] renderAndEncodePage(
            final BufferedImage source, final int scale, final boolean extendEdgeColor) throws IOException {
        if (source == null || source.getType() != BufferedImage.TYPE_INT_ARGB) {
            return null;
        }
        final DataBuffer db = source.getRaster().getDataBuffer();
        if (!(db instanceof DataBufferInt srcInt) || srcInt.getNumBanks() != 1 || srcInt.getOffset() != 0) {
            return null;
        }
        final int srcW = source.getWidth();
        final int srcH = source.getHeight();
        final int[] srcPixels = srcInt.getData();
        if ((long) srcW * srcH > srcPixels.length || srcW <= 0 || srcH <= 0) {
            return null;
        }

        final int dstW = scale == 1 ? srcW : (srcW / scale) + (srcW % scale != 0 ? 1 : 0);
        final int dstH = scale == 1 ? srcH : (srcH / scale) + (srcH % scale != 0 ? 1 : 0);

        if (extendEdgeColor && dstW >= 2 && dstH >= 2) {
            final BufferedImage workingImage = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
            final int[] backing = ((DataBufferInt) workingImage.getRaster().getDataBuffer()).getData();
            if (scale == 1) {
                System.arraycopy(srcPixels, 0, backing, 0, backing.length);
            } else {
                DownscaleFastOp.downscaleBox(scale, srcPixels, srcW, srcH, backing, dstW, dstH, 0);
            }
            EdgeExtendFastOp.tryExtendEdgeColor(workingImage, EDGE_EXTEND_THRESHOLD, null);
            final byte[] bytes = FastPngEncoder.encodeArgbToBytes(backing, dstW, dstH);
            workingImage.flush();
            return bytes;
        }

        final int[] workPixels = new int[dstW * dstH];
        if (scale == 1) {
            System.arraycopy(srcPixels, 0, workPixels, 0, workPixels.length);
        } else {
            DownscaleFastOp.downscaleBox(scale, srcPixels, srcW, srcH, workPixels, dstW, dstH, 0);
        }
        if (extendEdgeColor) {
            return FastPngEncoder.encodeArgbToBytes(workPixels, dstW, dstH);
        }

        final int[] cleaned = new int[dstW * dstH];
        EdgeCleanFastOp.cleanPixels(workPixels, dstW, dstH, EDGE_CLEAN_RADIUS, false, cleaned);
        return FastPngEncoder.encodeArgbToBytes(cleaned, dstW, dstH);
    }

    private static ExecutorService createWorkerPool() {
        final int threads = Math.max(
                MIN_PARALLEL_PAGES,
                Math.min(MAX_POOL_THREADS, Runtime.getRuntime().availableProcessors()));
        return RuntimeThreads.fixedLane(
                threads,
                WORK_QUEUE_CAPACITY,
                RuntimeThreads.daemonThreads(WORKER_PREFIX, RuntimeThreads.contextClassLoader(FastPngExporter.class)));
    }

    private static ExportHandles resolveHandles(final Class<?> bundleClass) throws Exception {
        ExportHandles cached = cachedHandles;
        if (cached != null && cached.bundleClass == bundleClass) {
            return cached;
        }
        synchronized (FastPngExporter.class) {
            cached = cachedHandles;
            if (cached != null && cached.bundleClass == bundleClass) {
                return cached;
            }
            cached = new ExportHandles(bundleClass);
            cachedHandles = cached;
            return cached;
        }
    }

    private static final class PreparedBatch {
        private byte[][] pages;
        private int cursor;
    }

    private static final class ExportHandles {
        private final Class<?> bundleClass;
        private final Method getTextureResources;
        private final Method getSetting;
        private final Method getEnableExtendEdgeColor;
        private final Method resGetImage;
        private final Method wiGetBufferedImage;

        ExportHandles(final Class<?> bundleClass) throws NoSuchMethodException, ClassNotFoundException {
            this.bundleClass = bundleClass;
            final ClassLoader loader = bundleClass.getClassLoader();
            this.getTextureResources = bundleClass.getMethod("b");
            this.getSetting = bundleClass.getMethod("c");
            this.getEnableExtendEdgeColor = this.getSetting.getReturnType().getMethod("getEnableExtendEdgeColor");

            final Class<?> resClass = Class.forName("com.live2d.graphics.CImageResource", false, loader);
            this.resGetImage = resClass.getMethod("getImage");
            final Class<?> wiClass = Class.forName("com.live2d.graphics.CWritableImage", false, loader);
            this.wiGetBufferedImage = wiClass.getMethod("getJBufferedImage");
        }
    }
}
