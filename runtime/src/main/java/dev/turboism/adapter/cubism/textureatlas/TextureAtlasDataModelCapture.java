package dev.turboism.adapter.cubism.textureatlas;

import java.util.Optional;
import java.util.UUID;

/** Connection-owned capture of the active native texture-atlas editor data model. */
public final class TextureAtlasDataModelCapture implements AutoCloseable {

    private final String key = "dev.turboism.texture-atlas.data-model." + UUID.randomUUID();
    private volatile boolean closed;

    String key() {
        return key;
    }

    void capture(final Object dataModel) {
        if (!closed && dataModel != null) {
            System.getProperties().put(key, dataModel);
        }
    }

    /**
     * Reads the most recently captured data model.
     *
     * <p>The value lives in a system property under a per-instance random key, so it survives
     * across classloaders but is removed on {@link #close()}; a closed capture reports empty
     * permanently, even if a capture was recorded before closing.
     *
     * @return the captured native data-model object, or empty if nothing has been captured yet
     *         or this capture is closed
     */
    public Optional<Object> current() {
        return closed ? Optional.empty() : Optional.ofNullable(System.getProperties().get(key));
    }

    @Override
    public void close() {
        closed = true;
        System.getProperties().remove(key);
    }
}
