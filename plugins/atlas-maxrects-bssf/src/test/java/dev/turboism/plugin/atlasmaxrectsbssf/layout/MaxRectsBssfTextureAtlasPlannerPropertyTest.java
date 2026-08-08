package dev.turboism.plugin.atlasmaxrectsbssf.layout;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxRectsBssfTextureAtlasPlannerPropertyTest {

    @Test
    void successfulBoundedPlansAreCanonicalAndRespectGeometry() {
        final MaxRectsBssfTextureAtlasPlanner planner = new MaxRectsBssfTextureAtlasPlanner();
        final Random random = new Random(0x5441544cL);
        int successfulPlans = 0;

        for (int sample = 0; sample < 512; sample++) {
            final int pageWidth = 4 + random.nextInt(29);
            final int pageHeight = 4 + random.nextInt(29);
            final int maximumMargin = (Math.min(pageWidth, pageHeight) - 1) / 2;
            final int margin = random.nextInt(Math.min(maximumMargin, 3) + 1);
            final int padding = random.nextInt(3);
            final TextureAtlasLayoutConstraints constraints = new TextureAtlasLayoutConstraints(
                pageWidth,
                pageHeight,
                margin,
                padding,
                1,
                false,
                false
            );
            final List<TextureAtlasLayoutItem> items = randomItems(random, sample);

            try {
                final TextureAtlasLayoutPlan forward = planner.plan(items, constraints);
                final List<TextureAtlasLayoutItem> reversed = new ArrayList<>(items);
                Collections.reverse(reversed);
                assertEquals(forward, planner.plan(reversed, constraints));
                assertGeometry(forward, constraints);
                successfulPlans++;
            } catch (TextureAtlasPackingException expected) {
                org.junit.jupiter.api.Assertions.assertTrue(
                    expected.reason() == TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED
                        || expected.reason() == TextureAtlasPackingException.Reason.ITEM_DOES_NOT_FIT
                );
            }
        }

        assertTrue(successfulPlans > 100, "property sample should exercise many successful plans");
    }

    private static List<TextureAtlasLayoutItem> randomItems(
        final Random random,
        final int sample
    ) {
        final int count = 1 + random.nextInt(8);
        final List<TextureAtlasLayoutItem> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            items.add(new TextureAtlasLayoutItem(
                "sample-" + sample + "-item-" + index,
                1 + random.nextInt(12),
                1 + random.nextInt(12)
            ));
        }
        Collections.shuffle(items, random);
        return List.copyOf(items);
    }

    private static void assertGeometry(
        final TextureAtlasLayoutPlan plan,
        final TextureAtlasLayoutConstraints constraints
    ) {
        final List<TextureAtlasPlacement> placements = plan.placements();
        for (int index = 0; index < placements.size(); index++) {
            final TextureAtlasPlacement placement = placements.get(index);
            assertTrue(placement.x() >= constraints.edgeMargin());
            assertTrue(placement.y() >= constraints.edgeMargin());
            assertTrue((long) placement.x() + placement.width()
                <= constraints.pageWidth() - constraints.edgeMargin());
            assertTrue((long) placement.y() + placement.height()
                <= constraints.pageHeight() - constraints.edgeMargin());
            for (int previous = 0; previous < index; previous++) {
                assertSeparated(
                    placement,
                    placements.get(previous),
                    constraints.itemPadding()
                );
            }
        }
    }

    private static void assertSeparated(
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
