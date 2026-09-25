package dev.turboism.adapter.cubism.core;

/**
 * Adapter-internal source of short-lived borrowed references to the Editor's active Core model.
 *
 * <p>Implementations never own or close the underlying Core model. Acquisition returns only a
 * scoped lease; no raw model reference crosses this package boundary.</p>
 */
interface ActiveCoreModelSource extends AutoCloseable {

    CoreModelAcquisition acquire(CorePublicApiProvider provider);

    /**
     * Best-effort publication of a borrowed model resolved lazily at first evaluated access.
     * Returns true when the model is now active; the default never publishes and rejects.
     * Sources that can accept a borrowed model override this (see {@link BorrowedCoreModelSource}).
     */
    default boolean tryPublishBorrowedModel(Object model, String identity) {
        return false;
    }

    /**
     * Requests a non-blocking release of the borrowed model: it is forgotten as soon as no lease
     * is outstanding; a later publication cancels the pending release. The default does nothing;
     * sources holding a borrowed model override this (see {@link BorrowedCoreModelSource}).
     */
    default void releaseWhenIdle() {
    }

    /**
     * Returns the currently published borrowed model reference, or {@code null} when none is
     * held. Used for identity comparison by the publishing side; never exposes the model to
     * callers outside this package's collaborators.
     */
    default Object publishedModel() {
        return null;
    }

    /**
     * Registers a listener notified after the active model reference becomes null (idle release,
     * explicit clear, or close). The default ignores it; sources that can clear a borrowed model
     * override this. Listeners run outside the source monitor.
     */
    default void onModelCleared(Runnable listener) {
    }

    /** Returns the current model generation without taking a lease. */
    long currentGeneration();

    @Override
    void close();
}
