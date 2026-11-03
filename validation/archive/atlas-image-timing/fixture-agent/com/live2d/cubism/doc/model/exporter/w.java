package com.live2d.cubism.doc.model.exporter;
import com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas;
/**
 * Host-named marker fixture for T029-STACK: puts a {@code com.live2d.cubism.doc.model.exporter.w.a}
 * frame on a sampled stack, driven from a non-EDT thread. A marker frame is raw evidence
 * only — it does not prove this frame initiated or scheduled the sampled call.
 */
public final class w {
    public final void a(CTextureAtlas atlas) {
        atlas.setupCacheImage$cubism(true, null);
    }
}
