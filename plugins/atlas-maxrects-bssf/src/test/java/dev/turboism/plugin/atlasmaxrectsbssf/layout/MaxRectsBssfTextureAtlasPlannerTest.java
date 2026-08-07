package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxRectsBssfTextureAtlasPlannerTest {

    private static final TextureAtlasLayoutConstraints SINGLE_PAGE =
        new TextureAtlasLayoutConstraints(10, 10, 0, 0, 1, false, false);

    @Test
    void producesTheSameSinglePagePlanRegardlessOfInputOrder() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();
        final List<TextureAtlasLayoutItem> forward = List.of(
            new TextureAtlasLayoutItem("texture-b", 4, 4),
            new TextureAtlasLayoutItem("texture-a", 4, 4),
            new TextureAtlasLayoutItem("texture-c", 2, 2)
        );
        final List<TextureAtlasLayoutItem> reverse = List.of(
            forward.get(2), forward.get(1), forward.get(0)
        );

        final TextureAtlasLayoutPlan expected = new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            List.of(
                placement("texture-a", 0, 0, 4, 4),
                placement("texture-b", 4, 0, 4, 4),
                placement("texture-c", 8, 0, 2, 2)
            )
        );

        assertEquals(expected, planner.plan(forward, SINGLE_PAGE));
        assertEquals(expected, planner.plan(reverse, SINGLE_PAGE));
    }

    @Test
    void choosesAFeasibleDeterministicCandidateInsteadOfReportingAFalseFailure() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();
        final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(2, 5, 0, 0, 1, false, false);
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("a", 1, 2),
            new TextureAtlasLayoutItem("b", 1, 2),
            new TextureAtlasLayoutItem("c", 1, 3),
            new TextureAtlasLayoutItem("d", 2, 1)
        );

        final TextureAtlasLayoutPlan plan = planner.plan(items, constraints);

        assertEquals(4, plan.placements().size());
        assertEquals(plan, planner.plan(List.of(items.get(3), items.get(2), items.get(1), items.get(0)), constraints));
    }

    @Test
    void appliesEdgeMarginAndItemPaddingWithoutOverlap() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();
        final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(12, 7, 1, 1, 1, false, false);

        final TextureAtlasLayoutPlan plan = planner.plan(
            List.of(
                new TextureAtlasLayoutItem("texture-b", 4, 4),
                new TextureAtlasLayoutItem("texture-a", 4, 4)
            ),
            constraints
        );

        assertEquals(
            new TextureAtlasLayoutPlan(
                12,
                7,
                1,
                List.of(
                    placement("texture-a", 1, 1, 4, 4),
                    placement("texture-b", 6, 1, 4, 4)
                )
            ),
            plan
        );
        assertPaddingSeparation(plan.placements().get(0), plan.placements().get(1), 1);
    }

    @Test
    void acceptsAnExactEdgeFitAndAnEmptyInput() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();

        assertEquals(
            new TextureAtlasLayoutPlan(
                10,
                10,
                1,
                List.of(placement("exact", 0, 0, 10, 10))
            ),
            planner.plan(List.of(new TextureAtlasLayoutItem("exact", 10, 10)), SINGLE_PAGE)
        );
        assertEquals(
            new TextureAtlasLayoutPlan(10, 10, 1, List.of()),
            planner.plan(List.of(), SINGLE_PAGE)
        );
    }

    @Test
    void createsTheMinimumContiguousPagesWithinTheIssuedLimit() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("texture-b", 6, 6),
            new TextureAtlasLayoutItem("texture-a", 6, 6)
        );

        final TextureAtlasLayoutPlan plan = planner.plan(
            items,
            new TextureAtlasLayoutConstraints(10, 10, 0, 0, 2, false, false)
        );

        assertEquals(
            new TextureAtlasLayoutPlan(
                10,
                10,
                2,
                List.of(
                    new TextureAtlasPlacement("texture-a", 0, 0, 0, 6, 6, false),
                    new TextureAtlasPlacement("texture-b", 1, 0, 0, 6, 6, false)
                )
            ),
            plan
        );
        assertThrows(
            TextureAtlasPackingException.class,
            () -> planner.plan(items, SINGLE_PAGE)
        );
    }


    @Test
    void avoidsGreedyFalseOverflowAcrossTheIssuedPageBudget() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("i0", 5, 6),
            new TextureAtlasLayoutItem("i1", 7, 6),
            new TextureAtlasLayoutItem("i2", 2, 9),
            new TextureAtlasLayoutItem("i3", 4, 1),
            new TextureAtlasLayoutItem("i4", 1, 4),
            new TextureAtlasLayoutItem("i5", 6, 4),
            new TextureAtlasLayoutItem("i6", 9, 2),
            new TextureAtlasLayoutItem("i7", 3, 8)
        );
        final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(10, 10, 0, 0, 2, false, false);

        final TextureAtlasLayoutPlan plan = planner.plan(items, constraints);
        final java.util.ArrayList<TextureAtlasLayoutItem> reversed = new java.util.ArrayList<>(items);
        java.util.Collections.reverse(reversed);

        assertEquals(2, plan.pageCount());
        assertEquals(8, plan.placements().size());
        assertEquals(plan, planner.plan(reversed, constraints));
    }

    @Test
    void rejectsUnsupportedOrAmbiguousPlannerInputs() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();

        assertThrows(NullPointerException.class, () -> planner.plan(null, SINGLE_PAGE));
        assertThrows(NullPointerException.class, () -> planner.plan(List.of(), null));
        assertThrows(NullPointerException.class, () -> planner.plan(
            java.util.Arrays.asList(new TextureAtlasLayoutItem("a", 1, 1), null),
            SINGLE_PAGE
        ));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(
            List.of(
                new TextureAtlasLayoutItem("a", 1, 1),
                new TextureAtlasLayoutItem("a", 1, 1)
            ),
            SINGLE_PAGE
        ));
        assertEquals(
            new TextureAtlasLayoutPlan(10, 10, 1, List.of()),
            planner.plan(
                List.of(),
                new TextureAtlasLayoutConstraints(10, 10, 0, 0, 2, false, false)
            )
        );
    }

    @Test
    void failsTypedWhenCandidatesCannotPlaceAnItemOrReservedSizeOverflows() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();

        final TextureAtlasPackingException tooLarge = assertThrows(
            TextureAtlasPackingException.class,
            () -> planner.plan(
                List.of(new TextureAtlasLayoutItem("too-large", 11, 1)),
                SINGLE_PAGE
            )
        );
        assertEquals("too-large", tooLarge.textureId());
        assertEquals(TextureAtlasPackingException.Reason.ITEM_DOES_NOT_FIT, tooLarge.reason());

        final TextureAtlasPackingException overflow = assertThrows(
            TextureAtlasPackingException.class,
            () -> planner.plan(
                List.of(new TextureAtlasLayoutItem("overflow", Integer.MAX_VALUE, 1)),
                new TextureAtlasLayoutConstraints(
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    0,
                    1,
                    1,
                    false,
                    false
                )
            )
        );
        assertEquals("overflow", overflow.textureId());
        assertEquals(TextureAtlasPackingException.Reason.INVALID_RESERVED_SIZE, overflow.reason());
    }

    @Test
    void scoresMaximumGeometryCandidatesWithoutOverflow() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();
        final int maximum = Integer.MAX_VALUE;
        final TextureAtlasLayoutConstraints constraints = new TextureAtlasLayoutConstraints(
            maximum,
            maximum,
            0,
            0,
            1,
            false,
            false
        );
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("wide", maximum - 3, 2),
            new TextureAtlasLayoutItem("tall", 2, maximum - 3),
            new TextureAtlasLayoutItem("corner", 1, 1)
        );

        final TextureAtlasLayoutPlan forward = planner.plan(items, constraints);
        final TextureAtlasLayoutPlan reverse = planner.plan(
            List.of(items.get(2), items.get(1), items.get(0)),
            constraints
        );

        assertEquals(3, forward.placements().size());
        assertEquals(forward, reverse);
    }

    private static TextureAtlasPlacement placement(
        final String textureId,
        final int x,
        final int y,
        final int width,
        final int height
    ) {
        return new TextureAtlasPlacement(textureId, 0, x, y, width, height, false);
    }

    private static void assertPaddingSeparation(
        final TextureAtlasPlacement left,
        final TextureAtlasPlacement right,
        final int padding
    ) {
        assertTrue(
            (long) left.x() + left.width() + padding <= right.x()
                || (long) right.x() + right.width() + padding <= left.x()
                || (long) left.y() + left.height() + padding <= right.y()
                || (long) right.y() + right.height() + padding <= left.y()
        );
    }
}
