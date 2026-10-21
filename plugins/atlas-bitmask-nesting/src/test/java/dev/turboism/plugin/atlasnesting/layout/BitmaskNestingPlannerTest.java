package dev.turboism.plugin.atlasnesting.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BitmaskNestingPlannerTest {
    @Test
    void rectangleShortcutPreservesShiftedOriginsIssuedQuarterTurnsAndScaleLocks() {
        final var items = new ArrayList<TextureAtlasPolygonItem>();
        for (int i = 0; i < 24; i++) {
            final String id = "rect-" + i;
            final var matrix = AffineTransform.getQuadrantRotateInstance(i % 4);
            matrix.scale(.8, .8);
            final double[] values = new double[6];
            matrix.getMatrix(values);
            items.add(new TextureAtlasPolygonItem(
                    id,
                    40,
                    60,
                    TextureAtlasOutline.of(new double[][] {{-5.3, 2.7}, {34.7, 2.7}, {34.7, 62.7}, {-5.3, 62.7}}),
                    new TextureAtlasItemLayoutPolicy(id, true, i % 3 == 0, i % 2 == 0, false),
                    TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                    values,
                    true));
        }
        final var c = constraints(256, 256, 3, TextureAtlasRotationMode.QUARTER, .6);
        final var plan = new BitmaskNestingPlanner(6).plan(items, c);
        assertEquals("exact-rectangle-bssf", plan.diagnostics().get("search"));
        assertTrue(plan.overflowTextureIds().isEmpty());
        assertValid(items, plan, c, 6);
        for (final var item : items) {
            final var p = plan.placementFor(item.textureId()).orElseThrow();
            assertEquals(item.policy().preserveScale() ? .8 : .6, p.scale(), 1e-8);
            if (item.policy().preserveAngle()) assertEquals(item.currentAngleDeg(), p.angleDeg(), 1e-8);
            assertEquals(0, Math.IEEEremainder(p.angleDeg() - item.currentAngleDeg(), 90), 1e-8);
        }
    }

    @Test
    void nestsSmallRectangleInsideConcavityDespiteOverlappingBoundingBoxes() {
        final var u = item(
                "u", new double[][] {{0, 0}, {100, 0}, {100, 100}, {78, 100}, {78, 24}, {22, 24}, {22, 100}, {0, 100}});
        final var small = item("small", new double[][] {{0, 0}, {42, 0}, {42, 62}, {0, 62}});
        final var c = constraints(108, 108, 3, TextureAtlasRotationMode.NONE, 1);
        final var plan = new BitmaskNestingPlanner().plan(List.of(u, small), c);
        assertTrue(plan.overflowTextureIds().isEmpty(), plan.toString());
        assertValid(List.of(u, small), plan, c);
        final var a = shape(u, plan.placementFor("u").orElseThrow());
        final var b = shape(small, plan.placementFor("small").orElseThrow());
        assertTrue(a.getBounds2D().intersects(b.getBounds2D()), "Must use concavity, not rectangle packing");
    }

    @Test
    void disjointRegionsLeaveUsableSpaceBetweenThem() {
        final var multi = new TextureAtlasPolygonItem(
                "multi",
                100,
                70,
                new TextureAtlasOutline(List.of(
                        new double[][] {{0, 0}, {20, 0}, {20, 70}, {0, 70}},
                        new double[][] {{80, 0}, {100, 0}, {100, 70}, {80, 70}})),
                null,
                TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                null,
                false);
        final var other = item("other", new double[][] {{0, 0}, {48, 0}, {48, 44}, {0, 44}});
        final var c = constraints(108, 78, 3, TextureAtlasRotationMode.NONE, 1);
        final var plan = new BitmaskNestingPlanner().plan(List.of(multi, other), c);
        assertTrue(plan.overflowTextureIds().isEmpty());
        assertValid(List.of(multi, other), plan, c);
    }

    @Test
    void conservativeRasterCoversSubpixelSliversAndRotatedEdges() {
        final var sliver =
                item("sliver", new double[][] {{-20.2, -5.9}, {99.3, 47.17}, {99.31, 47.19}, {-20.2, -5.89}});
        final Shape raw = OutlineRasterizer.shape(sliver.outline(), AffineTransform.getRotateInstance(0.37));
        for (final int cell : new int[] {1, 2, 4, 8}) {
            final var raster = OutlineRasterizer.raster(raw, 3, cell);
            final Area padded = padded(raw, 3 - 1e-4);
            for (int y = 0; y < raster.mask().height; y++) {
                for (int x = 0; x < raster.mask().width; x++) {
                    if (padded.intersects(
                            raster.originX() + x * cell + 1e-7,
                            raster.originY() + y * cell + 1e-7,
                            cell - 2e-7,
                            cell - 2e-7)) {
                        assertTrue(raster.mask().get(x, y), "Raster lost covered cell at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void adjacentFixedObstaclesMayShareAConservativeCellWithoutViolatingGap() {
        final var items = new ArrayList<TextureAtlasPolygonItem>();
        // A 4096 page uses 8px cells. The 6.1px gap is valid, even though
        // independently buffered masks overlap after conservative grid snapping.
        for (int i = 0; i < 2; i++) {
            final String id = "fixed-" + i;
            items.add(new TextureAtlasPolygonItem(
                    id,
                    30,
                    40,
                    TextureAtlasOutline.rect(30, 40),
                    TextureAtlasItemLayoutPolicy.excluded(id),
                    TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                    new double[] {1, 0, 0, 1, 10.3 + i * 36.1, 10.7},
                    true));
        }
        final var c = constraints(4096, 4096, 3, TextureAtlasRotationMode.NONE, 1);
        final var plan = new BitmaskNestingPlanner(6).plan(items, c);
        assertEquals(2, plan.placements().size());
        assertValid(items, plan, c, 6);
        assertThrows(IllegalArgumentException.class, () -> new BitmaskNestingPlanner(7).plan(items, c));
    }

    @Test
    void respectsLockedTransformsAndExcludedItems() {
        final var fixed = new TextureAtlasPolygonItem(
                "fixed",
                40,
                50,
                TextureAtlasOutline.rect(40, 50),
                TextureAtlasItemLayoutPolicy.excluded("fixed"),
                TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                new double[] {0, 0.8, -0.8, 0, 100.3, 25.7},
                true);
        final var locked = new TextureAtlasPolygonItem(
                "locked",
                30,
                30,
                TextureAtlasOutline.rect(30, 30),
                new TextureAtlasItemLayoutPolicy("locked", true, true, true, false),
                TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                new double[] {0.6, 0.8, -0.8, 0.6, 0, 0},
                true);
        final var off = new TextureAtlasPolygonItem(
                "off",
                10,
                10,
                TextureAtlasOutline.rect(10, 10),
                TextureAtlasItemLayoutPolicy.excluded("off"),
                TextureAtlasOutlineSource.BOUNDS_FALLBACK,
                null,
                false);
        final var items = List.of(fixed, locked, off);
        final var c = constraints(160, 160, 3, TextureAtlasRotationMode.FREE, 0.7);
        final var plan = new BitmaskNestingPlanner().plan(items, c);
        assertEquals(
                new TextureAtlasPolygonPlacement("fixed", 100.3, 25.7, 90, 0.8),
                plan.placementFor("fixed").orElseThrow());
        assertEquals(
                locked.currentAngleDeg(),
                plan.placementFor("locked").orElseThrow().angleDeg(),
                1e-8);
        assertEquals(1, plan.placementFor("locked").orElseThrow().scale(), 1e-8);
        assertTrue(plan.placementFor("off").isEmpty());
        assertFalse(plan.overflowTextureIds().contains("off"));
        assertValid(items, plan, c);
    }

    @Test
    void deterministicAndValidAcrossMixedConcaveInputsAndRotations() {
        final var items = new ArrayList<TextureAtlasPolygonItem>();
        final Random random = new Random(51);
        for (int i = 0; i < 75; i++) {
            final int w = 12 + random.nextInt(60), h = 12 + random.nextInt(80);
            items.add(item("l-" + i, new double[][] {{0, 0}, {w, 0}, {w, h * .3}, {w * .3, h * .3}, {w * .3, h}, {0, h}
            }));
        }
        for (final var mode : TextureAtlasRotationMode.values()) {
            final var c = constraints(512, 512, 4, mode, 1);
            final var planner = new BitmaskNestingPlanner();
            final var a = planner.plan(items, c);
            final var reversed = new ArrayList<>(items);
            java.util.Collections.reverse(reversed);
            final var b = planner.plan(reversed, c, true);
            assertEquals(a, b);
            assertValid(items, a, c);
            if (mode == TextureAtlasRotationMode.NONE) {
                for (final var p : a.placements()) assertEquals(0, p.angleDeg());
            }
        }
    }

    @Test
    void automaticScaleKeepsFullFitCheckpointAndFixedScaleNeverShrinks() {
        final List<TextureAtlasPolygonItem> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) items.add(item("rect-" + i, new double[][] {{0, 0}, {60, 0}, {60, 80}, {0, 80}}));
        final var planner = new BitmaskNestingPlanner();
        final var fixed = planner.plan(items, constraints(160, 160, 3, TextureAtlasRotationMode.QUARTER, 1));
        assertEquals(1, fixed.scale());
        assertFalse(fixed.overflowTextureIds().isEmpty());
        final var c = constraints(160, 160, 3, TextureAtlasRotationMode.QUARTER, 0);
        final var auto = planner.plan(items, c);
        assertTrue(auto.overflowTextureIds().isEmpty(), auto.toString());
        assertTrue(auto.scale() > 0 && auto.scale() < 1);
        assertValid(items, auto, c);
    }

    @Test
    void duplicateIdsAreRejected() {
        final var a = item("a", new double[][] {{0, 0}, {30, 0}, {0, 30}});
        assertThrows(
                IllegalArgumentException.class,
                () -> new BitmaskNestingPlanner()
                        .plan(List.of(a, a), constraints(100, 100, 2, TextureAtlasRotationMode.FREE, 1)));
    }

    static TextureAtlasPolygonItem item(final String id, final double[][] ring) {
        double w = 1, h = 1;
        for (final var p : ring) {
            w = Math.max(w, p[0]);
            h = Math.max(h, p[1]);
        }
        return new TextureAtlasPolygonItem(
                id,
                (int) Math.ceil(w),
                (int) Math.ceil(h),
                TextureAtlasOutline.of(ring),
                null,
                TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                null,
                false);
    }

    static TextureAtlasPolygonConstraints constraints(
            final int w, final int h, final int gap, final TextureAtlasRotationMode rotation, final double scale) {
        return new TextureAtlasPolygonConstraints(
                w, h, gap, rotation, scale, TextureAtlasLayoutBackend.AUTO, TextureAtlasLayoutQuality.BALANCED);
    }

    static Shape shape(final TextureAtlasPolygonItem item, final TextureAtlasPolygonPlacement p) {
        final var t = AffineTransform.getTranslateInstance(p.x(), p.y());
        t.rotate(Math.toRadians(p.angleDeg()));
        t.scale(p.scale(), p.scale());
        return OutlineRasterizer.shape(item.outline(), t);
    }

    private static Area padded(final Shape shape, final double gap) {
        final Area area = new Area(shape);
        if (gap > 0)
            area.add(new Area(new BasicStroke((float) gap, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    .createStrokedShape(shape)));
        return area;
    }

    static void assertValid(
            final List<TextureAtlasPolygonItem> items,
            final TextureAtlasPolygonPlan plan,
            final TextureAtlasPolygonConstraints c) {
        assertValid(items, plan, c, c.margin());
    }

    static void assertValid(
            final List<TextureAtlasPolygonItem> items,
            final TextureAtlasPolygonPlan plan,
            final TextureAtlasPolygonConstraints c,
            final double gap) {
        final List<Area> areas = new ArrayList<>();
        final var ids = new java.util.HashSet<String>();
        for (final var p : plan.placements()) {
            assertTrue(ids.add(p.textureId()), "duplicate placement");
            final var item = items.stream()
                    .filter(i -> i.textureId().equals(p.textureId()))
                    .findFirst()
                    .orElseThrow();
            final Shape raw = shape(item, p);
            final var bounds = raw.getBounds2D();
            assertTrue(bounds.getMinX() >= c.margin() - 1e-5 && bounds.getMinY() >= c.margin() - 1e-5);
            assertTrue(bounds.getMaxX() <= c.pageWidth() - c.margin() + 1e-5
                    && bounds.getMaxY() <= c.pageHeight() - c.margin() + 1e-5);
            final Area expanded = padded(raw, Math.max(0, gap - 1e-4));
            for (final var other : areas) {
                final Area intersection = new Area(expanded);
                intersection.intersect(other);
                assertTrue(intersection.isEmpty(), "Insufficient contour separation: " + p.textureId());
            }
            areas.add(expanded);
        }
        for (final var id : plan.overflowTextureIds()) assertTrue(ids.add(id), "duplicate overflow");
        for (final var item : items) {
            assertEquals(item.policy().participate() || item.currentlyPlaced(), ids.contains(item.textureId()));
        }
    }
}
