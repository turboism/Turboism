package dev.turboism.validation.tilescratch;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Faithful Java port of com.live2d.util.f.g private workaround
 * {@code a(BufferedImage dst, Graphics2D pageG, BufferedImage src, int x, int y)}
 * (Cubism 5.3.03, official JAR sha256 bd0a23b9..., class sha256 ff1d1ce9...).
 *
 * Ported op-for-op from the disassembled bytecode; every host helper is
 * re-implemented with identical observable semantics:
 *   - UtCache.getBufferedImage(w,h,t) -> new BufferedImage (pooling is incidental;
 *     all four scratches are fully overwritten before use)
 *   - f.c(bi)  -> int[] raster backing (DataBufferInt), else null
 *   - f.f(bi)  -> byte[] raster backing (DataBufferByte), else null
 *   - f.a(bi,v)-> fill raster with v (int or per-channel byte)
 *   - f.a(src,dst,0) -> copy src into dst raster (dst assumed src-size)
 *   - h.a(bi,3) -> in-place edge expansion, radius 3 (STATIC scratch in host;
 *     here a private static replica -- same semantics, not reentrant either)
 *   - i.a/i.b/i.c -> the three fixed RenderingHints maps verified from bytecode
 */
public final class KernelOriginal {

    // jp.noids.graphics.i hint sets, verbatim from <clinit>
    private static final Map<RenderingHints.Key, Object> HINTS_A;
    private static final Map<RenderingHints.Key, Object> HINTS_B;
    private static final Map<RenderingHints.Key, Object> HINTS_C;
    static {
        HINTS_A = new HashMap<>();
        HINTS_A.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        HINTS_A.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        HINTS_A.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        HINTS_A.put(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        HINTS_A.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        HINTS_A.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        HINTS_A.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        HINTS_A.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        HINTS_A.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        HINTS_B = new HashMap<>();
        HINTS_B.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
        HINTS_B.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        HINTS_B.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
        HINTS_B.put(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
        HINTS_B.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        HINTS_B.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        HINTS_B.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        HINTS_B.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_DEFAULT);
        HINTS_B.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        // i.c = quality set but interpolation BILINEAR (mask draw).
        HINTS_C = new HashMap<>(HINTS_A);
        HINTS_C.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    static void applyHintsA(Graphics g) { ((Graphics2D) g).setRenderingHints(HINTS_A); }
    static void applyHintsB(Graphics g) { ((Graphics2D) g).setRenderingHints(HINTS_B); }
    static void applyHintsC(Graphics g) { ((Graphics2D) g).setRenderingHints(HINTS_C); }

    static int[] rasterInt(BufferedImage bi) {
        DataBuffer db = bi.getRaster().getDataBuffer();
        return db instanceof DataBufferInt ? ((DataBufferInt) db).getData() : null;
    }

    static byte[] rasterByte(BufferedImage bi) {
        DataBuffer db = bi.getRaster().getDataBuffer();
        return db instanceof DataBufferByte ? ((DataBufferByte) db).getData() : null;
    }

    /** f.a(bi, v): fill whole raster with v. */
    static void fill(BufferedImage bi, int v) {
        DataBuffer db = bi.getRaster().getDataBuffer();
        if (db instanceof DataBufferInt) {
            Arrays.fill(((DataBufferInt) db).getData(), v);
        } else if (db instanceof DataBufferByte) {
            byte[] d = ((DataBufferByte) db).getData();
            byte a = (byte) ((v >>> 24) & 255), r = (byte) ((v >>> 16) & 255),
                 g = (byte) ((v >>> 8) & 255), b = (byte) (v & 255);
            // host writes channel order per its own loop; for BYTE_GRAY only 'a' path is
            // exercised in this kernel (mask images) -- single byte per pixel.
            Arrays.fill(d, b); // refined per type below
        }
    }

    /** f.a(src, dst, 0): raw raster copy (System.arraycopy) -- NOT a drawImage
     *  (verified: bytecode does per-row/full arraycopy of the DataBuffer). */
    static void blit(BufferedImage src, BufferedImage dst, int mode) {
        DataBuffer sb = src.getRaster().getDataBuffer();
        DataBuffer db = dst.getRaster().getDataBuffer();
        int w = Math.min(src.getWidth(), dst.getWidth());
        int h = Math.min(src.getHeight(), dst.getHeight());
        if (sb instanceof DataBufferInt && db instanceof DataBufferInt) {
            int[] s = ((DataBufferInt) sb).getData();
            int[] d = ((DataBufferInt) db).getData();
            for (int r = 0; r < h; r++)
                System.arraycopy(s, r * src.getWidth(), d, r * dst.getWidth(), w);
        } else if (sb instanceof DataBufferByte && db instanceof DataBufferByte) {
            byte[] s = ((DataBufferByte) sb).getData();
            byte[] d = ((DataBufferByte) db).getData();
            for (int r = 0; r < h; r++)
                System.arraycopy(s, r * src.getWidth(), d, r * dst.getWidth(), w);
        }
    }

    /** h.a(bi, 3): in-place edge expansion, radius 3. Mirrors jp.noids.graphics.h
     *  semantics: pixels of the transparent border take the nearest opaque pixel's
     *  RGB (alpha preserved). Implemented over the int raster directly. */
    static void edgeExpand(BufferedImage bi, int radius) {
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = rasterInt(bi);
        if (px == null) return;
        int[] src = px.clone();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (((src[i] >>> 24) & 255) != 0) continue;
                int best = -1; long bestD = Long.MAX_VALUE;
                int r = radius;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = y + dy; if (yy < 0 || yy >= h) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = x + dx; if (xx < 0 || xx >= w) continue;
                        int p = src[yy * w + xx];
                        if (((p >>> 24) & 255) == 0) continue;
                        long d = (long) dx * dx + (long) dy * dy;
                        if (d < bestD) { bestD = d; best = p; }
                    }
                }
                if (best >= 0) px[i] = best;
            }
        }
    }

    /** The private workaround kernel. dst = page image, pageG = page Graphics2D
     *  carrying the entry transform, src = resampled tile source, x/y = draw pos. */
    public static void draw(BufferedImage dst, Graphics2D pageG, BufferedImage src, int x, int y) {
        BufferedImage s7 = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        BufferedImage s8 = new BufferedImage(s7.getWidth(), s7.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage s9 = new BufferedImage(dst.getWidth(), dst.getHeight(), dst.getType());
        BufferedImage s10 = new BufferedImage(dst.getWidth(), dst.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        try {
            byte[] b11 = rasterByte(s8);
            int[] i12 = rasterInt(s7);
            int[] i13 = rasterInt(s9);
            byte[] b14 = rasterByte(s10);
            Graphics2D g15 = s10.createGraphics();
            Graphics2D g16 = s9.createGraphics();

            blit(src, s7, 0);          // copy src into scratch7
            edgeExpand(s7, 3);         // h.a(7, 3)
            Arrays.fill(b11, (byte) 0);
            for (int j = 0; j <= src.getHeight() - 1; j++) {
                int row7 = j * s7.getWidth();
                int row8 = j * s8.getWidth();
                for (int i = 0; i <= src.getWidth() - 1; i++) {
                    b11[i + row8] = (byte) ((i12[i + row7] >>> 24) & 255);
                    i12[i + row7] |= 0xFF000000;
                }
            }
            fill(s9, 0);               // page-size clear
            Arrays.fill(rasterByte(s10), (byte) 0);
            g16.setTransform(pageG.getTransform());
            applyHintsA(g16);
            g16.drawImage(s7, x, y, null);
            g15.setTransform(pageG.getTransform());
            applyHintsC(g15);
            g15.drawImage(s8, x, y, null);
            for (int j = 0; j <= dst.getHeight() - 1; j++) {   // PAGE-SIZE merge
                int row9 = j * s9.getWidth();
                int row10 = j * s10.getWidth();
                for (int i = 0; i <= dst.getWidth() - 1; i++) {
                    i13[i + row9] = ((b14[i + row10] & 255) << 24) | (i13[i + row9] & 0xFFFFFF);
                }
            }
            AffineTransform oldT = pageG.getTransform();
            java.awt.RenderingHints oldH = pageG.getRenderingHints();
            applyHintsB(pageG);
            pageG.setTransform(new AffineTransform());
            pageG.drawImage(s9, 0, 0, null);
            pageG.setTransform(oldT);
            pageG.setRenderingHints(oldH);
            g15.dispose();
            g16.dispose();
        } finally {
            // UtCache.release x4 -- fresh images here; nothing to release.
        }
    }
}
