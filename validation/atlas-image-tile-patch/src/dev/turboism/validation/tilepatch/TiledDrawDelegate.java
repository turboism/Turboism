package dev.turboism.validation.tilepatch;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

/**
 * Bbox-bounded delegate replacing the body of Cubism 5.3.03
 * {@code com.live2d.util.f.g.a(BufferedImage, Graphics2D, BufferedImage, int, int)}.
 *
 * <p>Semantics identical to the original page-size pipeline (verified offline in
 * validation/atlas-image-tile-scratch, 5000-case pixel-identical): the page-size
 * scratch9/10 are shrunk to the transformed-tile bbox clipped to the page; the
 * merge loop and SrcOver composite cover only that box. All heavy helpers are the
 * REAL host ones (UtCache pool, jp.noids.graphics.f raster ops, h.a edge fill,
 * i.a/b/c hint sets) so every non-size-related behavior is bit-identical.</p>
 *
 * <p>Loaded on the system classloader via the agent jar; calls into jp.noids.*
 * resolve against the host classes directly.</p>
 */
public final class TiledDrawDelegate {

    private static final int TYPE_BYTE_GRAY = 10;

    private TiledDrawDelegate() {
    }

    public static void draw(BufferedImage dst, Graphics2D pageG, BufferedImage src,
                            int x, int y) {
        // s7: src copy target, same size/type as src — mirrors original.
        BufferedImage s7 = jp.noids.util.UtCache.getBufferedImage(
            src.getWidth(), src.getHeight(), src.getType());
        BufferedImage s8 = null, s9 = null, s10 = null;
        Graphics2D g15 = null, g16 = null;
        try {
            s8 = jp.noids.util.UtCache.getBufferedImage(
                s7.getWidth(), s7.getHeight(), TYPE_BYTE_GRAY);

            // bbox of the transformed tile in page space, clipped to page. The host pool
            // may return images larger than requested (type-keyed, >= w/h, <= 9x area), so
            // the box is computed from the union of the REAL s7/s8 bounds — matching the
            // original, whose draws cover each scratch's full extent.
            AffineTransform pageT = pageG.getTransform();
            final Rectangle2D realRect = new Rectangle2D.Double(
                x, y, s7.getWidth(), s7.getHeight());
            Rectangle2D.union(realRect, new Rectangle2D.Double(
                x, y, s8.getWidth(), s8.getHeight()), realRect);
            Rectangle2D tr = pageT.createTransformedShape(realRect).getBounds2D();
            int bx, by, bw, bh;
            if (Double.isNaN(tr.getX()) || Double.isNaN(tr.getY())
                || Double.isNaN(tr.getWidth()) || Double.isNaN(tr.getHeight())) {
                bx = by = bw = bh = 0;
            } else {
                bx = (int) Math.floor(tr.getX()) - 1;
                by = (int) Math.floor(tr.getY()) - 1;
                bw = (int) Math.ceil(tr.getMaxX()) + 1 - bx;
                bh = (int) Math.ceil(tr.getMaxY()) + 1 - by;
            }
            if (bx < 0) { bw += bx; bx = 0; }
            if (by < 0) { bh += by; by = 0; }
            if (bw > dst.getWidth() - bx) bw = dst.getWidth() - bx;
            if (bh > dst.getHeight() - by) bh = dst.getHeight() - by;

            // Src-side work happens identically regardless of clipping — the
            // original copies + expands + builds the mask unconditionally.
            jp.noids.graphics.f.a(src, s7, 0);          // raw raster copy
            jp.noids.graphics.h.a(s7, 3);               // scanline edge fill, a<=3 threshold

            byte[] b11 = jp.noids.graphics.f.f(s8);
            int[] i12 = jp.noids.graphics.f.c(s7);
            Arrays.fill(b11, (byte) 0);
            for (int j = 0; j <= src.getHeight() - 1; j++) {
                int row7 = j * s7.getWidth();
                int row8 = j * s8.getWidth();
                for (int i = 0; i <= src.getWidth() - 1; i++) {
                    b11[i + row8] = (byte) ((i12[i + row7] >>> 24) & 255);
                    i12[i + row7] |= 0xFF000000;
                }
            }

            if (bw <= 0 || bh <= 0) {
                TilePatchProbe.recordSkipped();
                return;
            }

            s9 = jp.noids.util.UtCache.getBufferedImage(bw, bh, dst.getType());
            s10 = jp.noids.util.UtCache.getBufferedImage(bw, bh, TYPE_BYTE_GRAY);

            int[] i13 = jp.noids.graphics.f.c(s9);
            byte[] b14 = jp.noids.graphics.f.f(s10);
            g16 = s9.createGraphics();
            g15 = s10.createGraphics();

            jp.noids.graphics.f.a(s9, 0);               // bbox clear
            Arrays.fill(b14, (byte) 0);

            AffineTransform shifted = new AffineTransform(pageT);
            shifted.preConcatenate(AffineTransform.getTranslateInstance(-bx, -by));

            g16.setTransform(shifted);
            jp.noids.graphics.i.a(g16);                  // quality set, BICUBIC
            g16.drawImage(s7, x, y, null);

            g15.setTransform(shifted);
            jp.noids.graphics.i.c(g15);                  // quality set, BILINEAR
            g15.drawImage(s8, x, y, null);

            // Merge only the requested bbox: s9/s10 may be pooled oversize images whose
            // real widths differ, so each raster is indexed by its own stride.
            for (int j = 0; j <= bh - 1; j++) {
                int row9 = j * s9.getWidth();
                int row10 = j * s10.getWidth();
                for (int i = 0; i <= bw - 1; i++) {
                    i13[i + row9] = ((b14[i + row10] & 255) << 24) | (i13[i + row9] & 0xFFFFFF);
                }
            }

            AffineTransform oldT = pageG.getTransform();
            RenderingHints oldH = pageG.getRenderingHints();
            jp.noids.graphics.i.b(pageG);                // speed set, NEAREST
            pageG.setTransform(new AffineTransform());
            pageG.drawImage(s9, bx, by, null);
            pageG.setTransform(oldT);
            pageG.setRenderingHints(oldH);

            TilePatchProbe.recordDraw((long) bw * bh, (long) dst.getWidth() * dst.getHeight());
        } finally {
            // Original has no dispose(); release mirrors host order.
            if (s7 != null) jp.noids.util.UtCache.release(s7);
            if (s8 != null) jp.noids.util.UtCache.release(s8);
            if (s9 != null) jp.noids.util.UtCache.release(s9);
            if (s10 != null) jp.noids.util.UtCache.release(s10);
        }
    }
}
