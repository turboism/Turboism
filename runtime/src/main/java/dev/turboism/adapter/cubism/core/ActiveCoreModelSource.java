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

    /** Returns the current model generation without taking a lease. */
    long currentGeneration();

    @Override
    void close();
}
