package jp.noids.graphics;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.HashMap;
import java.util.Map;

/** Fixture replica of jp.noids.graphics.i — the three verified hint sets. */
public class i {

    public static final Map<RenderingHints.Key, Object> a = new HashMap<>();
    public static final Map<RenderingHints.Key, Object> b = new HashMap<>();
    public static final Map<RenderingHints.Key, Object> c = new HashMap<>();

    static {
        // i.a — QUALITY set, BICUBIC (color tile draw)
        a.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        a.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        a.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        a.put(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        a.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        a.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        a.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        a.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        a.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // i.b — SPEED set, NEAREST_NEIGHBOR (final composite)
        b.put(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
        b.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        b.put(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
        b.put(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
        b.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        b.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        b.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        b.put(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_DEFAULT);
        b.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        // i.c — QUALITY set, BILINEAR (mask draw)
        c.putAll(a);
        c.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    public static void a(Graphics g) { ((Graphics2D) g).setRenderingHints(a); }
    public static void b(Graphics g) { ((Graphics2D) g).setRenderingHints(b); }
    public static void c(Graphics g) { ((Graphics2D) g).setRenderingHints(c); }
}
