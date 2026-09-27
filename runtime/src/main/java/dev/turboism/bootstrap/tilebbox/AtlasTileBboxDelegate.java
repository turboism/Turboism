package dev.turboism.bootstrap.tilebbox;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Bbox-bounded delegate replacing the body of the Cubism private per-image alpha workaround —
 * {@code com/live2d/util/f/g.a(BufferedImage, Graphics2D, BufferedImage, int, int)} on 5.3.x,
 * the same kernel under {@code com/live2d/util/e/g} on 5.2.03.
 *
 * <p>Semantics identical to the original page-size pipeline — proven pixel-identical on the
 * real 5.3.03 host on both the editor-open and export paths (page SHA-256 digests equal, all
 * six pages) and offline across thousands of randomized cases. The page-size scratch buffers,
 * the alpha merge loop and the SrcOver composite are shrunk to the transformed-tile bounding
 * box clipped to the page; every heavy helper is the REAL host one (UtCache pool,
 * jp.noids.graphics.f raster ops, h.a edge fill, i.a/b/c hint sets — byte-identical across
 * 5.2.03 and 5.3.03) so every non-size-related behavior is bit-identical.</p>
 *
 * <p>This class lives inside the agent jar, which the manifest places on Boot-Class-Path:
 * it is therefore bootstrap-loaded and cannot hold symbolic references to {@code jp.noids.*}
 * host types (the bootstrap loader cannot see the application classpath). The patched call
 * site passes the patched class object itself — resolved in the host class's own loader
 * context — and every host helper is invoked reflectively through that loader. The ten
 * Method handles resolve once per loader and are cached; {@link #verifyHostAccess} lets the
 * transformer prove they exist before committing patched bytes, so a missing helper can only
 * ever decline the patch, never fail inside a draw call.</p>
 *
 * <p>When the bounding box cannot be computed or is empty, the delegate still performs the
 * same src-side work as the original (copy, edge fill, mask build) and simply skips the
 * composite, matching the original's behavior for degenerate transforms.</p>
 */
public final class AtlasTileBboxDelegate {

    private static final int TYPE_BYTE_GRAY = 10;
    private static volatile Helpers helpers;

    private AtlasTileBboxDelegate() {
    }

    /**
     * Proves every host helper resolves through the loader that defined the patched class.
     *
     * @return {@code null} when all ten handles resolve, otherwise a diagnostic detail
     */
    public static String verifyHostAccess(final ClassLoader hostLoader) {
        try {
            resolve(hostLoader);
            return null;
        } catch (IllegalStateException unavailable) {
            return String.valueOf(unavailable.getMessage());
        }
    }

    /** Replacement body for the patched host method; {@code hostClass} is the patched class. */
    public static void draw(final Class<?> hostClass, final BufferedImage dst,
                            final Graphics2D pageG, final BufferedImage src,
                            final int x, final int y) {
        final Helpers host = resolve(hostClass.getClassLoader());
        // s7: src copy target, same size/type as src — mirrors original.
        BufferedImage s7 = (BufferedImage) call(host.poolGet,
            src.getWidth(), src.getHeight(), src.getType());
        BufferedImage s8 = null, s9 = null, s10 = null;
        try {
            s8 = (BufferedImage) call(host.poolGet,
                s7.getWidth(), s7.getHeight(), TYPE_BYTE_GRAY);

            // bbox of the transformed tile in page space, clipped to page. The host pool
            // may return images larger than requested (type-keyed, >= w/h, <= 9x area), so
            // the box is computed from the union of the REAL s7/s8 bounds — matching the
            // original, whose draws cover each scratch's full extent.
            AffineTransform pageT = pageG.getTransform();
            final Rectangle2D realRect = new Rectangle2D.Double(
                x, y, s7.getWidth(), s7.getHeight());
            Rectangle2D.union(realRect, new Rectangle2D.Double(
                x, y, s8.getWidth(), s8.getHeight()), realRect);
            Rectangle2D tr = pageT.createTransformedShape(realRect).getBounds2D();
            int bx, by, bw, bh;
            if (Double.isNaN(tr.getX()) || Double.isNaN(tr.getY())
                || Double.isNaN(tr.getWidth()) || Double.isNaN(tr.getHeight())) {
                bx = by = bw = bh = 0;
            } else {
                bx = (int) Math.floor(tr.getX()) - 1;
                by = (int) Math.floor(tr.getY()) - 1;
                bw = (int) Math.ceil(tr.getMaxX()) + 1 - bx;
                bh = (int) Math.ceil(tr.getMaxY()) + 1 - by;
            }
            if (bx < 0) { bw += bx; bx = 0; }
            if (by < 0) { bh += by; by = 0; }
            if (bw > dst.getWidth() - bx) bw = dst.getWidth() - bx;
            if (bh > dst.getHeight() - by) bh = dst.getHeight() - by;

            // Src-side work happens identically regardless of clipping — the
            // original copies + expands + builds the mask unconditionally.
            call(host.rasterCopy, src, s7, 0);          // raw raster copy
            call(host.edgeFill, s7, 3);                 // scanline edge fill, a<=3 threshold

            byte[] b11 = (byte[]) call(host.byteRaster, s8);
            int[] i12 = (int[]) call(host.intRaster, s7);
            Arrays.fill(b11, (byte) 0);
            for (int j = 0; j <= src.getHeight() - 1; j++) {
                int row7 = j * s7.getWidth();
                int row8 = j * s8.getWidth();
                for (int i = 0; i <= src.getWidth() - 1; i++) {
                    b11[i + row8] = (byte) ((i12[i + row7] >>> 24) & 255);
                    i12[i + row7] |= 0xFF000000;
                }
            }

            if (bw <= 0 || bh <= 0) {
                return;
            }

            s9 = (BufferedImage) call(host.poolGet, bw, bh, dst.getType());
            s10 = (BufferedImage) call(host.poolGet, bw, bh, TYPE_BYTE_GRAY);

            int[] i13 = (int[]) call(host.intRaster, s9);
            byte[] b14 = (byte[]) call(host.byteRaster, s10);
            Graphics2D g16 = s9.createGraphics();
            Graphics2D g15 = s10.createGraphics();

            call(host.rasterFill, s9, 0);               // bbox clear
            Arrays.fill(b14, (byte) 0);

            AffineTransform shifted = new AffineTransform(pageT);
            shifted.preConcatenate(AffineTransform.getTranslateInstance(-bx, -by));

            g16.setTransform(shifted);
            call(host.hintsQuality, g16);               // quality set, BICUBIC
            g16.drawImage(s7, x, y, null);

            g15.setTransform(shifted);
            call(host.hintsMask, g15);                  // quality set, BILINEAR
            g15.drawImage(s8, x, y, null);

            // Merge only the requested bbox: s9/s10 may be pooled oversize images whose
            // real widths differ, so each raster is indexed by its own stride.
            for (int j = 0; j <= bh - 1; j++) {
                int row9 = j * s9.getWidth();
                int row10 = j * s10.getWidth();
                for (int i = 0; i <= bw - 1; i++) {
                    i13[i + row9] = ((b14[i + row10] & 255) << 24) | (i13[i + row9] & 0xFFFFFF);
                }
            }

            AffineTransform oldT = pageG.getTransform();
            RenderingHints oldH = pageG.getRenderingHints();
            call(host.hintsSpeed, pageG);               // speed set, NEAREST
            pageG.setTransform(new AffineTransform());
            pageG.drawImage(s9, bx, by, null);
            pageG.setTransform(oldT);
            pageG.setRenderingHints(oldH);
        } finally {
            // Original has no dispose(); release mirrors host order.
            if (s7 != null) call(host.poolRelease, s7);
            if (s8 != null) call(host.poolRelease, s8);
            if (s9 != null) call(host.poolRelease, s9);
            if (s10 != null) call(host.poolRelease, s10);
        }
    }

    private static Helpers resolve(final ClassLoader loader) {
        Helpers resolved = helpers;
        if (resolved == null || resolved.loader != loader) {
            synchronized (AtlasTileBboxDelegate.class) {
                resolved = helpers;
                if (resolved == null || resolved.loader != loader) {
                    resolved = load(loader);
                    helpers = resolved;
                }
            }
        }
        return resolved;
    }

    private static Helpers load(final ClassLoader loader) {
        if (loader == null) {
            throw new IllegalStateException(
                "atlas tile-bbox: patched class is bootstrap-loaded; host unreachable");
        }
        try {
            final Class<?> utCache = Class.forName("jp.noids.util.UtCache", false, loader);
            final Class<?> raster = Class.forName("jp.noids.graphics.f", false, loader);
            final Class<?> edge = Class.forName("jp.noids.graphics.h", false, loader);
            final Class<?> hints = Class.forName("jp.noids.graphics.i", false, loader);
            return new Helpers(loader,
                utCache.getMethod("getBufferedImage", int.class, int.class, int.class),
                utCache.getMethod("release", Object.class),
                raster.getMethod("a", BufferedImage.class, BufferedImage.class, int.class),
                raster.getMethod("a", BufferedImage.class, int.class),
                raster.getMethod("c", BufferedImage.class),
                raster.getMethod("f", BufferedImage.class),
                edge.getMethod("a", BufferedImage.class, int.class),
                hints.getMethod("a", Graphics.class),
                hints.getMethod("b", Graphics.class),
                hints.getMethod("c", Graphics.class));
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("atlas tile-bbox host helper missing", missing);
        }
    }

    private static Object call(final Method method, final Object... args) {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("atlas tile-bbox host helper failed", cause);
        } catch (IllegalAccessException inaccessible) {
            throw new IllegalStateException("atlas tile-bbox host helper inaccessible", inaccessible);
        }
    }

    /** The host helpers the delegate invokes, resolved against the host class loader. */
    private static final class Helpers {
        private final ClassLoader loader;
        private final Method poolGet;
        private final Method poolRelease;
        private final Method rasterCopy;
        private final Method rasterFill;
        private final Method intRaster;
        private final Method byteRaster;
        private final Method edgeFill;
        private final Method hintsQuality;
        private final Method hintsSpeed;
        private final Method hintsMask;

        private Helpers(final ClassLoader loader, final Method poolGet, final Method poolRelease,
                        final Method rasterCopy, final Method rasterFill, final Method intRaster,
                        final Method byteRaster, final Method edgeFill, final Method hintsQuality,
                        final Method hintsSpeed, final Method hintsMask) {
            this.loader = loader;
            this.poolGet = poolGet;
            this.poolRelease = poolRelease;
            this.rasterCopy = rasterCopy;
            this.rasterFill = rasterFill;
            this.intRaster = intRaster;
            this.byteRaster = byteRaster;
            this.edgeFill = edgeFill;
            this.hintsQuality = hintsQuality;
            this.hintsSpeed = hintsSpeed;
            this.hintsMask = hintsMask;
        }
    }
}
