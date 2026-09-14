package com.live2d.util.f;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
/** Host-named stub: per-image draw kernel. */
public class g {
    public int calls;
    public final void a(BufferedImage src, Graphics graphics, BufferedImage dst,
                        int x, int y, double scale, boolean quality) {
        calls++;
    }
}
