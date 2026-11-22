package dev.turboism.adapter.cubism.textureatlas.interactive;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Compiles interactive atlas ArtMesh shape lists into deduplicated edge geometries and caches
 * pre-rasterized translucent overlay sprites keyed by linear transform and color state.
 *
 * <p>Caching local screen-aligned sprites eliminates per-frame {@code AffineTransform.createTransformedShape}
 * allocations and repeated Marlin scanline stroking on unchanged pan and tile-translation frames, while
 * viewport frustum culling and chunked bounds preserve interactive speed at deep zoom levels.</p>
 */
final class AtlasInteractiveRenderSpriteCache {

    private static final int EDGE_CHUNK_SEGMENTS = 128;
    private static final int COORDS_PER_EDGE = 4;
    private static final int SPRITE_PADDING_PX = 2;
    private static final int SPRITE_MAX_DIMENSION_PX = 2048;
    private static final int SPRITE_MAX_AREA_PX = 4_194_304;
    private static final int BYTES_PER_PIXEL = 4;
    private static final long MAX_CACHE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_CACHED_SPRITES = 256;
    private static final double MIN_ZOOM_REUSE_RATIO = 0.40d;
    private static final double MAX_ZOOM_REUSE_RATIO = 2.50d;
    private static final float STROKE_DIRECT_LINE_MAX_WIDTH = 1.25f;
    private static final int COORDS_BUFFER_SIZE = 6;
    private static final int DEFAULT_STROKE_WIDTH_BITS = Float.floatToRawIntBits(1.0f);
    private static final double MIN_VISIBLE_SPAN_PX = 2.0d;
    private static final double MATRIX_DETERMINANT_EPSILON = 1.0e-10d;
    private static final long EDGE_HASH_PRIME = 0x9E3779B97F4A7C15L;
    private static final float BOUND_INIT_MIN = Float.POSITIVE_INFINITY;
    private static final float BOUND_INIT_MAX = Float.NEGATIVE_INFINITY;
    private static final double SUBPIXEL_EPSILON = 1.0e-4d;
    private static final AffineTransform IDENTITY = new AffineTransform();

    private final Map<ArrayList<?>, CompiledEntry> compiledByList =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<SpriteKey, SoftReference<CachedSprite>> spritesByKey =
            new LinkedHashMap<>(64, 0.75f, true);
    private final Map<CompiledOverlay, CachedSprite> latestSpriteByOverlay =
            Collections.synchronizedMap(new WeakHashMap<>());

    private long totalEstimatedBytes;

    /**
     * Renders interactive shape overlays using cached raster sprites when linear scale/rotation holds,
     * scaled fallback sprites during rapid wheel zoom, or frustum-culled direct drawing at high zoom.
     */
    boolean renderOverlay(
            final Graphics2D g2d,
            final ArrayList<?> shapes,
            final AffineTransform currentTx,
            final Color fillColor,
            final Color strokeColor,
            final boolean isIconPass,
            final boolean activeWheelZoom,
            final RenderMetrics metrics) {
        if (shapes == null || shapes.isEmpty() || g2d == null || currentTx == null) {
            return false;
        }
        final CompiledOverlay compiled = compiledOverlay(shapes);
        if (compiled == null) {
            return false;
        }

        // Measure screen-space clip bounds under identity transform so clip comparisons and local
        // inverse clipping share the same coordinate basis as direct path rendering.
        g2d.setTransform(IDENTITY);
        final Rectangle clipBounds = g2d.getClipBounds();
        final float strokeWidth = resolveStrokeWidth(g2d.getStroke());
        final int strokePad = Math.max(1, (int) Math.ceil(strokeWidth)) + SPRITE_PADDING_PX;

        final Rectangle2D transformedBounds = transformBounds(currentTx, compiled.bounds);
        if (clipBounds != null && !intersectsExpanded(clipBounds, transformedBounds, strokePad)) {
            g2d.setTransform(currentTx);
            if (metrics != null) {
                metrics.frustumCulled++;
            }
            return true;
        }

        if (isIconPass) {
            drawDirectCulled(g2d, compiled, currentTx, clipBounds, fillColor, strokeColor, strokeWidth, strokePad);
            g2d.setTransform(currentTx);
            if (metrics != null) {
                metrics.directDrawn++;
            }
            return true;
        }

        final boolean aaOn = isAntialiased(g2d);
        final int fillArgb = fillColor != null ? fillColor.getRGB() : 0;
        final int strokeArgb = strokeColor != null ? strokeColor.getRGB() : 0;
        final int strokeBits = Float.floatToRawIntBits(strokeWidth);
        final long m00Bits = Double.doubleToLongBits(currentTx.getScaleX());
        final long m10Bits = Double.doubleToLongBits(currentTx.getShearY());
        final long m01Bits = Double.doubleToLongBits(currentTx.getShearX());
        final long m11Bits = Double.doubleToLongBits(currentTx.getScaleY());

        final SpriteKey key = new SpriteKey(
                compiled, fillArgb, strokeArgb, strokeBits, aaOn, m00Bits, m10Bits, m01Bits, m11Bits);
        final CachedSprite exactHit = lookupSprite(key);
        if (exactHit != null) {
            blitExactSprite(g2d, exactHit, currentTx);
            g2d.setTransform(currentTx);
            if (metrics != null) {
                metrics.spriteHits++;
            }
            return true;
        }

        if (activeWheelZoom) {
            final CachedSprite recent = latestSpriteByOverlay.get(compiled);
            if (canReuseScaledSprite(recent, currentTx, fillArgb, strokeArgb, aaOn)) {
                blitScaledSprite(g2d, recent, currentTx);
                g2d.setTransform(currentTx);
                if (metrics != null) {
                    metrics.zoomScaledHits++;
                }
                return true;
            }
        }

        final AffineTransform linearTx = new AffineTransform(
                currentTx.getScaleX(),
                currentTx.getShearY(),
                currentTx.getShearX(),
                currentTx.getScaleY(),
                0.0d,
                0.0d);
        final Rectangle2D linearBounds = transformBounds(linearTx, compiled.bounds);
        final double spanW = linearBounds.getWidth();
        final double spanH = linearBounds.getHeight();
        final int spriteW = (int) Math.ceil(spanW) + SPRITE_PADDING_PX * 2;
        final int spriteH = (int) Math.ceil(spanH) + SPRITE_PADDING_PX * 2;

        if (spanW >= MIN_VISIBLE_SPAN_PX
                && spanH >= MIN_VISIBLE_SPAN_PX
                && spriteW > 0
                && spriteH > 0
                && spriteW <= SPRITE_MAX_DIMENSION_PX
                && spriteH <= SPRITE_MAX_DIMENSION_PX
                && (long) spriteW * spriteH <= SPRITE_MAX_AREA_PX) {
            final CachedSprite built = buildSprite(
                    compiled,
                    linearTx,
                    linearBounds,
                    spriteW,
                    spriteH,
                    fillColor,
                    strokeColor,
                    strokeWidth,
                    aaOn,
                    key);
            if (built != null) {
                blitExactSprite(g2d, built, currentTx);
                g2d.setTransform(currentTx);
                if (metrics != null) {
                    metrics.spriteBuilds++;
                }
                return true;
            }
        }

        drawDirectCulled(g2d, compiled, currentTx, clipBounds, fillColor, strokeColor, strokeWidth, strokePad);
        g2d.setTransform(currentTx);
        if (metrics != null) {
            metrics.directDrawn++;
        }
        return true;
    }

    /** Clears all compiled shape and raster sprite caches. */
    void clear() {
        compiledByList.clear();
        latestSpriteByOverlay.clear();
        synchronized (spritesByKey) {
            spritesByKey.clear();
            totalEstimatedBytes = 0L;
        }
    }

    private CompiledOverlay compiledOverlay(final ArrayList<?> shapes) {
        final int size = shapes.size();
        if (size == 0) {
            return null;
        }
        final Object first = shapes.get(0);
        final Object last = shapes.get(size - 1);
        final int firstId = System.identityHashCode(first);
        final int lastId = System.identityHashCode(last);

        final CompiledEntry existing = compiledByList.get(shapes);
        if (existing != null
                && existing.size == size
                && existing.firstId == firstId
                && existing.lastId == lastId) {
            return existing.overlay;
        }

        final CompiledOverlay built = compile(shapes);
        if (built == null) {
            return null;
        }
        compiledByList.put(shapes, new CompiledEntry(size, firstId, lastId, built));
        return built;
    }

    private static CompiledOverlay compile(final ArrayList<?> shapes) {
        final List<ShapeEntry> shapeEntries = new ArrayList<>(shapes.size());
        final List<Shape> nonSegmentShapes = new ArrayList<>(0);
        final HashSet<EdgeKey> seenEdges = new HashSet<>(Math.max(16, shapes.size() * 64));
        final FloatArray edgeCoords = new FloatArray(Math.max(64, shapes.size() * 128));

        double unionMinX = Double.POSITIVE_INFINITY;
        double unionMinY = Double.POSITIVE_INFINITY;
        double unionMaxX = Double.NEGATIVE_INFINITY;
        double unionMaxY = Double.NEGATIVE_INFINITY;

        final float[] coords = new float[COORDS_BUFFER_SIZE];
        for (int i = 0, n = shapes.size(); i < n; i++) {
            final Object item = shapes.get(i);
            if (!(item instanceof Shape shape)) {
                continue;
            }
            final Rectangle2D b = shape.getBounds2D();
            if (b.isEmpty() && b.getWidth() == 0.0d && b.getHeight() == 0.0d) {
                continue;
            }
            unionMinX = Math.min(unionMinX, b.getMinX());
            unionMinY = Math.min(unionMinY, b.getMinY());
            unionMaxX = Math.max(unionMaxX, b.getMaxX());
            unionMaxY = Math.max(unionMaxY, b.getMaxY());
            shapeEntries.add(new ShapeEntry(shape, b));

            final boolean segmentsOnly = extractSegments(shape, coords, seenEdges, edgeCoords);
            if (!segmentsOnly) {
                nonSegmentShapes.add(shape);
            }
        }

        if (shapeEntries.isEmpty() || !Double.isFinite(unionMinX)) {
            return null;
        }

        final float[] packedEdges = edgeCoords.toArray();
        final float[] chunkBounds = buildChunkBounds(packedEdges);
        final Path2D.Float edgePath = buildEdgePath(packedEdges);
        final Rectangle2D.Double unionBounds = new Rectangle2D.Double(
                unionMinX,
                unionMinY,
                Math.max(0.0d, unionMaxX - unionMinX),
                Math.max(0.0d, unionMaxY - unionMinY));
        return new CompiledOverlay(
                unionBounds,
                List.copyOf(shapeEntries),
                packedEdges,
                chunkBounds,
                edgePath,
                List.copyOf(nonSegmentShapes));
    }

    private static boolean extractSegments(
            final Shape shape, final float[] coords, final HashSet<EdgeKey> seenEdges, final FloatArray edgeCoords) {
        final PathIterator it = shape.getPathIterator(null);
        float startX = 0.0f;
        float startY = 0.0f;
        float prevX = 0.0f;
        float prevY = 0.0f;
        boolean hasCurrent = false;
        boolean allLinear = true;

        while (!it.isDone()) {
            final int seg = it.currentSegment(coords);
            switch (seg) {
                case PathIterator.SEG_MOVETO -> {
                    startX = coords[0];
                    startY = coords[1];
                    prevX = startX;
                    prevY = startY;
                    hasCurrent = true;
                }
                case PathIterator.SEG_LINETO -> {
                    if (hasCurrent) {
                        appendUniqueEdge(prevX, prevY, coords[0], coords[1], seenEdges, edgeCoords);
                    }
                    prevX = coords[0];
                    prevY = coords[1];
                    hasCurrent = true;
                }
                case PathIterator.SEG_CLOSE -> {
                    if (hasCurrent) {
                        appendUniqueEdge(prevX, prevY, startX, startY, seenEdges, edgeCoords);
                        prevX = startX;
                        prevY = startY;
                    }
                }
                default -> allLinear = false;
            }
            it.next();
        }
        return allLinear;
    }

    private static void appendUniqueEdge(
            final float x0,
            final float y0,
            final float x1,
            final float y1,
            final HashSet<EdgeKey> seenEdges,
            final FloatArray edgeCoords) {
        if (x0 == x1 && y0 == y1) {
            return;
        }
        final EdgeKey key = EdgeKey.of(x0, y0, x1, y1);
        if (seenEdges.add(key)) {
            edgeCoords.add(x0, y0, x1, y1);
        }
    }

    private static float[] buildChunkBounds(final float[] packedEdges) {
        final int totalEdges = packedEdges.length / COORDS_PER_EDGE;
        final int chunkCount = (totalEdges + EDGE_CHUNK_SEGMENTS - 1) / EDGE_CHUNK_SEGMENTS;
        final float[] bounds = new float[chunkCount * COORDS_PER_EDGE];
        for (int c = 0; c < chunkCount; c++) {
            final int startEdge = c * EDGE_CHUNK_SEGMENTS;
            final int endEdge = Math.min(totalEdges, startEdge + EDGE_CHUNK_SEGMENTS);
            float minX = BOUND_INIT_MIN;
            float minY = BOUND_INIT_MIN;
            float maxX = BOUND_INIT_MAX;
            float maxY = BOUND_INIT_MAX;
            for (int e = startEdge; e < endEdge; e++) {
                final int base = e * COORDS_PER_EDGE;
                final float x0 = packedEdges[base];
                final float y0 = packedEdges[base + 1];
                final float x1 = packedEdges[base + 2];
                final float y1 = packedEdges[base + 3];
                minX = Math.min(minX, Math.min(x0, x1));
                minY = Math.min(minY, Math.min(y0, y1));
                maxX = Math.max(maxX, Math.max(x0, x1));
                maxY = Math.max(maxY, Math.max(y0, y1));
            }
            final int bBase = c * COORDS_PER_EDGE;
            bounds[bBase] = minX;
            bounds[bBase + 1] = minY;
            bounds[bBase + 2] = maxX;
            bounds[bBase + 3] = maxY;
        }
        return bounds;
    }

    private static Path2D.Float buildEdgePath(final float[] packedEdges) {
        final int edgeCount = packedEdges.length / COORDS_PER_EDGE;
        final Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO, Math.max(16, edgeCount * 2));
        for (int i = 0; i < packedEdges.length; i += COORDS_PER_EDGE) {
            path.moveTo(packedEdges[i], packedEdges[i + 1]);
            path.lineTo(packedEdges[i + 2], packedEdges[i + 3]);
        }
        return path;
    }

    private CachedSprite lookupSprite(final SpriteKey key) {
        synchronized (spritesByKey) {
            final SoftReference<CachedSprite> ref = spritesByKey.get(key);
            final CachedSprite sprite = ref != null ? ref.get() : null;
            if (sprite == null && ref != null) {
                spritesByKey.remove(key);
                recomputeEstimatedBytes();
            }
            return sprite;
        }
    }

    private CachedSprite buildSprite(
            final CompiledOverlay compiled,
            final AffineTransform linearTx,
            final Rectangle2D linearBounds,
            final int spriteW,
            final int spriteH,
            final Color fillColor,
            final Color strokeColor,
            final float strokeWidth,
            final boolean aaOn,
            final SpriteKey key) {
        final double originX = Math.floor(linearBounds.getMinX()) - SPRITE_PADDING_PX;
        final double originY = Math.floor(linearBounds.getMinY()) - SPRITE_PADDING_PX;
        final AffineTransform localSpriteTx = new AffineTransform(
                linearTx.getScaleX(),
                linearTx.getShearY(),
                linearTx.getShearX(),
                linearTx.getScaleY(),
                -originX,
                -originY);

        final BufferedImage image = new BufferedImage(spriteW, spriteH, BufferedImage.TYPE_INT_ARGB_PRE);
        final Graphics2D sg = image.createGraphics();
        try {
            sg.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    aaOn ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            sg.setStroke(new BasicStroke(strokeWidth));
            drawDirectUnclipped(sg, compiled, localSpriteTx, fillColor, strokeColor, strokeWidth);
        } finally {
            sg.dispose();
        }

        final long byteCost = (long) spriteW * spriteH * BYTES_PER_PIXEL;
        final CachedSprite cached = new CachedSprite(
                image,
                originX,
                originY,
                linearTx.getScaleX(),
                linearTx.getShearY(),
                linearTx.getShearX(),
                linearTx.getScaleY(),
                key.fillArgb,
                key.strokeArgb,
                aaOn,
                byteCost);
        synchronized (spritesByKey) {
            final SoftReference<CachedSprite> prev = spritesByKey.put(key, new SoftReference<>(cached));
            if (prev != null) {
                recomputeEstimatedBytes();
            } else {
                totalEstimatedBytes += byteCost;
            }
            evictIfNeeded();
        }
        latestSpriteByOverlay.put(key.overlay, cached);
        return cached;
    }

    private void evictIfNeeded() {
        final Iterator<Map.Entry<SpriteKey, SoftReference<CachedSprite>>> it =
                spritesByKey.entrySet().iterator();
        while (it.hasNext() && (spritesByKey.size() > MAX_CACHED_SPRITES || totalEstimatedBytes > MAX_CACHE_BYTES)) {
            final Map.Entry<SpriteKey, SoftReference<CachedSprite>> entry = it.next();
            final CachedSprite popped = entry.getValue().get();
            it.remove();
            if (popped != null) {
                totalEstimatedBytes = Math.max(0L, totalEstimatedBytes - popped.byteCost);
            }
        }
    }

    private void recomputeEstimatedBytes() {
        long bytes = 0L;
        final Iterator<Map.Entry<SpriteKey, SoftReference<CachedSprite>>> it =
                spritesByKey.entrySet().iterator();
        while (it.hasNext()) {
            final CachedSprite s = it.next().getValue().get();
            if (s == null) {
                it.remove();
            } else {
                bytes += s.byteCost;
            }
        }
        totalEstimatedBytes = bytes;
    }

    private static void blitExactSprite(
            final Graphics2D g2d, final CachedSprite sprite, final AffineTransform currentTx) {
        final double drawX = currentTx.getTranslateX() + sprite.originX;
        final double drawY = currentTx.getTranslateY() + sprite.originY;
        final double fracX = drawX - Math.rint(drawX);
        final double fracY = drawY - Math.rint(drawY);
        if (Math.abs(fracX) <= SUBPIXEL_EPSILON && Math.abs(fracY) <= SUBPIXEL_EPSILON) {
            g2d.setTransform(IDENTITY);
            g2d.drawImage(sprite.image, (int) Math.rint(drawX), (int) Math.rint(drawY), null);
        } else {
            g2d.setTransform(AffineTransform.getTranslateInstance(drawX, drawY));
            g2d.drawImage(sprite.image, 0, 0, null);
        }
    }

    private static boolean canReuseScaledSprite(
            final CachedSprite cached,
            final AffineTransform currentTx,
            final int fillArgb,
            final int strokeArgb,
            final boolean aaOn) {
        if (cached == null || cached.fillArgb != fillArgb || cached.strokeArgb != strokeArgb || cached.aaOn != aaOn) {
            return false;
        }
        final double cachedDet = cached.m00 * cached.m11 - cached.m10 * cached.m01;
        final double curDet =
                currentTx.getScaleX() * currentTx.getScaleY() - currentTx.getShearY() * currentTx.getShearX();
        if (Math.abs(cachedDet) < MATRIX_DETERMINANT_EPSILON || Math.abs(curDet) < MATRIX_DETERMINANT_EPSILON) {
            return false;
        }
        final double cachedNorm = Math.hypot(cached.m00, cached.m10);
        final double curNorm = Math.hypot(currentTx.getScaleX(), currentTx.getShearY());
        if (cachedNorm < MATRIX_DETERMINANT_EPSILON) {
            return false;
        }
        final double ratio = curNorm / cachedNorm;
        return ratio >= MIN_ZOOM_REUSE_RATIO && ratio <= MAX_ZOOM_REUSE_RATIO;
    }

    private static void blitScaledSprite(
            final Graphics2D g2d, final CachedSprite cached, final AffineTransform currentTx) {
        final AffineTransform cachedLinear = new AffineTransform(cached.m00, cached.m10, cached.m01, cached.m11, 0.0d, 0.0d);
        final Object prevInterp = g2d.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        try {
            final AffineTransform delta = new AffineTransform(currentTx);
            delta.concatenate(cachedLinear.createInverse());
            delta.translate(cached.originX, cached.originY);
            g2d.setTransform(IDENTITY);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(cached.image, delta, null);
        } catch (final NoninvertibleTransformException impossible) {
            g2d.setTransform(currentTx);
        } finally {
            if (prevInterp != null) {
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp);
            }
        }
    }

    private static void drawDirectUnclipped(
            final Graphics2D g2d,
            final CompiledOverlay compiled,
            final AffineTransform tx,
            final Color fillColor,
            final Color strokeColor,
            final float strokeWidth) {
        if (fillColor != null) {
            g2d.setColor(fillColor);
            g2d.setTransform(tx);
            for (int i = 0, n = compiled.shapeEntries.size(); i < n; i++) {
                g2d.fill(compiled.shapeEntries.get(i).shape);
            }
        }
        if (strokeColor != null) {
            g2d.setColor(strokeColor);
            drawEdgesRange(g2d, compiled, tx, null, strokeWidth, 0, compiled.edgeCoords.length / COORDS_PER_EDGE);
            if (!compiled.nonSegmentShapes.isEmpty()) {
                g2d.setTransform(IDENTITY);
                for (int i = 0, n = compiled.nonSegmentShapes.size(); i < n; i++) {
                    g2d.draw(tx.createTransformedShape(compiled.nonSegmentShapes.get(i)));
                }
            }
        }
    }

    private static void drawDirectCulled(
            final Graphics2D g2d,
            final CompiledOverlay compiled,
            final AffineTransform tx,
            final Rectangle clipBounds,
            final Color fillColor,
            final Color strokeColor,
            final float strokeWidth,
            final int strokePad) {
        if (clipBounds == null) {
            drawDirectUnclipped(g2d, compiled, tx, fillColor, strokeColor, strokeWidth);
            return;
        }
        final Rectangle2D localClip = inverseExpandedClip(tx, clipBounds, strokePad);
        if (fillColor != null) {
            g2d.setColor(fillColor);
            g2d.setTransform(tx);
            for (int i = 0, n = compiled.shapeEntries.size(); i < n; i++) {
                final ShapeEntry entry = compiled.shapeEntries.get(i);
                if (localClip == null || entry.bounds.intersects(localClip)) {
                    g2d.fill(entry.shape);
                }
            }
        }
        if (strokeColor != null) {
            g2d.setColor(strokeColor);
            final float[] chunks = compiled.chunkBounds;
            final int totalEdges = compiled.edgeCoords.length / COORDS_PER_EDGE;
            final int chunkCount = chunks.length / COORDS_PER_EDGE;
            for (int c = 0; c < chunkCount; c++) {
                final int bBase = c * COORDS_PER_EDGE;
                if (localClip != null
                        && !localClip.intersects(
                                chunks[bBase],
                                chunks[bBase + 1],
                                Math.max(SUBPIXEL_EPSILON, chunks[bBase + 2] - chunks[bBase]),
                                Math.max(SUBPIXEL_EPSILON, chunks[bBase + 3] - chunks[bBase + 1]))) {
                    continue;
                }
                final int start = c * EDGE_CHUNK_SEGMENTS;
                final int end = Math.min(totalEdges, start + EDGE_CHUNK_SEGMENTS);
                drawEdgesRange(g2d, compiled, tx, localClip, strokeWidth, start, end);
            }
            if (!compiled.nonSegmentShapes.isEmpty()) {
                g2d.setTransform(IDENTITY);
                for (int i = 0, n = compiled.nonSegmentShapes.size(); i < n; i++) {
                    g2d.draw(tx.createTransformedShape(compiled.nonSegmentShapes.get(i)));
                }
            }
        }
    }

    private static void drawEdgesRange(
            final Graphics2D g2d,
            final CompiledOverlay compiled,
            final AffineTransform tx,
            final Rectangle2D localClip,
            final float strokeWidth,
            final int startEdge,
            final int endEdge) {
        final float[] edges = compiled.edgeCoords;
        if (strokeWidth <= STROKE_DIRECT_LINE_MAX_WIDTH) {
            g2d.setTransform(IDENTITY);
            final double m00 = tx.getScaleX();
            final double m01 = tx.getShearX();
            final double m02 = tx.getTranslateX();
            final double m10 = tx.getShearY();
            final double m11 = tx.getScaleY();
            final double m12 = tx.getTranslateY();
            final double minX = localClip != null ? localClip.getMinX() : 0.0d;
            final double minY = localClip != null ? localClip.getMinY() : 0.0d;
            final double maxX = localClip != null ? localClip.getMaxX() : 0.0d;
            final double maxY = localClip != null ? localClip.getMaxY() : 0.0d;
            final boolean clip = localClip != null;
            for (int e = startEdge; e < endEdge; e++) {
                final int base = e * COORDS_PER_EDGE;
                final float x0 = edges[base];
                final float y0 = edges[base + 1];
                final float x1 = edges[base + 2];
                final float y1 = edges[base + 3];
                if (clip) {
                    if ((x0 < minX && x1 < minX)
                            || (x0 > maxX && x1 > maxX)
                            || (y0 < minY && y1 < minY)
                            || (y0 > maxY && y1 > maxY)) {
                        continue;
                    }
                }
                final int sx0 = (int) Math.rint(m00 * x0 + m01 * y0 + m02);
                final int sy0 = (int) Math.rint(m10 * x0 + m11 * y0 + m12);
                final int sx1 = (int) Math.rint(m00 * x1 + m01 * y1 + m02);
                final int sy1 = (int) Math.rint(m10 * x1 + m11 * y1 + m12);
                g2d.drawLine(sx0, sy0, sx1, sy1);
            }
            return;
        }
        g2d.setTransform(IDENTITY);
        if (startEdge == 0 && endEdge == edges.length / COORDS_PER_EDGE && localClip == null) {
            g2d.draw(tx.createTransformedShape(compiled.edgePath));
            return;
        }
        final Path2D.Float slice = new Path2D.Float(Path2D.WIND_NON_ZERO, Math.max(16, (endEdge - startEdge) * 2));
        for (int e = startEdge; e < endEdge; e++) {
            final int base = e * COORDS_PER_EDGE;
            slice.moveTo(edges[base], edges[base + 1]);
            slice.lineTo(edges[base + 2], edges[base + 3]);
        }
        g2d.draw(tx.createTransformedShape(slice));
    }

    private static Rectangle2D transformBounds(final AffineTransform tx, final Rectangle2D bounds) {
        final double x0 = bounds.getMinX();
        final double y0 = bounds.getMinY();
        final double x1 = bounds.getMaxX();
        final double y1 = bounds.getMaxY();
        final Point2D.Double p = new Point2D.Double();

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        final double[][] corners = {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}};
        for (final double[] corner : corners) {
            p.setLocation(corner[0], corner[1]);
            tx.transform(p, p);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        return new Rectangle2D.Double(minX, minY, Math.max(0.0d, maxX - minX), Math.max(0.0d, maxY - minY));
    }

    private static Rectangle2D inverseExpandedClip(
            final AffineTransform tx, final Rectangle clipBounds, final int pad) {
        final AffineTransform inv;
        try {
            inv = tx.createInverse();
        } catch (final NoninvertibleTransformException ignored) {
            return null;
        }
        final Rectangle2D expanded = new Rectangle2D.Double(
                clipBounds.x - pad,
                clipBounds.y - pad,
                clipBounds.width + pad * 2.0d,
                clipBounds.height + pad * 2.0d);
        return transformBounds(inv, expanded);
    }

    private static boolean intersectsExpanded(
            final Rectangle clip, final Rectangle2D bounds, final int pad) {
        return bounds.intersects(
                clip.x - pad,
                clip.y - pad,
                clip.width + pad * 2.0d,
                clip.height + pad * 2.0d);
    }

    private static float resolveStrokeWidth(final Stroke stroke) {
        if (stroke instanceof BasicStroke bs) {
            return bs.getLineWidth();
        }
        return Float.intBitsToFloat(DEFAULT_STROKE_WIDTH_BITS);
    }

    private static boolean isAntialiased(final Graphics2D g2d) {
        return Objects.equals(
                g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING), RenderingHints.VALUE_ANTIALIAS_ON);
    }

    /** Tracks cache performance counters during overlay rendering. */
    static final class RenderMetrics {
        long spriteHits;
        long spriteBuilds;
        long zoomScaledHits;
        long directDrawn;
        long frustumCulled;
    }

    private record CompiledEntry(int size, int firstId, int lastId, CompiledOverlay overlay) {}

    private record ShapeEntry(Shape shape, Rectangle2D bounds) {}

    private record CompiledOverlay(
            Rectangle2D.Double bounds,
            List<ShapeEntry> shapeEntries,
            float[] edgeCoords,
            float[] chunkBounds,
            Path2D.Float edgePath,
            List<Shape> nonSegmentShapes) {}

    private record SpriteKey(
            CompiledOverlay overlay,
            int fillArgb,
            int strokeArgb,
            int strokeBits,
            boolean aaOn,
            long m00Bits,
            long m10Bits,
            long m01Bits,
            long m11Bits) {}

    private record CachedSprite(
            BufferedImage image,
            double originX,
            double originY,
            double m00,
            double m10,
            double m01,
            double m11,
            int fillArgb,
            int strokeArgb,
            boolean aaOn,
            long byteCost) {}

    private record EdgeKey(int ax, int ay, int bx, int by) {
        static EdgeKey of(final float x0, final float y0, final float x1, final float y1) {
            final int ix0 = Float.floatToIntBits(x0 == 0.0f ? 0.0f : x0);
            final int iy0 = Float.floatToIntBits(y0 == 0.0f ? 0.0f : y0);
            final int ix1 = Float.floatToIntBits(x1 == 0.0f ? 0.0f : x1);
            final int iy1 = Float.floatToIntBits(y1 == 0.0f ? 0.0f : y1);
            final boolean firstLess = ix0 < ix1 || (ix0 == ix1 && iy0 <= iy1);
            return firstLess ? new EdgeKey(ix0, iy0, ix1, iy1) : new EdgeKey(ix1, iy1, ix0, iy0);
        }

        @Override
        public int hashCode() {
            long h = ax * EDGE_HASH_PRIME;
            h = (h ^ ay) * EDGE_HASH_PRIME;
            h = (h ^ bx) * EDGE_HASH_PRIME;
            h ^= by;
            return Long.hashCode(h);
        }
    }

    private static final class FloatArray {
        private float[] data;
        private int length;

        FloatArray(final int initialCapacity) {
            this.data = new float[initialCapacity];
        }

        void add(final float a, final float b, final float c, final float d) {
            if (length + COORDS_PER_EDGE > data.length) {
                data = java.util.Arrays.copyOf(data, Math.max(length + COORDS_PER_EDGE, data.length * 2));
            }
            data[length++] = a;
            data[length++] = b;
            data[length++] = c;
            data[length++] = d;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, length);
        }
    }
}
