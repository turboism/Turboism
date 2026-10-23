package dev.turboism.sdk.cubism.textureatlas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextureAtlasCompletePolygonContractTest {
    @Test
    void outlinesCompareByContentAndCannotMutateIssuedGeometry() {
        var outline = TextureAtlasOutline.rect(10, 20);
        var copy = TextureAtlasOutline.rect(10, 20);
        assertEquals(outline, copy);
        assertEquals(outline.hashCode(), copy.hashCode());
        outline.rings().get(0)[0][0] = 99;
        assertEquals(copy, outline);
        assertNull(new TextureAtlasLayoutItem("old-client", 10, 20).outline());
    }

    @Test
    void completePolygonPagesRejectDuplicateImagesAndMixedRectangleResults() {
        var page = new TextureAtlasPolygonPlan(
                32,
                32,
                1,
                List.of(new TextureAtlasPolygonPlacement("image", 1, 1, 0, 1)),
                List.of(),
                TextureAtlasLayoutBackend.AUTO,
                Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextureAtlasLayoutPlan(32, 32, 2, List.of(), List.of(), 1, List.of(), List.of(page, page)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TextureAtlasLayoutPlan(
                        32,
                        32,
                        1,
                        List.of(),
                        List.of(new TextureAtlasPlacement("rect", 0, 1, 1, 2, 2, false)),
                        1,
                        List.of(),
                        List.of(page)));
    }
}
