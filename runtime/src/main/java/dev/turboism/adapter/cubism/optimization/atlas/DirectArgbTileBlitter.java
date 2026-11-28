package dev.turboism.adapter.cubism.optimization.atlas;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.util.Arrays;

/**
 * Direct {@code int[]} ARGB and ARGB_PRE tile compositor bypassing Java2D {@code MaskBlit} on
 * axis-aligned, quadrant-rotated, and scaled blits with clean Java2D fallback.
 */
public final class DirectArgbTileBlitter {

    private static final int ALPHA_SHIFT = 24;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int BYTE_MASK = 0xFF;
    private static final int RGB_MASK = 0x00FF_FFFF;
    private static final int ALPHA_MASK = 0xFF00_0000;
    private static final int MAX_CHANNEL = 255;
    private static final int RED_MASK = 0x00FF_0000;
    private static final int GREEN_MASK = 0x0000_FF00;
    private static final int BLUE_MASK = 0x0000_00FF;
    private static final int[] EXPECTED_ARGB_MASKS = {RED_MASK, GREEN_MASK, BLUE_MASK, ALPHA_MASK};
    private static final int[] EXPECTED_RGB_MASKS = {RED_MASK, GREEN_MASK, BLUE_MASK};
    private static final int MUL8_ROUND = 0x80;
    private static final int ALPHA_TABLE_SIZE = MAX_CHANNEL + 1;
    private static final int PREMUL_SHIFT = 24;
    private static final long PREMUL_DIV_ROUND = 1L << (PREMUL_SHIFT - 1);
    private static final long[] INV_ALPHA_24 = buildInvAlpha24();
    private static final int FIXED_SHIFT = 32;
    private static final long FIXED_HALF = 1L << (FIXED_SHIFT - 1);
    private static final double FIXED_SCALE = 4_294_967_296.0;
    private static final int BILINEAR_WEIGHT_SHIFT = 24;
    private static final int BILINEAR_WEIGHT_SCALE = 256;
    private static final int BILINEAR_ROUND = 0x8000;
    private static final int BILINEAR_RESULT_SHIFT = 16;
    private static final int MATRIX_SIZE = 6;
    private static final double MATRIX_EPSILON = 1e-6;
    private static final double CLIP_EPSILON = 1e-6;
    private static final double UNIT_SCALE = 1.0;
    private static final double PIXEL_CENTER_OFFSET = 0.5;
    private static final float ZERO_ALPHA = 0.0f;
    private static final float FULL_ALPHA = 1.0f;
    private static final float FLOAT_CHANNEL_SCALE = 255.0f;
    private static final AffineTransform IDENTITY_TRANSFORM = new AffineTransform();

    private DirectArgbTileBlitter() {}

    /**
     * Composites {@code src} into {@code dst} honoring {@code pageG}'s transform, rectangular
     * clip, {@link AlphaComposite}, and interpolation hint when supported by direct pixel math.
     *
     * @param dst target image buffer
     * @param pageG active graphics state providing transform, clip, composite, and hints
     * @param src source tile image
     * @param x source X offset in user coordinates
     * @param y source Y offset in user coordinates
     * @return {@code true} when composited directly; {@code false} when caller must run Java2D
     */
    public static boolean tryBlit(
            final BufferedImage dst, final Graphics2D pageG, final BufferedImage src, final int x, final int y) {
        if (dst == null || pageG == null || src == null) {
            return false;
        }
        final IntPixels dstPx = IntPixels.extract(dst);
        final IntPixels srcPx = IntPixels.extract(src);
        if (dstPx == null || srcPx == null) {
            return false;
        }
        final CompositeMode composite = CompositeMode.extract(pageG);
        if (composite == null) {
            return false;
        }
        final int[] clip = resolveDeviceClip(pageG, dstPx.width, dstPx.height);
        if (clip == null) {
            return false;
        }
        if (composite.isNoOp() || clip[0] >= clip[2] || clip[1] >= clip[3]) {
            return true;
        }

        final AffineTransform combined = new AffineTransform(pageG.getTransform());
        if (x != 0 || y != 0) {
            combined.translate(x, y);
        }
        final double[] m = new double[MATRIX_SIZE];
        combined.getMatrix(m);
        final double m00 = m[0];
        final double m10 = m[1];
        final double m01 = m[2];
        final double m11 = m[3];
        final double m02 = m[4];
        final double m12 = m[5];

        if (!isFinite(m00, m10, m01, m11, m02, m12)) {
            return false;
        }
        final double det = m00 * m11 - m01 * m10;
        if (Math.abs(det) <= MATRIX_EPSILON) {
            return false;
        }

        final boolean axisAligned = Math.abs(m10) <= MATRIX_EPSILON && Math.abs(m01) <= MATRIX_EPSILON;
        final boolean quadrantSwap = Math.abs(m00) <= MATRIX_EPSILON && Math.abs(m11) <= MATRIX_EPSILON;
        if (!axisAligned && !quadrantSwap) {
            return false;
        }

        if (isExactGridOrthogonal(axisAligned, m00, m10, m01, m11, m02, m12)) {
            blitExactGridOrthogonal(dstPx, srcPx, composite, clip, axisAligned, m00, m10, m01, m11, m02, m12);
            return true;
        }

        final Object interpHint = pageG.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        if (RenderingHints.VALUE_INTERPOLATION_BICUBIC.equals(interpHint)) {
            return false;
        }
        final boolean bilinear = RenderingHints.VALUE_INTERPOLATION_BILINEAR.equals(interpHint);
        blitScaledOrthogonal(dstPx, srcPx, composite, clip, m00, m10, m01, m11, m02, m12, det, bilinear);
        return true;
    }

    /**
     * Merges an opaque RGB scratch image with a byte-gray alpha mask directly into {@code dst},
     * bypassing intermediate alpha write-back and Java2D {@code MaskBlit}.
     *
     * @param dst target atlas image
     * @param pageG destination graphics with active clip, composite, and identity transform
     * @param bx destination box X origin
     * @param by destination box Y origin
     * @param bw box width in pixels
     * @param bh box height in pixels
     * @param opaqueRgb scratch image holding RGB channels
     * @param byteAlphaMask {@code TYPE_BYTE_GRAY} scratch image holding per-pixel alpha
     * @return {@code true} when composited directly; {@code false} to fall back to Java2D
     */
    public static boolean tryCompositeOpaqueRgbAndByteAlpha(
            final BufferedImage dst,
            final Graphics2D pageG,
            final int bx,
            final int by,
            final int bw,
            final int bh,
            final BufferedImage opaqueRgb,
            final BufferedImage byteAlphaMask) {
        if (dst == null || pageG == null || opaqueRgb == null || byteAlphaMask == null) {
            return false;
        }
        if (bw <= 0 || bh <= 0 || !pageG.getTransform().isIdentity()) {
            return false;
        }
        final IntPixels dstPx = IntPixels.extract(dst);
        final IntPixels rgbPx = IntPixels.extractRgb(opaqueRgb);
        final BytePixels alphaPx = BytePixels.extract(byteAlphaMask);
        if (dstPx == null || rgbPx == null || alphaPx == null) {
            return false;
        }
        if (rgbPx.width < bw || rgbPx.height < bh || alphaPx.width < bw || alphaPx.height < bh) {
            return false;
        }
        final CompositeMode composite = CompositeMode.extract(pageG);
        if (composite == null) {
            return false;
        }
        final int[] clip = resolveDeviceClip(pageG, dstPx.width, dstPx.height);
        if (clip == null) {
            return false;
        }
        if (composite.isNoOp()) {
            return true;
        }
        final int dx0 = Math.max(clip[0], bx);
        final int dy0 = Math.max(clip[1], by);
        final int dx1 = Math.min(clip[2], bx + bw);
        final int dy1 = Math.min(clip[3], by + bh);
        if (dx0 >= dx1 || dy0 >= dy1) {
            return true;
        }
        final int[] dstData = dstPx.data;
        final int[] rgbData = rgbPx.data;
        final byte[] alphaData = alphaPx.data;
        final boolean dstPremul = dstPx.premultiplied;
        final boolean fastSrcOver = composite.isFastSrcOver();
        final boolean fastSrc = composite.isFastSrc();

        for (int dy = dy0; dy < dy1; dy++) {
            final int sy = dy - by;
            int dstIdx = dstPx.baseOffset + dy * dstPx.stride + dx0;
            int rgbIdx = rgbPx.baseOffset + sy * rgbPx.stride + (dx0 - bx);
            int alphaIdx = alphaPx.baseOffset + sy * alphaPx.stride + (dx0 - bx);
            for (int dx = dx0; dx < dx1; dx++, dstIdx++, rgbIdx++, alphaIdx++) {
                final int a = alphaData[alphaIdx] & BYTE_MASK;
                final int rgb = rgbData[rgbIdx] & RGB_MASK;
                final int sp = (a << ALPHA_SHIFT) | rgb;
                if (fastSrcOver && !dstPremul) {
                    if (a == 0) {
                        continue;
                    }
                    if (a == MAX_CHANNEL) {
                        dstData[dstIdx] = sp;
                    } else {
                        dstData[dstIdx] = blendSrcOverArgb(sp, dstData[dstIdx]);
                    }
                } else if (fastSrc && !dstPremul) {
                    dstData[dstIdx] = sp;
                } else {
                    dstData[dstIdx] = compositePixel(sp, false, dstData[dstIdx], dstPremul, composite);
                }
            }
        }
        return true;
    }

    /**
     * Resamples a {@code TYPE_BYTE_GRAY} mask using bilinear interpolation for axis-aligned and
     * quadrant-rotated transforms matching Java2D {@code VALUE_INTERPOLATION_BILINEAR}.
     *
     * @param dstGray destination byte-gray image sized at least {@code bw x bh}
     * @param bw target region width
     * @param bh target region height
     * @param shifted transform mapping user space into destination {@code (0..bw, 0..bh)}
     * @param srcGray source byte-gray mask
     * @param srcX source X draw offset matching {@code Graphics2D.drawImage(srcGray, x, y, null)}
     * @param srcY source Y draw offset matching {@code Graphics2D.drawImage(srcGray, x, y, null)}
     * @param logicalSrcW active mask width inside {@code srcGray}
     * @param logicalSrcH active mask height inside {@code srcGray}
     * @return {@code true} when resampled directly; {@code false} for arbitrary rotation/skew
     */
    public static boolean tryResampleByteGrayBilinear(
            final BufferedImage dstGray,
            final int bw,
            final int bh,
            final AffineTransform shifted,
            final BufferedImage srcGray,
            final int srcX,
            final int srcY,
            final int logicalSrcW,
            final int logicalSrcH) {
        if (dstGray == null
                || shifted == null
                || srcGray == null
                || bw <= 0
                || bh <= 0
                || logicalSrcW <= 0
                || logicalSrcH <= 0) {
            return false;
        }
        final BytePixels dstPx = BytePixels.extract(dstGray);
        final BytePixels srcPx = BytePixels.extract(srcGray);
        if (dstPx == null
                || srcPx == null
                || dstPx.width < bw
                || dstPx.height < bh
                || srcPx.width < logicalSrcW
                || srcPx.height < logicalSrcH) {
            return false;
        }
        final AffineTransform combined = new AffineTransform(shifted);
        if (srcX != 0 || srcY != 0) {
            combined.translate(srcX, srcY);
        }
        final double[] m = new double[MATRIX_SIZE];
        combined.getMatrix(m);
        final double m00 = m[0];
        final double m10 = m[1];
        final double m01 = m[2];
        final double m11 = m[3];
        final double m02 = m[4];
        final double m12 = m[5];
        if (!isFinite(m00, m10, m01, m11, m02, m12)) {
            return false;
        }
        final double det = m00 * m11 - m01 * m10;
        if (Math.abs(det) <= MATRIX_EPSILON) {
            return false;
        }
        final boolean axisAligned = Math.abs(m10) <= MATRIX_EPSILON && Math.abs(m01) <= MATRIX_EPSILON;
        final boolean quadrantSwap = Math.abs(m00) <= MATRIX_EPSILON && Math.abs(m11) <= MATRIX_EPSILON;
        if (!axisAligned && !quadrantSwap) {
            return false;
        }

        final int sw = logicalSrcW;
        final int sh = logicalSrcH;
        final byte[] dstData = dstPx.data;
        final byte[] srcData = srcPx.data;
        final double invDet = 1.0 / det;
        final double inv00 = m11 * invDet;
        final double inv01 = -m01 * invDet;
        final double inv10 = -m10 * invDet;
        final double inv11 = m00 * invDet;

        for (int dy = 0; dy < bh; dy++) {
            final int rowStart = dstPx.baseOffset + dy * dstPx.stride;
            final double ry = dy + PIXEL_CENTER_OFFSET - m12;
            for (int dx = 0; dx < bw; dx++) {
                final double rx = dx + PIXEL_CENTER_OFFSET - m02;
                final double sx = inv00 * rx + inv01 * ry;
                final double sy = inv10 * rx + inv11 * ry;
                if (sx < 0.0 || sx >= sw || sy < 0.0 || sy >= sh) {
                    dstData[rowStart + dx] = 0;
                    continue;
                }
                final double px = clamp(sx - PIXEL_CENTER_OFFSET, 0.0, sw - 1.0);
                final double py = clamp(sy - PIXEL_CENTER_OFFSET, 0.0, sh - 1.0);
                final int x0 = (int) px;
                final int y0 = (int) py;
                final int x1 = Math.min(sw - 1, x0 + 1);
                final int y1 = Math.min(sh - 1, y0 + 1);
                final long fx = (long) (px * FIXED_SCALE);
                final long fy = (long) (py * FIXED_SCALE);
                final int wx = (int) ((fx >>> BILINEAR_WEIGHT_SHIFT) & BYTE_MASK);
                final int wy = (int) ((fy >>> BILINEAR_WEIGHT_SHIFT) & BYTE_MASK);
                final int iwx = BILINEAR_WEIGHT_SCALE - wx;
                final int iwy = BILINEAR_WEIGHT_SCALE - wy;
                final int row0 = srcPx.baseOffset + y0 * srcPx.stride;
                final int row1 = srcPx.baseOffset + y1 * srcPx.stride;
                final int a00 = srcData[row0 + x0] & BYTE_MASK;
                final int a10 = srcData[row0 + x1] & BYTE_MASK;
                final int a01 = srcData[row1 + x0] & BYTE_MASK;
                final int a11 = srcData[row1 + x1] & BYTE_MASK;
                final int val = (((a00 * iwx + a10 * wx) * iwy) + ((a01 * iwx + a11 * wx) * wy) + BILINEAR_ROUND)
                        >>> BILINEAR_RESULT_SHIFT;
                dstData[rowStart + dx] = (byte) Math.min(MAX_CHANNEL, val);
            }
        }
        return true;
    }

    /**
     * Checks whether the combined transform maps pixel centers cleanly with 1:1 orthogonal unit scale
     * and integer translation, allowing exact zero-resampling blits even when bicubic hint is set.
     */
    static boolean isExactGridBlit(final AffineTransform transform, final int x, final int y) {
        if (transform == null) {
            return false;
        }
        final AffineTransform combined = new AffineTransform(transform);
        if (x != 0 || y != 0) {
            combined.translate(x, y);
        }
        final double[] m = new double[MATRIX_SIZE];
        combined.getMatrix(m);
        if (!isFinite(m[0], m[1], m[2], m[3], m[4], m[5])) {
            return false;
        }
        final double det = m[0] * m[3] - m[1] * m[2];
        if (Math.abs(det) <= MATRIX_EPSILON) {
            return false;
        }
        final boolean axisAligned = Math.abs(m[1]) <= MATRIX_EPSILON && Math.abs(m[2]) <= MATRIX_EPSILON;
        final boolean quadrantSwap = Math.abs(m[0]) <= MATRIX_EPSILON && Math.abs(m[3]) <= MATRIX_EPSILON;
        return (axisAligned || quadrantSwap) && isExactGridOrthogonal(axisAligned, m[0], m[1], m[2], m[3], m[4], m[5]);
    }

    private static boolean isExactGridOrthogonal(
            final boolean axisAligned,
            final double m00,
            final double m10,
            final double m01,
            final double m11,
            final double m02,
            final double m12) {
        final boolean unitAxes = axisAligned
                ? Math.abs(Math.abs(m00) - UNIT_SCALE) <= MATRIX_EPSILON
                        && Math.abs(Math.abs(m11) - UNIT_SCALE) <= MATRIX_EPSILON
                : Math.abs(Math.abs(m10) - UNIT_SCALE) <= MATRIX_EPSILON
                        && Math.abs(Math.abs(m01) - UNIT_SCALE) <= MATRIX_EPSILON;
        if (!unitAxes) {
            return false;
        }
        return isIntegral(m02, MATRIX_EPSILON) && isIntegral(m12, MATRIX_EPSILON);
    }

    private static void blitExactGridOrthogonal(
            final IntPixels dst,
            final IntPixels src,
            final CompositeMode composite,
            final int[] clip,
            final boolean axisAligned,
            final double m00,
            final double m10,
            final double m01,
            final double m11,
            final double m02,
            final double m12) {
        final int im00 = axisAligned ? (int) Math.round(m00) : 0;
        final int im10 = axisAligned ? 0 : (int) Math.round(m10);
        final int im01 = axisAligned ? 0 : (int) Math.round(m01);
        final int im11 = axisAligned ? (int) Math.round(m11) : 0;
        final int itx = (int) Math.round(m02);
        final int ity = (int) Math.round(m12);

        final int sw = src.width;
        final int sh = src.height;
        final int[] cornerX = {itx, im00 * sw + itx, im01 * sh + itx, im00 * sw + im01 * sh + itx};
        final int[] cornerY = {ity, im10 * sw + ity, im11 * sh + ity, im10 * sw + im11 * sh + ity};
        final int minX = Math.min(Math.min(cornerX[0], cornerX[1]), Math.min(cornerX[2], cornerX[3]));
        final int maxX = Math.max(Math.max(cornerX[0], cornerX[1]), Math.max(cornerX[2], cornerX[3]));
        final int minY = Math.min(Math.min(cornerY[0], cornerY[1]), Math.min(cornerY[2], cornerY[3]));
        final int maxY = Math.max(Math.max(cornerY[0], cornerY[1]), Math.max(cornerY[2], cornerY[3]));

        final int dx0 = Math.max(clip[0], minX);
        final int dy0 = Math.max(clip[1], minY);
        final int dx1 = Math.min(clip[2], maxX);
        final int dy1 = Math.min(clip[3], maxY);
        if (dx0 >= dx1 || dy0 >= dy1) {
            return;
        }

        final int det = im00 * im11 - im01 * im10;
        final int inv00 = im11 * det;
        final int inv01 = -im01 * det;
        final int inv10 = -im10 * det;
        final int inv11 = im00 * det;
        // Exact integer offset deriving (sx, sy) from pixel-center inverse mapping.
        final int offX = (inv00 - 2 * inv00 * itx + inv01 - 2 * inv01 * ity - 1) >> 1;
        final int offY = (inv10 - 2 * inv10 * itx + inv11 - 2 * inv11 * ity - 1) >> 1;

        final int[] dstData = dst.data;
        final int[] srcData = src.data;
        final int srcStepX = inv00 + inv10 * src.stride;
        final boolean sameFormat = dst.premultiplied == src.premultiplied;
        final boolean fastSrc = composite.isFastSrc() && sameFormat;
        final boolean fastSrcOverArgb = composite.isFastSrcOver() && !src.premultiplied && !dst.premultiplied;
        final boolean fastSrcOverPre = composite.isFastSrcOver() && src.premultiplied && dst.premultiplied;

        for (int dy = dy0; dy < dy1; dy++) {
            final int sxStart = inv00 * dx0 + inv01 * dy + offX;
            final int syStart = inv10 * dx0 + inv11 * dy + offY;
            int dstIdx = dst.baseOffset + dy * dst.stride + dx0;
            int srcIdx = src.baseOffset + syStart * src.stride + sxStart;

            if (fastSrc && srcStepX == 1) {
                System.arraycopy(srcData, srcIdx, dstData, dstIdx, dx1 - dx0);
            } else if (fastSrc) {
                for (int dx = dx0; dx < dx1; dx++, dstIdx++, srcIdx += srcStepX) {
                    dstData[dstIdx] = srcData[srcIdx];
                }
            } else if (fastSrcOverArgb) {
                for (int dx = dx0; dx < dx1; dx++, dstIdx++, srcIdx += srcStepX) {
                    final int sp = srcData[srcIdx];
                    final int sa = sp >>> ALPHA_SHIFT;
                    if (sa == 0) {
                        continue;
                    }
                    if (sa == MAX_CHANNEL) {
                        dstData[dstIdx] = sp;
                    } else {
                        dstData[dstIdx] = blendSrcOverArgb(sp, dstData[dstIdx]);
                    }
                }
            } else if (fastSrcOverPre) {
                for (int dx = dx0; dx < dx1; dx++, dstIdx++, srcIdx += srcStepX) {
                    final int sp = srcData[srcIdx];
                    final int sa = sp >>> ALPHA_SHIFT;
                    if (sa == 0) {
                        continue;
                    }
                    if (sa == MAX_CHANNEL) {
                        dstData[dstIdx] = sp;
                    } else {
                        dstData[dstIdx] = blendSrcOverPremul(sp, dstData[dstIdx]);
                    }
                }
            } else {
                for (int dx = dx0; dx < dx1; dx++, dstIdx++, srcIdx += srcStepX) {
                    dstData[dstIdx] = compositePixel(
                            srcData[srcIdx], src.premultiplied, dstData[dstIdx], dst.premultiplied, composite);
                }
            }
        }
    }

    private static void blitScaledOrthogonal(
            final IntPixels dst,
            final IntPixels src,
            final CompositeMode composite,
            final int[] clip,
            final double m00,
            final double m10,
            final double m01,
            final double m11,
            final double m02,
            final double m12,
            final double det,
            final boolean bilinear) {
        final int sw = src.width;
        final int sh = src.height;
        final double[] cx = {m02, m00 * sw + m02, m01 * sh + m02, m00 * sw + m01 * sh + m02};
        final double[] cy = {m12, m10 * sw + m12, m11 * sh + m12, m10 * sw + m11 * sh + m12};
        final double minX = Math.min(Math.min(cx[0], cx[1]), Math.min(cx[2], cx[3]));
        final double maxX = Math.max(Math.max(cx[0], cx[1]), Math.max(cx[2], cx[3]));
        final double minY = Math.min(Math.min(cy[0], cy[1]), Math.min(cy[2], cy[3]));
        final double maxY = Math.max(Math.max(cy[0], cy[1]), Math.max(cy[2], cy[3]));

        final int dx0 = Math.max(clip[0], (int) Math.floor(minX));
        final int dy0 = Math.max(clip[1], (int) Math.floor(minY));
        final int dx1 = Math.min(clip[2], (int) Math.ceil(maxX));
        final int dy1 = Math.min(clip[3], (int) Math.ceil(maxY));
        if (dx0 >= dx1 || dy0 >= dy1) {
            return;
        }

        final double invDet = 1.0 / det;
        final double inv00 = m11 * invDet;
        final double inv01 = -m01 * invDet;
        final double inv10 = -m10 * invDet;
        final double inv11 = m00 * invDet;
        final int[] dstData = dst.data;
        final int[] srcData = src.data;
        final boolean srcPremul = src.premultiplied;
        final boolean dstPremul = dst.premultiplied;

        final long stepFxX = (long) (inv00 * FIXED_SCALE);
        final long stepFyX = (long) (inv10 * FIXED_SCALE);
        final long stepFxY = (long) (inv01 * FIXED_SCALE);
        final long stepFyY = (long) (inv11 * FIXED_SCALE);
        final double inv02 = (m01 * m12 - m11 * m02) * invDet;
        final double inv12 = (m10 * m02 - m00 * m12) * invDet;
        final double rx0 = dx0 + PIXEL_CENTER_OFFSET;
        final double ry0 = dy0 + PIXEL_CENTER_OFFSET;
        long fxRow = (long) ((inv00 * rx0 + inv01 * ry0 + inv02) * FIXED_SCALE);
        long fyRow = (long) ((inv10 * rx0 + inv11 * ry0 + inv12) * FIXED_SCALE);
        final long maxPxX = ((long) (sw - 1)) << FIXED_SHIFT;
        final long maxPxY = ((long) (sh - 1)) << FIXED_SHIFT;

        for (int dy = dy0; dy < dy1; dy++, fxRow += stepFxY, fyRow += stepFyY) {
            long fx = fxRow;
            long fy = fyRow;
            int dstIdx = dst.baseOffset + dy * dst.stride + dx0;
            for (int dx = dx0; dx < dx1; dx++, dstIdx++, fx += stepFxX, fy += stepFyX) {
                if (fx < 0L || fy < 0L) {
                    continue;
                }
                final int ix = (int) (fx >> FIXED_SHIFT);
                final int iy = (int) (fy >> FIXED_SHIFT);
                if (ix >= sw || iy >= sh) {
                    continue;
                }
                if (!bilinear) {
                    final int raw = srcData[src.baseOffset + iy * src.stride + ix];
                    dstData[dstIdx] = compositePixel(raw, srcPremul, dstData[dstIdx], dstPremul, composite);
                } else {
                    final int sampledPremul = sampleBilinearPremulFixed(
                            srcData, src.baseOffset, src.stride, sw, sh, maxPxX, maxPxY, srcPremul, fx, fy);
                    dstData[dstIdx] = compositePixel(sampledPremul, true, dstData[dstIdx], dstPremul, composite);
                }
            }
        }
    }

    private static int sampleBilinearPremulFixed(
            final int[] srcData,
            final int baseOffset,
            final int stride,
            final int sw,
            final int sh,
            final long maxPxX,
            final long maxPxY,
            final boolean srcPremul,
            final long fx,
            final long fy) {
        final long px = Math.max(0L, Math.min(maxPxX, fx - FIXED_HALF));
        final long py = Math.max(0L, Math.min(maxPxY, fy - FIXED_HALF));
        final int x0 = (int) (px >>> FIXED_SHIFT);
        final int y0 = (int) (py >>> FIXED_SHIFT);
        final int x1 = Math.min(sw - 1, x0 + 1);
        final int y1 = Math.min(sh - 1, y0 + 1);
        final int wx = (int) ((px >>> BILINEAR_WEIGHT_SHIFT) & BYTE_MASK);
        final int wy = (int) ((py >>> BILINEAR_WEIGHT_SHIFT) & BYTE_MASK);
        final int iwx = BILINEAR_WEIGHT_SCALE - wx;
        final int iwy = BILINEAR_WEIGHT_SCALE - wy;

        final int row0 = baseOffset + y0 * stride;
        final int row1 = baseOffset + y1 * stride;
        final int c00 = srcPremul ? srcData[row0 + x0] : toPremul(srcData[row0 + x0]);
        final int c10 = srcPremul ? srcData[row0 + x1] : toPremul(srcData[row0 + x1]);
        final int c01 = srcPremul ? srcData[row1 + x0] : toPremul(srcData[row1 + x0]);
        final int c11 = srcPremul ? srcData[row1 + x1] : toPremul(srcData[row1 + x1]);

        final int a = clampChannel(((((c00 >>> ALPHA_SHIFT) * iwx + (c10 >>> ALPHA_SHIFT) * wx) * iwy)
                        + (((c01 >>> ALPHA_SHIFT) * iwx + (c11 >>> ALPHA_SHIFT) * wx) * wy)
                        + BILINEAR_ROUND)
                >>> BILINEAR_RESULT_SHIFT);
        if (a == 0) {
            return 0;
        }
        final int r = clampChannel((((((c00 >>> RED_SHIFT) & BYTE_MASK) * iwx + ((c10 >>> RED_SHIFT) & BYTE_MASK) * wx)
                                * iwy)
                        + ((((c01 >>> RED_SHIFT) & BYTE_MASK) * iwx + ((c11 >>> RED_SHIFT) & BYTE_MASK) * wx) * wy)
                        + BILINEAR_ROUND)
                >>> BILINEAR_RESULT_SHIFT);
        final int g = clampChannel((((((c00 >>> GREEN_SHIFT) & BYTE_MASK) * iwx
                                        + ((c10 >>> GREEN_SHIFT) & BYTE_MASK) * wx)
                                * iwy)
                        + ((((c01 >>> GREEN_SHIFT) & BYTE_MASK) * iwx + ((c11 >>> GREEN_SHIFT) & BYTE_MASK) * wx) * wy)
                        + BILINEAR_ROUND)
                >>> BILINEAR_RESULT_SHIFT);
        final int b = clampChannel(((((c00 & BYTE_MASK) * iwx + (c10 & BYTE_MASK) * wx) * iwy)
                        + (((c01 & BYTE_MASK) * iwx + (c11 & BYTE_MASK) * wx) * wy)
                        + BILINEAR_ROUND)
                >>> BILINEAR_RESULT_SHIFT);
        return (a << ALPHA_SHIFT) | (Math.min(a, r) << RED_SHIFT) | (Math.min(a, g) << GREEN_SHIFT) | Math.min(a, b);
    }

    private static int compositePixel(
            final int sp,
            final boolean srcPremul,
            final int dp,
            final boolean dstPremul,
            final CompositeMode composite) {
        int sa = sp >>> ALPHA_SHIFT;
        int srP;
        int sgP;
        int sbP;
        if (srcPremul) {
            srP = (sp >>> RED_SHIFT) & BYTE_MASK;
            sgP = (sp >>> GREEN_SHIFT) & BYTE_MASK;
            sbP = sp & BYTE_MASK;
            if (composite.extraAlpha255 < MAX_CHANNEL) {
                final int ea = composite.extraAlpha255;
                sa = mul8(sa, ea);
                srP = mul8(srP, ea);
                sgP = mul8(sgP, ea);
                sbP = mul8(sbP, ea);
            }
        } else {
            if (composite.extraAlpha255 < MAX_CHANNEL) {
                sa = mul8(sa, composite.extraAlpha255);
            }
            srP = mul8((sp >>> RED_SHIFT) & BYTE_MASK, sa);
            sgP = mul8((sp >>> GREEN_SHIFT) & BYTE_MASK, sa);
            sbP = mul8(sp & BYTE_MASK, sa);
        }
        if (composite.rule == AlphaComposite.SRC) {
            if (sa == 0) {
                return 0;
            }
            if (dstPremul) {
                return (sa << ALPHA_SHIFT) | (srP << RED_SHIFT) | (sgP << GREEN_SHIFT) | sbP;
            }
            if (sa == MAX_CHANNEL) {
                return ALPHA_MASK | (srP << RED_SHIFT) | (sgP << GREEN_SHIFT) | sbP;
            }
            if (!srcPremul && composite.extraAlpha255 == MAX_CHANNEL) {
                return sp;
            }
            return fromPremul(sa, srP, sgP, sbP);
        }
        if (sa == 0) {
            return dp;
        }
        if (sa == MAX_CHANNEL) {
            return (dstPremul || (srcPremul && srP <= sa && sgP <= sa && sbP <= sa))
                    ? (ALPHA_MASK | (srP << RED_SHIFT) | (sgP << GREEN_SHIFT) | sbP)
                    : sp;
        }
        final int invSa = MAX_CHANNEL - sa;
        final int da = dp >>> ALPHA_SHIFT;
        final int outA = sa + mul8(da, invSa);
        if (outA == 0) {
            return 0;
        }
        final int drP;
        final int dgP;
        final int dbP;
        if (dstPremul) {
            drP = mul8((dp >>> RED_SHIFT) & BYTE_MASK, invSa);
            dgP = mul8((dp >>> GREEN_SHIFT) & BYTE_MASK, invSa);
            dbP = mul8(dp & BYTE_MASK, invSa);
        } else {
            final int dEffA = outA - sa;
            drP = mul8((dp >>> RED_SHIFT) & BYTE_MASK, dEffA);
            dgP = mul8((dp >>> GREEN_SHIFT) & BYTE_MASK, dEffA);
            dbP = mul8(dp & BYTE_MASK, dEffA);
        }
        final int orP = Math.min(outA, srP + drP);
        final int ogP = Math.min(outA, sgP + dgP);
        final int obP = Math.min(outA, sbP + dbP);
        if (dstPremul) {
            return (outA << ALPHA_SHIFT) | (orP << RED_SHIFT) | (ogP << GREEN_SHIFT) | obP;
        }
        return fromPremul(outA, orP, ogP, obP);
    }

    private static int blendSrcOverArgb(final int sp, final int dp) {
        final int sa = sp >>> ALPHA_SHIFT;
        final int sr = mul8((sp >>> RED_SHIFT) & BYTE_MASK, sa);
        final int sg = mul8((sp >>> GREEN_SHIFT) & BYTE_MASK, sa);
        final int sb = mul8(sp & BYTE_MASK, sa);
        final int da = dp >>> ALPHA_SHIFT;
        if (da == 0) {
            return fromPremul(sa, sr, sg, sb);
        }
        final int invSa = MAX_CHANNEL - sa;
        final int dEffA = mul8(da, invSa);
        final int outA = sa + dEffA;
        if (outA == 0) {
            return 0;
        }
        final int orP = sr + mul8((dp >>> RED_SHIFT) & BYTE_MASK, dEffA);
        final int ogP = sg + mul8((dp >>> GREEN_SHIFT) & BYTE_MASK, dEffA);
        final int obP = sb + mul8(dp & BYTE_MASK, dEffA);
        return fromPremul(outA, orP, ogP, obP);
    }

    private static int blendSrcOverPremul(final int sp, final int dp) {
        final int sa = sp >>> ALPHA_SHIFT;
        final int invSa = MAX_CHANNEL - sa;
        final int da = dp >>> ALPHA_SHIFT;
        final int outA = Math.min(MAX_CHANNEL, sa + mul8(da, invSa));
        final int or = Math.min(outA, ((sp >>> RED_SHIFT) & BYTE_MASK) + mul8((dp >>> RED_SHIFT) & BYTE_MASK, invSa));
        final int og =
                Math.min(outA, ((sp >>> GREEN_SHIFT) & BYTE_MASK) + mul8((dp >>> GREEN_SHIFT) & BYTE_MASK, invSa));
        final int ob = Math.min(outA, (sp & BYTE_MASK) + mul8(dp & BYTE_MASK, invSa));
        return (outA << ALPHA_SHIFT) | (or << RED_SHIFT) | (og << GREEN_SHIFT) | ob;
    }

    private static int toPremul(final int argb) {
        final int sa = argb >>> ALPHA_SHIFT;
        if (sa == 0) {
            return 0;
        }
        if (sa == MAX_CHANNEL) {
            return argb;
        }
        return (sa << ALPHA_SHIFT)
                | (mul8((argb >>> RED_SHIFT) & BYTE_MASK, sa) << RED_SHIFT)
                | (mul8((argb >>> GREEN_SHIFT) & BYTE_MASK, sa) << GREEN_SHIFT)
                | mul8(argb & BYTE_MASK, sa);
    }

    private static long[] buildInvAlpha24() {
        final long[] table = new long[ALPHA_TABLE_SIZE];
        for (int a = 1; a <= MAX_CHANNEL; a++) {
            // Mirrors OpenJDK Div8 24-bit fixed-point reciprocal table for exact IntArgb parity.
            table[a] = ((((long) MAX_CHANNEL) << PREMUL_SHIFT) + (a >> 1)) / a;
        }
        return table;
    }

    private static int fromPremul(final int a, final int rP, final int gP, final int bP) {
        final long scale = INV_ALPHA_24[a];
        final int r = Math.min(MAX_CHANNEL, (int) ((rP * scale + PREMUL_DIV_ROUND) >>> PREMUL_SHIFT));
        final int g = Math.min(MAX_CHANNEL, (int) ((gP * scale + PREMUL_DIV_ROUND) >>> PREMUL_SHIFT));
        final int b = Math.min(MAX_CHANNEL, (int) ((bP * scale + PREMUL_DIV_ROUND) >>> PREMUL_SHIFT));
        return (a << ALPHA_SHIFT) | (r << RED_SHIFT) | (g << GREEN_SHIFT) | b;
    }

    private static int mul8(final int c, final int a) {
        final int t = c * a + MUL8_ROUND;
        return (t + (t >> GREEN_SHIFT)) >> GREEN_SHIFT;
    }

    private static int clampChannel(final int v) {
        return v < 0 ? 0 : Math.min(MAX_CHANNEL, v);
    }

    private static double clamp(final double v, final double min, final double max) {
        return v < min ? min : Math.min(max, v);
    }

    private static boolean isFinite(
            final double a, final double b, final double c, final double d, final double e, final double f) {
        return Double.isFinite(a)
                && Double.isFinite(b)
                && Double.isFinite(c)
                && Double.isFinite(d)
                && Double.isFinite(e)
                && Double.isFinite(f);
    }

    private static boolean isIntegral(final double value, final double eps) {
        return Math.abs(value - Math.rint(value)) <= eps;
    }

    private static int[] resolveDeviceClip(final Graphics2D g2d, final int dstW, final int dstH) {
        if (g2d.getClip() == null) {
            return new int[] {0, 0, dstW, dstH};
        }
        final AffineTransform saved = g2d.getTransform();
        final Shape devClip;
        try {
            g2d.setTransform(IDENTITY_TRANSFORM);
            devClip = g2d.getClip();
        } finally {
            g2d.setTransform(saved);
        }
        if (devClip == null) {
            return new int[] {0, 0, dstW, dstH};
        }
        if (!(devClip instanceof Rectangle2D rect)) {
            return null;
        }
        final double minX = rect.getMinX();
        final double minY = rect.getMinY();
        final double maxX = rect.getMaxX();
        final double maxY = rect.getMaxY();
        if (!isIntegral(minX, CLIP_EPSILON)
                || !isIntegral(minY, CLIP_EPSILON)
                || !isIntegral(maxX, CLIP_EPSILON)
                || !isIntegral(maxY, CLIP_EPSILON)) {
            return null;
        }
        final int x0 = Math.max(0, (int) Math.round(minX));
        final int y0 = Math.max(0, (int) Math.round(minY));
        final int x1 = Math.min(dstW, (int) Math.round(maxX));
        final int y1 = Math.min(dstH, (int) Math.round(maxY));
        return new int[] {x0, y0, x1, y1};
    }

    private record IntPixels(int[] data, int baseOffset, int stride, int width, int height, boolean premultiplied) {

        static IntPixels extract(final BufferedImage image) {
            return extractInternal(image, false);
        }

        static IntPixels extractRgb(final BufferedImage image) {
            return extractInternal(image, true);
        }

        private static IntPixels extractInternal(final BufferedImage image, final boolean allowOpaqueRgb) {
            final int type = image.getType();
            final boolean isArgb = type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_ARGB_PRE;
            final boolean isRgb = allowOpaqueRgb && type == BufferedImage.TYPE_INT_RGB;
            if (!isArgb && !isRgb) {
                return null;
            }
            final int width = image.getWidth();
            final int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            final WritableRaster raster = image.getRaster();
            if (!(raster.getSampleModel() instanceof SinglePixelPackedSampleModel spm)) {
                return null;
            }
            final int[] expectedMasks = isRgb ? EXPECTED_RGB_MASKS : EXPECTED_ARGB_MASKS;
            if (!Arrays.equals(spm.getBitMasks(), expectedMasks)) {
                return null;
            }
            final DataBuffer buffer = raster.getDataBuffer();
            if (!(buffer instanceof DataBufferInt intDb) || intDb.getNumBanks() != 1) {
                return null;
            }
            final int[] data = intDb.getData();
            final int stride = spm.getScanlineStride();
            final int offset =
                    intDb.getOffset() - raster.getSampleModelTranslateY() * stride - raster.getSampleModelTranslateX();
            if (stride < width || offset < 0 || offset + (long) (height - 1) * stride + width > data.length) {
                return null;
            }
            return new IntPixels(data, offset, stride, width, height, type == BufferedImage.TYPE_INT_ARGB_PRE);
        }
    }

    private record BytePixels(byte[] data, int baseOffset, int stride, int width, int height) {

        static BytePixels extract(final BufferedImage image) {
            if (image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
                return null;
            }
            final int width = image.getWidth();
            final int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            final WritableRaster raster = image.getRaster();
            final DataBuffer buffer = raster.getDataBuffer();
            if (!(raster.getSampleModel() instanceof ComponentSampleModel csm)
                    || csm.getNumBands() != 1
                    || csm.getPixelStride() != 1
                    || !(buffer instanceof DataBufferByte byteDb)
                    || byteDb.getNumBanks() != 1) {
                return null;
            }
            final byte[] data = byteDb.getData();
            final int stride = csm.getScanlineStride();
            final int offset =
                    byteDb.getOffset() - raster.getSampleModelTranslateY() * stride - raster.getSampleModelTranslateX();
            if (stride < width || offset < 0 || offset + (long) (height - 1) * stride + width > data.length) {
                return null;
            }
            return new BytePixels(data, offset, stride, width, height);
        }
    }

    private record CompositeMode(int rule, int extraAlpha255) {

        static CompositeMode extract(final Graphics2D g2d) {
            final Composite composite = g2d.getComposite();
            if (!(composite instanceof AlphaComposite ac)) {
                return null;
            }
            final int rule = ac.getRule();
            if (rule != AlphaComposite.SRC_OVER && rule != AlphaComposite.SRC) {
                return null;
            }
            final float alpha = Math.max(ZERO_ALPHA, Math.min(FULL_ALPHA, ac.getAlpha()));
            return new CompositeMode(rule, Math.round(alpha * FLOAT_CHANNEL_SCALE));
        }

        boolean isNoOp() {
            return rule == AlphaComposite.SRC_OVER && extraAlpha255 == 0;
        }

        boolean isFastSrcOver() {
            return rule == AlphaComposite.SRC_OVER && extraAlpha255 == MAX_CHANNEL;
        }

        boolean isFastSrc() {
            return rule == AlphaComposite.SRC && extraAlpha255 == MAX_CHANNEL;
        }
    }
}
