package dev.turboism.adapter.cubism.optimization.psd;

import dev.turboism.adapter.cubism.exportperf.DownscaleFastOp;
import dev.turboism.adapter.cubism.exportperf.EdgeCleanFastOp;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides thread-safe image operations, fast convolution filters, and a bounded daemon worker
 * pool for PSD import and layer analysis.
 *
 * <p>Reuses {@link DownscaleFastOp} and {@link EdgeCleanFastOp} so PSD acceleration works even when
 * export performance hooks are inactive, and replaces division-heavy inner-loop convolutions with
 * exact unrolled 3x3 kernels.
 */
public final class PsdImageOps {

    private static final int MIN_WORKERS = 2;
    private static final int MAX_WORKERS = 8;
    private static final long WORKER_IDLE_SECONDS = 30L;
    private static final int KERNEL_3X3_LEN = 9;
    private static final int KERNEL_3X3_SIZE = 3;
    private static final int CHANNEL_COUNT_RGB = 3;
    private static final int BYTE_MASK = 0xFF;
    private static final int SHIFT_RED = 16;
    private static final int SHIFT_GREEN = 8;
    private static final int DEFAULT_EDGE_DIVISOR = 1;
    private static final int DEFAULT_BLUR_DIVISOR = 9;
    private static final int LAPLACIAN_CENTER = -8;
    private static final String THREAD_NAME_PREFIX = "turboism-psd-worker-";

    private static final String WRITABLE_IMAGE_CLASS = "com.live2d.graphics.CWritableImage";
    private static final String COLOR_TYPE_ENUM = "com.live2d.graphics.n";
    private static final String EDGE_CLEAN_HOST_CLASS = "jp.noids.graphics.h";
    private static final String METHOD_CREATE_REDUCED = "createReduced";
    private static final String METHOD_GET_INT_BUFFER = "getIntBuffer";
    private static final String METHOD_GET_BYTE_BUFFER = "getByteBuffer";
    private static final String METHOD_GET_WIDTH = "getWidth";
    private static final String METHOD_GET_HEIGHT = "getHeight";
    private static final String METHOD_GET_TYPE = "getType";
    private static final String FIELD_ARGB_TYPE = "c";
    private static final String FIELD_GRAY_TYPE = "f";

    private static final Object EDGE_CLEAN_FALLBACK_LOCK = new Object();
    private static final Object HANDLES_LOCK = new Object();
    private static final Map<ClassLoader, ImageHandles> HANDLES_BY_LOADER =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile ImageHandles lastHandles;

    private static final ExecutorService WORKER_POOL = createWorkerPool();

    private PsdImageOps() {}

    /**
     * Returns the shared bounded daemon worker pool for independent PSD layer CPU work.
     *
     * @return daemon executor service capped between 2 and 8 threads with idle timeout
     */
    public static ExecutorService workerPool() {
        return WORKER_POOL;
    }

    /**
     * Returns the bounded worker concurrency target derived from host processors.
     *
     * @return worker thread count in {@code [2, 8]}
     */
    public static int workerCount() {
        final int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(MIN_WORKERS, Math.min(MAX_WORKERS, cpus));
    }

    /**
     * Cleans matte fringes safely across worker threads using {@link EdgeCleanFastOp}.
     *
     * <p>Falls back synchronized onto {@code jp.noids.graphics.h.a} only when the source image does
     * not match {@code TYPE_INT_ARGB}.
     *
     * @param source input layer image
     * @param radius spiral dilation radius
     * @param greyFill whether background padding uses mid-grey rather than white
     * @return cleaned image matching host output
     */
    public static BufferedImage cleanEdgesThreadSafe(
            final BufferedImage source, final int radius, final boolean greyFill) {
        final BufferedImage fast = EdgeCleanFastOp.fastCleanEdges(source, radius, greyFill);
        if (fast != null) {
            return fast;
        }
        if (source == null) {
            return null;
        }
        final ImageHandles handles = resolveHandles(source.getClass().getClassLoader());
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        final ImageHandles contextHandles =
                handles != null ? handles : (loader != null ? resolveHandles(loader) : null);
        if (contextHandles == null || contextHandles.hostEdgeClean == null) {
            return null;
        }
        synchronized (EDGE_CLEAN_FALLBACK_LOCK) {
            try {
                return (BufferedImage) contextHandles.hostEdgeClean.invoke(source, radius, greyFill);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw new IllegalStateException("Edge clean fallback failed", failure);
            }
        }
    }

    /**
     * Downscales a {@code CWritableImage} directly on {@code int[]} buffers for {@code Step001}.
     *
     * @param srcWritable host {@code CWritableImage} instance
     * @param scale integer downscale factor per axis
     * @param margin border padding pixels around reduced output
     * @return reduced {@code CWritableImage} matching {@code createReduced(scale, margin)}
     */
    public static Object fastCreateReducedOrDefault(final Object srcWritable, final int scale, final int margin) {
        if (srcWritable == null) {
            return null;
        }
        final ImageHandles handles = resolveHandles(srcWritable.getClass().getClassLoader());
        if (handles == null) {
            return null;
        }
        try {
            if (scale > 0 && margin >= 0) {
                final Object srcType = handles.getType.invoke(srcWritable);
                if (srcType == handles.argbType) {
                    final int[] srcBuf = (int[]) handles.getIntBuffer.invoke(srcWritable);
                    if (srcBuf != null) {
                        final int srcW = (int) handles.getWidth.invoke(srcWritable);
                        final int srcH = (int) handles.getHeight.invoke(srcWritable);
                        final int dstW = srcW / scale + (srcW % scale != 0 ? 1 : 0);
                        final int dstH = srcH / scale + (srcH % scale != 0 ? 1 : 0);
                        final int paddedW = dstW + margin * 2;
                        final int paddedH = dstH + margin * 2;
                        final Object dstWritable = handles.newWritableImage.invoke(paddedW, paddedH, handles.argbType);
                        final int[] dstBuf = (int[]) handles.getIntBuffer.invoke(dstWritable);
                        if (dstBuf != null) {
                            DownscaleFastOp.downscaleBox(scale, srcBuf, srcW, srcH, dstBuf, dstW, dstH, margin);
                            return dstWritable;
                        }
                    }
                }
            }
            return handles.createReduced.invoke(srcWritable, scale, margin);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("createReduced invocation failed", failure);
        }
    }

    /**
     * Applies fast grayscale edge detection matching {@code com.live2d.graphics.image.a.a}.
     *
     * @param filter host {@code image.a.a} instance
     * @param srcWritable source {@code TYPE_INT_ARGB} {@code CWritableImage}
     * @param dstWritable optional destination {@code BYTE_GRAY} image, or {@code null}
     * @param kernel convolution kernel array
     * @param kernelSize square kernel dimension
     * @param divisor positive normalization divisor
     * @return populated {@code BYTE_GRAY} {@code CWritableImage}, or {@code null} on fallback
     */
    public static Object tryFastStep002EdgeFilter(
            final Object filter,
            final Object srcWritable,
            final Object dstWritable,
            final int[] kernel,
            final int kernelSize,
            final int divisor) {
        if (filter == null || srcWritable == null || kernel == null || kernelSize <= 0 || divisor == 0) {
            return null;
        }
        final ImageHandles handles = resolveHandles(srcWritable.getClass().getClassLoader());
        if (handles == null || handles.grayType == null || handles.argbType == null) {
            return null;
        }
        try {
            if (handles.getType.invoke(srcWritable) != handles.argbType) {
                return null;
            }
            final int width = (int) handles.getWidth.invoke(srcWritable);
            final int height = (int) handles.getHeight.invoke(srcWritable);
            final int[] srcBuf = (int[]) handles.getIntBuffer.invoke(srcWritable);
            if (srcBuf == null) {
                return null;
            }
            final Object outWritable = dstWritable != null
                    ? dstWritable
                    : handles.newWritableImage.invoke(width, height, handles.grayType);
            final byte[] dstBuf = (byte[]) handles.getByteBuffer.invoke(outWritable);
            if (dstBuf == null || srcBuf.length < (long) width * height || dstBuf.length < (long) width * height) {
                return null;
            }
            if (!convolveEdgeGray(srcBuf, dstBuf, width, height, kernel, kernelSize, divisor)) {
                return null;
            }
            return outWritable;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Applies fast 3x3 edge blur matching {@code Step003_EdgeBlur_A.a}.
     *
     * @param srcWritable source {@code BYTE_GRAY} {@code CWritableImage}
     * @param dstWritable optional destination {@code BYTE_GRAY} image, or {@code null}
     * @param kernel nine-element 3x3 blur kernel
     * @param divisor positive normalization divisor
     * @return blurred {@code BYTE_GRAY} {@code CWritableImage}, or {@code null} on fallback
     */
    public static Object tryFastStep003Blur(
            final Object srcWritable, final Object dstWritable, final int[] kernel, final int divisor) {
        if (srcWritable == null || kernel == null || kernel.length != KERNEL_3X3_LEN || divisor == 0) {
            return null;
        }
        final ImageHandles handles = resolveHandles(srcWritable.getClass().getClassLoader());
        if (handles == null || handles.grayType == null) {
            return null;
        }
        try {
            if (handles.getType.invoke(srcWritable) != handles.grayType) {
                return null;
            }
            final int width = (int) handles.getWidth.invoke(srcWritable);
            final int height = (int) handles.getHeight.invoke(srcWritable);
            final byte[] srcBuf = (byte[]) handles.getByteBuffer.invoke(srcWritable);
            if (srcBuf == null) {
                return null;
            }
            final Object outWritable = dstWritable != null
                    ? dstWritable
                    : handles.newWritableImage.invoke(width, height, handles.grayType);
            final byte[] dstBuf = (byte[]) handles.getByteBuffer.invoke(outWritable);
            if (dstBuf == null || srcBuf.length < (long) width * height || dstBuf.length < (long) width * height) {
                return null;
            }
            blurGray3x3(srcBuf, dstBuf, width, height, kernel, divisor);
            return outWritable;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Executes bit-identical RGB gradient magnitude convolution into signed-byte grayscale.
     *
     * <p>Preserves a 1-pixel zero border and exact integer truncation semantics matching {@code
     * com.live2d.graphics.image.a.a.a}.
     *
     * @param src packed {@code TYPE_INT_ARGB} source pixel buffer
     * @param dst output {@code TYPE_BYTE_GRAY} byte buffer
     * @param width image width in pixels
     * @param height image height in pixels
     * @param kernel square convolution weights indexed in reversed spatial order matching host
     * @param kernelSize square kernel side length
     * @param divisor kernel weight divisor
     * @return {@code true} if kernel size is handled by the unrolled path, else {@code false}
     */
    public static boolean convolveEdgeGray(
            final int[] src,
            final byte[] dst,
            final int width,
            final int height,
            final int[] kernel,
            final int kernelSize,
            final int divisor) {
        if (kernelSize != KERNEL_3X3_SIZE || kernel.length != KERNEL_3X3_LEN || divisor == 0) {
            return false;
        }
        if (width < KERNEL_3X3_SIZE || height < KERNEL_3X3_SIZE) {
            return true;
        }
        if (divisor == DEFAULT_EDGE_DIVISOR && isDefaultLaplacianKernel(kernel)) {
            convolveDefaultEdge3x3(src, dst, width, height);
            return true;
        }
        convolveGeneralEdge3x3(src, dst, width, height, kernel, divisor);
        return true;
    }

    /**
     * Executes bit-identical 3x3 integer box blur on a {@code TYPE_BYTE_GRAY} buffer.
     *
     * @param src input byte grayscale pixels
     * @param dst output byte grayscale pixels
     * @param width image width in pixels
     * @param height image height in pixels
     * @param kernel nine-element 3x3 integer kernel
     * @param divisor positive normalization divisor
     */
    public static void blurGray3x3(
            final byte[] src,
            final byte[] dst,
            final int width,
            final int height,
            final int[] kernel,
            final int divisor) {
        if (width < KERNEL_3X3_SIZE || height < KERNEL_3X3_SIZE) {
            return;
        }
        if (divisor == DEFAULT_BLUR_DIVISOR && isUniformOnesKernel(kernel)) {
            blurBox3x3Div9(src, dst, width, height);
            return;
        }
        final int k0 = kernel[0];
        final int k1 = kernel[1];
        final int k2 = kernel[2];
        final int k3 = kernel[3];
        final int k4 = kernel[4];
        final int k5 = kernel[5];
        final int k6 = kernel[6];
        final int k7 = kernel[7];
        final int k8 = kernel[8];
        for (int y = 1; y < height - 1; y++) {
            final int rowAbove = (y - 1) * width;
            final int rowMid = y * width;
            final int rowBelow = (y + 1) * width;
            for (int x = 1; x < width - 1; x++) {
                // Match host indexing order (cx + 1 - col, cy + 1 - row) across all 9 taps.
                final int sum = k0 * (src[rowBelow + x + 1] & BYTE_MASK)
                        + k1 * (src[rowBelow + x] & BYTE_MASK)
                        + k2 * (src[rowBelow + x - 1] & BYTE_MASK)
                        + k3 * (src[rowMid + x + 1] & BYTE_MASK)
                        + k4 * (src[rowMid + x] & BYTE_MASK)
                        + k5 * (src[rowMid + x - 1] & BYTE_MASK)
                        + k6 * (src[rowAbove + x + 1] & BYTE_MASK)
                        + k7 * (src[rowAbove + x] & BYTE_MASK)
                        + k8 * (src[rowAbove + x - 1] & BYTE_MASK);
                dst[rowMid + x] = (byte) (sum / divisor);
            }
        }
    }

    /**
     * Reads ARGB pixels from {@code image} directly when backed by single-bank {@code TYPE_INT_ARGB}.
     *
     * @param image candidate AWT image
     * @return backing int array or {@code null}
     */
    static int[] tryRawIntPixels(final BufferedImage image) {
        if (image == null || image.getType() != BufferedImage.TYPE_INT_ARGB) {
            return null;
        }
        if (image.getRaster().getDataBuffer() instanceof DataBufferInt db
                && db.getNumBanks() == 1
                && db.getOffset() == 0) {
            return db.getData();
        }
        return null;
    }

    /**
     * Reads gray bytes from {@code image} directly when backed by single-bank {@code TYPE_BYTE_GRAY}.
     *
     * @param image candidate AWT image
     * @return backing byte array or {@code null}
     */
    static byte[] tryRawGrayBytes(final BufferedImage image) {
        if (image == null || image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            return null;
        }
        if (image.getRaster().getDataBuffer() instanceof DataBufferByte db
                && db.getNumBanks() == 1
                && db.getOffset() == 0) {
            return db.getData();
        }
        return null;
    }

    private static void convolveDefaultEdge3x3(final int[] src, final byte[] dst, final int width, final int height) {
        for (int y = 1; y < height - 1; y++) {
            final int rowAbove = (y - 1) * width;
            final int rowMid = y * width;
            final int rowBelow = (y + 1) * width;
            for (int x = 1; x < width - 1; x++) {
                final int p00 = src[rowAbove + x - 1];
                final int p01 = src[rowAbove + x];
                final int p02 = src[rowAbove + x + 1];
                final int p10 = src[rowMid + x - 1];
                final int p11 = src[rowMid + x];
                final int p12 = src[rowMid + x + 1];
                final int p20 = src[rowBelow + x - 1];
                final int p21 = src[rowBelow + x];
                final int p22 = src[rowBelow + x + 1];

                final int sumR = ((p00 >>> SHIFT_RED) & BYTE_MASK)
                        + ((p01 >>> SHIFT_RED) & BYTE_MASK)
                        + ((p02 >>> SHIFT_RED) & BYTE_MASK)
                        + ((p10 >>> SHIFT_RED) & BYTE_MASK)
                        - 8 * ((p11 >>> SHIFT_RED) & BYTE_MASK)
                        + ((p12 >>> SHIFT_RED) & BYTE_MASK)
                        + ((p20 >>> SHIFT_RED) & BYTE_MASK)
                        + ((p21 >>> SHIFT_RED) & BYTE_MASK)
                        + ((p22 >>> SHIFT_RED) & BYTE_MASK);
                final int sumG = ((p00 >>> SHIFT_GREEN) & BYTE_MASK)
                        + ((p01 >>> SHIFT_GREEN) & BYTE_MASK)
                        + ((p02 >>> SHIFT_GREEN) & BYTE_MASK)
                        + ((p10 >>> SHIFT_GREEN) & BYTE_MASK)
                        - 8 * ((p11 >>> SHIFT_GREEN) & BYTE_MASK)
                        + ((p12 >>> SHIFT_GREEN) & BYTE_MASK)
                        + ((p20 >>> SHIFT_GREEN) & BYTE_MASK)
                        + ((p21 >>> SHIFT_GREEN) & BYTE_MASK)
                        + ((p22 >>> SHIFT_GREEN) & BYTE_MASK);
                final int sumB = (p00 & BYTE_MASK)
                        + (p01 & BYTE_MASK)
                        + (p02 & BYTE_MASK)
                        + (p10 & BYTE_MASK)
                        - 8 * (p11 & BYTE_MASK)
                        + (p12 & BYTE_MASK)
                        + (p20 & BYTE_MASK)
                        + (p21 & BYTE_MASK)
                        + (p22 & BYTE_MASK);

                final int gray = (Math.abs(sumR) + Math.abs(sumG) + Math.abs(sumB)) / CHANNEL_COUNT_RGB;
                dst[rowMid + x] = (byte) gray;
            }
        }
    }

    private static void convolveGeneralEdge3x3(
            final int[] src,
            final byte[] dst,
            final int width,
            final int height,
            final int[] kernel,
            final int divisor) {
        final int k0 = kernel[0];
        final int k1 = kernel[1];
        final int k2 = kernel[2];
        final int k3 = kernel[3];
        final int k4 = kernel[4];
        final int k5 = kernel[5];
        final int k6 = kernel[6];
        final int k7 = kernel[7];
        final int k8 = kernel[8];
        for (int y = 1; y < height - 1; y++) {
            final int rowAbove = (y - 1) * width;
            final int rowMid = y * width;
            final int rowBelow = (y + 1) * width;
            for (int x = 1; x < width - 1; x++) {
                final int p0 = src[rowBelow + x + 1];
                final int p1 = src[rowBelow + x];
                final int p2 = src[rowBelow + x - 1];
                final int p3 = src[rowMid + x + 1];
                final int p4 = src[rowMid + x];
                final int p5 = src[rowMid + x - 1];
                final int p6 = src[rowAbove + x + 1];
                final int p7 = src[rowAbove + x];
                final int p8 = src[rowAbove + x - 1];

                final int sumR = k0 * ((p0 >>> SHIFT_RED) & BYTE_MASK)
                        + k1 * ((p1 >>> SHIFT_RED) & BYTE_MASK)
                        + k2 * ((p2 >>> SHIFT_RED) & BYTE_MASK)
                        + k3 * ((p3 >>> SHIFT_RED) & BYTE_MASK)
                        + k4 * ((p4 >>> SHIFT_RED) & BYTE_MASK)
                        + k5 * ((p5 >>> SHIFT_RED) & BYTE_MASK)
                        + k6 * ((p6 >>> SHIFT_RED) & BYTE_MASK)
                        + k7 * ((p7 >>> SHIFT_RED) & BYTE_MASK)
                        + k8 * ((p8 >>> SHIFT_RED) & BYTE_MASK);
                final int sumG = k0 * ((p0 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k1 * ((p1 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k2 * ((p2 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k3 * ((p3 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k4 * ((p4 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k5 * ((p5 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k6 * ((p6 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k7 * ((p7 >>> SHIFT_GREEN) & BYTE_MASK)
                        + k8 * ((p8 >>> SHIFT_GREEN) & BYTE_MASK);
                final int sumB = k0 * (p0 & BYTE_MASK)
                        + k1 * (p1 & BYTE_MASK)
                        + k2 * (p2 & BYTE_MASK)
                        + k3 * (p3 & BYTE_MASK)
                        + k4 * (p4 & BYTE_MASK)
                        + k5 * (p5 & BYTE_MASK)
                        + k6 * (p6 & BYTE_MASK)
                        + k7 * (p7 & BYTE_MASK)
                        + k8 * (p8 & BYTE_MASK);

                final int avg = (Math.abs(sumR) / divisor + Math.abs(sumG) / divisor + Math.abs(sumB) / divisor)
                        / CHANNEL_COUNT_RGB;
                dst[rowMid + x] = (byte) avg;
            }
        }
    }

    private static void blurBox3x3Div9(final byte[] src, final byte[] dst, final int width, final int height) {
        for (int y = 1; y < height - 1; y++) {
            final int rowAbove = (y - 1) * width;
            final int rowMid = y * width;
            final int rowBelow = (y + 1) * width;
            for (int x = 1; x < width - 1; x++) {
                final int sum = (src[rowBelow + x + 1] & BYTE_MASK)
                        + (src[rowBelow + x] & BYTE_MASK)
                        + (src[rowBelow + x - 1] & BYTE_MASK)
                        + (src[rowMid + x + 1] & BYTE_MASK)
                        + (src[rowMid + x] & BYTE_MASK)
                        + (src[rowMid + x - 1] & BYTE_MASK)
                        + (src[rowAbove + x + 1] & BYTE_MASK)
                        + (src[rowAbove + x] & BYTE_MASK)
                        + (src[rowAbove + x - 1] & BYTE_MASK);
                dst[rowMid + x] = (byte) (sum / DEFAULT_BLUR_DIVISOR);
            }
        }
    }

    private static boolean isDefaultLaplacianKernel(final int[] k) {
        return k[0] == 1
                && k[1] == 1
                && k[2] == 1
                && k[3] == 1
                && k[4] == LAPLACIAN_CENTER
                && k[5] == 1
                && k[6] == 1
                && k[7] == 1
                && k[8] == 1;
    }

    private static boolean isUniformOnesKernel(final int[] k) {
        return k[0] == 1 && k[1] == 1 && k[2] == 1 && k[3] == 1 && k[4] == 1 && k[5] == 1 && k[6] == 1 && k[7] == 1
                && k[8] == 1;
    }

    private static ExecutorService createWorkerPool() {
        final int workers = workerCount();
        final AtomicInteger id = new AtomicInteger(1);
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + id.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
                workers, workers, WORKER_IDLE_SECONDS, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static ImageHandles resolveHandles(final ClassLoader loader) {
        if (loader == null) {
            return null;
        }
        final ImageHandles cached = lastHandles;
        if (cached != null && cached.loader == loader) {
            return cached;
        }
        synchronized (HANDLES_LOCK) {
            final ImageHandles mapped = HANDLES_BY_LOADER.get(loader);
            if (mapped != null) {
                lastHandles = mapped;
                return mapped;
            }
            try {
                final Class<?> writableClass = Class.forName(WRITABLE_IMAGE_CLASS, false, loader);
                final Class<?> colorTypeClass = Class.forName(COLOR_TYPE_ENUM, false, loader);
                final Object argbType = colorTypeClass.getField(FIELD_ARGB_TYPE).get(null);
                final Object grayType = colorTypeClass.getField(FIELD_GRAY_TYPE).get(null);

                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final MethodHandle getType =
                        lookup.findVirtual(writableClass, METHOD_GET_TYPE, MethodType.methodType(colorTypeClass));
                final MethodHandle getWidth =
                        lookup.findVirtual(writableClass, METHOD_GET_WIDTH, MethodType.methodType(int.class));
                final MethodHandle getHeight =
                        lookup.findVirtual(writableClass, METHOD_GET_HEIGHT, MethodType.methodType(int.class));
                final MethodHandle getIntBuffer =
                        lookup.findVirtual(writableClass, METHOD_GET_INT_BUFFER, MethodType.methodType(int[].class));
                final MethodHandle getByteBuffer =
                        lookup.findVirtual(writableClass, METHOD_GET_BYTE_BUFFER, MethodType.methodType(byte[].class));
                final MethodHandle createReduced = lookup.findVirtual(
                        writableClass,
                        METHOD_CREATE_REDUCED,
                        MethodType.methodType(writableClass, int.class, int.class));
                final MethodHandle newWritableImage =
                        lookup.unreflectConstructor(writableClass.getConstructor(int.class, int.class, colorTypeClass));

                MethodHandle hostEdgeClean = null;
                try {
                    final Class<?> hostEdgeClass = Class.forName(EDGE_CLEAN_HOST_CLASS, false, loader);
                    hostEdgeClean = lookup.findStatic(
                            hostEdgeClass,
                            "a",
                            MethodType.methodType(BufferedImage.class, BufferedImage.class, int.class, boolean.class));
                } catch (final Throwable ignored) {
                    // Optional when unit testing without host edge clean utility on classpath.
                }

                final ImageHandles created = new ImageHandles(
                        loader,
                        argbType,
                        grayType,
                        getType,
                        getWidth,
                        getHeight,
                        getIntBuffer,
                        getByteBuffer,
                        createReduced,
                        newWritableImage,
                        hostEdgeClean);
                HANDLES_BY_LOADER.put(loader, created);
                lastHandles = created;
                return created;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return null;
            }
        }
    }

    private record ImageHandles(
            ClassLoader loader,
            Object argbType,
            Object grayType,
            MethodHandle getType,
            MethodHandle getWidth,
            MethodHandle getHeight,
            MethodHandle getIntBuffer,
            MethodHandle getByteBuffer,
            MethodHandle createReduced,
            MethodHandle newWritableImage,
            MethodHandle hostEdgeClean) {}
}
