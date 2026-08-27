package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/**
 * Plugin-owned panel handle contributed into the native texture-atlas editor
 * window through {@link TextureAtlasEditorUi#attach()}. The plugin updates the
 * panel content through this semantic surface; the host renderer and its
 * concrete widget type are owned by the runtime adapter.
 */
@CubismEditor({"5.3.02", "5.3.03"})
public interface TextureAtlasEditorPanel extends AutoCloseable {

    /** Replaces the panel's displayed text. */
    void setText(String text);

    /** Detaches this panel and releases its runtime registration. Idempotent. */
    @Override
    void close();
}
