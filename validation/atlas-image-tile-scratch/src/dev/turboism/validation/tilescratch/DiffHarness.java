package dev.turboism.validation.tilescratch;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

/**
 * Differential harness: run the faithful port (KernelOriginal) and the bbox
 * variant (KernelTiled) on identical inputs, compare final page pixels exactly,
 * and measure merge-loop work (page px vs bbox px).
 *
 * Case space: page 64..1024, src 4..512, draw pos incl. negative/off-page,
 * transforms = identity, translate, scale, fractional translate+scale,
 * rotate (incl. non-90deg), shear. Src content: random noise tiles with
 * irregular alpha (semi-transparent edges, holes, fully-opaque, fully-clear).
 */
public final class DiffHarness {

    public static void main(String[] args) {
        int cases = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 0x5EED;
        Random rng = new Random(seed);

        int diffs = 0, clippedCases = 0, emptyCases = 0;
        long origWork = 0, tiledWork = 0;
        int firstDiffCase = -1;

        for (int c = 0; c < cases; c++) {
            int pw = 64 + rng.nextInt(961);
            int ph = 64 + rng.nextInt(961);
            int sw = 4 + rng.nextInt(509);
            int sh = 4 + rng.nextInt(509);
            int x = rng.nextInt(pw + 512) - 256;      // may go off-page
            int y = rng.nextInt(ph + 512) - 256;

            int srcType = switch (rng.nextInt(3)) {
                case 0 -> BufferedImage.TYPE_INT_ARGB;
                case 1 -> BufferedImage.TYPE_INT_ARGB_PRE;
                default -> BufferedImage.TYPE_INT_RGB;
            };
            int pageType = rng.nextBoolean() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_ARGB_PRE;
            AffineTransform t = randomTransform(rng);
            BufferedImage src = randomSrc(rng, sw, sh, srcType);
            BufferedImage pageA = randomPage(rng, pw, ph, pageType);
            BufferedImage pageB = copy(pageA);

            Graphics2D gA = pageA.createGraphics();
            gA.setTransform(t);
            KernelOriginal.draw(pageA, gA, src, x, y);
            origWork += (long) pw * ph;
            gA.dispose();

            Graphics2D gB = pageB.createGraphics();
            gB.setTransform(t);
            KernelTiled.draw(pageB, gB, src, x, y);
            tiledWork += KernelTiled.mergePixels;
            KernelTiled.mergePixels = 0;
            gB.dispose();

            int d = diffCount(pageA, pageB);
            if (d > 0) {
                diffs++;
                if (firstDiffCase < 0) {
                    firstDiffCase = c;
                    System.out.printf("FIRST DIFF case=%d page=%dx%d src=%dx%d pos=(%d,%d) T=%s diffPx=%d%n",
                            c, pw, ph, sw, sh, x, y, t, d);
                }
            }
        }

        System.out.printf("cases=%d diffs=%d origWork=%d tiledWork=%d ratio=%.3f%n",
                cases, diffs, origWork, tiledWork, (double) tiledWork / Math.max(1, origWork));
        if (diffs == 0) System.out.println("RESULT: PIXEL-IDENTICAL");
        else System.out.println("RESULT: DIFFERENCES FOUND");
    }

    private static AffineTransform randomTransform(Random rng) {
        AffineTransform t = new AffineTransform();
        switch (rng.nextInt(6)) {
            case 0: break;                                          // identity
            case 1: t.translate(rng.nextInt(200) - 100, rng.nextInt(200) - 100); break;
            case 2: {                                               // scale (incl <0.45)
                double s = 0.2 + rng.nextDouble() * 2.8;
                t.translate(rng.nextInt(200) - 100, rng.nextInt(200) - 100);
                t.scale(s, s);
                break;
            }
            case 3: {                                               // fractional translate + aniso scale
                t.translate(rng.nextDouble() * 200 - 100, rng.nextDouble() * 200 - 100);
                t.scale(0.2 + rng.nextDouble() * 2.0, 0.2 + rng.nextDouble() * 2.0);
                break;
            }
            case 4: {                                               // rotate (incl. arbitrary angles)
                t.translate(rng.nextInt(300), rng.nextInt(300));
                t.rotate(rng.nextDouble() * Math.PI * 2);
                break;
            }
            default: {                                              // shear
                t.translate(rng.nextInt(300), rng.nextInt(300));
                t.shear(rng.nextDouble() * 0.8 - 0.4, rng.nextDouble() * 0.8 - 0.4);
                break;
            }
        }
        return t;
    }

    private static BufferedImage randomSrc(Random rng, int w, int h, int type) {
        BufferedImage bi = new BufferedImage(w, h, type);
        int[] px = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        int mode = rng.nextInt(5);
        for (int i = 0; i < px.length; i++) {
            int r = rng.nextInt(256), g = rng.nextInt(256), b = rng.nextInt(256);
            int a;
            switch (mode) {
                case 0: a = 255; break;                              // opaque
                case 1: a = 0; break;                                // fully transparent
                case 2: a = (i % w == 0 || i % w == w - 1 || i / w == 0 || i / w == h - 1) ? 0 : 255; break; // border
                case 3: a = rng.nextInt(256); break;                 // noise alpha
                default: a = rng.nextBoolean() ? 0 : 255; break;     // binary alpha
            }
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return bi;
    }

    private static BufferedImage randomPage(Random rng, int w, int h, int type) {
        BufferedImage bi = new BufferedImage(w, h, type);
        int[] px = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++)
            px[i] = rng.nextInt(256) << 24 | rng.nextInt(0x1000000);
        return bi;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        // raster copy -- drawImage would round-trip premult and drift semi-transparent px
        int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] d = ((DataBufferInt) c.getRaster().getDataBuffer()).getData();
        System.arraycopy(s, 0, d, 0, s.length);
        return c;
    }

    private static int diffCount(BufferedImage a, BufferedImage b) {
        int[] pa = ((DataBufferInt) a.getRaster().getDataBuffer()).getData();
        int[] pb = ((DataBufferInt) b.getRaster().getDataBuffer()).getData();
        int d = 0;
        for (int i = 0; i < pa.length; i++) if (pa[i] != pb[i]) d++;
        return d;
    }
}
