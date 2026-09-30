package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/**
 * Framework capability: contributes plugin-owned UI into the native texture-atlas
 * editor window (the editor view panel). Attached panels are appended to the
 * bottom of the editor panel on the exact host; the plugin owns the panel's
 * content and refresh policy (for example, a statistics panel fed by
 * {@link TextureAtlasEditorSession}).
 */
@CubismEditor({"5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"})
public interface TextureAtlasEditorUi {

    /**
     * Attaches a plugin-owned panel to the native texture-atlas editor window and
     * returns the panel handle. Safe to call before or after the editor window
     * opens: panels registered while the window is already visible are attached
     * immediately. The host renderer is supplied by the runtime adapter.
     */
    TextureAtlasEditorPanel attach();
}
