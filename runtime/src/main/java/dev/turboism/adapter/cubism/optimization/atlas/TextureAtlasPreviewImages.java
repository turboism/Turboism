package dev.turboism.adapter.cubism.optimization.atlas;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Decodes and downscales atlas layer preview images concurrently off the event dispatch thread.
 *
 * <p>{@code CModelImage.getFilteredImage()} still runs sequentially on EDT; only thread-safe
 * {@code CImageResource.getImage()} decoding and BICUBIC scaling run in background workers,
 * while {@code previewImg} fields are populated on EDT before {@code TAE_DataModel} returns.</p>
 */
public final class TextureAtlasPreviewImages implements AutoCloseable {

    private static final int MAX_WORKERS = 4;
    private static final int MIN_WORKERS = 2;
    private static final int MAX_QUEUE_CAPACITY = 65_536;
    private static final long CLOSE_WAIT_MILLIS = 2_000L;
    private static final String WORKER_NAME = "turboism-texture-atlas-preview";
    private static final String LOG_COMPONENT = "texture-atlas-preview";
    private static final String SCALE_FAILED = "Parallel preview scaling failed, rebuilding on EDT";
    private static final String GRAPHICS_PACKAGE = "com.live2d.graphics.";
    private static final String IMAGE_RESOURCE_CLASS = GRAPHICS_PACKAGE + "CImageResource";
    private static final String WRITABLE_IMAGE_CLASS = GRAPHICS_PACKAGE + "CWritableImage";
    private static final String COLOR_TYPE_CLASS = GRAPHICS_PACKAGE + "n";
    private static final String GET_IMAGE_METHOD = "getImage";
    private static final String CREATE_SCALED_METHOD = "createScaled";
    private static final String GET_BUFFERED_IMAGE_METHOD = "getJBufferedImage";

    private static final ClassValue<Optional<Host>> HOST = new ClassValue<>() {
        @Override
        protected Optional<Host> computeValue(final Class<?> srcImageClass) {
            return Host.bind(srcImageClass);
        }
    };

    private final List<PendingPreview> pending = new ArrayList<>();
    private final IdentityHashMap<Object, List<DedupeEntry>> deduplicated = new IdentityHashMap<>();
    private ExecutorService executor;
    private Host host;

    /** Schedules preview generation within an active open session, or builds synchronously outside it. */
    public static Object acquireOrSchedule(
            final Object layer, final Object srcImage, final int width, final int height, final Object colorType)
            throws Throwable {
        Objects.requireNonNull(srcImage, "srcImage");
        final Host bound = HOST.get(srcImage.getClass()).orElseThrow(IllegalStateException::new);
        final TextureAtlasPreviewImages batch = TextureAtlasLoading.previewBatch();
        if (batch == null || layer == null || !TextureAtlasLoading.hasLayerSetPreview(layer)) {
            return bound.buildSync(srcImage, width, height, colorType);
        }
        return batch.submit(bound, layer, srcImage, width, height, colorType);
    }

    private Object submit(
            final Host bound,
            final Object layer,
            final Object srcImage,
            final int width,
            final int height,
            final Object colorType) {
        if (host == null) {
            host = bound;
        }
        final Future<BufferedImage> future = reuseOrSubmit(bound, srcImage, width, height, colorType);
        pending.add(new PendingPreview(layer, srcImage, width, height, colorType, future));
        return null;
    }

    private Future<BufferedImage> reuseOrSubmit(
            final Host bound, final Object srcImage, final int width, final int height, final Object colorType) {
        final List<DedupeEntry> entries = deduplicated.computeIfAbsent(srcImage, ignored -> new ArrayList<>(1));
        for (int i = 0; i < entries.size(); i++) {
            final DedupeEntry entry = entries.get(i);
            if (entry.matches(width, height, colorType)) {
                return entry.future();
            }
        }
        final ExecutorService pool = ensureExecutor(bound);
        final Future<BufferedImage> future =
                pool.submit(() -> bound.buildScaledBuffer(srcImage, width, height, colorType));
        entries.add(new DedupeEntry(width, height, colorType, future));
        return future;
    }

    private ExecutorService ensureExecutor(final Host bound) {
        if (executor == null) {
            final int cpus = Runtime.getRuntime().availableProcessors();
            final int workers = Math.max(MIN_WORKERS, Math.min(MAX_WORKERS, Math.max(MIN_WORKERS, cpus - 1)));
            executor = RuntimeThreads.fixedLane(
                    workers,
                    RuntimeThreads.linkedQueue(MAX_QUEUE_CAPACITY),
                    RuntimeThreads.daemonThreads(
                            WORKER_NAME, bound.imageResourceClass().getClassLoader()));
        }
        return executor;
    }

    void awaitAll() {
        if (pending.isEmpty()) return;
        final Host bound = Objects.requireNonNull(host, "host");
        try {
            for (int i = 0; i < pending.size(); i++) {
                final PendingPreview item = pending.get(i);
                Object writable = null;
                try {
                    final BufferedImage buf = item.future().get();
                    writable = bound.wrapBufferedImage(buf);
                } catch (ExecutionException failure) {
                    final Throwable cause = failure.getCause();
                    FatalErrors.rethrowIfFatal(cause);
                    RuntimeDiagnostics.error(LOG_COMPONENT, SCALE_FAILED, cause);
                    writable = fallbackSync(bound, item);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    writable = fallbackSync(bound, item);
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    RuntimeDiagnostics.error(LOG_COMPONENT, SCALE_FAILED, failure);
                    writable = fallbackSync(bound, item);
                }
                if (writable != null) {
                    try {
                        TextureAtlasLoading.assignLayerPreview(item.layer(), writable);
                    } catch (Throwable failure) {
                        FatalErrors.rethrowIfFatal(failure);
                        RuntimeDiagnostics.error(LOG_COMPONENT, SCALE_FAILED, failure);
                    }
                }
            }
        } finally {
            pending.clear();
            deduplicated.clear();
        }
    }

    private static Object fallbackSync(final Host bound, final PendingPreview item) {
        try {
            return bound.buildSync(item.srcImage(), item.width(), item.height(), item.colorType());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, SCALE_FAILED, failure);
            return null;
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < pending.size(); i++) {
            pending.get(i).future().cancel(true);
        }
        pending.clear();
        deduplicated.clear();
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record DedupeEntry(int width, int height, Object colorType, Future<BufferedImage> future) {
        boolean matches(final int w, final int h, final Object type) {
            return width == w && height == h && Objects.equals(colorType, type);
        }
    }

    private record PendingPreview(
            Object layer, Object srcImage, int width, int height, Object colorType, Future<BufferedImage> future) {}

    private record Host(
            Class<?> imageResourceClass,
            MethodHandle getImage,
            MethodHandle createScaled,
            MethodHandle getBufferedImage,
            MethodHandle newWritableImage) {
        static Optional<Host> bind(final Class<?> srcImageClass) {
            try {
                final ClassLoader loader = srcImageClass.getClassLoader();
                if (loader == null || !IMAGE_RESOURCE_CLASS.equals(srcImageClass.getName())) {
                    return Optional.empty();
                }
                final MethodHandles.Lookup lookup = MethodHandles.lookup();
                final Class<?> writableClass = loader.loadClass(WRITABLE_IMAGE_CLASS);
                final Class<?> colorTypeClass = loader.loadClass(COLOR_TYPE_CLASS);
                return Optional.of(new Host(
                        srcImageClass,
                        lookup.unreflect(srcImageClass.getMethod(GET_IMAGE_METHOD)),
                        lookup.unreflect(
                                writableClass.getMethod(CREATE_SCALED_METHOD, int.class, int.class, colorTypeClass)),
                        lookup.unreflect(writableClass.getMethod(GET_BUFFERED_IMAGE_METHOD)),
                        lookup.unreflectConstructor(writableClass.getConstructor(BufferedImage.class))));
            } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
                return Optional.empty();
            }
        }

        Object buildSync(final Object srcImage, final int width, final int height, final Object colorType)
                throws Throwable {
            final Object full = getImage.invoke(srcImage);
            return createScaled.invoke(full, width, height, colorType);
        }

        BufferedImage buildScaledBuffer(
                final Object srcImage, final int width, final int height, final Object colorType) throws Exception {
            try {
                final Object scaledWritable = buildSync(srcImage, width, height, colorType);
                return (BufferedImage) getBufferedImage.invoke(scaledWritable);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw failure instanceof Exception ex ? ex : new IllegalStateException(failure);
            }
        }

        Object wrapBufferedImage(final BufferedImage bufferedImage) throws Throwable {
            return newWritableImage.invoke(bufferedImage);
        }
    }
}
