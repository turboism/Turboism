package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/**
 * Plugin-owned panel handle contributed into the native texture-atlas editor
 * window through {@link TextureAtlasEditorUi#attach()}. The plugin updates the
 * panel content through this semantic surface; the host renderer and its
 * concrete widget type are owned by the runtime adapter.
 */
@CubismEditor({"5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"})
public interface TextureAtlasEditorPanel extends AutoCloseable {

    /** Replaces the panel's displayed text. */
    void setText(String text);

    /** Detaches this panel and releases its runtime registration. Idempotent. */
    @Override
    void close();
}
