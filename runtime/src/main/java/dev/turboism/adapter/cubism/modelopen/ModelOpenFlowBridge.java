package dev.turboism.adapter.cubism.modelopen;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Coordinates safe per-thread handoff between {@code CModelSource$a.a(File, boolean)} and
 * {@code com/live2d/cubism/doc/a/b/b.b(File)} so background PNG pre-decoding runs before setup and
 * an unmutated immediate duplicate {@code setup()} call is elided.
 */
public final class ModelOpenFlowBridge {

    private static final String GET_TEXTURE_MANAGER_METHOD = "getTextureManager";
    private static final ThreadLocal<WeakReference<Object>> COMPLETED_FACTORY_SETUP = new ThreadLocal<>();

    private static volatile Method getTextureManagerMethod;
    private static volatile Class<?> cachedModelSourceClass;

    private ModelOpenFlowBridge() {}

    /**
     * Clears any prior setup token and transient >64 MiB fallback buffer before opening a model.
     */
    public static void beginFactoryOpen() {
        COMPLETED_FACTORY_SETUP.remove();
        DirectTextureUploadBridge.clearTransientFallbackBuffer();
    }

    /**
     * Clears the transient >64 MiB texture fallback buffer after model open finishes.
     */
    public static void endDocumentLoad() {
        COMPLETED_FACTORY_SETUP.remove();
        DirectTextureUploadBridge.clearTransientFallbackBuffer();
    }

    /**
     * Schedules background PNG pre-decoding on {@code modelSource}'s texture manager before factory
     * {@code CModelSource.setup()} runs, keeping {@code CModelSource.setup()} bytecode untouched.
     *
     * @param modelSource deserialized {@code CModelSource} instance about to initialize
     */
    public static void beforeFactorySetup(final Object modelSource) {
        if (modelSource == null) {
            return;
        }
        try {
            final Class<?> modelClass = modelSource.getClass();
            Method getter = getTextureManagerMethod;
            if (getter == null || cachedModelSourceClass != modelClass) {
                getter = modelClass.getMethod(GET_TEXTURE_MANAGER_METHOD);
                cachedModelSourceClass = modelClass;
                getTextureManagerMethod = getter;
            }
            final Object textureManager = getter.invoke(modelSource);
            if (textureManager != null) {
                ImageDecodeBridge.scheduleTextureManagerPredecode(textureManager);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Records that factory deserialization finished invoking {@code CModelSource.setup()} cleanly on
     * {@code modelSource} and schedules background PNG pre-decoding to overlap verify and UI build.
     *
     * @param modelSource initialized {@code CModelSource} instance
     */
    public static void markFactorySetupCompleted(final Object modelSource) {
        if (modelSource != null) {
            COMPLETED_FACTORY_SETUP.set(new WeakReference<>(modelSource));
            beforeFactorySetup(modelSource);
        }
    }

    /**
     * Consumes the pending factory-setup token and returns {@code true} only when {@code modelSource}
     * matches by object identity.
     *
     * @param modelSource target {@code CModelSource} about to undergo redundant {@code setup()}
     * @return {@code true} when an unmutated setup just completed on the same thread and instance
     */
    public static boolean consumeFactorySetupCompleted(final Object modelSource) {
        final WeakReference<Object> ref = COMPLETED_FACTORY_SETUP.get();
        COMPLETED_FACTORY_SETUP.remove();
        return ref != null && modelSource != null && ref.get() == modelSource;
    }
}
