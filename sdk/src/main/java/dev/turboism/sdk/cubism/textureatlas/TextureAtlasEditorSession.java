package dev.turboism.sdk.cubism.textureatlas;


import java.util.Optional;

/**
 * Framework capability: reads the active native texture-atlas editor session.
 *
 * <p>Returns the whole-atlas summary and the currently selected texture summary
 * (model-image count and size distribution). The host view is attached by the
 * runtime; this read-only session never mutates authoring state.</p>
 */
public interface TextureAtlasEditorSession {

    /** Summary of the whole active texture atlas (all pages). */
    Optional<TextureAtlasSummary> summary();

    /** Summary of the texture currently selected in the editor list, if any. */
    Optional<TextureAtlasSummary> selectedTexture();
}
