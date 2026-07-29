package dev.turboism.plugin.textureatlas.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartBucketTextureAtlasPlannerTest {

    @Test
    void keepsLegacySizeBucketsOnSeparateContiguousPagesDeterministically() {
        final PartBucketTextureAtlasPlanner planner = new PartBucketTextureAtlasPlanner();
        final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(100, 100, 0, 0, 3, false, false);
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("small", 25, 10),
            new TextureAtlasLayoutItem("large", 52, 10),
            new TextureAtlasLayoutItem("medium", 26, 10)
        );

        final TextureAtlasLayoutPlan forward = planner.plan(items, constraints);
        final TextureAtlasLayoutPlan reverse = planner.plan(
            List.of(items.get(2), items.get(1), items.get(0)), constraints
        );
        final Map<String, Integer> pages = forward.placements().stream().collect(
            Collectors.toMap(placement -> placement.textureId(), placement -> placement.pageIndex())
        );

        assertEquals(3, forward.pageCount());
        assertEquals(Map.of("large", 0, "medium", 1, "small", 2), pages);
        assertEquals(forward, reverse);
    }


    @Test
    void usesLegacyTargetFillToPreallocateAndBalanceBucketPages() {
        final PartBucketTextureAtlasPlanner planner = new PartBucketTextureAtlasPlanner();
        final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(100, 100, 0, 0, 2, false, false);
        final List<TextureAtlasLayoutItem> items = java.util.stream.IntStream.range(0, 16)
            .mapToObj(index -> new TextureAtlasLayoutItem("small-" + index, 24, 24))
            .toList();

        final TextureAtlasLayoutPlan plan = planner.plan(items, constraints);
        final Map<Integer, Long> perPage = plan.placements().stream().collect(
            Collectors.groupingBy(placement -> placement.pageIndex(), Collectors.counting())
        );

        assertEquals(2, plan.pageCount());
        assertEquals(Map.of(0, 8L, 1, 8L), perPage);
        final java.util.ArrayList<TextureAtlasLayoutItem> reversed = new java.util.ArrayList<>(items);
        java.util.Collections.reverse(reversed);
        assertEquals(plan, planner.plan(reversed, constraints));
    }

    @Test
    void failsTypedWhenLegacyTargetFillRequiresMorePagesThanAllowed() {
        final PartBucketTextureAtlasPlanner planner = new PartBucketTextureAtlasPlanner();
        final List<TextureAtlasLayoutItem> items = java.util.stream.IntStream.range(0, 16)
            .mapToObj(index -> new TextureAtlasLayoutItem("small-" + index, 24, 24))
            .toList();

        final TextureAtlasPackingException failure = org.junit.jupiter.api.Assertions.assertThrows(
            TextureAtlasPackingException.class,
            () -> planner.plan(
                items,
                new TextureAtlasLayoutConstraints(100, 100, 0, 0, 1, false, false)
            )
        );

        assertEquals(TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED, failure.reason());
    }


    @Test
    void classifiesExactLegacyBucketThresholds() {
        final PartBucketTextureAtlasPlanner planner = new PartBucketTextureAtlasPlanner();
        final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(100, 100, 0, 0, 6, false, false);
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("large-edge", 52, 1),
            new TextureAtlasLayoutItem("large-area", 45, 40),
            new TextureAtlasLayoutItem("medium-edge", 26, 1),
            new TextureAtlasLayoutItem("medium-area", 25, 18),
            new TextureAtlasLayoutItem("small", 25, 17)
        );

        final TextureAtlasLayoutPlan plan = planner.plan(items, constraints);
        final Map<String, Integer> pages = plan.placements().stream().collect(
            Collectors.toMap(placement -> placement.textureId(), placement -> placement.pageIndex())
        );

        assertEquals(pages.get("large-edge"), pages.get("large-area"));
        assertEquals(pages.get("medium-edge"), pages.get("medium-area"));
        assertEquals(pages.get("large-edge") + 1, pages.get("medium-edge"));
        assertEquals(pages.get("medium-edge") + 1, pages.get("small"));
    }

    @Test
    void includesDoublePaddingInLegacyTargetFillEstimate() {
        final PartBucketTextureAtlasPlanner planner = new PartBucketTextureAtlasPlanner();
        final List<TextureAtlasLayoutItem> items = java.util.stream.IntStream.range(0, 4)
            .mapToObj(index -> new TextureAtlasLayoutItem("small-" + index, 40, 40))
            .toList();

        final TextureAtlasLayoutPlan plan = planner.plan(
            items,
            new TextureAtlasLayoutConstraints(100, 100, 0, 5, 2, false, false)
        );

        assertEquals(2, plan.pageCount());
    }


    @Test
    void doesNotPreallocateEmptyPagesForOneLargeItem() {
        final TextureAtlasLayoutPlan plan = new PartBucketTextureAtlasPlanner().plan(
            List.of(new TextureAtlasLayoutItem("large", 90, 90)),
            new TextureAtlasLayoutConstraints(100, 100, 0, 0, 2, false, false)
        );

        assertEquals(1, plan.pageCount());
        assertEquals(0, plan.placements().get(0).pageIndex());
    }

    @Test
    void usesRemainingPageBudgetWhenBalancedGroupNeedsGeometricOverflow() {
        final TextureAtlasLayoutPlan plan = new PartBucketTextureAtlasPlanner().plan(
            List.of(
                new TextureAtlasLayoutItem("medium-a", 50, 50),
                new TextureAtlasLayoutItem("medium-b", 50, 50)
            ),
            new TextureAtlasLayoutConstraints(90, 60, 0, 0, 2, false, false)
        );

        assertEquals(2, plan.pageCount());
    }


    @Test
    void laterBucketCanConsumeRemainingGlobalPagesAfterEarlierBucket() {
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("large", 52, 20),
            new TextureAtlasLayoutItem("m0", 34, 35),
            new TextureAtlasLayoutItem("m1", 34, 35),
            new TextureAtlasLayoutItem("m2", 34, 35),
            new TextureAtlasLayoutItem("m3", 34, 35),
            new TextureAtlasLayoutItem("m4", 34, 35)
        );
        final PartBucketTextureAtlasPlanner planner = new PartBucketTextureAtlasPlanner();
        final TextureAtlasLayoutConstraints exactBudget =
            new TextureAtlasLayoutConstraints(100, 100, 0, 0, 6, false, false);

        final TextureAtlasLayoutPlan plan = planner.plan(items, exactBudget);
        assertEquals(3, plan.pageCount());
        assertEquals(6, plan.placements().size());
        org.junit.jupiter.api.Assertions.assertThrows(
            TextureAtlasPackingException.class,
            () -> planner.plan(
                items,
                new TextureAtlasLayoutConstraints(100, 100, 0, 0, 2, false, false)
            )
        );
    }


    @Test
    void reportsTypedOverflowForLegacyPaddedAreaArithmetic() {
        final TextureAtlasPackingException failure = org.junit.jupiter.api.Assertions.assertThrows(
            TextureAtlasPackingException.class,
            () -> new PartBucketTextureAtlasPlanner().plan(
                List.of(new TextureAtlasLayoutItem("huge", Integer.MAX_VALUE, Integer.MAX_VALUE)),
                new TextureAtlasLayoutConstraints(
                    Integer.MAX_VALUE, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 1, false, false
                )
            )
        );

        assertEquals(TextureAtlasPackingException.Reason.INVALID_RESERVED_SIZE, failure.reason());
    }
}
