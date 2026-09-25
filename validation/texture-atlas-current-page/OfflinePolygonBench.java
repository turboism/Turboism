package dev.turboism.validation.texture;

import dev.turboism.plugin.atlasdalsoo.layout.DalsooPolygonPlanner;
import dev.turboism.plugin.atlasmaxrectsbssf.layout.CurrentPageTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Offline-only synthetic benchmark for the polygon packing backend.
 * No host, fixture, or production Agent loading. Mirrors OfflinePolicyBench
 * conventions: deterministic seeds, warmup+median, determinism and
 * bounds/overlap validation outside timing.
 */
public final class OfflinePolygonBench {
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        System.out.println("backend,shape,count,rotation,scaleMode,parallel,medianMs,scale,placed,overflow,coverage,outputHash");
        final DalsooPolygonPlanner polygon = new DalsooPolygonPlanner();
        final CurrentPageTextureAtlasPlanner rect = new CurrentPageTextureAtlasPlanner();
        for (String shape : List.of("rect", "lshape", "star", "mixed"))
        for (int count : new int[]{100, 500})
        for (String rotation : List.of("NONE", "QUARTER", "FREE"))
        for (double scaleMode : new double[]{0, 1})
        for (boolean parallel : new boolean[]{false, true}) {
            // bound runtime: FREE costs ~18x candidates per placement; its
            // parallel variant multiplies that by four kernel configs, and
            // concave outlines multiply it further - parallel coverage is
            // demonstrated on the rect control family
            if (parallel && !shape.equals("rect")) continue;
            if (parallel && rotation.equals("FREE")) continue;
            if (count > 100 && (rotation.equals("FREE") || parallel)) continue;
            if (count > 100 && scaleMode == 0) continue;
            // 500-item scaling evidence on rect + lshape; star is the slowest
            // concave family and mixed reuses its kernel path
            if (count > 100 && (shape.equals("star") || shape.equals("mixed"))) continue;
            final List<TextureAtlasPolygonItem> items = build(shape, count);
            final double area = items.stream()
                .mapToDouble(i -> Math.abs(signedArea(i.outline().rings().get(0)))).sum();
            final int side = (int) Math.ceil(Math.sqrt(area / 0.9)) + 8;
            final TextureAtlasPolygonConstraints c = new TextureAtlasPolygonConstraints(
                side, side, 3, TextureAtlasRotationMode.valueOf(rotation), scaleMode,
                TextureAtlasLayoutBackend.DALSOO_POLYGON,
                TextureAtlasLayoutQuality.BALANCED);
            // auto-scale multiplies each run by up to maxTry pack attempts and
            // is fully deterministic; 500-item packs cost minutes each, so
            // deterministic cases take a single measured sample
            final int warmups = (scaleMode == 0 || count > 100) ? 0 : 1;
            final int measured = (scaleMode == 0 || count > 100) ? 1 : 3;
            final double[] samples = new double[measured];
            TextureAtlasPolygonPlan reference = null;
            for (int it = 0; it < warmups + measured; it++) {
                final long start = System.nanoTime();
                final TextureAtlasPolygonPlan plan = polygon.plan(items, c, parallel);
                final double ms = (System.nanoTime() - start) / 1e6;
                sink = plan;
                validate(items, c, plan);
                if (reference != null && !signature(reference).equals(signature(plan))) {
                    throw new AssertionError("nondeterministic");
                }
                reference = plan;
                if (it >= warmups) samples[it - warmups] = ms;
            }
            Arrays.sort(samples);
            final double coverage = placedArea(items, reference) / (side * (double) side);
            final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(signature(reference).getBytes(StandardCharsets.UTF_8)));
            System.out.printf(Locale.ROOT,
                "dalsoo,%s,%d,%s,%.0f,%s,%.3f,%.6f,%d,%d,%.4f,%s%n",
                shape, count, rotation, scaleMode, parallel, samples[measured / 2],
                reference.scale(), reference.placements().size(),
                reference.overflowTextureIds().size(), coverage, hash);
        }
        // rectangle control: same items as rectangles through the existing planner
        for (String shape : List.of("rect", "lshape", "star", "mixed"))
        for (int count : new int[]{100, 500})
        for (double scaleMode : new double[]{0, 1}) {
            final List<TextureAtlasPolygonItem> src = build(shape, count);
            final List<TextureAtlasLayoutItem> items = new ArrayList<>();
            double area = 0;
            for (TextureAtlasPolygonItem i : src) {
                items.add(new TextureAtlasLayoutItem(i.textureId(), i.width(), i.height()));
                area += (double) i.width() * i.height();
            }
            final int side = (int) Math.ceil(Math.sqrt(area / 0.9)) + 8;
            final var c = TextureAtlasLayoutConstraints.currentPage(side, side, 3, true, scaleMode);
            final double[] samples = new double[5];
            TextureAtlasLayoutPlan reference = null;
            for (int it = 0; it < 8; it++) {
                final long start = System.nanoTime();
                final var plan = rect.plan(items, c, false);
                final double ms = (System.nanoTime() - start) / 1e6;
                sink = plan;
                reference = plan;
                if (it >= 3) samples[it - 3] = ms;
            }
            Arrays.sort(samples);
            System.out.printf(Locale.ROOT,
                "maxrects,%s,%d,QUARTER,%.0f,false,%.3f,%.6f,%d,%d,%.4f,%s%n",
                shape, count, scaleMode, samples[2], reference.scale(),
                reference.placements().size(),
                count - reference.placements().size(), -1.0, "-");
        }
    }

    private static List<TextureAtlasPolygonItem> build(final String shape, final int count) {
        final Random random = new Random(97 + count);
        final List<TextureAtlasPolygonItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int w = 16 + random.nextInt(81);
            final int h = 16 + random.nextInt(81);
            final String id = String.format(Locale.ROOT, "item-%05d", i);
            final TextureAtlasOutline outline = switch (shape) {
                case "rect" -> TextureAtlasOutline.rect(w, h);
                case "lshape" -> lShape(w, h);
                case "star" -> star(Math.max(w, h) / 2.0);
                default -> (i % 3 == 0) ? star(Math.max(w, h) / 2.0)
                    : (i % 3 == 1) ? lShape(w, h) : TextureAtlasOutline.rect(w, h);
            };
            items.add(new TextureAtlasPolygonItem(id, w, h, outline,
                TextureAtlasItemLayoutPolicy.participating(id),
                TextureAtlasOutlineSource.DRAW_DATA_SHAPES, null, false));
        }
        return items;
    }

    private static TextureAtlasOutline lShape(final double w, final double h) {
        return TextureAtlasOutline.of(new double[][] {
            {0, 0}, {w, 0}, {w, h / 2}, {w / 2, h / 2}, {w / 2, h}, {0, h}});
    }

    private static TextureAtlasOutline star(final double r) {
        final double[][] pts = new double[10][];
        for (int i = 0; i < 10; i++) {
            final double radius = i % 2 == 0 ? r : r * 0.45;
            final double a = Math.PI / 5 * i;
            pts[i] = new double[] {r + radius * Math.cos(a), r + radius * Math.sin(a)};
        }
        return TextureAtlasOutline.of(pts);
    }

    private static double signedArea(final double[][] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length; i++) {
            final double[] a = ring[i], b = ring[(i + 1) % ring.length];
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum * 0.5;
    }

    private static double placedArea(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonPlan plan) {
        final Map<String, TextureAtlasPolygonItem> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.textureId(), i));
        double total = 0;
        for (TextureAtlasPolygonPlacement p : plan.placements()) {
            final TextureAtlasPolygonItem item = byId.get(p.textureId());
            double itemArea = 0;
            for (double[][] ring : item.outline().rings()) {
                itemArea += Math.abs(signedArea(ring));
            }
            total += itemArea * p.scale() * p.scale();
        }
        return total;
    }

    private static String signature(final TextureAtlasPolygonPlan plan) {
        final StringBuilder sb = new StringBuilder();
        sb.append(plan.scale());
        for (TextureAtlasPolygonPlacement p : plan.placements()) {
            sb.append('|').append(p.textureId()).append(',').append(p.x())
                .append(',').append(p.y()).append(',').append(p.angleDeg())
                .append(',').append(p.scale());
        }
        return sb.append('~').append(plan.overflowTextureIds()).toString();
    }

    private static void validate(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonConstraints c, final TextureAtlasPolygonPlan plan) {
        if (!Double.isFinite(plan.scale()) || plan.scale() <= 0) {
            throw new AssertionError("scale");
        }
        final Map<String, TextureAtlasPolygonItem> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.textureId(), i));
        final Set<String> seen = new HashSet<>();
        final List<java.awt.geom.Area> placed = new ArrayList<>();
        for (TextureAtlasPolygonPlacement p : plan.placements()) {
            final TextureAtlasPolygonItem item = byId.get(p.textureId());
            if (item == null || !seen.add(p.textureId())) {
                throw new AssertionError("identity");
            }
            final java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
            at.translate(p.x(), p.y());
            at.rotate(Math.toRadians(p.angleDeg()));
            at.scale(p.scale(), p.scale());
            final java.awt.geom.Area union = new java.awt.geom.Area();
            for (double[][] ring : item.outline().rings()) {
                final java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
                path.moveTo(ring[0][0], ring[0][1]);
                for (int i = 1; i < ring.length; i++) path.lineTo(ring[i][0], ring[i][1]);
                path.closePath();
                union.add(new java.awt.geom.Area(path));
            }
            final java.awt.geom.Area area = union.createTransformedArea(at);
            final var b = area.getBounds2D();
            if (b.getMinX() < c.margin() - 0.5 || b.getMinY() < c.margin() - 0.5
                || b.getMaxX() > c.pageWidth() - c.margin() + 0.5
                || b.getMaxY() > c.pageHeight() - c.margin() + 0.5) {
                throw new AssertionError("margin " + p.textureId() + " " + b);
            }
            for (java.awt.geom.Area other : placed) {
                final java.awt.geom.Area test = new java.awt.geom.Area(area);
                test.intersect(other);
                final var tb = test.getBounds2D();
                if (!test.isEmpty() && tb.getWidth() * tb.getHeight() > 1e-3) {
                    throw new AssertionError("overlap " + p.textureId());
                }
            }
            placed.add(area);
        }
    }
}
