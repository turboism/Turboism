package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/**
 * Framework capability: contributes plugin-owned UI into the native texture-atlas
 * editor window (the editor view panel). Attached panels are appended to the
 * bottom of the editor panel on the exact host; the plugin owns the panel's
 * content and refresh policy (for example, a statistics panel fed by
 * {@link TextureAtlasEditorSession}).
 */
@CubismEditor({"5.3.02", "5.3.03"})
public interface TextureAtlasEditorUi {

    /**
     * Attaches a plugin-owned panel to the native texture-atlas editor window and
     * returns the panel handle. Safe to call before or after the editor window
     * opens: panels registered while the window is already visible are attached
     * immediately. The host renderer is supplied by the runtime adapter.
     */
    TextureAtlasEditorPanel attach();

    /**
     * Reports whether a live runtime backend backs this service.
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
    static TextureAtlasEditorUi unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Sentinel returned by {@link #unavailable()}: {@link #attach()} throws a stable
     * {@link UnsupportedOperationException}, matching the other unavailable contribution
     * surfaces; probe with {@link #isAvailable()} first.
     */
    enum Unavailable implements TextureAtlasEditorUi {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public TextureAtlasEditorPanel attach() {
            throw new UnsupportedOperationException("texture atlas editor UI contribution is unavailable");
        }
    }
}
