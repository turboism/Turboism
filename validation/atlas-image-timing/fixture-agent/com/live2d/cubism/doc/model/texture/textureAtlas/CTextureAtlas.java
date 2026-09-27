package com.live2d.cubism.doc.model.texture.textureAtlas;
import com.live2d.util.a.a;
/** Host-named stub: per-page generation entry points. */
public class CTextureAtlas {
    public int calls;
    public final void updateTexture(boolean force, boolean quality, a progress) {
        calls++;
        setupCacheImage$cubism(force, progress);
    }
    public final void setupCacheImage$cubism(boolean force, a progress) {
        calls++;
    }
}
