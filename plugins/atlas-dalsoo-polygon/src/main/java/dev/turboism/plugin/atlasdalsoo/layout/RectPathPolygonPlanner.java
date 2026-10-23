package dev.turboism.plugin.atlasdalsoo.layout;

import dev.turboism.plugin.atlasdalsoo.PolygonLayoutSettings;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPackingException;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Rectangle-contract adapter around {@link DalsooPolygonPlanner}.
 *
 * <p>Used when the {@code dalsoo} algorithm is invoked through the rectangle
 * dialog/registry path, which supplies only {@code (textureId, width, height)}.
 * Each item is planned with its bounding rectangle as the outline
 * ({@code BOUNDS_FALLBACK}); arbitrary rotations collapse to the
 * {@code rotated} flag on the rectangle plan. This is an honest degradation of the
 * polygon path - real contours only reach the planner through the polygon layout
 * service.</p>
 */
public final class RectPathPolygonPlanner implements TextureAtlasLayoutPlanner {

    private final Supplier<PolygonLayoutSettings> settings;

    public RectPathPolygonPlanner(final Supplier<PolygonLayoutSettings> settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public TextureAtlasLayoutPlan plan(
            final List<TextureAtlasLayoutItem> items, final TextureAtlasLayoutConstraints constraints) {
        return plan(items, constraints, false);
    }

    @Override
    public TextureAtlasLayoutPlan plan(
            final List<TextureAtlasLayoutItem> items,
            final TextureAtlasLayoutConstraints constraints,
            final boolean parallel) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(constraints, "constraints");
        final PolygonLayoutSettings policy = settings.get();
        if (constraints.singlePageOptions() != null) return planPage(items, constraints, parallel, policy);
        if (items.stream().anyMatch(item -> item.outline() != null))
            return planOutlinedAtlas(items, constraints, parallel, policy);
        final var remaining = new ArrayList<>(items);
        final var placements = new ArrayList<TextureAtlasPlacement>();
        int pages = 0;
        do {
            final var page = planPage(remaining, constraints, parallel, policy);
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

    private TextureAtlasLayoutPlan planPage(
            final List<TextureAtlasLayoutItem> items,
            final TextureAtlasLayoutConstraints constraints,
            final boolean parallel,
            final PolygonLayoutSettings policy) {
        final TextureAtlasPolygonPlan polygonPlan = computePage(items, constraints, parallel, policy);
        final List<TextureAtlasPlacement> placements = new ArrayList<>();
        for (final var p : polygonPlan.placements()) {
            final TextureAtlasLayoutItem item = items.stream()
                    .filter(i -> i.textureId().equals(p.textureId()))
                    .findFirst()
                    .orElse(null);
            if (item == null) {
                continue;
            }
            final boolean quarterTurned =
                    Math.abs(normalize90(p.angleDeg()) - 90) < 1 || Math.abs(normalize90(p.angleDeg()) - 270) < 1;
            // the kernel origin is the transform origin T·R(θ)·S, not the
            // rectangle AABB top-left the rect contract requires: fold the
            // rotated extents back so x/y name the placed bounding box
            final double rad = Math.toRadians(normalize90(p.angleDeg()));
            final double cos = Math.cos(rad), sin = Math.sin(rad);
            final double sw = item.width() * p.scale();
            final double sh = item.height() * p.scale();
            final int x = Math.max(0, (int) Math.round(p.x() + Math.min(0, cos * sw) + Math.min(0, -sin * sh)));
            final int y = Math.max(0, (int) Math.round(p.y() + Math.min(0, sin * sw) + Math.min(0, cos * sh)));
            final int w = (int) Math.ceil(item.width() * p.scale());
            final int h = (int) Math.ceil(item.height() * p.scale());
            placements.add(new TextureAtlasPlacement(
                    p.textureId(), 0, x, y, quarterTurned ? h : w, quarterTurned ? w : h, quarterTurned));
        }
        // items absent from placements are the plan's overflow; the rectangle
        // current-page contract treats omitted ids as overflow implicitly
        return TextureAtlasLayoutPlan.currentPage(
                constraints.pageWidth(), constraints.pageHeight(), placements, polygonPlan.scale());
    }

    private TextureAtlasPolygonPlan computePage(
            List<TextureAtlasLayoutItem> items,
            TextureAtlasLayoutConstraints constraints,
            boolean parallel,
            PolygonLayoutSettings policy) {
        final List<TextureAtlasPolygonItem> polygonItems = new ArrayList<>(items.size());
        for (final TextureAtlasLayoutItem item : items) {
            polygonItems.add(new TextureAtlasPolygonItem(
                    item.textureId(),
                    item.width(),
                    item.height(),
                    item.outline() == null ? TextureAtlasOutline.rect(item.width(), item.height()) : item.outline(),
                    TextureAtlasItemLayoutPolicy.participating(item.textureId()),
                    item.outline() == null
                            ? TextureAtlasOutlineSource.BOUNDS_FALLBACK
                            : TextureAtlasOutlineSource.DRAW_DATA_SHAPES,
                    null,
                    false));
        }
        // the rectangle contract cannot express free angles: degrade FREE to
        // QUARTER here instead of misreporting a 45° placement as a quarter turn;
        // the emitted polygon plan's rotationMode diagnostic records QUARTER
        final var polygonConstraints = new TextureAtlasPolygonConstraints(
                constraints.pageWidth(),
                constraints.pageHeight(),
                constraints.singlePageOptions() == null
                        ? constraints.edgeMargin()
                        : constraints.edgeMargin() + constraints.itemPadding(),
                constraints.singlePageOptions() == null
                        ? dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode.NONE
                        : policy.rotation() == dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode.FREE
                                ? dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode.QUARTER
                                : policy.rotation(),
                constraints.singlePageOptions() == null ? 1D : policy.automaticScale() ? 0 : policy.fixedScale(),
                dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend.DALSOO_POLYGON,
                policy.quality());
        return new DalsooPolygonPlanner(
                        null,
                        null,
                        null,
                        policy.useAbey(),
                        policy.autoScaleTolerance(),
                        policy.autoScaleMaxTry(),
                        constraints.singlePageOptions() == null ? constraints.itemPadding() : -1)
                .plan(polygonItems, polygonConstraints, parallel);
    }

    private TextureAtlasLayoutPlan planOutlinedAtlas(
            List<TextureAtlasLayoutItem> items,
            TextureAtlasLayoutConstraints constraints,
            boolean parallel,
            PolygonLayoutSettings policy) {
        final var remaining = new ArrayList<>(items);
        final var pages = new ArrayList<TextureAtlasPolygonPlan>();
        do {
            final var page = computePage(remaining, constraints, parallel, policy);
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

    private static double normalize90(final double angleDeg) {
        double a = angleDeg % 360;
        if (a < 0) {
            a += 360;
        }
        return Math.round(a / 90.0) * 90.0;
    }
}
