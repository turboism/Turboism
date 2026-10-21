package dev.turboism.plugin.atlasfolders.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.atlasfolders.layout.FolderTextureAtlasPlanner.Folder;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FolderTextureAtlasPlannerTest {
    private final FolderTextureAtlasPlanner planner =
            new FolderTextureAtlasPlanner(FolderTextureAtlasPlannerTest::pack);

    @Test
    void fixedSizeUsesTheSelectedSquareLimit() {
        var fixed = new FolderTextureAtlasPlanner(FolderTextureAtlasPlannerTest::pack, false);
        var result = fixed.plan(root(folder("small", item("a", 10))), 128, 8, 0, 0);
        assertEquals(new TextureAtlasPageSize(128, 128), result.pageSize(0));
    }

    @Test
    void selectedPlannerHandlesEveryPageAndOverflowIsRecursivelyDivided() {
        List<Integer> calls = new ArrayList<>();
        TextureAtlasLayoutPlanner selected = (items, constraints) -> {
            calls.add(items.size());
            // A current-page planner may return partial results when a folder overflows.
            return new TextureAtlasLayoutPlan(
                    constraints.pageWidth(),
                    constraints.pageHeight(),
                    1,
                    List.of(new TextureAtlasPlacement(
                            items.get(0).textureId(),
                            0,
                            0,
                            0,
                            items.get(0).width(),
                            items.get(0).height(),
                            false)));
        };
        var result = new FolderTextureAtlasPlanner(selected, false)
                .plan(root(new Folder("leaf", List.of(item("a", 40), item("b", 40)), List.of())), 128, 8, 0, 0);
        assertEquals(2, result.pageCount());
        assertTrue(calls.contains(2));
        assertTrue(calls.contains(1));
    }

    @Test
    void preservesTopLevelOrderAndSizesEachPageIndependently() {
        var plan = plan(root(folder("head", item("head", 60)), folder("body", item("body", 100))));
        assertEquals(List.of("001 head", "002 body"), plan.pageNames());
        assertEquals(List.of(new TextureAtlasPageSize(64, 64), new TextureAtlasPageSize(128, 128)), plan.pageSizes());
        assertEquals(
                List.of("head", "body"),
                plan.placements().stream().map(TextureAtlasPlacement::textureId).toList());
        assertEquals(1D, plan.scale());
    }

    @Test
    void keepsNestedFoldersTogetherWhenTheyFit() {
        var plan = plan(root(new Folder(
                "head", List.of(), List.of(folder("eyes", item("eye", 20)), folder("mouth", item("mouth", 20))))));
        assertEquals(1, plan.pageCount());
        assertEquals("001 head", plan.pageNames().get(0));
        assertEquals(2, plan.placements().size());
    }

    @Test
    void recursivelyDividesOverflowingFoldersInTreeOrder() {
        var branch = new Folder(
                "head",
                List.of(),
                List.of(
                        new Folder(
                                "eyes",
                                List.of(),
                                List.of(folder("left", item("l", 100)), folder("right", item("r", 100)))),
                        folder("mouth", item("m", 100))));
        var plan = plan(root(branch, folder("body", item("b", 60))));
        assertEquals(List.of("001 left", "002 right", "003 mouth", "004 body"), plan.pageNames());
        assertEquals(4, plan.placements().size());
    }

    @Test
    void splitsLeavesAndRetainsDirectAndUnassignedImages() {
        var tree = new Folder(
                "shared",
                List.of(item("unused", 20)),
                List.of(
                        new Folder("head", List.of(item("direct", 100)), List.of(folder("eyes", item("eye", 100)))),
                        new Folder("body", List.of(item("a", 100), item("b", 100)), List.of())));
        var plan = plan(tree);
        assertEquals(5, plan.pageCount());
        assertEquals(
                Set.of("unused", "direct", "eye", "a", "b"),
                new HashSet<>(plan.placements().stream()
                        .map(TextureAtlasPlacement::textureId)
                        .toList()));
    }

    @Test
    void packsDeterministicallyRegardlessOfImageEnumeration() {
        var a = item("a", 40);
        var b = item("b", 60);
        assertEquals(
                plan(root(new Folder("head", List.of(a, b), List.of()))),
                plan(root(new Folder("head", List.of(b, a), List.of()))));
    }

    @Test
    void enforcesBudgetAndRejectsOversizedImagesWithoutScaling() {
        assertThrows(
                IllegalArgumentException.class,
                () -> planner.plan(root(folder("a", item("a", 100)), folder("b", item("b", 100))), 128, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> plan(root(folder("a", item("a", 129)))));
    }

    @Test
    void respectsMarginAndPadding() {
        var result =
                planner.plan(root(new Folder("head", List.of(item("a", 60), item("b", 60)), List.of())), 128, 8, 3, 6);
        assertEquals(2, result.pageCount());
        for (var p : result.placements()) {
            assertTrue(p.x() >= 3 && p.y() >= 3);
            assertTrue(p.x() + p.width() + 3 <= result.pageSize(p.pageIndex()).width());
            assertEquals(60, p.width());
        }
    }

    @Test
    void rejectsDuplicateOwnershipAndSkipsEmptyFolders() {
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(root(folder("a", item("x", 10)), folder("b", item("x", 10)))));
        assertEquals(
                1,
                plan(root(new Folder("empty", List.of(), List.of()), folder("a", item("x", 10))))
                        .pageCount());
    }

    /** Algorithm fixture: stable shelf packing through the public SDK, without any algorithm plugin. */
    private static TextureAtlasLayoutPlan pack(List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints c) {
        List<TextureAtlasPlacement> placements = new ArrayList<>();
        int x = c.edgeMargin(), y = c.edgeMargin(), rowHeight = 0;
        for (var item : items.stream()
                .sorted(Comparator.comparing(TextureAtlasLayoutItem::textureId))
                .toList()) {
            if (x + item.width() + c.edgeMargin() > c.pageWidth()) {
                x = c.edgeMargin();
                y += rowHeight + c.itemPadding();
                rowHeight = 0;
            }
            if (y + item.height() + c.edgeMargin() > c.pageHeight())
                throw new TextureAtlasPackingException(
                        item.textureId(), TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED);
            placements.add(new TextureAtlasPlacement(item.textureId(), 0, x, y, item.width(), item.height(), false));
            x += item.width() + c.itemPadding();
            rowHeight = Math.max(rowHeight, item.height());
        }
        return new TextureAtlasLayoutPlan(c.pageWidth(), c.pageHeight(), 1, placements);
    }

    private TextureAtlasLayoutPlan plan(Folder root) {
        return planner.plan(root, 128, 16, 0, 0);
    }

    private static Folder root(Folder... children) {
        return new Folder("shared", List.of(), List.of(children));
    }

    private static Folder folder(String name, TextureAtlasLayoutItem item) {
        return new Folder(name, List.of(item), List.of());
    }

    private static TextureAtlasLayoutItem item(String id, int edge) {
        return new TextureAtlasLayoutItem(id, edge, edge);
    }
}
