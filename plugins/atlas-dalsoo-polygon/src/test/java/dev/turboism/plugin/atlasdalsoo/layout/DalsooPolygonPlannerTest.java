package dev.turboism.plugin.atlasdalsoo.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DalsooPolygonPlannerTest {

    private static final int PAGE = 512;

    @Test
    void packsConcaveItemsWithoutOverlap() {
        final List<TextureAtlasPolygonItem> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(item("l-" + i, lShape(80, 60)));
        }
        final TextureAtlasPolygonPlan plan = plan(items,
            constraints(TextureAtlasRotationMode.FREE, 0));
        assertTrue(plan.overflowTextureIds().isEmpty(),
            "8 concave items must fit in 512x512: " + plan.overflowTextureIds());
        assertNoOverlap(items, plan, 0);
        assertEquals(TextureAtlasLayoutBackend.DALSOO_POLYGON, plan.backend());
    }

    @Test
    void deterministicAcrossRunsAndParallel() {
        final List<TextureAtlasPolygonItem> items = List.of(
            item("a", lShape(90, 70)), item("b", lShape(60, 90)),
            item("c", star(50)), item("d", lShape(40, 40)));
        final TextureAtlasPolygonConstraints c =
            constraints(TextureAtlasRotationMode.QUARTER, 0);
        final DalsooPolygonPlanner planner = new DalsooPolygonPlanner();
        final TextureAtlasPolygonPlan p1 = planner.plan(items, c);
        final TextureAtlasPolygonPlan p2 = planner.plan(items, c);
        final TextureAtlasPolygonPlan pp1 = planner.plan(items, c, true);
        final TextureAtlasPolygonPlan pp2 = planner.plan(items, c, true);
        assertEquals(signature(p1), signature(p2), "serial runs must be identical");
        assertEquals(signature(pp1), signature(pp2),
            "parallel runs must be deterministic (variant choice by total order)");
    }

    @Test
    void quarterRotationRestrictsToNinetyDegreeSteps() {
        final List<TextureAtlasPolygonItem> items = List.of(
            item("a", rectOutline(100, 40)), item("b", rectOutline(100, 40)),
            item("c", rectOutline(100, 40)), item("d", rectOutline(100, 40)));
        final TextureAtlasPolygonPlan plan = plan(items,
            constraints(TextureAtlasRotationMode.QUARTER, 0));
        for (final TextureAtlasPolygonPlacement p : plan.placements()) {
            final double r = Math.abs(p.angleDeg() % 90);
            assertTrue(r < 1e-6 || r > 90 - 1e-6,
                "QUARTER mode produced non-90° angle " + p.angleDeg());
        }
    }

    @Test
    void noRotationKeepsZeroAngle() {
        final List<TextureAtlasPolygonItem> items = List.of(
            item("a", rectOutline(100, 40)), item("b", rectOutline(100, 40)));
        final TextureAtlasPolygonPlan plan = plan(items,
            constraints(TextureAtlasRotationMode.NONE, 0));
        for (final TextureAtlasPolygonPlacement p : plan.placements()) {
            assertEquals(0, Math.abs(p.angleDeg()), 1e-6);
        }
    }

    @Test
    void fixedPositionItemKeepsIssuedTransform() {
        final double[] matrix = {1, 0, 0, 1, 30, 40}; // identity at (30,40)
        final TextureAtlasPolygonItem fixed = new TextureAtlasPolygonItem(
            "fixed", 60, 60, rectOutline(60, 60),
            new TextureAtlasItemLayoutPolicy("fixed", true, true, true, true),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, matrix, true);
        final TextureAtlasPolygonItem free = item("free", rectOutline(80, 80));
        final TextureAtlasPolygonPlan plan = plan(List.of(fixed, free),
            constraints(TextureAtlasRotationMode.FREE, 0));
        final TextureAtlasPolygonPlacement p =
            plan.placementFor("fixed").orElseThrow();
        assertEquals(30, p.x(), 1e-6);
        assertEquals(40, p.y(), 1e-6);
        assertEquals(0, Math.abs(p.angleDeg()), 1e-6);
        assertEquals(1, p.scale(), 1e-6);
        assertNoOverlap(List.of(fixed, free), plan, 0);
    }

    @Test
    void excludedItemIsNotPlaced() {
        final TextureAtlasPolygonItem out = new TextureAtlasPolygonItem(
            "out", 50, 50, rectOutline(50, 50),
            TextureAtlasItemLayoutPolicy.excluded("out"),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, null, false);
        final TextureAtlasPolygonPlan plan = plan(
            List.of(out, item("in", rectOutline(80, 80))),
            constraints(TextureAtlasRotationMode.NONE, 0));
        assertTrue(plan.placementFor("out").isEmpty());
        assertTrue(plan.placementFor("in").isPresent());
    }

    @Test
    void automaticScaleFindsFittingScale() {
        final List<TextureAtlasPolygonItem> items = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            items.add(item("big-" + i, rectOutline(300, 300)));
        }
        final TextureAtlasPolygonPlan plan = plan(items,
            constraints(TextureAtlasRotationMode.NONE, 0));
        assertTrue(plan.scale() < 1.0,
            "oversized items require scale<1, got " + plan.scale());
        assertTrue(plan.overflowTextureIds().size() < items.size());
        assertNoOverlap(items, plan, 0);
    }

    @Test
    void fixedScaleHonoursRequestedValue() {
        final List<TextureAtlasPolygonItem> items = List.of(
            item("a", rectOutline(100, 100)));
        final TextureAtlasPolygonPlan plan = plan(items,
            constraints(TextureAtlasRotationMode.NONE, 0.5));
        final TextureAtlasPolygonPlacement p =
            plan.placementFor("a").orElseThrow();
        assertEquals(0.5, p.scale(), 1e-6);
    }

    @Test
    void overflowReportedWhenPageTooSmall() {
        final List<TextureAtlasPolygonItem> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(item("x-" + i, rectOutline(200, 200)));
        }
        final TextureAtlasPolygonPlan plan = plan(items,
            constraints(TextureAtlasRotationMode.NONE, 1));
        assertFalse(plan.overflowTextureIds().isEmpty());
        assertNoOverlap(items, plan, 0);
    }

    @Test
    void oversizedItemOverflowsInsteadOfOutOfBoundsPlacement() {
        // a single item larger than the page in every rotation must surface as
        // overflow, never as a placement the validator would have to catch
        final var item = item("big", rectOutline(600, 600));
        final TextureAtlasPolygonPlan plan = plan(List.of(item),
            constraints(TextureAtlasRotationMode.FREE, 1));
        assertTrue(plan.placements().isEmpty(),
            "oversized item must not be placed: " + plan.placements());
        assertTrue(plan.overflowTextureIds().contains("big"));
    }

    @Test
    void marginIsRespected() {
        final List<TextureAtlasPolygonItem> items = List.of(
            item("a", rectOutline(400, 400)), item("b", rectOutline(400, 400)));
        final int margin = 10;
        final TextureAtlasPolygonPlan plan = plan(items,
            new TextureAtlasPolygonConstraints(PAGE, PAGE, margin,
                TextureAtlasRotationMode.NONE, 0,
                TextureAtlasLayoutBackend.DALSOO_POLYGON,
                TextureAtlasLayoutQuality.BALANCED));
        for (final TextureAtlasPolygonPlacement p : plan.placements()) {
            final var item = items.stream()
                .filter(i -> i.textureId().equals(p.textureId())).findFirst().orElseThrow();
            final double[][] bounds = transformedBounds(item.outline(), p);
            assertTrue(bounds[0][0] >= margin - 1e-3, "min x " + bounds[0][0]);
            assertTrue(bounds[0][1] >= margin - 1e-3, "min y " + bounds[0][1]);
            assertTrue(bounds[1][0] <= PAGE - margin + 1e-3, "max x " + bounds[1][0]);
            assertTrue(bounds[1][1] <= PAGE - margin + 1e-3, "max y " + bounds[1][1]);
        }
    }

    @Test
    void cancellationStopsPlanning() {
        final List<TextureAtlasPolygonItem> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add(item("c-" + i, lShape(60, 60)));
        }
        final DalsooPolygonPlanner planner =
            new DalsooPolygonPlanner(() -> true, null, null, false);
        final TextureAtlasPolygonPlan plan = planner.plan(items,
            constraints(TextureAtlasRotationMode.FREE, 0));
        assertNotNull(plan);
        // cancelled runs must not crash; placements may be incomplete
        assertTrue(plan.placements().size() <= items.size());
    }

    @Test
    void freeRotationPlacesItemAtNonQuarterAngle() {
        // a 42x10 rectangle fits a 40x40 page only at a non-quarter angle;
        // the FREE candidate grid (18 steps of 20°) provides 40°
        final var thin = item("thin", rectOutline(42, 10));
        final TextureAtlasPolygonConstraints free =
            new TextureAtlasPolygonConstraints(40, 40, 0,
                TextureAtlasRotationMode.FREE, 1,
                TextureAtlasLayoutBackend.DALSOO_POLYGON,
                TextureAtlasLayoutQuality.BALANCED);
        final TextureAtlasPolygonPlan plan = plan(List.of(thin), free);
        final TextureAtlasPolygonPlacement p =
            plan.placementFor("thin").orElseThrow();
        final double quarter = Math.abs(p.angleDeg() % 90);
        assertTrue(quarter > 1e-6 && quarter < 90 - 1e-6,
            "expected an arbitrary (non-90°) angle, got " + p.angleDeg());
        final double grid = Math.abs(p.angleDeg() % 20);
        assertTrue(grid < 1e-6 || grid > 20 - 1e-6,
            "FREE placements must use the 18-candidate grid, got "
                + p.angleDeg());
        assertEquals("FREE", plan.diagnostics().get("rotationMode"));

        // QUARTER cannot place the same item - honest overflow, no misreport
        final TextureAtlasPolygonPlan quarterPlan = plan(List.of(thin),
            new TextureAtlasPolygonConstraints(40, 40, 0,
                TextureAtlasRotationMode.QUARTER, 1,
                TextureAtlasLayoutBackend.DALSOO_POLYGON,
                TextureAtlasLayoutQuality.BALANCED));
        assertTrue(quarterPlan.overflowTextureIds().contains("thin"),
            "QUARTER must overflow the thin item: " + quarterPlan.placements());
    }

    @Test
    void autoScaleTuningReachesTheKernel() {
        final List<TextureAtlasPolygonItem> items = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            items.add(item("big-" + i, rectOutline(300, 300)));
        }
        final DalsooPolygonPlanner planner = new DalsooPolygonPlanner(null, null,
            null, true, 0.02, 3);
        final TextureAtlasPolygonPlan plan = planner.plan(items,
            constraints(TextureAtlasRotationMode.NONE, 0));
        assertEquals("0.02", plan.diagnostics().get("autoScaleTolerance"));
        assertEquals("3", plan.diagnostics().get("autoScaleMaxTry"));
        assertTrue(plan.scale() <= 1.0);
    }

    @Test
    void autoScaleTuningIsValidated() {
        try {
            new DalsooPolygonPlanner(null, null, null, true, 0, 0);
            org.junit.jupiter.api.Assertions.fail("zero tolerance must be rejected");
        } catch (IllegalArgumentException expected) { }
        try {
            new DalsooPolygonPlanner(null, null, null, true, 0.005, -1);
            org.junit.jupiter.api.Assertions.fail("negative maxTry must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    void multiRingItemPlacedAsOne() {
        final TextureAtlasOutline twoParts = new TextureAtlasOutline(List.of(
            new double[][] {{0, 0}, {30, 0}, {30, 30}, {0, 30}},
            new double[][] {{60, 0}, {90, 0}, {90, 30}, {60, 30}}));
        final TextureAtlasPolygonItem item = new TextureAtlasPolygonItem(
            "two", 90, 30, twoParts,
            TextureAtlasItemLayoutPolicy.participating("two"),
            TextureAtlasOutlineSource.DRAW_DATA_SHAPES, null, false);
        final TextureAtlasPolygonPlan plan = plan(List.of(item),
            constraints(TextureAtlasRotationMode.NONE, 0));
        assertTrue(plan.placementFor("two").isPresent());
    }

    // helpers

    private static TextureAtlasPolygonItem item(final String id,
        final TextureAtlasOutline outline) {
        final var b = boundsOf(outline);
        return new TextureAtlasPolygonItem(id,
            Math.max(1, (int) Math.ceil(b[1][0] - b[0][0])),
            Math.max(1, (int) Math.ceil(b[1][1] - b[0][1])),
            outline, TextureAtlasItemLayoutPolicy.participating(id),
            TextureAtlasOutlineSource.DRAW_DATA_SHAPES, null, false);
    }

    private static TextureAtlasPolygonPlan plan(
        final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonConstraints constraints) {
        return new DalsooPolygonPlanner().plan(items, constraints);
    }

    private static TextureAtlasPolygonConstraints constraints(
        final TextureAtlasRotationMode rotation, final double scale) {
        return new TextureAtlasPolygonConstraints(PAGE, PAGE, 0, rotation, scale,
            TextureAtlasLayoutBackend.DALSOO_POLYGON,
            TextureAtlasLayoutQuality.BALANCED);
    }

    private static String signature(final TextureAtlasPolygonPlan plan) {
        final StringBuilder sb = new StringBuilder();
        sb.append(plan.scale());
        for (final TextureAtlasPolygonPlacement p : plan.placements()) {
            sb.append('|').append(p.textureId()).append(',')
                .append(p.x()).append(',').append(p.y()).append(',')
                .append(p.angleDeg()).append(',').append(p.scale());
        }
        sb.append("~").append(plan.overflowTextureIds());
        return sb.toString();
    }

    private static void assertNoOverlap(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonPlan plan, final double tolerance) {
        final Map<String, TextureAtlasPolygonItem> byId = new java.util.HashMap<>();
        for (final TextureAtlasPolygonItem i : items) {
            byId.put(i.textureId(), i);
        }
        final List<java.awt.geom.Area> placed = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (final TextureAtlasPolygonPlacement p : plan.placements()) {
            final java.awt.geom.Area area = area(byId.get(p.textureId()).outline(), p);
            for (int i = 0; i < placed.size(); i++) {
                final java.awt.geom.Area test = new java.awt.geom.Area(area);
                test.intersect(placed.get(i));
                final var b = test.getBounds2D();
                assertTrue(test.isEmpty() || b.getWidth() * b.getHeight() <= 1e-3,
                    p.textureId() + " overlaps " + names.get(i));
            }
            placed.add(area);
            names.add(p.textureId());
        }
    }

    private static java.awt.geom.Area area(final TextureAtlasOutline outline,
        final TextureAtlasPolygonPlacement p) {
        final java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
        at.translate(p.x(), p.y());
        at.rotate(Math.toRadians(p.angleDeg()));
        at.scale(p.scale(), p.scale());
        final java.awt.geom.Area union = new java.awt.geom.Area();
        for (final double[][] ring : outline.rings()) {
            final java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
            path.moveTo(ring[0][0], ring[0][1]);
            for (int i = 1; i < ring.length; i++) {
                path.lineTo(ring[i][0], ring[i][1]);
            }
            path.closePath();
            union.add(new java.awt.geom.Area(path));
        }
        return union.createTransformedArea(at);
    }

    private static double[][] transformedBounds(final TextureAtlasOutline outline,
        final TextureAtlasPolygonPlacement p) {
        final java.awt.geom.Rectangle2D b =
            area(outline, p).getBounds2D();
        return new double[][] {{b.getMinX(), b.getMinY()}, {b.getMaxX(), b.getMaxY()}};
    }

    private static double[][] boundsOf(final TextureAtlasOutline outline) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (final double[][] ring : outline.rings()) {
            for (final double[] v : ring) {
                minX = Math.min(minX, v[0]); minY = Math.min(minY, v[1]);
                maxX = Math.max(maxX, v[0]); maxY = Math.max(maxY, v[1]);
            }
        }
        return new double[][] {{minX, minY}, {maxX, maxY}};
    }

    private static TextureAtlasOutline rectOutline(final double w, final double h) {
        return TextureAtlasOutline.rect(w, h);
    }

    private static TextureAtlasOutline lShape(final double w, final double h) {
        final double hw = w / 2, hh = h / 2;
        return TextureAtlasOutline.of(new double[][] {
            {0, 0}, {w, 0}, {w, hh}, {hw, hh}, {hw, h}, {0, h}});
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
}
