package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Accelerates PNG image decoding with memory-cached streams, fast pixel format conversion, and a
 * bounded background pre-decode pool with pixel budget control.
 */
public final class ImageDecodeBridge {

    private static final int SHIFT_ALPHA = 24;
    private static final int SHIFT_RED = 16;
    private static final int SHIFT_GREEN = 8;
    private static final int BYTE_MASK = 0xFF;
    private static final int OPAQUE_ALPHA = 0xFF00_0000;
    private static final int ABGR_PIXEL_STRIDE = 4;
    private static final int BGR_PIXEL_STRIDE = 3;
    private static final int[] EXPECTED_ABGR_BANDS = {3, 2, 1, 0};
    private static final int[] EXPECTED_BGR_BANDS = {2, 1, 0};

    private static final int MIN_POOL_THREADS = 2;
    private static final int MAX_POOL_THREADS = 6;
    private static final int MAX_PREDECODE_QUEUE_CAPACITY = 512;
    private static final long MIN_IN_FLIGHT_PIXELS = 33_554_432L;
    private static final long MAX_IN_FLIGHT_PIXELS_CAP = 201_326_592L;
    private static final long ARGB_BYTES_PER_PIXEL = 4L;
    private static final long HEAP_BUDGET_DIVISOR = 8L;
    private static final long BYTES_PER_BUDGET_PIXEL = ARGB_BYTES_PER_PIXEL * HEAP_BUDGET_DIVISOR;
    private static final long MAX_IN_FLIGHT_PIXELS =
            computeMaxInFlightPixels(Runtime.getRuntime().maxMemory());
    private static final int MIN_PREDECODE_WIDTH = 1;
    private static final String PREDECODE_THREAD_NAME = "turboism-png-predecode";

    private static final String METHOD_GET_TEXTURE_ATLASES = "getTextureAtlases";
    private static final String METHOD_GET_CACHED_ATLAS_IMAGE = "getCachedAtlasImage";
    private static final String METHOD_GET_ALL_MODEL_IMAGES = "getAllModelImages";
    private static final String METHOD_GET_FILTERED_IMAGE = "getFilteredImage";
    private static final String FIELD_FILTERED_IMAGE = "_filteredImage";
    private static final String METHOD_GET_TEXTURE_LIST = "getTextureList";
    private static final String METHOD_GET_CHILDREN = "getChildren";
    private static final String METHOD_GET_IMAGE_RESOURCE = "getImageResource";
    private static final String METHOD_IS_DISPOSED = "isDisposed";
    private static final String METHOD_GET_WIDTH = "getWidth";
    private static final String METHOD_GET_HEIGHT = "getHeight";
    private static final String METHOD_GET_IMAGE_OR_NULL = "getImageOrNullIfDisposed";
    private static final String METHOD_NEEDS_PREDECODE = "turboism$needsPredecode";

    private static final Object BUDGET_LOCK = new Object();
    private static long inFlightPixels;

    private static final Set<Object> SCHEDULED_RESOURCES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static volatile ExecutorService predecodeExecutor;
    private static volatile PredecodeHandles cachedHandles;
    private static volatile ImageResourceHandles cachedResourceHandles;

    private ImageDecodeBridge() {}

    /**
     * Reads a buffered image, wrapping owned in-memory streams in {@link MemoryCacheImageInputStream}
     * without changing global {@link ImageIO} cache settings.
     *
     * @param activeStream effective stream (possibly progress-wrapped) passed to ImageIO
     * @param originalStream original input stream argument supplied to {@code getBufferedImage}
     * @return decoded raw {@code BufferedImage}, or {@code null} if no reader matched
     * @throws IOException when decoding fails
     */
    public static BufferedImage readBufferedImage(final InputStream activeStream, final InputStream originalStream)
            throws IOException {
        return readBufferedImage(activeStream, originalStream, -1);
    }

    /**
     * Wraps an immutable image payload in a {@link ByteArrayInputStream} so unread callers can
     * decode directly without copying the backing array.
     *
     * @param imageBytes immutable encoded image payload
     * @return {@link ByteArrayInputStream} backed directly by {@code imageBytes}
     */
    public static ByteArrayInputStream wrapImmutableImageBytes(final byte[] imageBytes) {
        return new ReadOnlyByteArrayInputStream(imageBytes);
    }

    /**
     * Decodes a buffered image directly into {@code TYPE_INT_ARGB} for eligible in-memory PNG
     * streams, falling back to {@link ImageIO} with memory-cached streams otherwise.
     *
     * @param activeStream effective stream (possibly progress-wrapped) passed to ImageIO
     * @param originalStream original input stream argument supplied to {@code getBufferedImage}
     * @param targetType requested {@link BufferedImage} type, or {@code -1} to preserve source type
     * @return decoded {@code BufferedImage}, or {@code null} if no reader matched
     * @throws IOException when decoding fails
     */
    public static BufferedImage readBufferedImage(
            final InputStream activeStream, final InputStream originalStream, final int targetType) throws IOException {
        if (originalStream instanceof ByteArrayInputStream && activeStream != null) {
            if (targetType == BufferedImage.TYPE_INT_ARGB) {
                final byte[] rawBytes = extractRawBytes(activeStream);
                final BufferedImage direct = FastPngDecoder.tryDecodeToIntArgb(rawBytes);
                if (direct != null) {
                    return direct;
                }
                try (MemoryCacheImageInputStream memoryStream =
                        new TrackedMemoryImageInputStream(new ByteArrayInputStream(rawBytes))) {
                    return ImageIO.read(memoryStream);
                }
            }
            try (MemoryCacheImageInputStream memoryStream = new TrackedMemoryImageInputStream(activeStream)) {
                return ImageIO.read(memoryStream);
            }
        }
        return ImageIO.read(activeStream);
    }

    static byte[] extractRawBytes(final InputStream stream) throws IOException {
        if (stream instanceof ReadOnlyByteArrayInputStream readOnlyStream) {
            final byte[] direct = readOnlyStream.claimUnreadBackingArray();
            if (direct != null) {
                return direct;
            }
        }
        return ArchiveReadBridge.readAllBytes(stream);
    }

    /**
     * Converts {@code TYPE_4BYTE_ABGR} or {@code TYPE_3BYTE_BGR} images directly to
     * {@code TYPE_INT_ARGB}, bypassing AWT {@code Graphics2D.drawImage} overhead.
     *
     * @param src decoded source {@code BufferedImage}
     * @param targetType requested {@code BufferedImage} type, or {@code -1} to keep original
     * @return converted image, {@code src} when unchanged, or {@code null} to run the native path
     */
    public static BufferedImage tryConvertToIntArgb(final BufferedImage src, final int targetType) {
        if (src == null) {
            return null;
        }
        final int srcType = src.getType();
        if (targetType == -1 || srcType == targetType) {
            return src;
        }
        if (targetType != BufferedImage.TYPE_INT_ARGB) {
            return null;
        }
        if (srcType != BufferedImage.TYPE_4BYTE_ABGR && srcType != BufferedImage.TYPE_3BYTE_BGR) {
            return null;
        }
        final int width = src.getWidth();
        final int height = src.getHeight();
        if (width <= 0 || height <= 0 || src.isAlphaPremultiplied()) {
            return null;
        }
        if (src.getColorModel() == null || !src.getColorModel().getColorSpace().isCS_sRGB()) {
            return null;
        }
        final WritableRaster raster = src.getRaster();
        if (raster == null
                || !(raster.getSampleModel() instanceof ComponentSampleModel sm)
                || !(raster.getDataBuffer() instanceof DataBufferByte db)
                || db.getNumBanks() != 1) {
            return null;
        }
        final int expectedStride = srcType == BufferedImage.TYPE_4BYTE_ABGR ? ABGR_PIXEL_STRIDE : BGR_PIXEL_STRIDE;
        final int[] expectedBands = srcType == BufferedImage.TYPE_4BYTE_ABGR ? EXPECTED_ABGR_BANDS : EXPECTED_BGR_BANDS;
        if (sm.getPixelStride() != expectedStride || !Arrays.equals(sm.getBandOffsets(), expectedBands)) {
            return null;
        }

        final BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int[] dstInts = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        final byte[] srcBytes = db.getData();
        final int scanStride = sm.getScanlineStride();
        final int baseOffset = db.getOffset()
                - raster.getSampleModelTranslateY() * scanStride
                - raster.getSampleModelTranslateX() * expectedStride;
        if (baseOffset < 0) {
            return null;
        }

        if (srcType == BufferedImage.TYPE_4BYTE_ABGR) {
            convertAbgrToArgb(srcBytes, dstInts, baseOffset, scanStride, width, height);
        } else {
            convertBgrToArgb(srcBytes, dstInts, baseOffset, scanStride, width, height);
        }
        src.flush();
        return dst;
    }

    /**
     * Submits undecoded texture images from a deserialized {@code CTextureManager} to a bounded
     * background worker pool so PNG decode overlaps model setup off the EDT.
     *
     * @param textureManager Cubism {@code CTextureManager} instance, nullable
     */
    public static void scheduleTextureManagerPredecode(final Object textureManager) {
        scheduleTextureManagerPredecode(textureManager, true);
    }

    /**
     * Submits undecoded texture images from a deserialized {@code CTextureManager} to a bounded
     * background worker pool.
     *
     * @param textureManager Cubism {@code CTextureManager} instance, nullable
     * @param allowLazyModelImageFilter {@code false} before {@code CModelSource.setup()} to read
     *     only already-deserialized {@code CModelImage._filteredImage} fields without triggering
     *     lazy filter evaluation
     */
    public static void scheduleTextureManagerPredecode(
            final Object textureManager, final boolean allowLazyModelImageFilter) {
        if (textureManager == null) {
            return;
        }
        final PredecodeHandles handles = resolveHandles(textureManager);
        if (handles == null) {
            return;
        }
        final List<Object> candidates = new ArrayList<>();
        try {
            collectAtlasResources(textureManager, handles, candidates);
            collectModelImageResources(textureManager, handles, allowLazyModelImageFilter, candidates);
            collectTextureListResources(textureManager, handles, candidates);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        final ExecutorService pool = getOrCreatePool(handles.loader);
        for (final Object resource : candidates) {
            enqueueResource(resource, handles.resourceHandles, pool);
        }
    }

    /**
     * Submits a single deserialized {@code CImageResource} to the bounded background pre-decode
     * pool using the shared deduplication set and heap-aware pixel budget.
     *
     * @param imageResource Cubism {@code CImageResource} instance, nullable
     */
    public static void scheduleImageResourcePredecode(final Object imageResource) {
        if (imageResource == null) {
            return;
        }
        final ImageResourceHandles handles = resolveResourceHandles(imageResource.getClass());
        if (handles == null) {
            return;
        }
        enqueueResource(imageResource, handles, getOrCreatePool(handles.loader));
    }

    private static void collectAtlasResources(
            final Object textureManager, final PredecodeHandles handles, final List<Object> out) throws Throwable {
        if (handles.getTextureAtlases == null || handles.getCachedAtlasImage == null) {
            return;
        }
        final Object atlases = handles.getTextureAtlases.invoke(textureManager);
        if (atlases instanceof List<?> list) {
            for (final Object atlas : list) {
                if (atlas != null) {
                    final Object res = handles.getCachedAtlasImage.invoke(atlas);
                    if (res != null) {
                        out.add(res);
                    }
                }
            }
        }
    }

    private static void collectModelImageResources(
            final Object textureManager,
            final PredecodeHandles handles,
            final boolean allowLazyModelImageFilter,
            final List<Object> out)
            throws Throwable {
        final MethodHandle imageGetter =
                allowLazyModelImageFilter ? handles.getFilteredImage : handles.getRawFilteredImage;
        if (handles.getAllModelImages == null || imageGetter == null) {
            return;
        }
        final Object modelImages = handles.getAllModelImages.invoke(textureManager);
        if (modelImages instanceof List<?> list) {
            for (final Object modelImage : list) {
                if (modelImage != null) {
                    final Object res = imageGetter.invoke(modelImage);
                    if (res != null) {
                        out.add(res);
                    }
                }
            }
        }
    }

    private static void collectTextureListResources(
            final Object textureManager, final PredecodeHandles handles, final List<Object> out) throws Throwable {
        if (handles.getTextureList == null
                || handles.getTextureGroupChildren == null
                || handles.getTextureImageResource == null) {
            return;
        }
        final Object group = handles.getTextureList.invoke(textureManager);
        if (group == null) {
            return;
        }
        final Object children = handles.getTextureGroupChildren.invoke(group);
        if (children instanceof List<?> list) {
            for (final Object texImage : list) {
                if (texImage != null) {
                    final Object res = handles.getTextureImageResource.invoke(texImage);
                    if (res != null) {
                        out.add(res);
                    }
                }
            }
        }
    }

    private static void enqueueResource(
            final Object resource, final ImageResourceHandles handles, final ExecutorService pool) {
        if (!SCHEDULED_RESOURCES.add(resource)) {
            return;
        }
        try {
            if ((boolean) handles.isDisposed.invoke(resource)) {
                return;
            }
            if (handles.needsPredecode != null && !(boolean) handles.needsPredecode.invoke(resource)) {
                return;
            }
            final int width = (int) handles.getWidth.invoke(resource);
            final int height = (int) handles.getHeight.invoke(resource);
            if (width < MIN_PREDECODE_WIDTH || height < MIN_PREDECODE_WIDTH) {
                return;
            }
            final long pixels = (long) width * height;
            pool.execute(() -> runPredecodeTask(resource, pixels, handles));
        } catch (final RejectedExecutionException rejected) {
            SCHEDULED_RESOURCES.remove(resource);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static void runPredecodeTask(final Object resource, final long pixels, final ImageResourceHandles handles) {
        try {
            if ((boolean) handles.isDisposed.invoke(resource)) {
                return;
            }
            if (handles.needsPredecode != null && !(boolean) handles.needsPredecode.invoke(resource)) {
                return;
            }
            if (!acquirePixelBudget(pixels)) {
                return;
            }
            try {
                if (!(boolean) handles.isDisposed.invoke(resource)) {
                    handles.getImageOrNullIfDisposed.invoke(resource);
                }
            } finally {
                releasePixelBudget(pixels);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static boolean acquirePixelBudget(final long pixels) {
        synchronized (BUDGET_LOCK) {
            while (inFlightPixels > 0L && inFlightPixels + pixels > MAX_IN_FLIGHT_PIXELS) {
                try {
                    BUDGET_LOCK.wait();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            inFlightPixels += pixels;
            return true;
        }
    }

    private static void releasePixelBudget(final long pixels) {
        synchronized (BUDGET_LOCK) {
            inFlightPixels = Math.max(0L, inFlightPixels - pixels);
            BUDGET_LOCK.notifyAll();
        }
    }

    private static ExecutorService getOrCreatePool(final ClassLoader preferredLoader) {
        final ExecutorService existing = predecodeExecutor;
        if (existing != null) {
            return existing;
        }
        synchronized (ImageDecodeBridge.class) {
            if (predecodeExecutor == null) {
                final int workers = computeWorkerCount(Runtime.getRuntime().availableProcessors());
                final ClassLoader contextLoader = preferredLoader != null
                        ? preferredLoader
                        : RuntimeThreads.contextClassLoader(ImageDecodeBridge.class);
                predecodeExecutor = RuntimeThreads.fixedLane(
                        workers,
                        MAX_PREDECODE_QUEUE_CAPACITY,
                        RuntimeThreads.daemonThreads(PREDECODE_THREAD_NAME, contextLoader));
            }
            return predecodeExecutor;
        }
    }

    static int computeWorkerCount(final int cpuCount) {
        return Math.min(MAX_POOL_THREADS, Math.max(MIN_POOL_THREADS, cpuCount - 1));
    }

    static long computeMaxInFlightPixels(final long maxHeapBytes) {
        if (maxHeapBytes <= 0L) {
            return MIN_IN_FLIGHT_PIXELS;
        }
        final long heapScaled = maxHeapBytes / BYTES_PER_BUDGET_PIXEL;
        return Math.min(MAX_IN_FLIGHT_PIXELS_CAP, Math.max(MIN_IN_FLIGHT_PIXELS, heapScaled));
    }

    private static void convertAbgrToArgb(
            final byte[] src,
            final int[] dst,
            final int baseOffset,
            final int scanStride,
            final int width,
            final int height) {
        if (baseOffset == 0 && scanStride == width * ABGR_PIXEL_STRIDE) {
            final int total = width * height;
            int s = 0;
            for (int i = 0; i < total; i++, s += ABGR_PIXEL_STRIDE) {
                dst[i] = ((src[s] & BYTE_MASK) << SHIFT_ALPHA)
                        | ((src[s + 3] & BYTE_MASK) << SHIFT_RED)
                        | ((src[s + 2] & BYTE_MASK) << SHIFT_GREEN)
                        | (src[s + 1] & BYTE_MASK);
            }
            return;
        }
        int dstIdx = 0;
        for (int y = 0; y < height; y++) {
            int s = baseOffset + y * scanStride;
            for (int x = 0; x < width; x++, s += ABGR_PIXEL_STRIDE) {
                dst[dstIdx++] = ((src[s] & BYTE_MASK) << SHIFT_ALPHA)
                        | ((src[s + 3] & BYTE_MASK) << SHIFT_RED)
                        | ((src[s + 2] & BYTE_MASK) << SHIFT_GREEN)
                        | (src[s + 1] & BYTE_MASK);
            }
        }
    }

    private static void convertBgrToArgb(
            final byte[] src,
            final int[] dst,
            final int baseOffset,
            final int scanStride,
            final int width,
            final int height) {
        if (baseOffset == 0 && scanStride == width * BGR_PIXEL_STRIDE) {
            final int total = width * height;
            int s = 0;
            for (int i = 0; i < total; i++, s += BGR_PIXEL_STRIDE) {
                dst[i] = OPAQUE_ALPHA
                        | ((src[s + 2] & BYTE_MASK) << SHIFT_RED)
                        | ((src[s + 1] & BYTE_MASK) << SHIFT_GREEN)
                        | (src[s] & BYTE_MASK);
            }
            return;
        }
        int dstIdx = 0;
        for (int y = 0; y < height; y++) {
            int s = baseOffset + y * scanStride;
            for (int x = 0; x < width; x++, s += BGR_PIXEL_STRIDE) {
                dst[dstIdx++] = OPAQUE_ALPHA
                        | ((src[s + 2] & BYTE_MASK) << SHIFT_RED)
                        | ((src[s + 1] & BYTE_MASK) << SHIFT_GREEN)
                        | (src[s] & BYTE_MASK);
            }
        }
    }

    private static PredecodeHandles resolveHandles(final Object textureManager) {
        final PredecodeHandles current = cachedHandles;
        final ClassLoader loader = textureManager.getClass().getClassLoader();
        if (current != null && current.loader == loader) {
            return current;
        }
        try {
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            final Class<?> managerClass = textureManager.getClass();
            final Class<?> atlasClass =
                    Class.forName("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas", false, loader);
            final Class<?> modelImageClass =
                    Class.forName("com.live2d.cubism.doc.model.texture.modelImage.CModelImage", false, loader);
            final Class<?> texGroupClass =
                    Class.forName("com.live2d.cubism.doc.model.texture.TextureImageGroup", false, loader);
            final Class<?> texImageClass =
                    Class.forName("com.live2d.cubism.doc.model.texture.CTextureImage", false, loader);
            final Class<?> imageResClass = Class.forName("com.live2d.graphics.CImageResource", false, loader);
            final Class<?> arrayListClass = Class.forName("com.live2d.type.CArrayList", false, loader);
            final ImageResourceHandles resourceHandles = resolveResourceHandles(imageResClass);
            if (resourceHandles == null) {
                return null;
            }

            final MethodHandle getTextureAtlases =
                    lookup.findVirtual(managerClass, METHOD_GET_TEXTURE_ATLASES, MethodType.methodType(List.class));
            final MethodHandle getCachedAtlasImage =
                    lookup.findVirtual(atlasClass, METHOD_GET_CACHED_ATLAS_IMAGE, MethodType.methodType(imageResClass));
            final MethodHandle getAllModelImages =
                    lookup.findVirtual(managerClass, METHOD_GET_ALL_MODEL_IMAGES, MethodType.methodType(List.class));
            final MethodHandle getFilteredImage = lookup.findVirtual(
                    modelImageClass, METHOD_GET_FILTERED_IMAGE, MethodType.methodType(imageResClass));
            MethodHandle getRawFilteredImage = null;
            try {
                final Field filteredImageField = modelImageClass.getDeclaredField(FIELD_FILTERED_IMAGE);
                filteredImageField.setAccessible(true);
                getRawFilteredImage = MethodHandles.lookup().unreflectGetter(filteredImageField);
            } catch (final NoSuchFieldException | IllegalAccessException | RuntimeException ignored) {
                // Skip pre-setup model images when direct field access is unavailable.
            }
            final MethodHandle getTextureList =
                    lookup.findVirtual(managerClass, METHOD_GET_TEXTURE_LIST, MethodType.methodType(texGroupClass));
            final MethodHandle getTextureGroupChildren =
                    lookup.findVirtual(texGroupClass, METHOD_GET_CHILDREN, MethodType.methodType(arrayListClass));
            final MethodHandle getTextureImageResource =
                    lookup.findVirtual(texImageClass, METHOD_GET_IMAGE_RESOURCE, MethodType.methodType(imageResClass));

            final PredecodeHandles resolved = new PredecodeHandles(
                    loader,
                    getTextureAtlases,
                    getCachedAtlasImage,
                    getAllModelImages,
                    getFilteredImage,
                    getRawFilteredImage,
                    getTextureList,
                    getTextureGroupChildren,
                    getTextureImageResource,
                    resourceHandles);
            cachedHandles = resolved;
            return resolved;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static ImageResourceHandles resolveResourceHandles(final Class<?> imageResClass) {
        final ImageResourceHandles current = cachedResourceHandles;
        final ClassLoader loader = imageResClass.getClassLoader();
        if (current != null && current.loader == loader && current.resourceClass == imageResClass) {
            return current;
        }
        try {
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            final MethodHandle isDisposed =
                    lookup.findVirtual(imageResClass, METHOD_IS_DISPOSED, MethodType.methodType(boolean.class));
            final MethodHandle getWidth =
                    lookup.findVirtual(imageResClass, METHOD_GET_WIDTH, MethodType.methodType(int.class));
            final MethodHandle getHeight =
                    lookup.findVirtual(imageResClass, METHOD_GET_HEIGHT, MethodType.methodType(int.class));
            final MethodHandle getImageOrNull = resolveGetImageHandle(lookup, imageResClass, loader);
            MethodHandle needsPredecode = null;
            try {
                needsPredecode =
                        lookup.findVirtual(imageResClass, METHOD_NEEDS_PREDECODE, MethodType.methodType(boolean.class));
            } catch (final NoSuchMethodException | IllegalAccessException ignored) {
                // Optional fast precheck added by ImageDecodePatcher.
            }
            final ImageResourceHandles resolved = new ImageResourceHandles(
                    loader, imageResClass, isDisposed, getWidth, getHeight, getImageOrNull, needsPredecode);
            cachedResourceHandles = resolved;
            return resolved;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static MethodHandle resolveGetImageHandle(
            final MethodHandles.Lookup lookup, final Class<?> imageResClass, final ClassLoader loader)
            throws ReflectiveOperationException {
        try {
            final Class<?> writableClass = Class.forName("com.live2d.graphics.CWritableImage", false, loader);
            return lookup.findVirtual(imageResClass, METHOD_GET_IMAGE_OR_NULL, MethodType.methodType(writableClass));
        } catch (final ClassNotFoundException | NoSuchMethodException ignored) {
            final Method method = imageResClass.getMethod(METHOD_GET_IMAGE_OR_NULL);
            return lookup.unreflect(method);
        }
    }

    private static final class ReadOnlyByteArrayInputStream extends ByteArrayInputStream {

        private final byte[] backing;

        private ReadOnlyByteArrayInputStream(final byte[] bytes) {
            super(bytes);
            this.backing = bytes;
        }

        private byte[] claimUnreadBackingArray() {
            if (this.pos != 0 || this.count != this.backing.length) {
                return null;
            }
            this.pos = this.count;
            return this.backing;
        }
    }

    private static final class TrackedMemoryImageInputStream extends MemoryCacheImageInputStream {

        private boolean closed;

        private TrackedMemoryImageInputStream(final InputStream stream) {
            super(stream);
        }

        @Override
        public void close() throws IOException {
            // ImageIO.read closes the stream when a reader matched; guard against double-close.
            if (!this.closed) {
                this.closed = true;
                super.close();
            }
        }
    }

    private record ImageResourceHandles(
            ClassLoader loader,
            Class<?> resourceClass,
            MethodHandle isDisposed,
            MethodHandle getWidth,
            MethodHandle getHeight,
            MethodHandle getImageOrNullIfDisposed,
            MethodHandle needsPredecode) {}

    private record PredecodeHandles(
            ClassLoader loader,
            MethodHandle getTextureAtlases,
            MethodHandle getCachedAtlasImage,
            MethodHandle getAllModelImages,
            MethodHandle getFilteredImage,
            MethodHandle getRawFilteredImage,
            MethodHandle getTextureList,
            MethodHandle getTextureGroupChildren,
            MethodHandle getTextureImageResource,
            ImageResourceHandles resourceHandles) {}
}
