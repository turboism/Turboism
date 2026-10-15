package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelTextureAtlasPlannerTest {

    @Test
    void singleSmallImagePacksIntoSmallestPoT() {
        var items = List.of(new TextureAtlasLayoutItem("img1", 120, 60));
        var plan = ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT);

        assertEquals(256, plan.pageWidth());
        assertEquals(256, plan.pageHeight());
        assertEquals(1, plan.pageCount());
        assertEquals(1, plan.placements().size());
        assertEquals(1.0, plan.scale());

        var p = plan.placements().get(0);
        assertEquals("img1", p.textureId());
        assertEquals(0, p.pageIndex());
        assertEquals(120, p.width());
        assertEquals(60, p.height());
        assertFalse(p.rotated());
        assertTrue(p.x() >= 2);
        assertTrue(p.y() >= 2);
        assertTrue(p.x() + p.width() <= 256 - 2);
        assertTrue(p.y() + p.height() <= 256 - 2);
    }

    @Test
    void standardImageSizesPreserveSourceResolution() {
        var items = List.of(new TextureAtlasLayoutItem("tex256", 256, 128));
        var plan = ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT);

        assertEquals(512, plan.pageWidth());
        assertEquals(512, plan.pageHeight());
        assertEquals(1, plan.pageCount());
        assertEquals(1.0, plan.scale());
        var p = plan.placements().get(0);
        assertEquals(256, p.width());
        assertEquals(128, p.height());

        var items2 = List.of(new TextureAtlasLayoutItem("tex512", 512, 256));
        var plan2 = ModelTextureAtlasPlanner.plan(items2, ModelTextureAtlasPlanner.Options.DEFAULT);
        assertEquals(1024, plan2.pageWidth());
        assertEquals(1024, plan2.pageHeight());
        assertEquals(1, plan2.pageCount());
        assertEquals(512, plan2.placements().get(0).width());
        assertEquals(256, plan2.placements().get(0).height());
    }

    @Test
    void duplicateImagesAreDeduplicatedCountedOnce() {
        var items = List.of(
                new TextureAtlasLayoutItem("shared_tex", 256, 128), new TextureAtlasLayoutItem("shared_tex", 256, 128));
        var plan = ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT);

        assertEquals(1, plan.placements().size());
        assertEquals("shared_tex", plan.placements().get(0).textureId());
    }

    @Test
    void duplicateImagesWithDifferentDimensionsAreRejected() {
        var items = List.of(
                new TextureAtlasLayoutItem("conflict_id", 256, 128),
                new TextureAtlasLayoutItem("conflict_id", 512, 512));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT));
    }

    @Test
    void multipleImagesPackDeterministicallyWithoutOverlap() {
        var items = List.of(
                new TextureAtlasLayoutItem("img_a", 256, 128),
                new TextureAtlasLayoutItem("img_b", 200, 200),
                new TextureAtlasLayoutItem("img_c", 100, 150),
                new TextureAtlasLayoutItem("img_d", 300, 100));
        var plan = ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT);

        assertEquals(1, plan.pageCount());
        assertEquals(4, plan.placements().size());

        for (int i = 0; i < plan.placements().size(); i++) {
            var a = plan.placements().get(i);
            assertTrue(a.x() >= 2 && a.y() >= 2);
            assertTrue(a.x() + a.width() <= plan.pageWidth() - 2);
            assertTrue(a.y() + a.height() <= plan.pageHeight() - 2);

            for (int j = i + 1; j < plan.placements().size(); j++) {
                var b = plan.placements().get(j);
                if (a.pageIndex() == b.pageIndex()) {
                    boolean overlap = a.x() < b.x() + b.width() + 2
                            && a.x() + a.width() + 2 > b.x()
                            && a.y() < b.y() + b.height() + 2
                            && a.y() + a.height() + 2 > b.y();
                    assertFalse(overlap, "Placements " + a.textureId() + " and " + b.textureId() + " must not overlap");
                }
            }
        }
    }

    @Test
    void multiPageSplitWhenExceedingSinglePageCapacity() {
        var items = new ArrayList<TextureAtlasLayoutItem>();
        for (int i = 0; i < 4; i++) {
            items.add(new TextureAtlasLayoutItem("large_" + i, 1500, 1500));
        }
        var options = new ModelTextureAtlasPlanner.Options(2048, 16, 100_000_000L, 2, 2);
        var plan = ModelTextureAtlasPlanner.plan(items, options);

        assertEquals(2048, plan.pageWidth());
        assertEquals(2048, plan.pageHeight());
        assertEquals(4, plan.pageCount());
        assertEquals(4, plan.placements().size());

        Set<Integer> pages = new HashSet<>();
        for (var p : plan.placements()) {
            pages.add(p.pageIndex());
        }
        assertEquals(Set.of(0, 1, 2, 3), pages);
    }

    @Test
    void preservesExistingAtlasIndependence() {
        // Model already has 2 atlases of size 1024x1024
        // The newly imported model has small textures that fit in 256x256
        var newItems = List.of(new TextureAtlasLayoutItem("small_new", 100, 80));

        var plan = ModelTextureAtlasPlanner.plan(
                newItems, ModelTextureAtlasPlanner.Options.DEFAULT, i -> "3D Model Atlas " + (2 + i + 1));

        // Should NOT be forced to 1024x1024!
        assertEquals(256, plan.pageWidth(), "New atlas should be sized by new textures, not old atlas");
        assertEquals(256, plan.pageHeight());
        assertEquals(1, plan.pageCount());
        assertEquals("3D Model Atlas 3", plan.pageNames().get(0));
    }

    @Test
    void avoidsGiantSinglePageWhenMultiPageSavesExcessiveMemory() {
        // Many 400x400 textures that could fit on 4096x4096 (1 page, 16.7M pixels)
        // or on 1024x1024 (2 pages, 2.1M pixels -> 8x less memory!)
        var items = new ArrayList<TextureAtlasLayoutItem>();
        for (int i = 0; i < 6; i++) {
            items.add(new TextureAtlasLayoutItem("tex_" + i, 450, 450));
        }
        var plan = ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT);

        // Verify it didn't blindly jump to 4096x4096
        assertTrue(plan.pageWidth() < 4096, "Should avoid excessive blank memory waste on giant 1 page");
        assertTrue((long) plan.pageCount() * plan.pageWidth() * plan.pageHeight() < 16_000_000L);
    }

    @Test
    void imageExceedingMaxAtlasEdgeThrowsIllegalArgumentException() {
        var items = List.of(new TextureAtlasLayoutItem("giant", 5000, 5000));
        var options = new ModelTextureAtlasPlanner.Options(4096, 32, 100_000_000L, 2, 2);
        assertThrows(IllegalArgumentException.class, () -> ModelTextureAtlasPlanner.plan(items, options));
    }

    @Test
    void exceedingPageBudgetThrowsIllegalStateException() {
        var items = new ArrayList<TextureAtlasLayoutItem>();
        for (int i = 0; i < 5; i++) {
            items.add(new TextureAtlasLayoutItem("img_" + i, 1500, 1500));
        }
        var options = new ModelTextureAtlasPlanner.Options(2048, 3, 100_000_000L, 2, 2);
        assertThrows(IllegalStateException.class, () -> ModelTextureAtlasPlanner.plan(items, options));
    }

    @Test
    void fullResolutionImageGetsExclusivePageWhileOtherImagesKeepMargins() {
        var items = List.of(
                new TextureAtlasLayoutItem("full", 4096, 4096), new TextureAtlasLayoutItem("small", 1024, 1024));
        var plan = ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT);
        assertEquals(4096, plan.pageWidth());
        assertEquals(2, plan.pageCount());
        var full = plan.placements().stream()
                .filter(p -> p.textureId().equals("full"))
                .findFirst()
                .orElseThrow();
        var small = plan.placements().stream()
                .filter(p -> p.textureId().equals("small"))
                .findFirst()
                .orElseThrow();
        assertEquals(0, full.x());
        assertEquals(0, full.y());
        assertEquals(4096, full.width());
        assertNotEquals(full.pageIndex(), small.pageIndex());
        assertTrue(small.x() >= 2 && small.y() >= 2);
        assertEquals(1.0, plan.scale());
        assertEquals(
                plan,
                ModelTextureAtlasPlanner.plan(
                        List.of(items.get(1), items.get(0)), ModelTextureAtlasPlanner.Options.DEFAULT));
    }

    @Test
    void exclusivePagesStillRespectPageAndMemoryBudgets() {
        var items =
                List.of(new TextureAtlasLayoutItem("full", 4096, 4096), new TextureAtlasLayoutItem("small", 32, 32));
        assertThrows(
                IllegalStateException.class,
                () -> ModelTextureAtlasPlanner.plan(
                        items, new ModelTextureAtlasPlanner.Options(4096, 1, 134_217_728L, 2, 2)));
        assertThrows(
                IllegalStateException.class,
                () -> ModelTextureAtlasPlanner.plan(
                        items, new ModelTextureAtlasPlanner.Options(4096, 32, 16_777_216L, 2, 2)));
        var single = ModelTextureAtlasPlanner.plan(List.of(items.get(0)), ModelTextureAtlasPlanner.Options.DEFAULT);
        assertEquals(1, single.pageCount());
    }

    @Test
    void emptyItemsThrowsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelTextureAtlasPlanner.plan(List.of(), ModelTextureAtlasPlanner.Options.DEFAULT));
    }
}
