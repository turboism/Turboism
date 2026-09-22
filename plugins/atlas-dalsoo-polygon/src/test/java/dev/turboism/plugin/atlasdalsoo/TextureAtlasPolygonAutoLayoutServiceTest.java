package dev.turboism.plugin.atlasdalsoo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The issued session bounds must win over plugin policy: a wider plugin
 * rotation degrades to the session mode and a fixed session scale is honored.
 */
class TextureAtlasPolygonAutoLayoutServiceTest {

    private static final TextureAtlasLayoutTarget TARGET = new TextureAtlasLayoutTarget() {
    };

    private static final class FakeLayouts implements TextureAtlasPolygonLayoutService {
        private final TextureAtlasPolygonLayoutSnapshot snapshot;
        private TextureAtlasPolygonPlan applied;

        FakeLayouts(final TextureAtlasPolygonLayoutSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public Optional<TextureAtlasPolygonLayoutSnapshot> currentPolygon() {
            return Optional.of(snapshot);
        }

        @Override
        public TextureAtlasLayoutApplyResult apply(final TextureAtlasLayoutTarget target,
            final TextureAtlasPolygonPlan plan) {
            this.applied = plan;
            return TextureAtlasLayoutApplyResult.applied();
        }
    }

    private static TextureAtlasPolygonItem rect(final String id) {
        return new TextureAtlasPolygonItem(id, 40, 40,
            TextureAtlasOutline.rect(40, 40),
            TextureAtlasItemLayoutPolicy.participating(id),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, null, false);
    }

    private static TextureAtlasPolygonLayoutSnapshot snapshot(final int w, final int h,
        final TextureAtlasRotationMode mode, final double requestedScale,
        final List<TextureAtlasPolygonItem> items) {
        return new TextureAtlasPolygonLayoutSnapshot(TARGET, "doc", "model", "atlas",
            new TextureAtlasPolygonConstraints(w, h, 0, mode, requestedScale,
                TextureAtlasLayoutBackend.DALSOO_POLYGON, TextureAtlasLayoutQuality.FAST),
            items, null);
    }

    private static PolygonLayoutSettings policy(final TextureAtlasRotationMode rotation,
        final boolean automaticScale, final double fixedScale) {
        return new PolygonLayoutSettings(TextureAtlasLayoutBackend.DALSOO_POLYGON,
            rotation, TextureAtlasLayoutQuality.FAST, automaticScale, fixedScale,
            false, false, Map.of());
    }

    @Test
    void sessionRotationBoundsPluginPolicy() {
        final var layouts = new FakeLayouts(snapshot(400, 400,
            TextureAtlasRotationMode.NONE, 0,
            List.of(rect("a"), rect("b"))));
        final List<String> log = new ArrayList<>();
        final var service = new TextureAtlasPolygonAutoLayoutService(layouts,
            () -> policy(TextureAtlasRotationMode.FREE, true, 1.0), log::add);
        final var result = service.applyAutomaticLayout(false);
        assertTrue(result.status().isPresent(),
            "apply failed: " + result.message().orElse("?"));
        assertTrue(log.stream().anyMatch(m -> m.contains("degraded")),
            "expected a degradation log line, got " + log);
        for (final var p : layouts.applied.placements()) {
            assertEquals(0, Math.abs(p.angleDeg()) % 90, 1e-6,
                "session NONE must bound plugin FREE");
        }
    }

    @Test
    void sessionRequestedScaleWinsOverPluginScale() {
        final var layouts = new FakeLayouts(snapshot(400, 400,
            TextureAtlasRotationMode.QUARTER, 0.5,
            List.of(rect("a"), rect("b"))));
        final var service = new TextureAtlasPolygonAutoLayoutService(layouts,
            () -> policy(TextureAtlasRotationMode.QUARTER, true, 1.0), m -> { });
        service.applyAutomaticLayout(false);
        assertEquals(0.5, layouts.applied.scale(), 1e-9);
    }

    @Test
    void automaticScaleNeverExceedsOne() {
        // one small item on a large page: the uncapped optimistic bound would be >1
        final var layouts = new FakeLayouts(snapshot(2000, 2000,
            TextureAtlasRotationMode.NONE, 0, List.of(rect("a"))));
        final var service = new TextureAtlasPolygonAutoLayoutService(layouts,
            () -> policy(TextureAtlasRotationMode.NONE, true, 1.0), m -> { });
        service.applyAutomaticLayout(false);
        assertTrue(layouts.applied.scale() <= 1.0,
            "automatic scale must not exceed 1, got " + layouts.applied.scale());
    }
}
