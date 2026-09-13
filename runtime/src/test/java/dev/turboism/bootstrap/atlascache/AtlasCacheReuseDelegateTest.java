package dev.turboism.bootstrap.atlascache;

import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.texture.modelImage.CModelImage;
import com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas;
import com.live2d.graphics.CImageResource;
import com.live2d.graphics.CWritableImage;
import com.live2d.type.CAffine;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signature-guard tests for {@link AtlasCacheReuseDelegate}.
 *
 * <p>The fixture {@code CTextureAtlas} reproduces the reviewed host body and a functional
 * composite, so these tests exercise the exact reflection paths the patched
 * {@code updateTexture} uses on a real host: a recorded signature may only ever reuse the
 * cache object it was built for, and only while every draw input is unchanged.</p>
 */
final class AtlasCacheReuseDelegateTest {

    private CModelSource source;
    private CTextureAtlas atlas;
    private CModelImage image;

    @BeforeEach
    void setUp() {
        AtlasCacheReuseDelegate.clearRecords();
        source = new CModelSource();
        atlas = new CTextureAtlas(source, 64, 64);
        image = new CModelImage(() -> new CImageResource(
            new CWritableImage(tile(0xFF204060))));
        source.getTextureManager().put(image);
        atlas.addEntry(image.getGuid(), new CAffine(AffineTransform.getTranslateInstance(4, 4)));
    }

    private static BufferedImage tile(final int argb) {
        final BufferedImage t = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) t.setRGB(x, y, argb);
        return t;
    }

    /** Simulates the patched body: build, then record the signature of the fresh cache. */
    private void buildAndRecord() {
        atlas.setupCacheImage$cubism(true, null);
        AtlasCacheReuseDelegate.rebuilt(CTextureAtlas.class, atlas, true);
    }

    @Test
    void declinesWhenNothingWasRecorded() {
        atlas.setupCacheImage$cubism(true, null);
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true));
    }

    @Test
    void reusesAfterARecordedBuild() {
        buildAndRecord();
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true));
    }

    @Test
    void rebuildsWhenFilteredContentIsInvalidated() {
        buildAndRecord();
        image.reset();
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
            "reset() bumps the version; the guard must rebuild");
    }

    @Test
    void rebuildsWhenFilteredPixelsChangeWithoutAVersionBump() {
        buildAndRecord();
        image.getFilteredImage().getImage().getJBufferedImage().setRGB(0, 0, 0xFFFFFFFF);
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
            "in-place pixel mutation must be caught by the pixel digest");
    }

    @Test
    void rebuildsWhenAnEntryMoves() {
        buildAndRecord();
        image.setModelImageLocalToCanvasTransform(
            new CAffine(AffineTransform.getTranslateInstance(1, 0)));
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true));
    }

    @Test
    void rebuildsWhenEntriesAreAddedRemovedOrReordered() {
        final CModelImage second = new CModelImage(() -> new CImageResource(
            new CWritableImage(tile(0xFF806040))));
        source.getTextureManager().put(second);

        buildAndRecord();
        atlas.addEntry(second.getGuid(),
            new CAffine(AffineTransform.getTranslateInstance(20, 20)));
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
            "an added entry changes the ordered list");

        buildAndRecord();
        atlas.getModelImages().remove(1);
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
            "a removed entry changes the ordered list");

        atlas.addEntry(second.getGuid(),
            new CAffine(AffineTransform.getTranslateInstance(20, 20)));
        buildAndRecord();
        atlas.getModelImages().add(0, atlas.getModelImages().remove(1));
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
            "reordering changes the draw order and must rebuild");
    }

    @Test
    void rebuildsWhenTheInstalledCacheObjectWasSwapped() {
        buildAndRecord();
        atlas.setCachedAtlasImage(new CImageResource(
            new CWritableImage(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))));
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
            "a cache object the signature was not built for cannot be reused");
    }

    @Test
    void rebuildsWhenThePrivatePathFlagDiffers() {
        buildAndRecord();
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, false));
    }

    @Test
    void rebuildsWhenAnEntryStopsResolving() {
        buildAndRecord();
        atlas.getModelImages().clear();
        atlas.addEntry(new com.live2d.type.CModelImageGuid(), new CAffine());
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true));
    }

    @Test
    void declinesWhenNoCacheIsInstalled() {
        AtlasCacheReuseDelegate.rebuilt(CTextureAtlas.class, atlas, true);
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
            "no cache → nothing to reuse");
    }

    @Test
    void reuseKeepsTheExactInstalledCacheObject() {
        buildAndRecord();
        final Object built = atlas.getCachedAtlasImage();
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true));
        assertTrue(atlas.getCachedAtlasImage() == built,
            "reuse must leave the recorded cache object installed");
    }

    /** Mirrors {@code CTextureAtlas.reinit}: a fresh instance carrying a pixel copy. */
    private CTextureAtlas copyWithCache(final BufferedImage pixels) {
        final CTextureAtlas copy = new CTextureAtlas(source, 64, 64);
        copy.addEntry(image.getGuid(),
            new CAffine(AffineTransform.getTranslateInstance(4, 4)));
        copy.setCachedAtlasImage(new CImageResource(new CWritableImage(pixels)));
        return copy;
    }

    private static BufferedImage copyOf(final BufferedImage src) {
        final BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
            BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    @Test
    void reusesAcrossInstancesWhenTheCopyCarriesTheBuiltPixels() {
        buildAndRecord();
        final CTextureAtlas copy = copyWithCache(copyOf(
            atlas.getCachedAtlasImage().getImage().getJBufferedImage()));
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
            "a deep copy whose cache already holds the recorded output needs no rebuild");
    }

    @Test
    void suppliesTheRecordedOutputWhenACopyCachePixelsDiffer() {
        buildAndRecord();
        final CTextureAtlas copy = copyWithCache(
            new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
            "same signature but stale cache → the recorded output is still correct");
        assertEquals(pixelHash(atlas.getCachedAtlasImage().getImage()
                .getJBufferedImage()),
            pixelHash(copy.getCachedAtlasImage().getImage().getJBufferedImage()),
            "the supplied copy must carry the recorded output's exact pixels");
        assertTrue(copy.getCachedAtlasImage() != atlas.getCachedAtlasImage(),
            "the supplied resource must be a fresh instance, never an alias");
    }

    @Test
    void suppliesTheRecordedOutputToACopyWithNoCache() {
        buildAndRecord();
        final CTextureAtlas copy = new CTextureAtlas(source, 64, 64);
        copy.addEntry(image.getGuid(),
            new CAffine(AffineTransform.getTranslateInstance(4, 4)));
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
            "a fresh copy with no cache takes the retained output instead of rebuilding");
        assertEquals(pixelHash(atlas.getCachedAtlasImage().getImage()
                .getJBufferedImage()),
            pixelHash(copy.getCachedAtlasImage().getImage().getJBufferedImage()));
    }

    @Test
    void aSuppliedInstanceThenReusesNormally() {
        buildAndRecord();
        final CTextureAtlas copy = new CTextureAtlas(source, 64, 64);
        copy.addEntry(image.getGuid(),
            new CAffine(AffineTransform.getTranslateInstance(4, 4)));
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true));
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
            "the supplied instance now has a same-instance record");
    }

    private static String pixelHash(final BufferedImage image) {
        final StringBuilder hash = new StringBuilder();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash.append(Integer.toHexString(image.getRGB(x, y)));
            }
        }
        return hash.toString();
    }

    @Test
    void declinesAnUnknownInstanceWhenNoOutputWasRecorded() {
        atlas.setupCacheImage$cubism(true, null);   // built out-of-band, never recorded
        final CTextureAtlas copy = copyWithCache(copyOf(
            atlas.getCachedAtlasImage().getImage().getJBufferedImage()));
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
            "no recorded build means the content claim cannot be verified");
    }

    @Test
    void verifyHostAccessResolvesAgainstTheFixture() {
        assertNull(AtlasCacheReuseDelegate.verifyHostAccess(
            AtlasCacheReuseDelegateTest.class.getClassLoader()));
    }
}
