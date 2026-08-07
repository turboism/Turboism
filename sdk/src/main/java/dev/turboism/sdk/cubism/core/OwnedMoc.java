package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

/**
 * Plugin-owned Cubism Core MOC handle built from {@code .moc3} bytes.
 *
 * <p>Exposes the byte-level diagnostics that the loader already computed, the Core
 * native handle identity, and model instantiation. Closing the MOC releases the Core
 * {@code CubismMoc} instance; every model instantiated from it is closed first.</p>
 */
@PreviewApi
public interface OwnedMoc extends AutoCloseable {

    /** MOC format version diagnosed from the loaded bytes. */
    MocVersion version();

    /** MOC byte consistency diagnosed from the loaded bytes. */
    MocConsistency consistency();

    /** Core native handle of the owned {@code CubismMoc} instance. */
    long nativeHandle();

    /**
     * Instantiates one evaluated model from this MOC.
     *
     * <p>Each call creates a new Core {@code CubismModel}; the returned model must be
     * closed independently. Reads are immutable adapter-owned copies of the evaluated
     * surface.</p>
     *
     * @throws UnsupportedOperationException when model instantiation is not admitted
     */
    OwnedModel instantiateModel();

    /**
     * Releases the owned Core MOC instance.
     *
     * <p>Any model created from this MOC must be closed before the MOC is closed;
     * the runtime closes them first when it owns them.</p>
     */
    @Override
    void close();
}
