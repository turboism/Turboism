package dev.turboism.validation.modelimport3d;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * Fixed material: 256x128 fully-opaque PNG, four solid-color quadrants plus an
 * asymmetric direction marker (a right-pointing arrow in the top-left quadrant
 * so rotation/mirroring is detectable). Expected colors and frozen sample
 * coordinates are declared as constants below; they are never derived from the
 * generated output. Tolerance is exact ARGB equality (0).
 */
final class Png {

    private Png() {}

    static final int W = 256;
    static final int H = 128;
    static final int ARGB_TL = 0xFFE23B3B;   // top-left quadrant
    static final int ARGB_TR = 0xFF3BB25A;   // top-right
    static final int ARGB_BL = 0xFF3B5CE2;   // bottom-left
    static final int ARGB_BR = 0xFFF0C832;   // bottom-right
    static final int ARGB_MARK = 0xFF101010; // direction marker
    static final int TOLERANCE = 0;          // exact ARGB

    // Asymmetric arrow inside TL quadrant, origin at (MX, MY):
    // shaft row y=MY+3 x in [MX, MX+13], head triangle x in [MX+9, MX+15].
    static final int MX = 24;
    static final int MY = 16;

    /** Frozen sample points: {x, y, expectedArgb, label}. */
    static final Object[][] SAMPLES = {
            {64, 32, ARGB_TL, "quadrant-TL-center"},
            {192, 32, ARGB_TR, "quadrant-TR-center"},
            {64, 96, ARGB_BL, "quadrant-BL-center"},
            {192, 96, ARGB_BR, "quadrant-BR-center"},
            {0, 0, ARGB_TL, "far-edge-NW"},
            {255, 0, ARGB_TR, "far-edge-NE"},
            {0, 127, ARGB_BL, "far-edge-SW"},
            {255, 127, ARGB_BR, "far-edge-SE"},
            {MX + 12, MY + 3, ARGB_MARK, "marker-arrow-head"},
            {MX + 2, MY + 3, ARGB_MARK, "marker-arrow-shaft"},
            {MX + 12, MY + 0, ARGB_TL, "marker-asymmetry-above-head"},
            {MX + 12, MY + 7, ARGB_TL, "marker-asymmetry-below-head"},
    };

    static BufferedImage generate() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean left = x < W / 2;
                boolean top = y < H / 2;
                img.setRGB(x, y, top ? (left ? ARGB_TL : ARGB_TR)
                        : (left ? ARGB_BL : ARGB_BR));
            }
        }
        for (int x = MX; x <= MX + 13; x++) img.setRGB(x, MY + 3, ARGB_MARK);
        for (int d = 0; d <= 3; d++) {
            img.setRGB(MX + 12 + d, MY + 3 - d, ARGB_MARK);
            img.setRGB(MX + 12 + d, MY + 3 + d, ARGB_MARK);
        }
        return img;
    }

    static byte[] pngBytes(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Independent check: returns per-sample lines plus failure reasons. */
    static java.util.List<String> check(BufferedImage img,
                                        java.util.List<String> failures) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (img.getWidth() != W || img.getHeight() != H) {
            failures.add("size " + img.getWidth() + "x" + img.getHeight()
                    + " != " + W + "x" + H);
        }
        int nonOpaque = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) != 0xFF) nonOpaque++;
            }
        }
        if (nonOpaque != 0) {
            failures.add("non-opaque pixels=" + nonOpaque);
        }
        for (Object[] s : SAMPLES) {
            int x = (Integer) s[0], y = (Integer) s[1], expected = (Integer) s[2];
            String label = (String) s[3];
            int actual = img.getRGB(x, y);
            boolean ok = actual == expected; // tolerance 0
            lines.add("sample[" + label + "] @(" + x + "," + y
                    + ") expected=0x" + Integer.toHexString(expected).toUpperCase()
                    + " actual=0x" + Integer.toHexString(actual).toUpperCase()
                    + " tol=0 " + (ok ? "match" : "MISMATCH"));
            if (!ok) failures.add("sample " + label + " mismatch");
        }
        lines.add("alpha: full-scan opaque pixels=" + (W * H - nonOpaque)
                + "/" + (W * H));
        return lines;
    }
}
