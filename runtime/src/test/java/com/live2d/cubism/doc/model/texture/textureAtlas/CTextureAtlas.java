package com.live2d.cubism.doc.model.texture.textureAtlas;

import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.texture.modelImage.CModelImage;
import com.live2d.graphics.CImageResource;
import com.live2d.graphics.CWritableImage;
import com.live2d.graphics.cachedImage.CCachedImageManager;
import com.live2d.type.CAffine;
import com.live2d.type.CModelImageGuid;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only fixture replica of the host's
 * {@code com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas}.
 *
 * <p>{@link #updateTexture} reproduces the reviewed host body instruction-for-instruction so
 * the {@code AtlasCacheReusePatcher} anchor gate applies; {@code setupCacheImage$cubism}
 * performs a real per-entry transformed composite so a skipped rebuild can be verified
 * pixel-identical, not just call-counted.</p>
 */
public class CTextureAtlas {
    private int width;
    private int height;
    private boolean isDirty_cachedAtlasImage = true;
    private int atlasVersion;
    private CImageResource cachedAtlasImage;
    private final CCachedImageManager cachedImageManager = new CCachedImageManager();
    private final List<ModelImageEntry> modelImages = new ArrayList<>();
    private final CModelSource modelSource;
    private final AtomicInteger setupCalls = new AtomicInteger();

    public CTextureAtlas(final CModelSource modelSource, final int width, final int height) {
        this.modelSource = modelSource;
        this.width = width;
        this.height = height;
    }

    /** Reproduces the reviewed host body exactly; the patcher rewrites this method. */
    public final void updateTexture(final boolean rebuild, final boolean privatePath,
                                    final com.live2d.util.a.a progress) {
        if (rebuild) {
            setupCacheImage$cubism(privatePath, progress);
        }
        cachedImageManager.a(cachedAtlasImage);
    }

    /** Functional stand-in: transformed per-entry composite into a fresh page image. */
    public final void setupCacheImage$cubism(final boolean privatePath,
                                             final com.live2d.util.a.a progress) {
        setupCalls.incrementAndGet();
        final BufferedImage page =
            new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = page.createGraphics();
        try {
            for (final ModelImageEntry entry : modelImages) {
                final CModelImage image = entry.getModelImage();
                if (image == null) continue;
                g.setTransform(entry.calcModelImageLocalToAtlasTransform());
                g.drawImage(image.getFilteredImage().getImage().getJBufferedImage(),
                    0, 0, null);
            }
        } finally {
            g.dispose();
        }
        cachedAtlasImage = new CImageResource(new CWritableImage(page));
        isDirty_cachedAtlasImage = false;
        atlasVersion++;
    }

    public final int getWidth() {
        return width;
    }

    public final int getHeight() {
        return height;
    }

    public final List<ModelImageEntry> getModelImages() {
        return modelImages;
    }

    public final CModelSource getModelSource() {
        return modelSource;
    }

    public final boolean isDirty_cachedAtlasImage() {
        return isDirty_cachedAtlasImage;
    }

    public final int getAtlasVersion() {
        return atlasVersion;
    }

    public final CImageResource getCachedAtlasImage() {
        return cachedAtlasImage;
    }

    public final void setDirty_cachedAtlasImage(final boolean dirty) {
        this.isDirty_cachedAtlasImage = dirty;
    }

    public final void setCachedAtlasImage(final CImageResource image) {
        this.cachedAtlasImage = image;
    }

    public final CCachedImageManager getCachedImageManager() {
        return cachedImageManager;
    }

    /** Test hook: how many times the page was actually recomposited. */
    public final int setupCalls() {
        return setupCalls.get();
    }

    /** Test hook: attach a tile at a fixed atlas transform. */
    public final ModelImageEntry addEntry(final CModelImageGuid guid,
                                          final CAffine atlasLocalToCanvas) {
        final ModelImageEntry entry = new ModelImageEntry(this, guid, atlasLocalToCanvas);
        modelImages.add(entry);
        return entry;
    }

    /** Mirror of the host's inner entry: guid + transforms, resolves through the atlas. */
    public final class ModelImageEntry {
        private final CTextureAtlas atlas;
        private final CModelImageGuid modelImageGuid;
        private final CAffine atlasLocalToCanvasTransform;

        public ModelImageEntry(final CTextureAtlas atlas, final CModelImageGuid guid,
                        final CAffine atlasLocalToCanvas) {
            this.atlas = atlas;
            this.modelImageGuid = guid;
            this.atlasLocalToCanvasTransform = atlasLocalToCanvas;
        }

        public final CTextureAtlas getAtlas() {
            return atlas;
        }

        public final CModelImageGuid getModelImageGuid() {
            return modelImageGuid;
        }

        public final CModelImage getModelImage() {
            return atlas.getModelSource().getTextureManager().getModelImage(modelImageGuid);
        }

        /**
         * Mirrors the host: atlasLocalToCanvas⁻¹ concatenated with the model image's
         * canvas transform — a pure computation over the two stored transforms.
         */
        public final CAffine calcModelImageLocalToAtlasTransform() {
            final CAffine inverse = new CAffine(atlasLocalToCanvasTransform);
            try {
                inverse.invert();
            } catch (java.awt.geom.NoninvertibleTransformException degenerate) {
                return new CAffine();
            }
            final CModelImage image = getModelImage();
            if (image != null && image.getModelImageLocalToCanvasTransform() != null) {
                inverse.concatenate(image.getModelImageLocalToCanvasTransform());
            }
            return inverse;
        }
    }
}
