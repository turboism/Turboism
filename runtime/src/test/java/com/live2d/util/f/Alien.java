package com.live2d.util.f;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Fixture: same owner name, different class — a method lacking the anchors. */
public final class Alien {
    private void a(BufferedImage dst, Graphics2D pageG, BufferedImage src, int x, int y) {
        pageG.drawImage(src, x, y, null);
    }
}
