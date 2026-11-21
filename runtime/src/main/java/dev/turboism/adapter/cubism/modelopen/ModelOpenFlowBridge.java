package dev.turboism.adapter.cubism.modelopen;

import java.lang.ref.WeakReference;

/**
 * Coordinates safe per-thread handoff between {@code CModelSource$a.a(File, boolean)} and
 * {@code com/live2d/cubism/doc/a/b/b.b(File)} so an unmutated immediate duplicate {@code setup()}
 * call is elided while keeping {@code verify$default(true)} and {@code updateModel} intact.
 */
public final class ModelOpenFlowBridge {

    private static final ThreadLocal<WeakReference<Object>> COMPLETED_FACTORY_SETUP = new ThreadLocal<>();

    private ModelOpenFlowBridge() {}

    /**
     * Clears any prior setup token on the current thread before opening a model document.
     */
    public static void beginFactoryOpen() {
        COMPLETED_FACTORY_SETUP.remove();
    }

    /**
     * Records that factory deserialization finished invoking {@code CModelSource.setup()} cleanly on
     * {@code modelSource}.
     *
     * @param modelSource initialized {@code CModelSource} instance
     */
    public static void markFactorySetupCompleted(final Object modelSource) {
        if (modelSource != null) {
            COMPLETED_FACTORY_SETUP.set(new WeakReference<>(modelSource));
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
