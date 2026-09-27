package jp.noids.util;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Fixture replica of jp.noids.util.UtCache — models the real pool contract: a cached image is
 * eligible when its type matches, both dimensions are at least the request, and its area is at
 * most TOO_BIG_SCALE times the requested area; the smallest sufficient image wins. Returned
 * images may therefore be LARGER than requested — code under test must index each image by its
 * own stride and must not assume request == result dimensions.
 */
public class UtCache {

    private static final int TOO_BIG_SCALE = 9;

    private static final HashMap<Integer, List<BufferedImage>> pool = new HashMap<>();

    public static synchronized BufferedImage getBufferedImage(int w, int h, int type) {
        final List<BufferedImage> list = pool.get(type);
        if (list != null) {
            final int reqArea = w * h;
            BufferedImage best = null;
            int bestOvershoot = Integer.MAX_VALUE;
            for (BufferedImage img : list) {
                if (img.getWidth() < w || img.getHeight() < h) continue;
                final int area = img.getWidth() * img.getHeight();
                if (area > reqArea * TOO_BIG_SCALE) continue;
                final int overshoot = area - reqArea;
                if (overshoot < bestOvershoot) {
                    best = img;
                    bestOvershoot = overshoot;
                }
            }
            if (best != null) {
                list.remove(best);
                return best;
            }
        }
        return new BufferedImage(w, h, type);
    }

    public static synchronized void release(Object img) {
        if (!(img instanceof BufferedImage bi)) return;
        pool.computeIfAbsent(bi.getType(), k -> new ArrayList<>()).add(bi);
    }
}
