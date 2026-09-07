package dev.turboism.adapter.cubism.optimization.image;

import dev.turboism.sdk.plugin.Registration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Loader-neutral, opt-in native PNG reuse callback. It only reads native resources while
 * their own monitor is held. Every callback failure returns to the unchanged native encoder.
 */
public final class ImageArchiveReuseBridge implements AutoCloseable {
    /** Startup opt-in and live disable switch; absent or non-true values disable reuse. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.imageArchiveReuse";
    /** JDK-only native callback slot. */
    public static final String CALLBACK_PROPERTY = "turboism.image-archive-reuse.callback";
    /** Read-only, payload-free counters for diagnostics and exact-host validation. */
    public static final String STATS_PROPERTY = "turboism.image-archive-reuse.stats";
    private final PngArchiveReuseCache cache = new PngArchiveReuseCache(1024);
    private final Class<?> resourceClass;
    private final Class<?> imageClass;
    private final Field image;
    private final Field png;
    private final Field resourceType;
    private final Field defaultType;
    private final Method pixels;
    private final Method width;
    private final Method height;
    private final Method imageType;
    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder decoded = new LongAdder();
    private final LongAdder archiveChecks = new LongAdder();
    private final LongAdder reused = new LongAdder();
    private final LongAdder fallback = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder decodedPixels = new LongAdder();
    private final LongAdder fallbackWithoutPng = new LongAdder();
    private final BiFunction<Object,Object,Object> callback = this::invoke;
    private final Supplier<Map<String,Long>> counters = this::snapshot;
    private Properties installedProperties;

    /** Binds only the two exact classes previously attested by the bootstrap installer. */
    public ImageArchiveReuseBridge(final Class<?> resourceClass, final Class<?> imageClass)
        throws ReflectiveOperationException {
        this.resourceClass = Objects.requireNonNull(resourceClass);
        this.imageClass = Objects.requireNonNull(imageClass);
        if (!resourceClass.getName().equals("com.live2d.graphics.CImageResource")
            || !imageClass.getName().equals("com.live2d.graphics.CWritableImage")
            || resourceClass.getClassLoader() != imageClass.getClassLoader()) {
            throw new IllegalArgumentException("image archive classes do not match the attested profile");
        }
        image = field(resourceClass,"image"); png = field(resourceClass,"imageFileBuf");
        resourceType = field(resourceClass,"type"); defaultType = field(resourceClass,"DEFAULT_COLOR_TYPE");
        pixels = imageClass.getMethod("getIntBuffer"); width = imageClass.getMethod("getWidth");
        height = imageClass.getMethod("getHeight"); imageType = imageClass.getMethod("getType");
    }

    /** Installs this callback once, rejecting any occupied slot rather than replacing it. */
    public synchronized Registration install() {
        if (active.get()) throw new IllegalStateException("image archive bridge already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(CALLBACK_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("image archive callback slot is occupied");
            }
            try {
                properties.put(CALLBACK_PROPERTY,callback); properties.put(STATS_PROPERTY,counters);
                installedProperties = properties; active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(CALLBACK_PROPERTY,callback); properties.remove(STATS_PROPERTY,counters);
                throw failure;
            }
        }
        return this::close;
    }

    private Object invoke(final Object resource, final Object archiveImage) {
        if (!active.get() || !Boolean.getBoolean(ENABLE_PROPERTY)) return null;
        try {
            if (resource == null || resource.getClass() != resourceClass || !Thread.holdsLock(resource)) return null;
            final Object currentImage = image.get(resource);
            if (currentImage == null || currentImage.getClass() != imageClass
                || (archiveImage != null && archiveImage != currentImage)) return null;
            final Object format = defaultType.get(null);
            if (format == null || resourceType.get(resource) != format || imageType.invoke(currentImage) != format) return null;
            final byte[] encoded = (byte[])png.get(resource);
            final int[] argb = (int[])pixels.invoke(currentImage);
            final int w = (int)width.invoke(currentImage), h = (int)height.invoke(currentImage);
            if (archiveImage == null) {
                decoded.increment(); cache.remember(resource,currentImage,encoded,w,h,argb);
                decodedPixels.add(argb.length);
                if (!active.get()) cache.clear();
                return null;
            }
            archiveChecks.increment();
            final byte[] result = cache.reusable(resource,currentImage,encoded,w,h,argb);
            if (result != null && active.get() && Boolean.getBoolean(ENABLE_PROPERTY)) {
                reused.increment(); return result;
            }
            if (encoded == null) fallbackWithoutPng.increment();
            fallback.increment(); return null;
        } catch (Throwable rejected) {
            failures.increment();
            return null;
        }
    }

    /** Returns counts only; this does not enumerate, retain or expose native image payloads. */
    public Map<String,Long> snapshot() {
        return Map.of("active",active.get()?1L:0L,"decodeObservations",decoded.sum(),
            "archiveChecks",archiveChecks.sum(),"reused",reused.sum(),"fallback",fallback.sum(),
            "failures",failures.sum(),"trackedProofs",(long)cache.size(),
            "decodePixelsObserved",decodedPixels.sum(),"fallbackWithoutPng",fallbackWithoutPng.sum());
    }

    /** Makes callbacks inert, identity-removes this installation's slots and releases proofs. */
    @Override public synchronized void close() {
        active.set(false);
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(CALLBACK_PROPERTY,callback); properties.remove(STATS_PROPERTY,counters);
        }
        cache.clear();
    }

    private static Field field(final Class<?> owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException("image archive field unavailable: " + name);
        return field;
    }
}
