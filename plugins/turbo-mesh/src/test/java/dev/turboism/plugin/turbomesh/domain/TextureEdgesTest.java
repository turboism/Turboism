package dev.turboism.plugin.turbomesh.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic-texture tests for the feature-line extraction path. */
class TextureEdgesTest {

    private static DomainMask maskFrom(final int[] argb, final int w, final int h) {
        return DomainMask.fromPixels(argb, w, h, 0.0, 0.0, 1.0, 1.0, 256, 8);
    }

    /** Square source geometry covering the whole [0,1]² UV. */
    private static ArtMeshGeometry square() {
        final List<Point2> corners =
                List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f), new Point2(0f, 1f));
        return new ArtMeshGeometry(corners, corners, List.of(0, 1, 2, 0, 2, 3));
    }

    /** Opaque image: left half red, right half blue. */
    private static int[] twoBlocks(final int w, final int h) {
        final int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = x < w / 2 ? 0xFFFF0000 : 0xFF0000FF;
            }
        }
        return argb;
    }

    /** Opaque white image with a 2px dark diagonal line (line art). */
    private static int[] lineArt(final int w, final int h) {
        final int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = 0xFFFFFFFF;
        }
        for (int x = 8; x < w - 8; x++) {
            final int y = 8 + (int) ((x - 8) * (h - 16.0) / (w - 16.0));
            argb[y * w + x] = 0xFF101010;
            argb[Math.min(h - 1, y + 1) * w + x] = 0xFF101010;
        }
        return argb;
    }

    @Test
    void colorBlockBoundaryProducesOneVerticalChain() {
        final DomainMask mask = maskFrom(twoBlocks(64, 64), 64, 64);
        final List<TextureEdges.EdgeChain> chains = TextureEdges.extract(mask, PlacementSettings.defaults());
        assertFalse(chains.isEmpty(), "colour boundary must produce at least one chain");
        // One dominant chain near x=0.5, roughly spanning the block edge vertically.
        TextureEdges.EdgeChain best = null;
        double bestLen = 0;
        for (final TextureEdges.EdgeChain ch : chains) {
            double len = 0;
            for (int i = 0; i + 1 < ch.points().size(); i++) {
                len += Math.hypot(
                        ch.points().get(i + 1).x() - ch.points().get(i).x(),
                        ch.points().get(i + 1).y() - ch.points().get(i).y());
            }
            if (len > bestLen) {
                bestLen = len;
                best = ch;
            }
        }
        assertTrue(bestLen > 0.5, "the block boundary should span most of the height");
        for (final Point2 p : best.points()) {
            assertTrue(Math.abs(p.x() - 0.5) < 0.1, "boundary chain should hug x=0.5: " + p);
        }
    }

    @Test
    void thinDarkLineYieldsSingleCenterline() {
        final DomainMask mask = maskFrom(lineArt(96, 96), 96, 96);
        final List<TextureEdges.EdgeChain> chains = TextureEdges.extract(mask, PlacementSettings.defaults());
        assertFalse(chains.isEmpty(), "a dark stroke must yield a centerline");
        // The two flanks of the stroke must NOT produce two parallel chains: check that
        // every chain point near the stroke's mid span sits close to the diagonal itself.
        int onLine = 0;
        int checked = 0;
        for (final TextureEdges.EdgeChain ch : chains) {
            for (final Point2 p : ch.points()) {
                if (p.x() < 0.3 || p.x() > 0.7) {
                    continue;
                }
                checked++;
                // Diagonal y ≈ 0.0833 + x*(0.833/0.833) = x in UV for this 96px line
                if (Math.abs(p.y() - p.x()) < 0.04) {
                    onLine++;
                }
            }
        }
        assertTrue(checked > 0, "chains should cover the stroke's middle span");
        assertTrue(
                (double) onLine / checked > 0.8,
                "chain points should sit on the stroke centreline, got " + onLine + "/" + checked);
    }

    @Test
    void uvToPixelAffineShiftsWhereTheBoundaryLands() {
        // Emulates a padded/margined cached canvas: the 192px content region sits at
        // raw-01 scale 0.75 with a +0.05 uv inset, so the colour boundary at raster x=96
        // is seen at uv = 96/256 + 0.05 = 0.425 — not the naive u = 96/256 = 0.375.
        final int w = 256;
        final int[] argb = new int[w * w];
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                if (x < 192) {
                    argb[y * w + x] = x < 96 ? 0xFFFF0000 : 0xFF0000FF;
                } else {
                    argb[y * w + x] = 0x00000000; // padding stays transparent
                }
            }
        }
        final double[] affine = {256.0, 0.0, -12.8, 0.0, 256.0, 0.0}; // px = 256u - 12.8
        final DomainMask mask = DomainMask.fromPixels(argb, w, w, affine, 0.0, 0.0, 1.0, 1.0, 256, 8);
        final List<TextureEdges.EdgeChain> chains = TextureEdges.extract(mask, PlacementSettings.defaults());
        assertFalse(chains.isEmpty(), "boundary must be extracted through the affine");
        TextureEdges.EdgeChain best = null;
        double bestLen = 0;
        for (final TextureEdges.EdgeChain ch : chains) {
            double len = 0;
            for (int i = 0; i + 1 < ch.points().size(); i++) {
                len += Math.hypot(
                        ch.points().get(i + 1).x() - ch.points().get(i).x(),
                        ch.points().get(i + 1).y() - ch.points().get(i).y());
            }
            if (len > bestLen) {
                bestLen = len;
                best = ch;
            }
        }
        for (final Point2 p : best.points()) {
            assertTrue(Math.abs(p.x() - 0.425) < 0.05, "boundary should sit at uv 0.425, got " + p);
        }
    }

    @Test
    void flatTextureProducesNoEdges() {
        final int w = 32;
        final int[] argb = new int[w * w];
        java.util.Arrays.fill(argb, 0xFF80C060);
        final DomainMask mask = maskFrom(argb, w, w);
        final List<TextureEdges.EdgeChain> chains = TextureEdges.extract(mask, PlacementSettings.defaults());
        assertTrue(chains.isEmpty(), "uniform texture must produce no feature lines");
    }

    @Test
    void plannerPlacesPointsAlongTextureEdges() throws TurboMeshPlanner.PlacementException {
        final ArtMeshGeometry src = square();
        final DomainMask mask = maskFrom(twoBlocks(64, 64), 64, 64);
        final TurboMeshPlanner.PlacementPlan plan = TurboMeshPlanner.plan(src, mask, PlacementSettings.defaults());
        // Points must exist on the central edge (x≈0.5): count vertices with |x-0.5|<0.01.
        long nearEdge = plan.geometry().uvs().stream()
                .filter(p -> Math.abs(p.x() - 0.5) < 0.01 && p.y() > 0.1 && p.y() < 0.9)
                .count();
        assertTrue(nearEdge >= 4, "the colour boundary should earn a vertical point line");
    }

    @Test
    void plannerKeepsMinimumSpacingOffRing() throws TurboMeshPlanner.PlacementException {
        // Texture with a checker-ish blob: verify no two output points sit closer than a
        // fraction of minSpacingUv apart in the interior (ring vertices exempt).
        final ArtMeshGeometry src = square();
        final int w = 96;
        final int[] argb = new int[w * w];
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                final boolean blob = (x - 48) * (x - 48) + (y - 48) * (y - 48) < 28 * 28;
                argb[y * w + x] = blob ? 0xFF203040 : 0xFFE8E0D8;
            }
        }
        final DomainMask mask = maskFrom(argb, w, w);
        final TurboMeshPlanner.PlacementPlan plan = TurboMeshPlanner.plan(src, mask, PlacementSettings.defaults());
        final List<Point2> pts = plan.geometry().uvs();
        final double minAllowed = PlacementSettings.defaults().minSpacingUv() * 0.25;
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                final double d = Math.hypot(
                        pts.get(i).x() - pts.get(j).x(),
                        pts.get(i).y() - pts.get(j).y());
                assertTrue(d > minAllowed || d == 0.0 && false, "points closer than spacing floor: " + d);
            }
        }
    }

    @Test
    void plannerPreservesSourceRingVertices() throws TurboMeshPlanner.PlacementException {
        // Every original ring vertex with a real corner must survive verbatim in the output.
        final List<Point2> ring = List.of(
                new Point2(0f, 0f),
                new Point2(1f, 0f),
                new Point2(1f, 0.6f),
                new Point2(0.5f, 1f),
                new Point2(0f, 0.6f));
        final List<Integer> idx = List.of(0, 1, 4, 1, 3, 4, 1, 2, 3);
        final ArtMeshGeometry src = new ArtMeshGeometry(ring, ring, idx);
        final DomainMask mask = maskFrom(twoBlocks(64, 64), 64, 64);
        final TurboMeshPlanner.PlacementPlan plan = TurboMeshPlanner.plan(src, mask, PlacementSettings.defaults());
        final List<Point2> out = plan.geometry().uvs();
        for (final Point2 v : ring) {
            assertTrue(
                    out.stream().anyMatch(p -> Math.hypot(p.x() - v.x(), p.y() - v.y()) < 1.0e-5),
                    "original ring vertex must be preserved: " + v);
        }
    }

    @Test
    void extractedChainsAreInsideDomain() {
        final DomainMask mask = maskFrom(twoBlocks(64, 64), 64, 64);
        for (final TextureEdges.EdgeChain ch : TextureEdges.extract(mask, PlacementSettings.defaults())) {
            for (final Point2 p : ch.points()) {
                assertTrue(mask.solidAtUv(p.x(), p.y()), "edge vertex outside alpha: " + p);
            }
        }
    }

    @Test
    void extractionIsDeterministic() {
        final DomainMask mask = maskFrom(lineArt(96, 96), 96, 96);
        final List<TextureEdges.EdgeChain> a = TextureEdges.extract(mask, PlacementSettings.defaults());
        final List<TextureEdges.EdgeChain> b = TextureEdges.extract(mask, PlacementSettings.defaults());
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).points(), b.get(i).points());
        }
    }

    @Test
    void geometryMaskYieldsNoEdges() {
        final DomainMask mask = DomainMask.rasterize(
                List.of(new Point2(0f, 0f), new Point2(1f, 0f), new Point2(1f, 1f)), List.of(0, 1, 2), 64);
        assertFalse(mask.hasTextureChannels());
        assertTrue(TextureEdges.extract(mask, PlacementSettings.defaults()).isEmpty());
    }

    @Test
    void stitchingBridgesLowContrastGap() {
        // Red|blue vertical boundary interrupted by one uniform green row: the mask drops
        // the boundary for that band, splitting the chain into two tip-to-tip fragments.
        // Stitching must rejoin them into one continuous boundary line.
        final int w = 64;
        final int h = 64;
        final int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = x < w / 2 ? 0xFFFF0000 : 0xFF0000FF;
            }
        }
        for (int x = 0; x < w; x++) {
            argb[32 * w + x] = 0xFF00AA00; // kills the boundary at row 32
        }
        final DomainMask mask = maskFrom(argb, w, h);
        final List<TextureEdges.EdgeChain> chains = TextureEdges.extract(mask, PlacementSettings.defaults());
        TextureEdges.EdgeChain best = null;
        double bestLen = 0;
        int longVerticalChains = 0;
        for (final TextureEdges.EdgeChain ch : chains) {
            double len = 0;
            double minY = 1, maxY = 0;
            for (int i = 0; i < ch.points().size(); i++) {
                minY = Math.min(minY, ch.points().get(i).y());
                maxY = Math.max(maxY, ch.points().get(i).y());
                if (i + 1 < ch.points().size()) {
                    len += Math.hypot(
                            ch.points().get(i + 1).x() - ch.points().get(i).x(),
                            ch.points().get(i + 1).y() - ch.points().get(i).y());
                }
            }
            if (len > 0.3 && maxY - minY > 0.3) {
                longVerticalChains++; // the green row's own horizontal edge doesn't count
            }
            if (len > bestLen) {
                bestLen = len;
                best = ch;
            }
        }
        assertTrue(bestLen > 0.75, "stitched boundary should span nearly the full height, got " + bestLen);
        assertEquals(1, longVerticalChains, "fragments must merge into one dominant chain");
        // The merged chain must bridge the gap row: points on both sides of y=0.5.
        double minY = 1, maxY = 0;
        for (final Point2 p : best.points()) {
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        assertTrue(minY < 0.45 && maxY > 0.55, "merged chain must cross the gap row");
    }

    @Test
    void parallelBoundariesAreNotStitched() {
        // Two parallel vertical boundaries (narrow green stripe): tips face each other
        // across a 4-cell gap but tangents are perpendicular to the connector — no merge.
        final int w = 64;
        final int h = 64;
        final int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= 31 && x <= 32) {
                    argb[y * w + x] = 0xFF00AA00;
                } else {
                    argb[y * w + x] = x < 31 ? 0xFFFF0000 : 0xFF0000FF;
                }
            }
        }
        // Crop the stripe boundaries mid-height so both have facing tips: paint uniform
        // red/blue caps at top and bottom that flatten the boundaries there.
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = 0xFFFFFFFF;
            }
        }
        for (int y = h - 8; y < h; y++) {
            for (int x = 0; x < w; x++) {
                argb[y * w + x] = 0xFFFFFFFF;
            }
        }
        final DomainMask mask = maskFrom(argb, w, h);
        final List<TextureEdges.EdgeChain> chains = TextureEdges.extract(mask, PlacementSettings.defaults());
        int onLeft = 0;
        int onRight = 0;
        int spanning = 0;
        for (final TextureEdges.EdgeChain ch : chains) {
            double minX = 1, maxX = 0, minY = 1, maxY = 0;
            for (final Point2 p : ch.points()) {
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y());
                maxY = Math.max(maxY, p.y());
            }
            if (maxY - minY > 0.3 && minX < 0.5 && maxX > 0.5) {
                spanning++; // a zig-zag merge of both stripe boundaries
            } else if (maxY - minY > 0.3 && maxX < 0.5) {
                onLeft++;
            } else if (maxY - minY > 0.3 && minX > 0.5) {
                onRight++;
            }
        }
        assertTrue(onLeft >= 1 && onRight >= 1, "both stripe boundaries should survive");
        assertEquals(0, spanning, "parallel boundaries must not merge into a zig-zag chain");
    }

    @Test
    void shortChainsAreFiltered() {
        // A lone 2-cell speck of contrast must not emit a chain.
        final int w = 32;
        final int[] argb = new int[w * w];
        java.util.Arrays.fill(argb, 0xFFFFFFFF);
        argb[10 * w + 10] = 0xFF000000;
        argb[10 * w + 11] = 0xFF000000;
        final DomainMask mask = maskFrom(argb, w, w);
        final List<TextureEdges.EdgeChain> chains = TextureEdges.extract(mask, PlacementSettings.defaults());
        final double maxLen = chains.stream()
                .mapToDouble(ch -> {
                    double l = 0;
                    for (int i = 0; i + 1 < ch.points().size(); i++) {
                        l += Math.hypot(
                                ch.points().get(i + 1).x() - ch.points().get(i).x(),
                                ch.points().get(i + 1).y() - ch.points().get(i).y());
                    }
                    return l;
                })
                .max()
                .orElse(0);
        assertTrue(maxLen < 0.15, "a speck may produce only a tiny perimeter loop, got " + maxLen);
    }
}
