package dev.turboism.plugin.atlasnesting;

import dev.turboism.plugin.atlasnesting.layout.BitmaskNestingPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Independently installable planner; the runtime owns selection, validation and writeback. */
public final class TextureAtlasNestingPlugin implements TurboismPlugin {
    private PluginContext context;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void enable() {
        if (context == null) throw new IllegalStateException("Plugin is not initialized");
        context.cubism()
                .textureAtlasAlgorithms()
                .register(new TextureAtlasLayoutAlgorithm(
                        "bitmask-nesting",
                        context.localization().text("texture-atlas.algorithm.bitmask-nesting"),
                        false,
                        false,
                        new Planner()));
        context.logger().info("Bitmask Nesting packing enabled");
    }

    final class Planner implements TextureAtlasPolygonLayoutPlanner {
        @Override
        public TextureAtlasPolygonPlan planPolygon(
                final TextureAtlasPolygonLayoutSnapshot snapshot, final boolean parallel) {
            final long started = System.nanoTime();
            final TextureAtlasPolygonPlan plan = new BitmaskNestingPlanner(
                            Math.multiplyExact(snapshot.constraints().margin(), 2))
                    .plan(snapshot.items(), snapshot.constraints());
            context.logger()
                    .info("bitmask-nesting: items=" + snapshot.items().size()
                            + " placed=" + plan.placements().size() + " overflow="
                            + plan.overflowTextureIds().size()
                            + " scale=" + plan.scale() + " plannerMs=" + (System.nanoTime() - started) / 1_000_000.0
                            + " " + plan.diagnostics());
            return plan;
        }

        @Override
        public TextureAtlasLayoutPlan plan(
                final List<TextureAtlasLayoutItem> items, final TextureAtlasLayoutConstraints constraints) {
            if (constraints.singlePageOptions() == null) {
                throw new UnsupportedOperationException("Bitmask Nesting supports current-page layout only");
            }
            final var polygons = new ArrayList<TextureAtlasPolygonItem>();
            final Map<String, TextureAtlasLayoutItem> byId = new HashMap<>();
            for (final var item : items) {
                byId.put(item.textureId(), item);
                polygons.add(new TextureAtlasPolygonItem(
                        item.textureId(),
                        item.width(),
                        item.height(),
                        TextureAtlasOutline.rect(item.width(), item.height()),
                        TextureAtlasItemLayoutPolicy.participating(item.textureId()),
                        TextureAtlasOutlineSource.BOUNDS_FALLBACK,
                        null,
                        false));
            }
            final double requested = constraints.singlePageOptions().requestedScale();
            final var c = new TextureAtlasPolygonConstraints(
                    constraints.pageWidth(),
                    constraints.pageHeight(),
                    constraints.edgeMargin(),
                    constraints.allowRotation() ? TextureAtlasRotationMode.QUARTER : TextureAtlasRotationMode.NONE,
                    requested,
                    TextureAtlasLayoutBackend.AUTO,
                    TextureAtlasLayoutQuality.BALANCED);
            final var result = new BitmaskNestingPlanner(constraints.itemPadding()).plan(polygons, c);
            final var placements = new ArrayList<TextureAtlasPlacement>();
            for (final var p : result.placements()) {
                final var item = byId.get(p.textureId());
                final double radians = Math.toRadians(p.angleDeg());
                final double w = item.width() * p.scale(), h = item.height() * p.scale();
                final double cos = Math.cos(radians), sin = Math.sin(radians);
                final int x = (int) Math.round(p.x() + Math.min(0, cos * w) + Math.min(0, -sin * h));
                final int y = (int) Math.round(p.y() + Math.min(0, sin * w) + Math.min(0, cos * h));
                final boolean rotated = Math.abs(Math.sin(radians)) > 0.5;
                placements.add(new TextureAtlasPlacement(
                        p.textureId(),
                        0,
                        x,
                        y,
                        (int) Math.ceil(rotated ? h : w),
                        (int) Math.ceil(rotated ? w : h),
                        rotated));
            }
            return TextureAtlasLayoutPlan.currentPage(
                    constraints.pageWidth(), constraints.pageHeight(), placements, result.scale());
        }
    }
}
