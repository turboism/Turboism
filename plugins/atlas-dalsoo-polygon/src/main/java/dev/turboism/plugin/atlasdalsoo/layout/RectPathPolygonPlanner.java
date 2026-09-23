package dev.turboism.plugin.atlasdalsoo.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import dev.turboism.plugin.atlasdalsoo.PolygonLayoutSettings;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlanner;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;

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
    public TextureAtlasLayoutPlan plan(final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints) {
        return plan(items, constraints, false);
    }

    @Override
    public TextureAtlasLayoutPlan plan(final List<TextureAtlasLayoutItem> items,
        final TextureAtlasLayoutConstraints constraints, final boolean parallel) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(constraints, "constraints");
        final PolygonLayoutSettings policy = settings.get();
        final List<TextureAtlasPolygonItem> polygonItems = new ArrayList<>(items.size());
        for (final TextureAtlasLayoutItem item : items) {
            polygonItems.add(new TextureAtlasPolygonItem(item.textureId(),
                item.width(), item.height(),
                TextureAtlasOutline.rect(item.width(), item.height()),
                TextureAtlasItemLayoutPolicy.participating(item.textureId()),
                TextureAtlasOutlineSource.BOUNDS_FALLBACK, null, false));
        }
        // the rectangle contract cannot express free angles: degrade FREE to
        // QUARTER here instead of misreporting a 45° placement as a quarter turn;
        // the emitted polygon plan's rotationMode diagnostic records QUARTER
        final var polygonConstraints = new TextureAtlasPolygonConstraints(
            constraints.pageWidth(), constraints.pageHeight(),
            constraints.edgeMargin() + constraints.itemPadding(),
            policy.rotation() == dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode.FREE
                ? dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode.QUARTER
                : policy.rotation(),
            policy.automaticScale() ? 0 : policy.fixedScale(),
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend.DALSOO_POLYGON,
            policy.quality());
        final TextureAtlasPolygonPlan polygonPlan = new DalsooPolygonPlanner(
            null, null, null, policy.useAbey(), policy.autoScaleTolerance(),
            policy.autoScaleMaxTry()).plan(polygonItems, polygonConstraints, parallel);
        final List<TextureAtlasPlacement> placements = new ArrayList<>();
        for (final var p : polygonPlan.placements()) {
            final TextureAtlasLayoutItem item = items.stream()
                .filter(i -> i.textureId().equals(p.textureId())).findFirst().orElse(null);
            if (item == null) {
                continue;
            }
            final boolean quarterTurned = Math.abs(normalize90(p.angleDeg()) - 90) < 1
                || Math.abs(normalize90(p.angleDeg()) - 270) < 1;
            // the kernel origin is the transform origin T·R(θ)·S, not the
            // rectangle AABB top-left the rect contract requires: fold the
            // rotated extents back so x/y name the placed bounding box
            final double rad = Math.toRadians(normalize90(p.angleDeg()));
            final double cos = Math.cos(rad), sin = Math.sin(rad);
            final double sw = item.width() * p.scale();
            final double sh = item.height() * p.scale();
            final int x = Math.max(0, (int) Math.round(p.x()
                + Math.min(0, cos * sw) + Math.min(0, -sin * sh)));
            final int y = Math.max(0, (int) Math.round(p.y()
                + Math.min(0, sin * sw) + Math.min(0, cos * sh)));
            final int w = (int) Math.ceil(item.width() * p.scale());
            final int h = (int) Math.ceil(item.height() * p.scale());
            placements.add(new TextureAtlasPlacement(p.textureId(), 0, x, y,
                quarterTurned ? h : w, quarterTurned ? w : h, quarterTurned));
        }
        // items absent from placements are the plan's overflow; the rectangle
        // current-page contract treats omitted ids as overflow implicitly
        return TextureAtlasLayoutPlan.currentPage(constraints.pageWidth(),
            constraints.pageHeight(), placements, polygonPlan.scale());
    }

    private static double normalize90(final double angleDeg) {
        double a = angleDeg % 360;
        if (a < 0) {
            a += 360;
        }
        return Math.round(a / 90.0) * 90.0;
    }
}
