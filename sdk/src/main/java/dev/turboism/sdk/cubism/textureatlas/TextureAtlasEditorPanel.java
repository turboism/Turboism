package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

/**
 * Plugin-owned panel handle contributed into the native texture-atlas editor
 * window through {@link TextureAtlasEditorUi#attach()}. The plugin updates the
 * panel content through this semantic surface; the host renderer and its
 * concrete widget type are owned by the runtime adapter.
 */
@PreviewApi
public interface TextureAtlasEditorPanel {

    /** Replaces the panel's displayed text. */
    void setText(String text);
}
