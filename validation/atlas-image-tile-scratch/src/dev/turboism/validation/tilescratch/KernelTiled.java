package dev.turboism.validation.tilescratch;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static dev.turboism.validation.tilescratch.KernelOriginal.*;

/**
 * Candidate optimization of {@link KernelOriginal#draw}: the page-size scratch
 * buffers (scratch9 color, scratch10 mask) are shrunk to the integer bounding
 * box of the transformed tile rect clipped to the page. The merge loop and the
 * final SrcOver composite then cover only that box.
 *
 * Equivalence argument: in the original, every pixel of scratch9/10 outside
 * the transformed tile rect is transparent (0) -- fill 0 + nothing drawn.
 * The merge loop leaves those pixels transparent. SrcOver-compositing a
 * transparent pixel leaves the page unchanged, so compositing only the bbox
 * region is pixel-identical, provided:
 *   1. bbox fully contains every destination pixel the drawImage touches
 *      (getBounds() + the interpolation's 1px coverage margin is included),
 *   2. the shifted transform produces identical samples (integer page-space
 *      translation does not perturb affine sampling),
 *   3. the merge loop over the bbox sees the same (rgb, mask) pairs.
 */
public final class KernelTiled {

    /** Work counters for reporting. */
    public static long mergePixels;

    public static void draw(BufferedImage dst, Graphics2D pageG, BufferedImage src, int x, int y) {
        BufferedImage s7 = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        BufferedImage s8 = new BufferedImage(s7.getWidth(), s7.getHeight(), BufferedImage.TYPE_BYTE_GRAY);

        // bbox of the transformed tile rect in page space, clipped to the page.
        AffineTransform pageT = pageG.getTransform();
        Rectangle2D tr = pageT.createTransformedShape(
                new Rectangle2D.Double(x, y, s7.getWidth(), s7.getHeight())).getBounds2D();
        int bx, by, bw, bh;
        if (tr.isEmpty() || Double.isNaN(tr.getX()) || Double.isNaN(tr.getY())
                || Double.isNaN(tr.getWidth()) || Double.isNaN(tr.getHeight())) {
            // Degenerate transform: drawImage renders nothing; page unchanged.
            bx = by = bw = bh = 0;
        } else {
            // +1px margin both sides to absorb interpolation coverage at the boundary.
            bx = (int) Math.floor(tr.getX()) - 1;
            by = (int) Math.floor(tr.getY()) - 1;
            bw = (int) Math.ceil(tr.getMaxX()) + 1 - bx;
            bh = (int) Math.ceil(tr.getMaxY()) + 1 - by;
        }
        if (bx < 0) { bw += bx; bx = 0; }
        if (by < 0) { bh += by; by = 0; }
        if (bw > dst.getWidth() - bx) bw = dst.getWidth() - bx;
        if (bh > dst.getHeight() - by) bh = dst.getHeight() - by;

        if (bw <= 0 || bh <= 0) {
            // Original still performs the full pipeline (no page change);
            // src copy/expand happens identically -- preserve that side effect.
            blit(src, s7, 0);
            edgeExpand(s7, 3);
            return;
        }

        BufferedImage s9 = new BufferedImage(bw, bh, dst.getType());
        BufferedImage s10 = new BufferedImage(bw, bh, BufferedImage.TYPE_BYTE_GRAY);
        try {
            byte[] b11 = rasterByte(s8);
            int[] i12 = rasterInt(s7);
            int[] i13 = rasterInt(s9);
            byte[] b14 = rasterByte(s10);
            Graphics2D g15 = s10.createGraphics();
            Graphics2D g16 = s9.createGraphics();

            blit(src, s7, 0);
            edgeExpand(s7, 3);
            Arrays.fill(b11, (byte) 0);
            for (int j = 0; j <= src.getHeight() - 1; j++) {
                int row7 = j * s7.getWidth();
                int row8 = j * s8.getWidth();
                for (int i = 0; i <= src.getWidth() - 1; i++) {
                    b11[i + row8] = (byte) ((i12[i + row7] >>> 24) & 255);
                    i12[i + row7] |= 0xFF000000;
                }
            }
            fill(s9, 0);
            Arrays.fill(b14, (byte) 0);

            // Shift page transform into bbox space: T' = Translate(-bx,-by) * T.
            AffineTransform shifted = new AffineTransform(pageT);
            shifted.preConcatenate(AffineTransform.getTranslateInstance(-bx, -by));

            g16.setTransform(shifted);
            applyHintsA(g16);
            g16.drawImage(s7, x, y, null);
            g15.setTransform(shifted);
            applyHintsC(g15);
            g15.drawImage(s8, x, y, null);

            for (int j = 0; j <= s9.getHeight() - 1; j++) {
                int row9 = j * s9.getWidth();
                int row10 = j * s10.getWidth();
                for (int i = 0; i <= s9.getWidth() - 1; i++) {
                    i13[i + row9] = ((b14[i + row10] & 255) << 24) | (i13[i + row9] & 0xFFFFFF);
                    mergePixels++;
                }
            }

            AffineTransform oldT = pageG.getTransform();
            RenderingHints oldH = pageG.getRenderingHints();
            applyHintsB(pageG);
            pageG.setTransform(new AffineTransform());
            pageG.drawImage(s9, bx, by, null);
            pageG.setTransform(oldT);
            pageG.setRenderingHints(oldH);
            g15.dispose();
            g16.dispose();
        } finally {
            // release
        }
    }
}
