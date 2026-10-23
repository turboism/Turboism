package dev.turboism.plugin.atlasnesting;

import dev.turboism.plugin.atlasnesting.layout.BitmaskNestingPlanner;
import dev.turboism.plugin.atlasnesting.layout.NestingFailure;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPackingException;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasSinglePageOptions;
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
        final TextureAtlasPolygonLayoutService layouts = context.cubism().textureAtlasPolygonLayouts();
        final TextureAtlasLayoutPlanner planner;
        if (layouts.isAvailable()) planner = new Planner();
        else {
            context.logger().info(context.localization().text("texture-atlas.nesting.bounds-mode"));
            planner = new RectanglePlanner();
        }
        context.cubism()
                .textureAtlasAlgorithms()
                .register(new TextureAtlasLayoutAlgorithm(
                        "bitmask-nesting",
                        context.localization().text("texture-atlas.algorithm.bitmask-nesting"),
                        false,
                        false,
                        planner));
        context.logger().info("Bitmask Nesting packing enabled");
    }

    /** Uses the runtime rectangle dispatch on hosts without the polygon service. */
    final class RectanglePlanner implements TextureAtlasLayoutPlanner {
        @Override
        public boolean supportsCompleteAtlas() {
            return true;
        }

        @Override
        public TextureAtlasLayoutPlan plan(
                final List<TextureAtlasLayoutItem> items, final TextureAtlasLayoutConstraints constraints) {
            return new Planner().plan(items, constraints);
        }
    }

    final class Planner implements TextureAtlasPolygonLayoutPlanner {
        @Override
        public boolean supportsCompleteAtlas() {
            return true;
        }

        @Override
        public TextureAtlasPolygonPlan planPolygon(
                final TextureAtlasPolygonLayoutSnapshot snapshot, final boolean parallel) {
            final long started = System.nanoTime();
            final TextureAtlasPolygonPlan plan = compute(
                    snapshot.items(),
                    snapshot.constraints(),
                    Math.multiplyExact(snapshot.constraints().margin(), 2));
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
                return planCompleteAtlas(items, constraints);
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
            final var result = compute(polygons, c, constraints.itemPadding());
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

        private TextureAtlasLayoutPlan planOutlinedAtlas(
                List<TextureAtlasLayoutItem> items, TextureAtlasLayoutConstraints constraints) {
            final var remaining = new ArrayList<>(items);
            final var pages = new ArrayList<TextureAtlasPolygonPlan>();
            do {
                final var polygons = remaining.stream()
                        .map(item -> new TextureAtlasPolygonItem(
                                item.textureId(),
                                item.width(),
                                item.height(),
                                item.outline() == null
                                        ? TextureAtlasOutline.rect(item.width(), item.height())
                                        : item.outline(),
                                TextureAtlasItemLayoutPolicy.participating(item.textureId()),
                                item.outline() == null
                                        ? TextureAtlasOutlineSource.BOUNDS_FALLBACK
                                        : TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                                null,
                                false))
                        .toList();
                final var page = compute(
                        polygons,
                        new TextureAtlasPolygonConstraints(
                                constraints.pageWidth(),
                                constraints.pageHeight(),
                                constraints.edgeMargin(),
                                TextureAtlasRotationMode.NONE,
                                1D,
                                TextureAtlasLayoutBackend.AUTO,
                                TextureAtlasLayoutQuality.BALANCED),
                        constraints.itemPadding());
                if (page.placements().isEmpty())
                    throw new TextureAtlasPackingException(
                            remaining.get(0).textureId(), TextureAtlasPackingException.Reason.ITEM_DOES_NOT_FIT);
                pages.add(new TextureAtlasPolygonPlan(
                        page.pageWidth(),
                        page.pageHeight(),
                        1D,
                        page.placements(),
                        List.of(),
                        page.backend(),
                        page.diagnostics()));
                final var placed =
                        page.placements().stream().map(p -> p.textureId()).collect(java.util.stream.Collectors.toSet());
                remaining.removeIf(item -> placed.contains(item.textureId()));
                if (!remaining.isEmpty() && pages.size() >= constraints.maxPages())
                    throw new TextureAtlasPackingException(
                            remaining.get(0).textureId(), TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED);
            } while (!remaining.isEmpty());
            return new TextureAtlasLayoutPlan(
                    constraints.pageWidth(),
                    constraints.pageHeight(),
                    pages.size(),
                    List.of(),
                    List.of(),
                    1D,
                    List.of(),
                    pages);
        }

        /** Reuses fixed-scale rectangle packing; a complete plan must place every image. */
        private TextureAtlasLayoutPlan planCompleteAtlas(
                final List<TextureAtlasLayoutItem> items, final TextureAtlasLayoutConstraints constraints) {
            if (items.stream().anyMatch(item -> item.outline() != null)) return planOutlinedAtlas(items, constraints);
            final var remaining = new ArrayList<>(items);
            final var placements = new ArrayList<TextureAtlasPlacement>();
            final var pageConstraints = new TextureAtlasLayoutConstraints(
                    constraints.pageWidth(),
                    constraints.pageHeight(),
                    constraints.edgeMargin(),
                    constraints.itemPadding(),
                    1,
                    false,
                    false,
                    new TextureAtlasSinglePageOptions(1D));
            int pages = 0;
            do {
                final var page = plan(remaining, pageConstraints);
                if (!remaining.isEmpty() && page.placements().isEmpty()) {
                    throw new TextureAtlasPackingException(
                            remaining.get(0).textureId(), TextureAtlasPackingException.Reason.ITEM_DOES_NOT_FIT);
                }
                final int pageIndex = pages++;
                for (final var placement : page.placements()) {
                    placements.add(new TextureAtlasPlacement(
                            placement.textureId(),
                            pageIndex,
                            placement.x(),
                            placement.y(),
                            placement.width(),
                            placement.height(),
                            false));
                }
                final var placed = page.placements().stream()
                        .map(TextureAtlasPlacement::textureId)
                        .collect(java.util.stream.Collectors.toSet());
                remaining.removeIf(item -> placed.contains(item.textureId()));
                if (!remaining.isEmpty() && pages >= constraints.maxPages()) {
                    throw new TextureAtlasPackingException(
                            remaining.get(0).textureId(), TextureAtlasPackingException.Reason.PAGE_BUDGET_EXHAUSTED);
                }
            } while (!remaining.isEmpty());
            return new TextureAtlasLayoutPlan(constraints.pageWidth(), constraints.pageHeight(), pages, placements);
        }

        private TextureAtlasPolygonPlan compute(
                final List<TextureAtlasPolygonItem> items,
                final TextureAtlasPolygonConstraints constraints,
                final int gap) {
            try {
                return new BitmaskNestingPlanner(gap).plan(items, constraints);
            } catch (NestingFailure failure) {
                throw new IllegalArgumentException(
                        context.localization().format(failure.messageKey(), failure.arguments()), failure);
            }
        }
    }
}
