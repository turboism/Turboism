package dev.turboism.adapter.cubism.textureatlas;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

/**
 * Independent plan validator for polygon layouts, evaluated against freshly read
 * session state before any host write.
 *
 * <p>Checks, in order: plan/page dimension match, the issued scale contract
 * (automatic scale never exceeds 1; a fixed {@code requestedScale} must be met
 * exactly), every participating and every fixed-position item covered exactly
 * once, no placements for excluded items, rotation-mode compliance (quarter
 * steps unless {@code FREE}; a plan may declare the mode it was produced under
 * via the {@code rotationMode} diagnostic, bounded by the issued session mode),
 * issued-angle/scale locks, margin bounds, and
 * pairwise non-overlap of the transformed union of each item's rings
 * (built with {@link Area} so multi-region items and stitched rings validate
 * exactly). Fixed-position items must keep their issued transform.</p>
 */
final class TextureAtlasPolygonPlanValidator {

    private static final double TOLERANCE = 1e-4;

    private TextureAtlasPolygonPlanValidator() {
    }

    /** Violation reported in plan order; {@code message} is operator-facing. */
    record Violation(String code, String message) {
    }

    static List<Violation> validate(final List<TextureAtlasPolygonItem> items,
        final TextureAtlasPolygonConstraints constraints,
        final TextureAtlasPolygonPlan plan) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(plan, "plan");
        final List<Violation> violations = new ArrayList<>();
        if (plan.pageWidth() != constraints.pageWidth()
            || plan.pageHeight() != constraints.pageHeight()) {
            violations.add(new Violation("page-mismatch",
                "plan page " + plan.pageWidth() + "x" + plan.pageHeight()
                    + " does not match the issued page "
                    + constraints.pageWidth() + "x" + constraints.pageHeight()));
        }
        final double requested = constraints.requestedScale();
        if (requested > 0
            && Math.abs(plan.scale() - requested) > 1e-12 * requested) {
            violations.add(new Violation("scale",
                "plan scale " + plan.scale()
                    + " does not respect the requested scale " + requested));
        } else if (requested == 0 && plan.scale() > 1 + TOLERANCE) {
            violations.add(new Violation("scale",
                "automatic scale must not exceed 1, got " + plan.scale()));
        }
        final int margin = constraints.margin();
        // The issued session mode is the writable bound; a plan may declare the
        // caller-requested mode it was produced under, never wider than issued.
        final TextureAtlasRotationMode sessionMode = constraints.rotationMode();
        TextureAtlasRotationMode effectiveMode = sessionMode;
        final String declaredValue = plan.diagnostics().get("rotationMode");
        if (declaredValue != null) {
            final TextureAtlasRotationMode declared = parseRotationMode(declaredValue);
            if (declared == null) {
                violations.add(new Violation("rotation-mode",
                    "plan declares unknown rotationMode '" + declaredValue + "'"));
            } else if (declared.ordinal() > sessionMode.ordinal()) {
                violations.add(new Violation("rotation-mode",
                    "plan rotationMode " + declared
                        + " exceeds the issued mode " + sessionMode));
            } else {
                effectiveMode = declared;
            }
        }
        final java.util.Map<String, TextureAtlasPolygonItem> byId = new java.util.HashMap<>();
        for (final TextureAtlasPolygonItem item : items) {
            byId.put(item.textureId(), item);
        }
        final java.util.Set<String> placedIds = new java.util.HashSet<>();
        final List<Area> placedAreas = new ArrayList<>();
        final List<String> placedNames = new ArrayList<>();
        for (final TextureAtlasPolygonPlacement placement : plan.placements()) {
            final TextureAtlasPolygonItem item = byId.get(placement.textureId());
            if (item == null) {
                violations.add(new Violation("unknown-item",
                    "placement for unknown textureId " + placement.textureId()));
                continue;
            }
            if (!placedIds.add(placement.textureId())) {
                violations.add(new Violation("duplicate-item",
                    "duplicate placement for " + placement.textureId()));
                continue;
            }
            final TextureAtlasItemLayoutPolicy policy = item.policy();
            if (policy != null && !policy.participate() && !item.currentlyPlaced()) {
                violations.add(new Violation("excluded-item",
                    "placement for excluded off-page item " + placement.textureId()));
                continue;
            }
            if (!Double.isFinite(placement.x()) || !Double.isFinite(placement.y())
                || !Double.isFinite(placement.angleDeg())
                || !(placement.scale() > 0) || !Double.isFinite(placement.scale())) {
                violations.add(new Violation("invalid-placement",
                    "non-finite transform or non-positive scale for "
                        + placement.textureId()));
                continue;
            }
            final double normalized = normalizeAngle(placement.angleDeg());
            final double issuedAngle = item.currentMatrix() == null ? 0
                : item.currentAngleDeg();
            if (lockedAngle(item)) {
                if (Math.abs(normalizeAngle(normalized - issuedAngle)) > TOLERANCE) {
                    violations.add(new Violation("locked-angle",
                        "angle-locked item " + placement.textureId()
                            + " rotated from " + issuedAngle + " to " + normalized));
                }
            } else if (effectiveMode == TextureAtlasRotationMode.NONE) {
                if (Math.abs(normalizeAngle(normalized - issuedAngle)) > TOLERANCE) {
                    violations.add(new Violation("rotation-mode",
                        "rotation NONE but " + placement.textureId() + " rotated to "
                            + normalized));
                }
            } else if (effectiveMode == TextureAtlasRotationMode.QUARTER) {
                if (Math.abs(normalized % 90) > TOLERANCE
                    && Math.abs(normalized % 90 - 90) > TOLERANCE) {
                    violations.add(new Violation("rotation-mode",
                        "rotation QUARTER but " + placement.textureId() + " rotated to "
                            + normalized));
                }
            }
            if (lockedScale(item)
                && Math.abs(placement.scale() - item.currentScale()) > TOLERANCE) {
                violations.add(new Violation("locked-scale",
                    "scale-locked item " + placement.textureId() + " rescaled from "
                        + item.currentScale() + " to " + placement.scale()));
            }
            if (lockedPosition(item)) {
                final double[] matrix = item.currentMatrix();
                if (matrix == null || Math.abs(placement.x() - matrix[4]) > TOLERANCE
                    || Math.abs(placement.y() - matrix[5]) > TOLERANCE
                    || Math.abs(normalizeAngle(normalized - issuedAngle)) > TOLERANCE) {
                    violations.add(new Violation("fixed-position",
                        "fixed-position item " + placement.textureId() + " moved"));
                }
            }
            final Area area = transformedArea(item, placement);
            final java.awt.geom.Rectangle2D bounds = area.getBounds2D();
            if (bounds.getMinX() < margin - TOLERANCE
                || bounds.getMinY() < margin - TOLERANCE
                || bounds.getMaxX() > plan.pageWidth() - margin + TOLERANCE
                || bounds.getMaxY() > plan.pageHeight() - margin + TOLERANCE) {
                violations.add(new Violation("margin",
                    "item " + placement.textureId() + " violates page margin"));
                continue;
            }
            for (int i = 0; i < placedAreas.size(); i++) {
                final Area other = placedAreas.get(i);
                final Area test = new Area(area);
                test.intersect(other);
                if (!test.isEmpty() && test.getBounds2D().getWidth() * test.getBounds2D().getHeight()
                    > TOLERANCE) {
                    violations.add(new Violation("overlap",
                        "item " + placement.textureId() + " overlaps "
                            + placedNames.get(i)));
                    break;
                }
            }
            placedAreas.add(area);
            placedNames.add(placement.textureId());
        }
        final java.util.Set<String> overflowIds = new java.util.HashSet<>();
        for (final String overflowId : plan.overflowTextureIds()) {
            if (!byId.containsKey(overflowId)) {
                violations.add(new Violation("unknown-item",
                    "overflow for unknown textureId " + overflowId));
            }
            if (!overflowIds.add(overflowId)) {
                violations.add(new Violation("duplicate-item",
                    "duplicate overflow entry for " + overflowId));
            }
            if (placedIds.contains(overflowId)) {
                violations.add(new Violation("duplicate-item",
                    overflowId + " is both placed and overflowed"));
            }
        }
        for (final TextureAtlasPolygonItem item : items) {
            final TextureAtlasItemLayoutPolicy policy = item.policy();
            final boolean mustPlace = policy == null || policy.participate()
                || item.currentlyPlaced();
            if (!mustPlace || placedIds.contains(item.textureId())) {
                continue;
            }
            if (policy != null && !policy.participate() && item.currentlyPlaced()) {
                // a placed non-participant keeps its issued transform and must
                // stay on the page - overflow would silently remove it
                violations.add(new Violation("excluded-item",
                    "excluded on-page item " + item.textureId()
                        + " was removed from the page"));
            } else if (!plan.overflowTextureIds().contains(item.textureId())) {
                violations.add(new Violation("missing-item",
                    "participating item " + item.textureId()
                        + " has neither placement nor overflow"));
            }
        }
        return violations;
    }

    private static TextureAtlasRotationMode parseRotationMode(final String value) {
        try {
            return TextureAtlasRotationMode.valueOf(value);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static boolean lockedAngle(final TextureAtlasPolygonItem item) {
        final TextureAtlasItemLayoutPolicy policy = item.policy();
        return policy != null && (policy.preserveAngle() || policy.preservePosition());
    }

    private static boolean lockedScale(final TextureAtlasPolygonItem item) {
        final TextureAtlasItemLayoutPolicy policy = item.policy();
        return policy != null && (policy.preserveScale()
            || (!policy.participate() && item.currentlyPlaced()));
    }

    private static boolean lockedPosition(final TextureAtlasPolygonItem item) {
        final TextureAtlasItemLayoutPolicy policy = item.policy();
        return policy != null && (policy.preservePosition() || !policy.participate())
            && item.currentlyPlaced();
    }

    private static Area transformedArea(final TextureAtlasPolygonItem item,
        final TextureAtlasPolygonPlacement placement) {
        final AffineTransform at = new AffineTransform();
        at.translate(placement.x(), placement.y());
        at.rotate(Math.toRadians(placement.angleDeg()));
        at.scale(placement.scale(), placement.scale());
        final Area union = new Area();
        for (final double[][] ring : item.outline().rings()) {
            final Path2D.Double path = new Path2D.Double();
            path.moveTo(ring[0][0], ring[0][1]);
            for (int i = 1; i < ring.length; i++) {
                path.lineTo(ring[i][0], ring[i][1]);
            }
            path.closePath();
            union.add(new Area(path));
        }
        return union.createTransformedArea(at);
    }

    private static double normalizeAngle(final double deg) {
        double a = deg % 360;
        if (a > 180) {
            a -= 360;
        } else if (a < -180) {
            a += 360;
        }
        return a;
    }
}
