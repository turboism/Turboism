package com.live2d.cubism.doc.model.texture;

import com.live2d.cubism.doc.model.texture.modelImage.CModelImage;
import com.live2d.type.CModelImageGuid;
import java.util.HashMap;
import java.util.Map;

/** Test-only stand-in for the host's {@code com.live2d.cubism.doc.model.texture.CTextureManager}. */
public class CTextureManager {
    private final Map<CModelImageGuid, CModelImage> images = new HashMap<>();

    public final void put(final CModelImage image) {
        images.put(image.getGuid(), image);
    }

    public final CModelImage getModelImage(final CModelImageGuid guid) {
        return images.get(guid);
    }
}
