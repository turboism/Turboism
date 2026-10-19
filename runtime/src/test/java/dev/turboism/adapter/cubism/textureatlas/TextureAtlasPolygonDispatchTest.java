package dev.turboism.adapter.cubism.textureatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TextureAtlasPolygonDispatchTest {
    @Test
    void nativeDispatchUsesRealOutlinesAndPolygonWriteback() {
        final Layouts layouts = new Layouts();
        final var registry = new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final var selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final var called = new AtomicBoolean();
        registry.register(new TextureAtlasLayoutAlgorithm("polygon", "Polygon", true, true, new Planner() {
            @Override
            public TextureAtlasPolygonPlan planPolygon(TextureAtlasPolygonLayoutSnapshot snapshot, boolean parallel) {
                assertSame(layouts.snapshot, snapshot);
                assertTrue(parallel);
                assertEquals(6, snapshot.items().get(0).outline().rings().get(0).length);
                called.set(true);
                return layouts.plan;
            }
        }));
        selection.select(new TextureAtlasLayoutSelection("polygon", true));
        assertTrue(new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts).dispatch());
        assertTrue(called.get());
        assertSame(layouts.plan, layouts.applied);
    }

    @Test
    void revokedPolygonRegistrationCannotWriteBack() {
        final Layouts layouts = new Layouts();
        final var registry = new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        final var selection = new TextureAtlasAutoLayoutSelection();
        registry.bindSelection(selection);
        final var called = new AtomicBoolean();
        registry.register(new TextureAtlasLayoutAlgorithm("polygon", "Polygon", true, true, new Planner() {
            @Override
            public TextureAtlasPolygonPlan planPolygon(TextureAtlasPolygonLayoutSnapshot snapshot, boolean parallel) {
                called.set(true);
                registry.register(new TextureAtlasLayoutAlgorithm("polygon", "Replacement", false, (i, c) -> null));
                return layouts.plan;
            }
        }));
        selection.select(new TextureAtlasLayoutSelection("polygon", false));
        assertFalse(new TextureAtlasAutoLayoutDispatcher(registry, selection, layouts).dispatch());
        assertTrue(called.get());
        assertNull(layouts.applied);
    }

    private abstract static class Planner implements TextureAtlasPolygonLayoutPlanner {
        @Override
        public TextureAtlasLayoutPlan plan(
                List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints constraints) {
            fail("Native polygon dispatch must not degrade to bounding rectangles");
            return null;
        }
    }

    private static final class Layouts implements TextureAtlasLayoutService, TextureAtlasPolygonLayoutService {
        final TextureAtlasLayoutTarget target = new TextureAtlasLayoutTarget() {};
        final TextureAtlasPolygonPlan plan = new TextureAtlasPolygonPlan(
                128, 128, 1, List.of(), List.of("concave"), TextureAtlasLayoutBackend.DALSOO_POLYGON, Map.of());
        final TextureAtlasPolygonLayoutSnapshot snapshot = new TextureAtlasPolygonLayoutSnapshot(
                target,
                "doc",
                "model",
                "atlas",
                new TextureAtlasPolygonConstraints(
                        128,
                        128,
                        0,
                        TextureAtlasRotationMode.FREE,
                        1,
                        TextureAtlasLayoutBackend.DALSOO_POLYGON,
                        TextureAtlasLayoutQuality.FAST),
                List.of(new TextureAtlasPolygonItem(
                        "concave",
                        40,
                        40,
                        TextureAtlasOutline.of(new double[][] {{0, 0}, {40, 0}, {40, 20}, {20, 20}, {20, 40}, {0, 40}}),
                        TextureAtlasItemLayoutPolicy.participating("concave"),
                        TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                        null,
                        false)),
                null);
        TextureAtlasPolygonPlan applied;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<TextureAtlasLayoutSnapshot> current() {
            return Optional.of(new TextureAtlasLayoutSnapshot(
                    target,
                    "doc",
                    "model",
                    "atlas",
                    TextureAtlasLayoutConstraints.currentPage(128, 128, 0, true, 1),
                    List.of(new TextureAtlasLayoutItem("concave", 40, 40)),
                    TextureAtlasLayoutPlan.currentPage(128, 128, List.of(), 1)));
        }

        @Override
        public Optional<TextureAtlasPolygonLayoutSnapshot> currentPolygon() {
            return Optional.of(snapshot);
        }

        @Override
        public TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutTarget target, TextureAtlasLayoutPlan plan) {
            fail("Polygon plan must use polygon validation and writeback");
            return null;
        }

        @Override
        public TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutTarget target, TextureAtlasPolygonPlan plan) {
            assertSame(this.target, target);
            applied = plan;
            return TextureAtlasLayoutApplyResult.applied();
        }
    }
}
