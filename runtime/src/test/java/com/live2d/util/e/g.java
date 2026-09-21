package com.live2d.util.e;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Fixture replica of com.live2d.util.e.g — the 5.2.03 host target. Its private workaround
 * method is instruction-identical to the 5.3.x {@code f/g} variant (same page-size scratch
 * pipeline, same anchor counts, same fixture jp.noids helpers); only the class name differs.
 * Used as the transform target: the patched body should be pixel-identical to this original.
 */
public final class g {

    public static final g a = new g();

    private g() {
    }

    /** Public entry mirroring how the kernel is reached. The real class's
     *  public {@code a(BI,Graphics,BI,int,int,double,boolean)} carries two
     *  callsites to the private workaround under a scale-based branch — a
     *  structure whose stackmap frames MUST survive transformation. The
     *  branch below reproduces that shape so frame-stripping bugs cannot
     *  pass verification silently. */
    public void draw(BufferedImage dst, Graphics2D pageG, BufferedImage src, int x, int y) {
        a(dst, (java.awt.Graphics) pageG, src, x, y, 1.0, true);
    }

    public void a(BufferedImage dst, java.awt.Graphics pageG, BufferedImage src,
                  int x, int y, double scale, boolean highQuality) {
        if (scale >= 0.45 || !highQuality) {
            a(dst, (Graphics2D) pageG, src, x, y);
        } else {
            for (int pass = 0; pass < 2; pass++) {
                a(dst, (Graphics2D) pageG, src, x, y);
            }
        }
    }

    private void a(BufferedImage dst, Graphics2D pageG, BufferedImage src, int x, int y) {
        BufferedImage s7 = jp.noids.util.UtCache.getBufferedImage(
            src.getWidth(), src.getHeight(), src.getType());
        BufferedImage s8 = jp.noids.util.UtCache.getBufferedImage(
            s7.getWidth(), s7.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage s9 = jp.noids.util.UtCache.getBufferedImage(
            dst.getWidth(), dst.getHeight(), dst.getType());
        BufferedImage s10 = jp.noids.util.UtCache.getBufferedImage(
            dst.getWidth(), dst.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        try {
            byte[] b11 = jp.noids.graphics.f.f(s8);
            int[] i12 = jp.noids.graphics.f.c(s7);
            int[] i13 = jp.noids.graphics.f.c(s9);
            byte[] b14 = jp.noids.graphics.f.f(s10);
            Graphics2D g15 = s10.createGraphics();
            Graphics2D g16 = s9.createGraphics();

            jp.noids.graphics.f.a(src, s7, 0);
            jp.noids.graphics.h.a(s7, 3);
            Arrays.fill(b11, (byte) 0);
            for (int j = 0; j <= src.getHeight() - 1; j++) {
                int row7 = j * s7.getWidth();
                int row8 = j * s8.getWidth();
                for (int i = 0; i <= src.getWidth() - 1; i++) {
                    b11[i + row8] = (byte) ((i12[i + row7] >>> 24) & 255);
                    i12[i + row7] |= 0xFF000000;
                }
            }
            jp.noids.graphics.f.a(s9, 0);
            Arrays.fill(jp.noids.graphics.f.f(s10), (byte) 0);   // third f.f call site
            g16.setTransform(pageG.getTransform());
            jp.noids.graphics.i.a(g16);
            g16.drawImage(s7, x, y, null);
            g15.setTransform(pageG.getTransform());
            jp.noids.graphics.i.c(g15);
            g15.drawImage(s8, x, y, null);
            for (int j = 0; j <= dst.getHeight() - 1; j++) {
                int row9 = j * s9.getWidth();
                int row10 = j * s10.getWidth();
                for (int i = 0; i <= dst.getWidth() - 1; i++) {
                    i13[i + row9] = ((b14[i + row10] & 255) << 24) | (i13[i + row9] & 0xFFFFFF);
                }
            }
            AffineTransform oldT = pageG.getTransform();
            RenderingHints oldH = pageG.getRenderingHints();
            jp.noids.graphics.i.b(pageG);
            pageG.setTransform(new AffineTransform());
            pageG.drawImage(s9, 0, 0, null);
            pageG.setTransform(oldT);
            pageG.setRenderingHints(oldH);
        } finally {
            jp.noids.util.UtCache.release(s7);
            jp.noids.util.UtCache.release(s8);
            jp.noids.util.UtCache.release(s9);
            jp.noids.util.UtCache.release(s10);
        }
    }
}
