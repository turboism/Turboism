package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpAtlasLayoutTest {
    static TextureAtlasLayoutSnapshot source(List<TextureAtlasLayoutItem> items, int pages) {
        return new TextureAtlasLayoutSnapshot(
                new McpAtlasDomainTest.Target(),
                "doc",
                "model",
                "atlas",
                new TextureAtlasLayoutConstraints(10, 10, 1, 1, pages, false, false),
                items,
                new TextureAtlasLayoutPlan(10, 10, 1, List.of()));
    }

    @Test
    void deterministicShelvesRespectPaddingMarginsAndMultiplePages() {
        var items = List.of(
                new TextureAtlasLayoutItem("c", 3, 3),
                new TextureAtlasLayoutItem("b", 4, 4),
                new TextureAtlasLayoutItem("a", 4, 4));
        var plan = McpAtlasLayout.shelf(source(items, 4), 400);
        assertEquals(2, plan.pageCount());
        assertEquals("a", plan.placements().get(0).textureId());
        var reversed = new ArrayList<>(items);
        Collections.reverse(reversed);
        assertEquals(plan, McpAtlasLayout.shelf(source(reversed, 4), 400));
        McpAtlasLayout.validate(source(items, 4), plan, 400);
        assertEquals(
                McpAtlasLayout.project(plan),
                McpAtlasLayout.project(McpAtlasLayout.parse(McpAtlasLayout.project(plan))));
    }

    @Test
    void emptyAtlasIsOnePageAndExactEdgeFitIsSupported() {
        assertEquals(1, McpAtlasLayout.shelf(source(List.of(), 1), 100).pageCount());
        var plan = McpAtlasLayout.shelf(source(List.of(new TextureAtlasLayoutItem("a", 8, 8)), 1), 100);
        assertEquals(1, plan.placements().get(0).x());
    }

    @Test
    void plannerRejectsUnfittingPageCountAndPixelBudget() {
        assertThrows(
                McpAtlasLayout.DoesNotFit.class,
                () -> McpAtlasLayout.shelf(source(List.of(new TextureAtlasLayoutItem("a", 9, 8)), 1), 100));
        assertThrows(
                McpAtlasLayout.DoesNotFit.class,
                () -> McpAtlasLayout.shelf(
                        source(
                                List.of(new TextureAtlasLayoutItem("a", 8, 8), new TextureAtlasLayoutItem("b", 8, 8)),
                                1),
                        400));
        assertThrows(McpAtlasLayout.Budget.class, () -> McpAtlasLayout.shelf(source(List.of(), 1), 99));
    }

    @Test
    void rejectsOmittedUnknownRotatedWrongDimensionsAndSpacing() {
        var source = source(List.of(new TextureAtlasLayoutItem("a", 3, 3), new TextureAtlasLayoutItem("b", 3, 3)), 2);
        for (var placements : List.of(
                List.<TextureAtlasPlacement>of(),
                List.of(new TextureAtlasPlacement("unknown", 0, 1, 1, 3, 3, false)),
                List.of(
                        new TextureAtlasPlacement("a", 0, 1, 1, 3, 3, true),
                        new TextureAtlasPlacement("b", 0, 5, 1, 3, 3, false)),
                List.of(
                        new TextureAtlasPlacement("a", 0, 1, 1, 2, 3, false),
                        new TextureAtlasPlacement("b", 0, 5, 1, 3, 3, false)),
                List.of(
                        new TextureAtlasPlacement("a", 0, 1, 1, 3, 3, false),
                        new TextureAtlasPlacement("b", 0, 4, 1, 3, 3, false)),
                List.of(
                        new TextureAtlasPlacement("a", 0, 0, 1, 3, 3, false),
                        new TextureAtlasPlacement("b", 0, 5, 1, 3, 3, false)))) {
            var plan = new TextureAtlasLayoutPlan(
                    10, 10, 1, List.of("page"), placements, 1, List.of(new TextureAtlasPageSize(10, 10)));
            assertThrows(IllegalArgumentException.class, () -> McpAtlasLayout.validate(source, plan, 200));
        }
    }

    @Test
    void rejectsEmptyExtraPagesIncompleteNamesAndImplicitSizes() {
        var source = source(List.of(new TextureAtlasLayoutItem("a", 3, 3)), 2);
        var placements = List.of(new TextureAtlasPlacement("a", 0, 1, 1, 3, 3, false));
        for (var plan : List.of(
                new TextureAtlasLayoutPlan(
                        10,
                        10,
                        2,
                        List.of("p", "q"),
                        placements,
                        1,
                        List.of(new TextureAtlasPageSize(10, 10), new TextureAtlasPageSize(10, 10))),
                new TextureAtlasLayoutPlan(
                        10, 10, 1, List.of(), placements, 1, List.of(new TextureAtlasPageSize(10, 10))),
                new TextureAtlasLayoutPlan(10, 10, 1, List.of("p"), placements)))
            assertThrows(IllegalArgumentException.class, () -> McpAtlasLayout.validate(source, plan, 200));
    }

    @Test
    void parserRejectsExtraFieldsFractionalAndNonfiniteNumbers() {
        var valid = McpAtlasLayout.project(McpAtlasLayout.shelf(source(List.of(), 1), 100));
        for (Object value : List.of(1.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            var map = new LinkedHashMap<>(valid);
            map.put("pageCount", value);
            assertThrows(RuntimeException.class, () -> McpAtlasLayout.parse(map));
        }
        var map = new LinkedHashMap<>(valid);
        map.put("target", "forbidden");
        assertThrows(IllegalArgumentException.class, () -> McpAtlasLayout.parse(map));
    }

    @Test
    void outlinesParticipateInSnapshotHashAndHaveAnAggregateVertexBudget() {
        var first = source(List.of(new TextureAtlasLayoutItem("a", 3, 3, TextureAtlasOutline.rect(2, 2))), 1);
        var second = source(List.of(new TextureAtlasLayoutItem("a", 3, 3, TextureAtlasOutline.rect(3, 2))), 1);
        assertNotEquals(
                McpWriteResults.digest(McpAtlasLayout.project(first)),
                McpWriteResults.digest(McpAtlasLayout.project(second)));
        var ring = new double[8193][2];
        for (int i = 0; i < ring.length; i++) ring[i] = new double[] {i, 0};
        assertThrows(
                McpAtlasLayout.Budget.class,
                () -> McpAtlasLayout.project(
                        source(List.of(new TextureAtlasLayoutItem("a", 3, 3, TextureAtlasOutline.of(ring))), 1)));
    }

    @Test
    void transientNativeInvocationTargetIsUnavailable() {
        var source = new TextureAtlasLayoutSnapshot(
                new McpAtlasDomainTest.Target(),
                "doc",
                "model",
                "atlas",
                TextureAtlasLayoutConstraints.currentPage(10, 10, 1, false, 1),
                List.of(),
                new TextureAtlasLayoutPlan(10, 10, 1, List.of()));
        assertThrows(UnsupportedOperationException.class, () -> McpAtlasLayout.project(source));
    }
}
