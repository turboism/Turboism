package com.live2d.cubism.appCtrlImpl;
import com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas;
/**
 * Host-named marker fixture for T029-STACK: puts a {@code com.live2d.cubism.appCtrlImpl.ap.a}
 * frame on a sampled stack. A marker frame is raw evidence only — it does not prove this
 * frame initiated or scheduled the sampled call.
 */
public final class ap {
    public final void a(CTextureAtlas atlas) {
        atlas.updateTexture(true, true, null);
    }
}
