package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;
import java.util.Optional;

/**
 * Framework capability: reads the active native texture-atlas editor session.
 *
 * <p>Returns the whole-atlas summary and the currently selected texture summary
 * (model-image count and size distribution). The host view is attached by the
 * runtime; this read-only session never mutates authoring state.</p>
 */
@CubismEditor({"5.3.02", "5.3.03"})
public interface TextureAtlasEditorSession {

    /** Summary of the whole active texture atlas (all pages). */
    Optional<TextureAtlasSummary> summary();

    /** Summary of the texture currently selected in the editor list, if any. */
    Optional<TextureAtlasSummary> selectedTexture();

    /**
     * Reports whether a live runtime backend backs this session.
     *
     * @return {@code false} when the backend backing this instance is
     *         unavailable, including the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static TextureAtlasEditorSession unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: every read reports empty. */
    enum Unavailable implements TextureAtlasEditorSession {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<TextureAtlasSummary> summary() {
            return Optional.empty();
        }

        @Override
        public Optional<TextureAtlasSummary> selectedTexture() {
            return Optional.empty();
        }
    }
}
