package dev.turboism.adapter.cubism.optimization.atlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class DirectArgbTileBlitterTest {

    private static final int CANVAS_W = 128;
    private static final int CANVAS_H = 128;
    private static final int TILE_W = 37;
    private static final int TILE_H = 29;
    private static final int BILINEAR_TOLERANCE_ARGB = 2;

    @Test
    void exactGridTranslationsAndQuadrantRotationsMatchJava2dAcrossImageTypes() {
        final int[] types = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE};
        final List<AffineTransform> transforms = List.of(
                AffineTransform.getTranslateInstance(11, 17),
                AffineTransform.getTranslateInstance(-12, 95),
                AffineTransform.getQuadrantRotateInstance(1, 44, 48),
                AffineTransform.getQuadrantRotateInstance(2, 52, 60),
                AffineTransform.getQuadrantRotateInstance(3, 36, 70),
                AffineTransform.getScaleInstance(-1.0, 1.0),
                AffineTransform.getScaleInstance(1.0, -1.0));
        final Random rng = new Random(42L);

        for (final int dstType : types) {
            for (final int srcType : types) {
                for (final AffineTransform tx : transforms) {
                    final AffineTransform effective = new AffineTransform(tx);
                    if (effective.getScaleX() < 0 || effective.getScaleY() < 0) {
                        effective.translate(-40, -50);
                    }
                    assertMatchesJava2d(
                            dstType,
                            srcType,
                            effective,
                            AlphaComposite.SrcOver,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
                            0,
                            0,
                            0,
                            rng);
                    final boolean pureTranslate = Math.abs(effective.getShearX()) < 1e-9
                            && Math.abs(effective.getShearY()) < 1e-9
                            && Math.abs(effective.getScaleX() - 1.0) < 1e-9
                            && Math.abs(effective.getScaleY() - 1.0) < 1e-9;
                    assertMatchesJava2d(
                            dstType,
                            srcType,
                            effective,
                            AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f),
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                            5,
                            -3,
                            pureTranslate ? 0 : 1,
                            rng);
                    final boolean exactSrc = pureTranslate
                            || (dstType == BufferedImage.TYPE_INT_ARGB_PRE
                                    && srcType == BufferedImage.TYPE_INT_ARGB_PRE);
                    assertMatchesJava2d(
                            dstType,
                            srcType,
                            effective,
                            AlphaComposite.Src,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                            0,
                            0,
                            exactSrc ? 0 : 1,
                            rng);
                }
            }
        }
    }

    @Test
    void scaledNearestAndBilinearBlitsMatchJava2dWithinRounding() {
        final Random rng = new Random(1337L);
        final int[] types = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE};
        final List<AffineTransform> scaled = List.of(
                createScaleTranslate(1.75, 1.35, 10.25, 14.5),
                createScaleTranslate(0.62, 0.54, 42.0, 28.0),
                createRotatedScale(1, 1.4, 0.8, 76.0, 24.0),
                createRotatedScale(3, 0.75, 1.5, 22.0, 88.0));

        for (final int dstType : types) {
            for (final int srcType : types) {
                for (final AffineTransform tx : scaled) {
                    assertMatchesJava2d(
                            dstType,
                            srcType,
                            tx,
                            AlphaComposite.SrcOver,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
                            0,
                            0,
                            0,
                            rng);
                    assertMatchesJava2d(
                            dstType,
                            srcType,
                            tx,
                            AlphaComposite.SrcOver,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                            3,
                            -2,
                            BILINEAR_TOLERANCE_ARGB,
                            rng);
                    assertMatchesJava2d(
                            dstType,
                            srcType,
                            tx,
                            AlphaComposite.getInstance(AlphaComposite.SRC, 0.75f),
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                            0,
                            0,
                            BILINEAR_TOLERANCE_ARGB,
                            rng);
                }
            }
        }
    }

    @Test
    void subimagesAndRectangularClipsMatchJava2d() {
        final Random rng = new Random(99L);
        final BufferedImage fullDst = randomArgbImage(160, 160, BufferedImage.TYPE_INT_ARGB, rng);
        final BufferedImage refFullDst = copyImage(fullDst);
        final BufferedImage fullSrc = randomArgbImage(80, 64, BufferedImage.TYPE_INT_ARGB_PRE, rng);

        final BufferedImage subDst = fullDst.getSubimage(12, 18, CANVAS_W, CANVAS_H);
        final BufferedImage refSubDst = refFullDst.getSubimage(12, 18, CANVAS_W, CANVAS_H);
        final BufferedImage subSrc = fullSrc.getSubimage(7, 5, TILE_W, TILE_H);

        final Graphics2D gDirect = subDst.createGraphics();
        final Graphics2D gRef = refSubDst.createGraphics();
        try {
            final AffineTransform tx = AffineTransform.getQuadrantRotateInstance(1, 60.0, 30.0);
            gDirect.setClip(14, 10, 72, 64);
            gRef.setClip(14, 10, 72, 64);
            gDirect.setTransform(tx);
            gRef.setTransform(tx);
            gDirect.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            gRef.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            assertTrue(DirectArgbTileBlitter.tryBlit(subDst, gDirect, subSrc, 4, -2));
            gRef.drawImage(subSrc, 4, -2, null);
        } finally {
            gDirect.dispose();
            gRef.dispose();
        }
        assertImagesWithinTolerance(refFullDst, fullDst, 0);
    }

    @Test
    void opaqueRgbPlusByteAlphaAndByteGrayBilinearMatchJava2dWithOversizedBuffers() {
        final Random rng = new Random(2026L);
        final BufferedImage directDst = randomArgbImage(160, 160, BufferedImage.TYPE_INT_ARGB, rng);
        final BufferedImage refDst = copyImage(directDst);

        final BufferedImage opaqueRgb = randomOpaqueRgbImage(96, 80, rng);
        final BufferedImage mask = new BufferedImage(96, 80, BufferedImage.TYPE_BYTE_GRAY);
        final byte[] maskData = ((DataBufferByte) mask.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < maskData.length; i++) {
            maskData[i] = (byte) (i * 19 + rng.nextInt(32));
        }

        final int bw = 48;
        final int bh = 36;
        final BufferedImage resampledDirect = new BufferedImage(80, 70, BufferedImage.TYPE_BYTE_GRAY);
        final BufferedImage resampledRef = new BufferedImage(80, 70, BufferedImage.TYPE_BYTE_GRAY);
        final AffineTransform shifted = AffineTransform.getTranslateInstance(6.5, 4.25);
        shifted.scale(0.85, 1.15);

        assertTrue(DirectArgbTileBlitter.tryResampleByteGrayBilinear(
                resampledDirect, bw, bh, shifted, mask, 2, -1, 44, 34));
        final Graphics2D maskG = resampledRef.createGraphics();
        try {
            maskG.setClip(0, 0, bw, bh);
            maskG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            maskG.setTransform(shifted);
            // Match logical source crop of 44x34
            maskG.drawImage(mask.getSubimage(0, 0, 44, 34), 2, -1, null);
        } finally {
            maskG.dispose();
        }
        final byte[] dBytes = ((DataBufferByte) resampledDirect.getRaster().getDataBuffer()).getData();
        final byte[] rBytes = ((DataBufferByte) resampledRef.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                final int idx = y * 80 + x;
                final int diff = Math.abs((dBytes[idx] & 0xFF) - (rBytes[idx] & 0xFF));
                assertTrue(diff <= 1, "ByteGray diff=" + diff + " at (" + x + "," + y + ")");
            }
        }

        final Graphics2D gDirect = directDst.createGraphics();
        final Graphics2D gRef = refDst.createGraphics();
        try {
            gDirect.setClip(16, 20, 64, 52);
            gRef.setClip(16, 20, 64, 52);
            assertTrue(DirectArgbTileBlitter.tryCompositeOpaqueRgbAndByteAlpha(
                    directDst, gDirect, 12, 16, bw, bh, opaqueRgb, mask));
            final int[] rgbData = ((DataBufferInt) opaqueRgb.getRaster().getDataBuffer()).getData();
            final BufferedImage merged = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
            final int[] mergedData = ((DataBufferInt) merged.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    final int alpha = (maskData[y * 96 + x] & 0xFF) << 24;
                    mergedData[y * bw + x] = alpha | (rgbData[y * 96 + x] & 0x00FF_FFFF);
                }
            }
            gRef.drawImage(merged, 12, 16, null);
        } finally {
            gDirect.dispose();
            gRef.dispose();
        }
        assertImagesWithinTolerance(refDst, directDst, 0);
    }

    @Test
    void unsupportedModesReturnFalseWithoutMutatingDestination() {
        final BufferedImage dst = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fillSolid(dst, 0xCC112233);
        final BufferedImage before = copyImage(dst);
        final BufferedImage src = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        fillSolid(src, 0xFF445566);

        final Graphics2D g = dst.createGraphics();
        try {
            // Non-90 degree rotation falls back cleanly.
            g.setTransform(AffineTransform.getRotateInstance(0.45, 20, 20));
            assertFalse(DirectArgbTileBlitter.tryBlit(dst, g, src, 0, 0));

            // Bicubic scaling falls back cleanly.
            g.setTransform(AffineTransform.getScaleInstance(0.75, 0.75));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            assertFalse(DirectArgbTileBlitter.tryBlit(dst, g, src, 0, 0));

            // Non-rectangular clip shape falls back cleanly.
            g.setTransform(new AffineTransform());
            g.setClip(new Ellipse2D.Double(4, 4, 30, 30));
            assertFalse(DirectArgbTileBlitter.tryBlit(dst, g, src, 0, 0));

            // Custom composite falls back cleanly.
            g.setClip(null);
            g.setComposite(new Composite() {
                @Override
                public CompositeContext createContext(
                        final ColorModel srcColorModel, final ColorModel dstColorModel, final RenderingHints hints) {
                    throw new UnsupportedOperationException();
                }
            });
            assertFalse(DirectArgbTileBlitter.tryBlit(dst, g, src, 0, 0));

            // Unsupported image type falls back cleanly.
            g.setComposite(AlphaComposite.SrcOver);
            final BufferedImage bgrSrc = new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR);
            assertFalse(DirectArgbTileBlitter.tryBlit(dst, g, bgrSrc, 0, 0));
        } finally {
            g.dispose();
        }
        assertImagesWithinTolerance(before, dst, 0);
    }

    private static void assertMatchesJava2d(
            final int dstType,
            final int srcType,
            final AffineTransform transform,
            final AlphaComposite composite,
            final Object interpHint,
            final int drawX,
            final int drawY,
            final int maxChannelDiff,
            final Random rng) {
        final BufferedImage directDst = randomArgbImage(CANVAS_W, CANVAS_H, dstType, rng);
        final BufferedImage refDst = copyImage(directDst);
        final BufferedImage src = randomArgbImage(TILE_W, TILE_H, srcType, rng);

        final Graphics2D gDirect = directDst.createGraphics();
        final Graphics2D gRef = refDst.createGraphics();
        try {
            gDirect.setComposite(composite);
            gRef.setComposite(composite);
            gDirect.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpHint);
            gRef.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpHint);
            gDirect.setTransform(transform);
            gRef.setTransform(transform);

            assertTrue(
                    DirectArgbTileBlitter.tryBlit(directDst, gDirect, src, drawX, drawY),
                    "Expected direct blit for dstType=" + dstType + ", srcType=" + srcType);
            gRef.drawImage(src, drawX, drawY, null);
        } finally {
            gDirect.dispose();
            gRef.dispose();
        }
        assertImagesWithinTolerance(refDst, directDst, maxChannelDiff);
    }

    private static AffineTransform createScaleTranslate(
            final double sx, final double sy, final double tx, final double ty) {
        final AffineTransform txObj = AffineTransform.getTranslateInstance(tx, ty);
        txObj.scale(sx, sy);
        return txObj;
    }

    private static AffineTransform createRotatedScale(
            final int quadrants, final double sx, final double sy, final double tx, final double ty) {
        final AffineTransform txObj = AffineTransform.getTranslateInstance(tx, ty);
        txObj.quadrantRotate(quadrants);
        txObj.scale(sx, sy);
        return txObj;
    }

    private static BufferedImage randomArgbImage(final int w, final int h, final int type, final Random rng) {
        final BufferedImage img = new BufferedImage(w, h, type);
        final boolean premul = type == BufferedImage.TYPE_INT_ARGB_PRE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int mode = rng.nextInt(8);
                final int a = mode == 0 ? 0 : (mode <= 2 ? 255 : rng.nextInt(256));
                int r = rng.nextInt(256);
                int g = rng.nextInt(256);
                int b = rng.nextInt(256);
                if (premul) {
                    r = (r * a + 127) / 255;
                    g = (g * a + 127) / 255;
                    b = (b * a + 127) / 255;
                }
                img.getRaster().setDataElements(x, y, new int[] {(a << 24) | (r << 16) | (g << 8) | b});
            }
        }
        return img;
    }

    private static BufferedImage randomOpaqueRgbImage(final int w, final int h, final Random rng) {
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        try {
            for (int y = 0; y < h; y += 4) {
                g.setColor(new Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)));
                g.fillRect(0, y, w, 4);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    private static BufferedImage copyImage(final BufferedImage src) {
        final BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        final Graphics2D g = copy.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static void fillSolid(final BufferedImage img, final int argb) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, argb);
            }
        }
    }

    private static void assertImagesWithinTolerance(
            final BufferedImage expected, final BufferedImage actual, final int maxDiff) {
        final int[] e = ((DataBufferInt) expected.getRaster().getDataBuffer()).getData();
        final int[] a = ((DataBufferInt) actual.getRaster().getDataBuffer()).getData();
        final int offE = expected.getRaster().getDataBuffer().getOffset()
                - expected.getRaster().getSampleModelTranslateY()
                        * ((java.awt.image.SinglePixelPackedSampleModel) expected.getSampleModel()).getScanlineStride()
                - expected.getRaster().getSampleModelTranslateX();
        final int offA = actual.getRaster().getDataBuffer().getOffset()
                - actual.getRaster().getSampleModelTranslateY()
                        * ((java.awt.image.SinglePixelPackedSampleModel) actual.getSampleModel()).getScanlineStride()
                - actual.getRaster().getSampleModelTranslateX();
        final int strideE =
                ((java.awt.image.SinglePixelPackedSampleModel) expected.getSampleModel()).getScanlineStride();
        final int strideA = ((java.awt.image.SinglePixelPackedSampleModel) actual.getSampleModel()).getScanlineStride();
        if (maxDiff == 0 && strideE == strideA && offE == offA && e.length == a.length) {
            assertArrayEquals(e, a);
            return;
        }
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                final int pxE = e[offE + y * strideE + x];
                final int pxA = a[offA + y * strideA + x];
                if (pxE == pxA) {
                    continue;
                }
                for (int shift = 0; shift <= 24; shift += 8) {
                    final int diff = Math.abs(((pxE >>> shift) & 0xFF) - ((pxA >>> shift) & 0xFF));
                    final int alphaE = pxE >>> 24;
                    final int alphaA = pxA >>> 24;
                    final int effectiveLimit = (expected.getType() == BufferedImage.TYPE_INT_ARGB && shift < 24)
                            ? Math.max(maxDiff, (maxDiff * 255 + Math.max(1, alphaE) - 1) / Math.max(1, alphaE))
                            : maxDiff;
                    final int effectiveDiff = (expected.getType() == BufferedImage.TYPE_INT_ARGB && shift < 24)
                            ? Math.abs((((pxE >>> shift) & 0xFF) * alphaE + 127) / 255
                                    - (((pxA >>> shift) & 0xFF) * alphaA + 127) / 255)
                            : diff;
                    assertTrue(
                            diff <= effectiveLimit || effectiveDiff <= maxDiff,
                            "Pixel mismatch at ("
                                    + x
                                    + ","
                                    + y
                                    + ") expected=0x"
                                    + Integer.toHexString(pxE)
                                    + " actual=0x"
                                    + Integer.toHexString(pxA));
                }
            }
        }
    }
}
