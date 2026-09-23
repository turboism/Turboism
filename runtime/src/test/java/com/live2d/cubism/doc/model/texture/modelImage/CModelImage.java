package com.live2d.cubism.doc.model.texture.modelImage;

import com.live2d.graphics.CImageResource;
import com.live2d.type.CAffine;
import com.live2d.type.CModelImageGuid;
import java.util.function.Supplier;

/**
 * Test-only stand-in for the host's
 * {@code com.live2d.cubism.doc.model.texture.modelImage.CModelImage}.
 *
 * <p>Mirrors the host contract the cache-reuse guard relies on: {@code reset()} is the single
 * invalidation point — it drops the filtered image and increments {@code modelImageVersion};
 * {@code getFilteredImage()} lazily rebuilds through the supplied factory.</p>
 */
public class CModelImage {
    private final CModelImageGuid guid = new CModelImageGuid();
    private final Supplier<CImageResource> filteredFactory;
    private CImageResource filteredImage;
    private CAffine modelImageLocalToCanvasTransform = new CAffine();
    private int modelImageVersion;
    private int filteredBuilds;

    public CModelImage(final Supplier<CImageResource> filteredFactory) {
        this.filteredFactory = filteredFactory;
    }

    public final CModelImageGuid getGuid() {
        return guid;
    }

    public final int getModelImageVersion() {
        return modelImageVersion;
    }

    public final CImageResource getFilteredImage() {
        if (filteredImage == null) {
            filteredImage = filteredFactory.get();
            filteredBuilds++;
        }
        return filteredImage;
    }

    /** Test hook: install a filtered image without a version bump (initial content). */
    public final void seedFilteredImage(final CImageResource image) {
        this.filteredImage = image;
    }

    /** Mirrors the host's sole invalidation point. */
    public final void reset() {
        filteredImage = null;
        modelImageVersion++;
    }

    public final int filteredBuilds() {
        return filteredBuilds;
    }

    /** The model image's canvas transform; feeds the entry's atlas transform calc. */
    public final CAffine getModelImageLocalToCanvasTransform() {
        return modelImageLocalToCanvasTransform;
    }

    /** Test hook: move the model image (a layout edit). */
    public final void setModelImageLocalToCanvasTransform(final CAffine transform) {
        this.modelImageLocalToCanvasTransform = transform;
    }
}
