package dev.turboism.adapter.cubism.core;

/**
 * Adapter-internal source of short-lived borrowed references to the Editor's active Core model.
 *
 * <p>Implementations never own or close the underlying Core model. Acquisition returns only a
 * scoped lease; no raw model reference crosses this package boundary.</p>
 */
interface ActiveCoreModelSource extends AutoCloseable {

    CoreModelAcquisition acquire(CorePublicApiProvider provider);

    @Override
    void close();
}
