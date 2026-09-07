package dev.turboism.sdk.cubism.textureatlas;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextureAtlasLayoutContractTest {

    @Test
    void currentPagePolicyIsExplicitAndRetainsCompleteAtlasConstructorCompatibility() {
        final var complete = new TextureAtlasLayoutConstraints(32, 32, 1, 2, 4, false, false);
        assertEquals(null, complete.singlePageOptions());
        final var nativePage = TextureAtlasLayoutConstraints.currentPage(32, 32, 2, true, 0);
        assertEquals(1, nativePage.maxPages());
        assertEquals(4, nativePage.itemPadding());
        assertEquals(2, nativePage.edgeMargin());
        assertEquals(0D, nativePage.singlePageOptions().requestedScale());
        assertEquals(true, nativePage.allowRotation());
        assertEquals(true, nativePage.allowScaling());
        for (double scale : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> TextureAtlasLayoutConstraints.currentPage(32, 32, 1, true, scale));
        }
        for (double scale : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> TextureAtlasLayoutPlan.currentPage(32, 32, List.of(), scale));
        }
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(32, 32, 1, 2, 2,
            true, true, new TextureAtlasSinglePageOptions(0)));
        assertEquals(1D, new TextureAtlasLayoutPlan(32, 32, 1, List.of()).scale());
        assertEquals(1D, new TextureAtlasLayoutPlan(32, 32, 1, List.of("page"), List.of()).scale());
    }

    @Test
    void planIsImmutableAndRejectsDuplicateTextureIds() {
        final TextureAtlasPlacement first = placement("texture-a", 0, 1, 1, 4, 4);
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            List.of(first)
        );

        assertEquals(List.of(first), plan.placements());
        assertThrows(UnsupportedOperationException.class, () -> plan.placements().clear());
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            List.of(first, placement("texture-a", 0, 5, 1, 2, 2))
        ));
    }

    @Test
    void planRejectsInvalidPagesOutOfBoundsPlacementsAndOverlap() {
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            0,
            List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            List.of(placement("texture-a", 1, 0, 0, 1, 1))
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            List.of(placement("texture-a", 0, 9, 0, 2, 1))
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            List.of(
                placement("texture-a", 0, 1, 1, 4, 4),
                placement("texture-b", 0, 4, 4, 2, 2)
            )
        ));
        assertThrows(NullPointerException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            null
        ));
        assertThrows(NullPointerException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            java.util.Arrays.asList(placement("texture-a", 0, 0, 0, 1, 1), null)
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutPlan(
            10,
            10,
            1,
            List.of(
                placement("a", 0, 0, 0, 1, 1),
                placement(" a ", 0, 2, 0, 1, 1)
            )
        ));
    }

    @Test
    void constraintsFailClosedForUnsupportedRotationScalingAndInvalidGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(
            0, 10, 0, 0, 1, false, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(
            10, 10, 5, 0, 1, false, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(
            10, 10, 0, 0, 1, true, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(
            10, 10, 0, 0, 1, false, true
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(
            10, 10, -1, 0, 1, false, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(
            10, 10, 0, -1, 1, false, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutConstraints(
            10, 10, 0, 0, 0, false, false
        ));
    }

    @Test
    void layoutItemsRequireStableIdsAndPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutItem(" ", 4, 4));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutItem("texture-a", 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasPlacement(
            "texture-a", -1, 0, 0, 1, 1, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasPlacement(
            "texture-a", 0, -1, 0, 1, 1, false
        ));
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasPlacement(
            "texture-a", 0, 0, 0, 0, 1, false
        ));
        final var rotated = new TextureAtlasPlacement("texture-a", 0, 0, 0, 2, 3, true);
        assertEquals(List.of(rotated), TextureAtlasLayoutPlan.currentPage(10, 10, List.of(rotated), 0.5).placements());
    }

    private static TextureAtlasPlacement placement(
        final String textureId,
        final int pageIndex,
        final int x,
        final int y,
        final int width,
        final int height
    ) {
        return new TextureAtlasPlacement(textureId, pageIndex, x, y, width, height, false);
    }
}
