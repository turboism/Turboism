package com.live2d.cubism.appCtrlImpl;
import com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas;
/**
 * Host-named marker fixture for T029-STACK: puts a {@code com.live2d.cubism.appCtrlImpl.al.c}
 * frame on a sampled stack. A marker frame is raw evidence only — it does not prove this
 * frame initiated or scheduled the sampled call.
 */
public final class al {
    public final void c(CTextureAtlas atlas) {
        atlas.updateTexture(false, true, null);
    }
}
