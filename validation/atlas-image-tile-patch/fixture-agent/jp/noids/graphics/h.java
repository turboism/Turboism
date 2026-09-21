package jp.noids.graphics;

import java.awt.image.BufferedImage;

/**
 * Fixture replica of jp.noids.graphics.h.a(BI,int) — the real 5303 algorithm:
 * four directional scanline fills (row L→R, row R→L, col T→B, col B→T).
 * Pixels with alpha<=3 inherit the last-seen a>3 pixel's RGB (own alpha kept);
 * if none seen yet or the transparent run exceeded n, write 0x00808080.
 * The run counter increments only on alpha==0 pixels.
 */
public class h {

    public static void a(BufferedImage bi, int n) {
        int[] arr = f.c(bi);
        int w = bi.getWidth(), hh = bi.getHeight();
        for (int y = 0; y < hh; y++) {
            int run = 0, last = -1;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int px = arr[i], a = (px >>> 24) & 255;
                if (a > 3) { run = 0; last = px & 0xFFFFFF; }
                else {
                    if (last == -1 || run >= n) arr[i] = 0x00808080;
                    else { arr[i] = (px & 0xFF000000) | last; if (a == 0) run++; }
                }
            }
            run = 0; last = -1;
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                int px = arr[i], a = (px >>> 24) & 255;
                if (a > 3) { run = 0; last = px & 0xFFFFFF; }
                else {
                    if (last == -1 || run >= n) arr[i] = 0x00808080;
                    else { arr[i] = (px & 0xFF000000) | last; if (a == 0) run++; }
                }
            }
        }
        for (int x = 0; x < w; x++) {
            int run = 0, last = -1;
            for (int y = 0; y < hh; y++) {
                int i = x + y * w;
                int px = arr[i], a = (px >>> 24) & 255;
                if (a > 3) { run = 0; last = px & 0xFFFFFF; }
                else {
                    if (last == -1 || run >= n) arr[i] = 0x00808080;
                    else { arr[i] = (px & 0xFF000000) | last; if (a == 0) run++; }
                }
            }
            run = 0; last = -1;
            for (int y = hh - 1; y >= 0; y--) {
                int i = x + y * w;
                int px = arr[i], a = (px >>> 24) & 255;
                if (a > 3) { run = 0; last = px & 0xFFFFFF; }
                else {
                    if (last == -1 || run >= n) arr[i] = 0x00808080;
                    else { arr[i] = (px & 0xFF000000) | last; if (a == 0) run++; }
                }
            }
        }
    }
}
