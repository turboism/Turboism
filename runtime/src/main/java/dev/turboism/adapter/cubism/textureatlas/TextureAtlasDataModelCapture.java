package dev.turboism.adapter.cubism.textureatlas;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Connection-owned capture of the active native texture-atlas editor data model. */
public final class TextureAtlasDataModelCapture implements AutoCloseable {

    private final AtomicReference<Object> current = new AtomicReference<>();
    private volatile boolean closed;

    void capture(final Object dataModel) {
        if (!closed && dataModel != null) {
            current.set(dataModel);
        }
    }

    public Optional<Object> current() {
        return closed ? Optional.empty() : Optional.ofNullable(current.get());
    }

    @Override
    public void close() {
        closed = true;
        current.set(null);
    }
}
