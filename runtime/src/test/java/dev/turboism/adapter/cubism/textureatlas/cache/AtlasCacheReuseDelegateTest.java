package dev.turboism.adapter.cubism.textureatlas.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.live2d.cubism.doc.model.CModelSource;
import com.live2d.cubism.doc.model.texture.modelImage.CModelImage;
import com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas;
import com.live2d.graphics.CImageResource;
import com.live2d.graphics.CWritableImage;
import com.live2d.type.CAffine;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.security.MessageDigest;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        image = new CModelImage(() -> new CImageResource(new CWritableImage(tile(0xFF204060))));
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
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
                "reset() bumps the version; the guard must rebuild");
    }

    @Test
    void rebuildsWhenFilteredPixelsChangeWithoutAVersionBump() {
        buildAndRecord();
        image.getFilteredImage().getImage().getJBufferedImage().setRGB(0, 0, 0xFFFFFFFF);
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
                "in-place pixel mutation must be caught by the pixel digest");
    }

    @Test
    void rebuildsWhenAnEntryMoves() {
        buildAndRecord();
        image.setModelImageLocalToCanvasTransform(new CAffine(AffineTransform.getTranslateInstance(1, 0)));
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true));
    }

    @Test
    void rebuildsWhenEntriesAreAddedRemovedOrReordered() {
        final CModelImage second = new CModelImage(() -> new CImageResource(new CWritableImage(tile(0xFF806040))));
        source.getTextureManager().put(second);

        buildAndRecord();
        atlas.addEntry(second.getGuid(), new CAffine(AffineTransform.getTranslateInstance(20, 20)));
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
                "an added entry changes the ordered list");

        buildAndRecord();
        atlas.getModelImages().remove(1);
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
                "a removed entry changes the ordered list");

        atlas.addEntry(second.getGuid(), new CAffine(AffineTransform.getTranslateInstance(20, 20)));
        buildAndRecord();
        atlas.getModelImages().add(0, atlas.getModelImages().remove(1));
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
                "reordering changes the draw order and must rebuild");
    }

    @Test
    void rebuildsWhenTheInstalledCacheObjectWasSwapped() {
        buildAndRecord();
        atlas.setCachedAtlasImage(
                new CImageResource(new CWritableImage(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB))));
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true),
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
        assertFalse(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true), "no cache → nothing to reuse");
    }

    @Test
    void reuseKeepsTheExactInstalledCacheObject() {
        buildAndRecord();
        final Object built = atlas.getCachedAtlasImage();
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, atlas, true));
        assertTrue(atlas.getCachedAtlasImage() == built, "reuse must leave the recorded cache object installed");
    }

    /** Mirrors {@code CTextureAtlas.reinit}: a fresh instance carrying a pixel copy. */
    private CTextureAtlas copyWithCache(final BufferedImage pixels) {
        final CTextureAtlas copy = new CTextureAtlas(source, 64, 64);
        copy.addEntry(image.getGuid(), new CAffine(AffineTransform.getTranslateInstance(4, 4)));
        copy.setCachedAtlasImage(new CImageResource(new CWritableImage(pixels)));
        return copy;
    }

    private static BufferedImage copyOf(final BufferedImage src) {
        final BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    @Test
    void reusesAcrossInstancesWhenTheCopyCarriesTheBuiltPixels() {
        buildAndRecord();
        final CTextureAtlas copy =
                copyWithCache(copyOf(atlas.getCachedAtlasImage().getImage().getJBufferedImage()));
        assertTrue(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
                "a deep copy whose cache already holds the recorded output needs no rebuild");
    }

    @Test
    void suppliesTheRecordedOutputWhenACopyCachePixelsDiffer() {
        buildAndRecord();
        final CTextureAtlas copy = copyWithCache(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        assertTrue(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
                "same signature but stale cache → the recorded output is still correct");
        assertEquals(
                pixelHash(atlas.getCachedAtlasImage().getImage().getJBufferedImage()),
                pixelHash(copy.getCachedAtlasImage().getImage().getJBufferedImage()),
                "the supplied copy must carry the recorded output's exact pixels");
        assertTrue(
                copy.getCachedAtlasImage() != atlas.getCachedAtlasImage(),
                "the supplied resource must be a fresh instance, never an alias");
    }

    @Test
    void suppliesTheRecordedOutputToACopyWithNoCache() {
        buildAndRecord();
        final CTextureAtlas copy = new CTextureAtlas(source, 64, 64);
        copy.addEntry(image.getGuid(), new CAffine(AffineTransform.getTranslateInstance(4, 4)));
        assertTrue(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
                "a fresh copy with no cache takes the retained output instead of rebuilding");
        assertEquals(
                pixelHash(atlas.getCachedAtlasImage().getImage().getJBufferedImage()),
                pixelHash(copy.getCachedAtlasImage().getImage().getJBufferedImage()));
    }

    @Test
    void aSuppliedInstanceThenReusesNormally() {
        buildAndRecord();
        final CTextureAtlas copy = new CTextureAtlas(source, 64, 64);
        copy.addEntry(image.getGuid(), new CAffine(AffineTransform.getTranslateInstance(4, 4)));
        assertTrue(AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true));
        assertTrue(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
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
        atlas.setupCacheImage$cubism(true, null); // built out-of-band, never recorded
        final CTextureAtlas copy =
                copyWithCache(copyOf(atlas.getCachedAtlasImage().getImage().getJBufferedImage()));
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, copy, true),
                "no recorded build means the content claim cannot be verified");
    }

    @Test
    void verifyHostAccessResolvesAgainstTheFixture() {
        assertNull(AtlasCacheReuseDelegate.verifyHostAccess(AtlasCacheReuseDelegateTest.class.getClassLoader()));
    }

    /** A single-entry 2×2 atlas drawing {@code img} at the identity transform. */
    private CTextureAtlas atlasOver(final CModelImage img) {
        final CTextureAtlas a = new CTextureAtlas(source, 2, 2);
        a.addEntry(img.getGuid(), new CAffine());
        return a;
    }

    private CModelImage imageOf(final BufferedImage pixels) {
        final CModelImage img = new CModelImage(() -> new CImageResource(new CWritableImage(pixels)));
        source.getTextureManager().put(img);
        return img;
    }

    @Test
    void anImageOverTheDigestBudgetIsNeverRecordedOrReused() {
        // 8193×8193 > 64 Mi pixels; TYPE_BYTE_BINARY keeps the real image near 8 MiB.
        final CModelImage big = imageOf(new BufferedImage(8193, 8193, BufferedImage.TYPE_BYTE_BINARY));
        final CTextureAtlas bigAtlas = atlasOver(big);

        bigAtlas.setupCacheImage$cubism(true, null);
        AtlasCacheReuseDelegate.rebuilt(CTextureAtlas.class, bigAtlas, true);

        assertEquals(0, AtlasCacheReuseDelegate.recordCount(), "an uncomputable signature must not be recorded");
        assertEquals(
                0, AtlasCacheReuseDelegate.outputCount(), "an uncomputable signature must not key a retained output");
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, bigAtlas, true),
                "content equality is unproven over budget, so the guard must rebuild");
    }

    @Test
    void anOversizedInPlaceEditFallsBackToAStockRebuild() {
        final BufferedImage pixels = new BufferedImage(8193, 8193, BufferedImage.TYPE_BYTE_BINARY);
        final CTextureAtlas bigAtlas = atlasOver(imageOf(pixels));

        bigAtlas.setupCacheImage$cubism(true, null);
        AtlasCacheReuseDelegate.rebuilt(CTextureAtlas.class, bigAtlas, true);
        final int stalePixel =
                bigAtlas.getCachedAtlasImage().getImage().getJBufferedImage().getRGB(0, 0);

        pixels.setRGB(0, 0, 0xFFFFFFFF); // in-place edit, no version bump
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, bigAtlas, true),
                "the guard must not claim reuse for pixels it never hashed");

        bigAtlas.setupCacheImage$cubism(true, null); // the stock path being declined to
        assertEquals(
                0xFFFFFFFF,
                bigAtlas.getCachedAtlasImage().getImage().getJBufferedImage().getRGB(0, 0),
                "a real rebuild picks up the edited pixel");
        assertNotEquals(
                stalePixel, 0xFFFFFFFF, "the recorded cache carried the pre-edit pixels, so reuse would be wrong");
    }

    @Test
    void anImageExactlyAtTheDigestBudgetStillReusesAndStillVerifies() {
        // 8192×8192 == 64 Mi pixels == the hashing budget boundary.
        final BufferedImage pixels = new BufferedImage(8192, 8192, BufferedImage.TYPE_BYTE_BINARY);
        final CTextureAtlas bigAtlas = atlasOver(imageOf(pixels));

        bigAtlas.setupCacheImage$cubism(true, null);
        AtlasCacheReuseDelegate.rebuilt(CTextureAtlas.class, bigAtlas, true);

        assertTrue(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, bigAtlas, true),
                "in-budget unchanged input must keep reusing");

        pixels.setRGB(0, 0, 0xFFFFFFFF);
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, bigAtlas, true),
                "the pixel digest must still catch an in-place edit at the boundary");
    }

    @Test
    void anUnreadableFilteredImageIsNeverRecordedOrReused() {
        final CModelImage broken = new CModelImage(() -> new CImageResource(new CWritableImage((BufferedImage) null)));
        source.getTextureManager().put(broken);
        final CTextureAtlas brokenAtlas = atlasOver(broken);

        brokenAtlas.setupCacheImage$cubism(true, null);
        AtlasCacheReuseDelegate.rebuilt(CTextureAtlas.class, brokenAtlas, true);

        assertEquals(
                0,
                AtlasCacheReuseDelegate.recordCount(),
                "unreadable pixels are an uncomputable signature, not a cache key");
        assertEquals(0, AtlasCacheReuseDelegate.outputCount(), "unreadable pixels must not key a retained output");
        assertFalse(
                AtlasCacheReuseDelegate.tryReuse(CTextureAtlas.class, brokenAtlas, true),
                "content equality is unproven, so the guard must rebuild");
    }

    // --- T041-DIGEST: the batched digest must hash the byte-identical stream ---

    /**
     * The original byte-at-a-time algorithm, kept verbatim as the parity reference for
     * the chunked implementation. Both branches — the raw {@code DataBufferInt} array
     * and the per-row {@code getRGB} fallback — and the budget guard are reproduced
     * exactly.
     */
    private static String referenceDigest(final BufferedImage image) {
        final long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > 64L * 1024L * 1024L) return null;
        final MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
        final DataBuffer buffer = image.getRaster().getDataBuffer();
        if (buffer instanceof DataBufferInt ints
                && image.getRaster().getDataBuffer().getSize() == image.getWidth() * image.getHeight()) {
            for (final int v : ints.getData()) {
                sha.update((byte) (v >>> 24));
                sha.update((byte) (v >>> 16));
                sha.update((byte) (v >>> 8));
                sha.update((byte) v);
            }
        } else {
            final int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
                for (final int v : row) {
                    sha.update((byte) (v >>> 24));
                    sha.update((byte) (v >>> 16));
                    sha.update((byte) (v >>> 8));
                    sha.update((byte) v);
                }
            }
        }
        final byte[] digest = sha.digest();
        final StringBuilder hex = new StringBuilder(64);
        for (final byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static BufferedImage randomImage(final int width, final int height, final int type, final long seed) {
        final BufferedImage image = new BufferedImage(width, height, type);
        final Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        return image;
    }

    @Test
    void batchedDigestMatchesByteAtATimeOnIntRasters() {
        // Odd dimensions keep the pixel count off power-of-two boundaries.
        for (final int type :
                new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB_PRE}) {
            final BufferedImage image = randomImage(37, 23, type, 0xC041 + type);
            assertEquals(
                    referenceDigest(image),
                    AtlasCacheReuseDelegate.pixelDigest(image),
                    "int-raster fast path must hash the identical big-endian stream");
        }
    }

    @Test
    void batchedDigestMatchesAcrossScratchBlockBoundaries() {
        // 300×200 = 240_000 digest bytes > 64 KiB scratch: exercises mid-stream flushes;
        // 128×128 = 65_536 bytes lands the last pixel exactly on a full block.
        for (final int[] dims : new int[][] {{300, 200}, {128, 128}, {129, 128}, {1, 70000}}) {
            final BufferedImage image =
                    randomImage(dims[0], dims[1], BufferedImage.TYPE_INT_ARGB, dims[0] * 31 + dims[1]);
            assertEquals(
                    referenceDigest(image),
                    AtlasCacheReuseDelegate.pixelDigest(image),
                    "multi-block and boundary-length streams must not reorder a single byte");
        }
    }

    @Test
    void batchedDigestMatchesOnTheGetRgbPath() {
        // Non-int buffers always take the per-row getRGB path; palette/banded types
        // also exercise ARGB conversion rather than raw passthrough.
        for (final int type : new int[] {
            BufferedImage.TYPE_3BYTE_BGR,
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_BYTE_GRAY,
            BufferedImage.TYPE_BYTE_BINARY
        }) {
            final BufferedImage image = randomImage(37, 23, type, 0xB0A7 + type);
            assertEquals(
                    referenceDigest(image),
                    AtlasCacheReuseDelegate.pixelDigest(image),
                    "getRGB fallback must hash the identical stream for type " + type);
        }
    }

    @Test
    void batchedDigestMatchesOnASubimageSharingAParentBuffer() {
        // A subimage keeps the parent's DataBuffer: getSize() (parent area) no longer
        // equals w*h, so even DataBufferInt rasters fall back to getRGB rows.
        final BufferedImage parent = randomImage(64, 64, BufferedImage.TYPE_INT_ARGB, 0x5EB1);
        final BufferedImage sub = parent.getSubimage(8, 8, 16, 16);
        assertEquals(
                referenceDigest(sub),
                AtlasCacheReuseDelegate.pixelDigest(sub),
                "a shared parent buffer must still take the getRGB path");
    }

    @Test
    void batchedDigestStillHashesTheOversizedBackingTail() {
        // DataBufferInt whose array is longer than its declared size: the fast-path
        // guard compares size, while getData() returns the whole array — the legacy
        // stream includes the tail ints and parity requires hashing them too.
        final int[] data = new int[64 + 16];
        final Random random = new Random(0xFEED);
        for (int i = 0; i < data.length; i++) data[i] = random.nextInt();
        final DataBufferInt buffer = new DataBufferInt(data, 64);
        final WritableRaster raster = Raster.createPackedRaster(
                buffer, 8, 8, 8, new int[] {0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000}, null);
        final BufferedImage image = new BufferedImage(
                new DirectColorModel(32, 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000), raster, false, null);
        assertEquals(
                referenceDigest(image),
                AtlasCacheReuseDelegate.pixelDigest(image),
                "the backing-array tail must remain part of the hashed stream");
    }

    @Test
    void batchedDigestStaysNullOverBudget() {
        final BufferedImage overBudget = new BufferedImage(8193, 8193, BufferedImage.TYPE_BYTE_BINARY);
        assertNull(referenceDigest(overBudget));
        assertNull(
                AtlasCacheReuseDelegate.pixelDigest(overBudget),
                "over-budget digests must remain uncomputable, never a comparable key");
    }
}
