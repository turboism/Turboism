package dev.turboism.adapter.cubism.optimization.image;

import dev.turboism.sdk.plugin.Registration;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Fresh, upload-only image preparation; owns no source image, texture, GL context or payload cache. */
public final class TextureUploadPreparationBridge implements AutoCloseable {
    /** Default-off startup opt-in and live disable switch. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.textureUploadPreparation";
    /** JDK-only (image, profile) callback slot. */
    public static final String CALLBACK_PROPERTY = "turboism.texture-upload-preparation.callback";
    /** Payload-free statistics. Prepared bytes are not an actual GPU-upload measurement. */
    public static final String STATS_PROPERTY = "turboism.texture-upload-preparation.stats";
    private final Class<?> profileClass;
    private final Method desktopProfile;
    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder calls = new LongAdder(), prepared = new LongAdder(), pixels = new LongAdder();
    private final LongAdder nanos = new LongAdder(), failures = new LongAdder(), argb = new LongAdder(), abgr = new LongAdder();
    private final BiFunction<Object, Object, Object> callback = this::invoke;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private Properties installedProperties;

    /** Binds the exact profile class attested by the bootstrap installer, without initializing a GL context. */
    public TextureUploadPreparationBridge(Class<?> profileClass) throws ReflectiveOperationException {
        if (!profileClass.getName().equals("com.jogamp.opengl.GLProfile")) throw new IllegalArgumentException("unattested profile class");
        this.profileClass = profileClass;
        desktopProfile = profileClass.getMethod("isGL2GL3");
    }

    /** Rejects occupied callback slots; installation lifetime belongs to bootstrap. */
    public synchronized Registration install() {
        if (active.get()) throw new IllegalStateException("texture preparation already installed");
        Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(CALLBACK_PROPERTY) || properties.containsKey(STATS_PROPERTY)) throw new IllegalStateException("texture preparation slots occupied");
            try {
                properties.put(CALLBACK_PROPERTY, callback); properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties; active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(CALLBACK_PROPERTY, callback); properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
        return this::close;
    }

    private Object invoke(Object image, Object profile) {
        if (!active.get() || !Boolean.getBoolean(ENABLE_PROPERTY)) return null;
        calls.increment();
        long start = System.nanoTime();
        try {
            if (!(image instanceof BufferedImage source) || source.getClass() != BufferedImage.class
                || profile == null || profile.getClass() != profileClass
                || !Boolean.TRUE.equals(desktopProfile.invoke(profile))) return null;
            if (source.getType() == BufferedImage.TYPE_INT_ARGB) argb.increment();
            if (source.getType() == BufferedImage.TYPE_4BYTE_ABGR) abgr.increment();
            BufferedImage result = CanonicalRgbaImage.prepare(source);
            if (result != null && active.get() && Boolean.getBoolean(ENABLE_PROPERTY)) {
                prepared.increment(); pixels.add((long) result.getWidth() * result.getHeight());
                return result;
            }
            return null;
        } catch (Exception | LinkageError rejected) {
            failures.increment(); return null;
        } finally { nanos.add(System.nanoTime() - start); }
    }

    /** Cumulative preparation work only: no GPU time, frame rate or retained-image accounting. */
    public Map<String, Long> snapshot() {
        return Map.of("active", active.get() ? 1L : 0L, "calls", calls.sum(), "prepared", prepared.sum(),
            "preparedPixels", pixels.sum(), "preparedBytes", pixels.sum() * 4, "preparationWallNs", nanos.sum(),
            "failures", failures.sum(), "intArgbInputs", argb.sum(), "abgrInputs", abgr.sum());
    }

    /** Makes outstanding callbacks inert and releases only this installation's JDK slots. */
    @Override public synchronized void close() {
        active.set(false);
        Properties properties = installedProperties; installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(CALLBACK_PROPERTY, callback); properties.remove(STATS_PROPERTY, statistics);
        }
    }
}
