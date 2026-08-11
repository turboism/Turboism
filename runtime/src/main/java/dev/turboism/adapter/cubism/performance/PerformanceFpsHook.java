package dev.turboism.adapter.cubism.performance;

/**
 * Handle for the preview-agent FPS counting hook. The agent publishes one
 * instance; the runtime mounts it while performance sampling is active and
 * closes it when sampling stops. {@link #close()} restores the instrumented
 * host bytecode and verifies the restoration; a failed restoration fails
 * closed with an exception.
 */
public interface PerformanceFpsHook {

    /**
     * Mounts the hook: registers the renderScene transformer, installs the
     * carrier callback, and enables RENDER_SCENE counting. Idempotent; fails
     * closed when the carrier is already owned by another probe.
     */
    void install();

    /** True while the renderScene transformer and carrier callback are mounted. */
    boolean isInstalled();

    /**
     * Cumulative renderScene call count since the hook was installed. FPS is
     * derived by the caller as {@code deltaCalls / elapsedSeconds} over a
     * sampling window.
     */
    long renderSceneCalls();

    /**
     * Unmounts the hook: removes the transformer, retransforms every
     * instrumented host class back to its original bytes, and verifies the
     * restored bytes (after == before). Idempotent.
     *
     * @throws IllegalStateException when bytecode restoration fails or the
     *                               restored bytes do not match the originals
     */
    void close();
}
