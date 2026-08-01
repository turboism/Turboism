package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

import javax.swing.JComponent;

/**
 * Framework capability: contributes plugin-owned UI into the native texture-atlas
 * editor window (the editor view panel). Attached components are appended to the
 * bottom of the editor panel on the exact host; the plugin owns the component's
 * content and refresh policy (for example, a statistics panel fed by
 * {@link TextureAtlasEditorSession}).
 */
@PreviewApi
public interface TextureAtlasEditorUi {

    /**
     * Attaches a plugin-owned panel to the native texture-atlas editor window.
     * Safe to call before or after the editor window opens: components registered
     * while the window is already visible are attached immediately.
     */
    void attach(JComponent panel);
}
