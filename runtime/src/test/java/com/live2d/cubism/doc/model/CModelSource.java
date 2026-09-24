package com.live2d.cubism.doc.model;

import com.live2d.cubism.doc.model.texture.CTextureManager;

/** Test-only stand-in for the host's {@code com.live2d.cubism.doc.model.CModelSource}. */
public class CModelSource {
    private final CTextureManager textureManager = new CTextureManager();

    public final CTextureManager getTextureManager() {
        return textureManager;
    }
}
