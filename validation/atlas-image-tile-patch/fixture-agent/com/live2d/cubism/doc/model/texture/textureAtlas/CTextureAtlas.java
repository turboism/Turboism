package com.live2d.cubism.doc.model.texture.textureAtlas;
import java.awt.image.BufferedImage;
import com.live2d.graphics.CImageResource;
import com.live2d.graphics.CWritableImage;
/** Fixture replica of CTextureAtlas — only what the hash hook needs. */
public class CTextureAtlas {
    private CImageResource cachedAtlasImage;
    public CImageResource getCachedAtlasImage() { return cachedAtlasImage; }
    /** Mirrors the real signature: setupCacheImage$cubism(ZLcom/live2d/util/a/a;)V */
    public void setupCacheImage$cubism(boolean flag, com.live2d.util.a.a op) {
        BufferedImage bi = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(3, 3, 0xFF112233);
        cachedAtlasImage = new CImageResource(new CWritableImage(bi));
    }
}
