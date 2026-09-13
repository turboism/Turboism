package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CurrentPageTextureAtlasPlannerTest {

    @Test
    void smallFullFitParallelRequestUsesSerialPreflightWithoutPartitioning() {
        var items = java.util.stream.IntStream.range(0, 16)
            .mapToObj(i -> new TextureAtlasLayoutItem("item-" + i, 48, 48)).toList();
        var c = TextureAtlasLayoutConstraints.currentPage(512, 512, 3, true, 1);
        var planner = new CurrentPageTextureAtlasPlanner();
        assertEquals(planner.plan(items, c, false), planner.plan(items, c, true));
        assertEquals(16, planner.plan(items, c, true).placements().size());
    }

    @Test
    void raisingPartitionThresholdMustNotDiscardUseful32ItemRegionalCandidates() {
        var random = new java.util.Random(83);
        var items = new java.util.ArrayList<TextureAtlasLayoutItem>();
        long area = 0;
        for (int i = 0; i < 32; i++) {
            int w = 16 + random.nextInt(81), h = 16 + random.nextInt(81);
            area += (long) w * h;
            items.add(new TextureAtlasLayoutItem(String.format(java.util.Locale.ROOT, "item-%05d", i), w, h));
        }
        int side = (int) Math.ceil(Math.sqrt(area / 1.15)) + 6;
        var c = TextureAtlasLayoutConstraints.currentPage(side, side, 3, true, 1);
        var planner = new CurrentPageTextureAtlasPlanner();
        assertEquals(19, planner.plan(items, c, false).placements().size());
        var regional = planner.plan(items, c, true);
        assertEquals(21, regional.placements().size());
        assertEquals(1D, regional.scale()); // Fixed scale must not shrink to hide overflow.
    }

    @Test
    void paddingReservationMayExceedIntegerRangeWithoutOverflowingContentCoordinates() {
        final var constraints = new TextureAtlasLayoutConstraints(Integer.MAX_VALUE, Integer.MAX_VALUE,
            0, 4, 1, false, false, new TextureAtlasSinglePageOptions(1));
        final var plan = new CurrentPageTextureAtlasPlanner().plan(List.of(
            new TextureAtlasLayoutItem("wide", Integer.MAX_VALUE, 1),
            new TextureAtlasLayoutItem("small", 1, 1)), constraints, false);
        assertEquals(2, plan.placements().size());
        final var wide = plan.placements().stream().filter(p -> p.textureId().equals("wide")).findFirst().orElseThrow();
        final var small = plan.placements().stream().filter(p -> p.textureId().equals("small")).findFirst().orElseThrow();
        assertEquals(0, wide.x());
        assertEquals(Integer.MAX_VALUE, wide.width());
        assertEquals(5, small.y());
        final var hugeGap = new TextureAtlasLayoutConstraints(Integer.MAX_VALUE, Integer.MAX_VALUE,
            0, Integer.MAX_VALUE, 1, false, false, new TextureAtlasSinglePageOptions(1));
        assertEquals(1, new CurrentPageTextureAtlasPlanner().plan(List.of(
            new TextureAtlasLayoutItem("only", 1, 1)), hugeGap, false).placements().size());
    }

    @Test
    void feasibilityPruningPreservesDenseAutomaticScaleAndAllInputs() {
        final var random = new java.util.Random(51);
        final var items = new java.util.ArrayList<TextureAtlasLayoutItem>();
        for (int i = 0; i < 500; i++) {
            items.add(new TextureAtlasLayoutItem(String.format(java.util.Locale.ROOT, "item-%05d", i),
                16 + random.nextInt(81), 16 + random.nextInt(81)));
        }
        final var constraints = TextureAtlasLayoutConstraints.currentPage(512, 512, 1, true, 0);
        final var planner = new CurrentPageTextureAtlasPlanner();
        final var serial = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
            () -> planner.plan(items, constraints, false));
        final var parallel = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
            () -> planner.plan(items, constraints, true));
        assertEquals(500, serial.placements().size());
        assertEquals(500, parallel.placements().size());
        // Captured before feasibility pruning: do not buy speed with a smaller result.
        assertEquals(0.347943977, serial.scale(), 1e-9);
        assertEquals(0.351092791, parallel.scale(), 1e-9);
        assertEquals(1, parallel.pageCount());
    }

    @Test
    void variedCurrentPagePlansPreserveGeometryPaddingAndInputOrderIndependence() {
        final var random = new java.util.Random(1729);
        final var planner = new CurrentPageTextureAtlasPlanner();
        for (int trial = 0; trial < 80; trial++) {
            final var items = new java.util.ArrayList<TextureAtlasLayoutItem>();
            for (int i = 0, count = 1 + random.nextInt(45); i < count; i++) {
                items.add(new TextureAtlasLayoutItem("image-" + i, 1 + random.nextInt(120), 1 + random.nextInt(120)));
            }
            final var c = TextureAtlasLayoutConstraints.currentPage(64 + random.nextInt(96), 64 + random.nextInt(96),
                1 + random.nextInt(3), random.nextBoolean(), trial % 3 == 0 ? 0 : 0.5 + random.nextInt(3) * 0.5);
            final boolean parallel = random.nextBoolean();
            final var result = planner.plan(items, c, parallel);
            assertEquals(1, result.pageCount());
            final var byId = items.stream().collect(java.util.stream.Collectors.toMap(TextureAtlasLayoutItem::textureId, i -> i));
            for (var placed : result.placements()) {
                final var source = byId.get(placed.textureId());
                assertNotNull(source);
                assertEquals(Math.ceil((placed.rotated() ? source.height() : source.width()) * result.scale()), placed.width());
                assertEquals(Math.ceil((placed.rotated() ? source.width() : source.height()) * result.scale()), placed.height());
                assertTrue(!placed.rotated() || c.allowRotation());
                assertTrue(placed.x() >= c.edgeMargin() && placed.y() >= c.edgeMargin());
                assertTrue(placed.x() + placed.width() <= c.pageWidth() - c.edgeMargin());
                assertTrue(placed.y() + placed.height() <= c.pageHeight() - c.edgeMargin());
                for (var other : result.placements()) {
                    if (placed == other) continue;
                    assertTrue(placed.x() + placed.width() + c.itemPadding() <= other.x()
                        || other.x() + other.width() + c.itemPadding() <= placed.x()
                        || placed.y() + placed.height() + c.itemPadding() <= other.y()
                        || other.y() + other.height() + c.itemPadding() <= placed.y());
                }
            }
            java.util.Collections.shuffle(items, random);
            assertEquals(result, planner.plan(items, c, parallel));
        }
    }

    @Test
    void denseFixedScaleInputReturnsOverflowWithoutSearchingSubsequentPages() {
        final var random = new java.util.Random(51);
        final var items = java.util.stream.IntStream.range(0, 500).mapToObj(i ->
            new TextureAtlasLayoutItem("image-" + i, 16 + random.nextInt(81), 16 + random.nextInt(81))).toList();
        final var result = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
            new CurrentPageTextureAtlasPlanner().plan(items, TextureAtlasLayoutConstraints.currentPage(512, 512, 1, false, 1), false));
        assertEquals(1, result.pageCount());
        assertEquals(1D, result.scale());
        assertTrue(result.placements().size() > 0 && result.placements().size() < items.size());
    }

    @Test
    void automaticScaleUsesAnAreaBoundRatherThanAnAbsoluteMinimumScale() {
        final var planner = new CurrentPageTextureAtlasPlanner();
        final var plan = planner.plan(List.of(new TextureAtlasLayoutItem("huge", 1_000_000, 1_000_000)),
            TextureAtlasLayoutConstraints.currentPage(10, 10, 0, false, 0), false);
        assertEquals(1, plan.placements().size());
        assertTrue(plan.scale() > 0 && plan.scale() <= 0.00001);
        assertEquals(10, plan.placements().get(0).width());
    }

    @Test
    void regionParallelPackingIsDeterministicAndSpansRegionsWithinOnePage() {
        final var planner = new CurrentPageTextureAtlasPlanner();
        final var items = java.util.stream.IntStream.range(0, 32)
            .mapToObj(i -> new TextureAtlasLayoutItem(String.format("image-%02d", i), 4, 4)).toList();
        final var constraints = TextureAtlasLayoutConstraints.currentPage(64, 64, 1, false, 1);
        final var serial = planner.plan(items, constraints, false);
        final var parallel = planner.plan(items, constraints, true);
        assertEquals(1, parallel.pageCount());
        assertEquals(items.size(), parallel.placements().size());
        assertNotEquals(serial, parallel, "parallel mode partitions the page, not merely the sort orders");
        for (int i = 0; i < 4; i++) assertEquals(parallel, planner.plan(items, constraints, true));
        assertTrue(parallel.placements().stream().anyMatch(p -> p.x() >= 32 && p.y() >= 32));
        assertEquals(1D, parallel.scale());
    }

    @Test
    void automaticScaleFitsTheIssuedImagesIntoThisPageWithoutAddingPages() {
        final var planner = new CurrentPageTextureAtlasPlanner();
        final var items = List.of(new TextureAtlasLayoutItem("large", 20, 20));
        final var plan = planner.plan(items, TextureAtlasLayoutConstraints.currentPage(10, 10, 0, false, 0), false);
        assertEquals(1, plan.pageCount());
        assertEquals(1, plan.placements().size());
        assertEquals(0.5, plan.scale(), 1.0 / 256);
        assertTrue(plan.placements().get(0).width() <= 10);
        final var small = planner.plan(List.of(new TextureAtlasLayoutItem("small", 2, 2)),
            TextureAtlasLayoutConstraints.currentPage(10, 10, 0, false, 0), false);
        assertEquals(1D, small.scale(), "automatic scale must not enlarge images");
    }
    @Test
    void fixedScaleAndRotationRespectTheCurrentPageSettings() {
        final var planner = new CurrentPageTextureAtlasPlanner();
        final var items = List.of(new TextureAtlasLayoutItem("image", 12, 8));
        final var constraints = TextureAtlasLayoutConstraints.currentPage(6, 8, 1, true, 0.5);
        final var plan = planner.plan(items, constraints, false);
        assertEquals(1, plan.pageCount());
        assertEquals(0.5, plan.scale());
        assertEquals(List.of(new TextureAtlasPlacement("image", 0, 1, 1, 4, 6, true)), plan.placements());
        final var noRotation = planner.plan(items,
            TextureAtlasLayoutConstraints.currentPage(6, 8, 1, false, 0.5), false);
        assertTrue(noRotation.placements().isEmpty());
        assertEquals(0.5, noRotation.scale());
    }
}
