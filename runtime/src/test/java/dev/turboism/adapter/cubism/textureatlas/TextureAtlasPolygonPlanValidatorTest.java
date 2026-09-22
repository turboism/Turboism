package dev.turboism.adapter.cubism.textureatlas;

import java.util.List;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutline;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasOutlineSource;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlacement;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasPolygonPlanValidatorTest {

    private static TextureAtlasPolygonItem item(final String id,
        final double w, final double h, final double[] matrix,
        final boolean placed) {
        return new TextureAtlasPolygonItem(id, (int) w, (int) h,
            TextureAtlasOutline.rect(w, h),
            TextureAtlasItemLayoutPolicy.participating(id),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, matrix, placed);
    }

    private static TextureAtlasPolygonConstraints constraints(final int w, final int h,
        final int margin, final TextureAtlasRotationMode mode,
        final double requestedScale) {
        return new TextureAtlasPolygonConstraints(w, h, margin, mode, requestedScale,
            TextureAtlasLayoutBackend.DALSOO_POLYGON, TextureAtlasLayoutQuality.BALANCED);
    }

    private static TextureAtlasPolygonConstraints constraints(final int w, final int h,
        final int margin, final TextureAtlasRotationMode mode) {
        return constraints(w, h, margin, mode, 0);
    }

    private static TextureAtlasPolygonPlan plan(final int w, final int h,
        final List<TextureAtlasPolygonPlacement> placements,
        final List<String> overflow) {
        return new TextureAtlasPolygonPlan(w, h, 1.0, placements, overflow,
            TextureAtlasLayoutBackend.DALSOO_POLYGON, java.util.Map.of());
    }

    private static TextureAtlasPolygonPlan plan(final int w, final int h,
        final double scale, final List<TextureAtlasPolygonPlacement> placements,
        final List<String> overflow) {
        return new TextureAtlasPolygonPlan(w, h, scale, placements, overflow,
            TextureAtlasLayoutBackend.DALSOO_POLYGON, java.util.Map.of());
    }

    private static TextureAtlasPolygonPlacement at(final String id,
        final double x, final double y) {
        return new TextureAtlasPolygonPlacement(id, x, y, 0, 1.0);
    }

    @Test
    void validPlanPasses() {
        final var items = List.of(item("a", 50, 50, null, false),
            item("b", 50, 50, null, false));
        final var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("a", 0, 0), at("b", 60, 0)), List.of()));
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void pageMismatchRejected() {
        final var items = List.of(item("a", 50, 50, null, false));
        final var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(10_000, 200, List.of(at("a", 0, 0)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("page-mismatch")));
    }

    @Test
    void scaleContractEnforced() {
        final var items = List.of(item("a", 50, 50, null, false));
        // automatic scale must never exceed 1
        var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE, 0),
            plan(200, 200, 1.5, List.of(at("a", 0, 0)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("scale")));
        // a fixed requested scale must be met exactly
        violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE, 0.5),
            plan(200, 200, 0.7, List.of(at("a", 0, 0)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("scale")));
        violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE, 0.5),
            plan(200, 200, 0.5, List.of(at("a", 0, 0)), List.of()));
        assertTrue(violations.stream().noneMatch(v -> v.code().equals("scale")),
            "violations: " + violations);
    }

    @Test
    void duplicatePlacementRejected() {
        final var items = List.of(item("a", 50, 50, null, false));
        final var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("a", 0, 0), at("a", 60, 0)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("duplicate-item")));
    }

    @Test
    void unknownAndMissingItemsRejected() {
        final var items = List.of(item("a", 50, 50, null, false));
        var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("a", 0, 0), at("ghost", 60, 0)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("unknown-item")));
        violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("missing-item")));
        // missing is acceptable when reported as overflow
        violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(), List.of("a")));
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void overflowIntegrityEnforced() {
        final var items = List.of(item("a", 50, 50, null, false));
        var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("a", 0, 0)), List.of("ghost")));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("unknown-item")));
        violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("a", 0, 0)), List.of("a")));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("duplicate-item")));
    }

    @Test
    void overlapDetected() {
        final var items = List.of(item("a", 50, 50, null, false),
            item("b", 50, 50, null, false));
        final var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("a", 0, 0), at("b", 10, 10)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("overlap")));
    }

    @Test
    void marginEnforced() {
        final var items = List.of(item("a", 50, 50, null, false));
        final var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 10, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("a", 0, 0)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("margin")));
    }

    @Test
    void rotationModeEnforced() {
        final var items = List.of(item("a", 50, 50, null, false));
        var p = new TextureAtlasPolygonPlacement("a", 100, 100, 45, 1.0);
        var violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.QUARTER),
            plan(200, 200, List.of(p), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("rotation-mode")));
        violations = TextureAtlasPolygonPlanValidator.validate(items,
            constraints(200, 200, 0, TextureAtlasRotationMode.FREE),
            plan(200, 200, List.of(p), List.of()));
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void lockedAngleMustKeepIssuedAngle() {
        final double[] matrix = {0.8660254, 0.5, -0.5, 0.8660254, 10, 10};
        final var locked = new TextureAtlasPolygonItem("lk", 40, 40,
            TextureAtlasOutline.rect(40, 40),
            new TextureAtlasItemLayoutPolicy("lk", true, true, false, false),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, matrix, true);
        // 30 deg issued, QUARTER would normally allow 90 steps only - a 90 deg
        // placement for the locked item must fail
        var violations = TextureAtlasPolygonPlanValidator.validate(List.of(locked),
            constraints(200, 200, 0, TextureAtlasRotationMode.QUARTER),
            plan(200, 200, List.of(new TextureAtlasPolygonPlacement("lk", 10, 50, 90, 1.0)),
                List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("locked-angle")),
            "violations: " + violations);
        violations = TextureAtlasPolygonPlanValidator.validate(List.of(locked),
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(new TextureAtlasPolygonPlacement("lk", 10, 50, 30, 1.0)),
                List.of()));
        assertTrue(violations.stream().noneMatch(v -> v.code().equals("locked-angle")),
            "violations: " + violations);
    }

    @Test
    void lockedScaleMustKeepIssuedScale() {
        final double[] matrix = {0.5, 0, 0, 0.5, 10, 10};
        final var locked = new TextureAtlasPolygonItem("ls", 40, 40,
            TextureAtlasOutline.rect(40, 40),
            new TextureAtlasItemLayoutPolicy("ls", true, false, true, false),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, matrix, true);
        var violations = TextureAtlasPolygonPlanValidator.validate(List.of(locked),
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(new TextureAtlasPolygonPlacement("ls", 50, 50, 0, 1.0)),
                List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("locked-scale")),
            "violations: " + violations);
        violations = TextureAtlasPolygonPlanValidator.validate(List.of(locked),
            constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(new TextureAtlasPolygonPlacement("ls", 50, 50, 0, 0.5)),
                List.of()));
        assertTrue(violations.stream().noneMatch(v -> v.code().equals("locked-scale")),
            "violations: " + violations);
    }

    @Test
    void fixedPositionMustKeepIssuedTransform() {
        final double[] matrix = {1, 0, 0, 1, 30, 40};
        final var fixed = new TextureAtlasPolygonItem("f", 50, 50,
            TextureAtlasOutline.rect(50, 50),
            new TextureAtlasItemLayoutPolicy("f", true, true, true, true),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, matrix, true);
        var violations = TextureAtlasPolygonPlanValidator.validate(
            List.of(fixed), constraints(200, 200, 0, TextureAtlasRotationMode.FREE),
            plan(200, 200, List.of(at("f", 60, 60)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("fixed-position")));
        violations = TextureAtlasPolygonPlanValidator.validate(
            List.of(fixed), constraints(200, 200, 0, TextureAtlasRotationMode.FREE),
            plan(200, 200, List.of(at("f", 30, 40)), List.of()));
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

    @Test
    void excludedOffPageItemCannotBePlaced() {
        final var out = new TextureAtlasPolygonItem("out", 50, 50,
            TextureAtlasOutline.rect(50, 50),
            TextureAtlasItemLayoutPolicy.excluded("out"),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, null, false);
        final var violations = TextureAtlasPolygonPlanValidator.validate(
            List.of(out), constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("out", 0, 0)), List.of()));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("excluded-item")));
    }

    @Test
    void excludedOnPageItemCannotOverflow() {
        final double[] matrix = {1, 0, 0, 1, 20, 20};
        final var staying = new TextureAtlasPolygonItem("stay", 50, 50,
            TextureAtlasOutline.rect(50, 50),
            TextureAtlasItemLayoutPolicy.excluded("stay"),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, matrix, true);
        final var violations = TextureAtlasPolygonPlanValidator.validate(
            List.of(staying), constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(), List.of("stay")));
        assertTrue(violations.stream().anyMatch(v -> v.code().equals("excluded-item")));
    }

    @Test
    void placedNonParticipantMayStay() {
        final double[] matrix = {1, 0, 0, 1, 20, 20};
        final var staying = new TextureAtlasPolygonItem("stay", 50, 50,
            TextureAtlasOutline.rect(50, 50),
            TextureAtlasItemLayoutPolicy.excluded("stay"),
            TextureAtlasOutlineSource.BOUNDS_FALLBACK, matrix, true);
        final var violations = TextureAtlasPolygonPlanValidator.validate(
            List.of(staying), constraints(200, 200, 0, TextureAtlasRotationMode.NONE),
            plan(200, 200, List.of(at("stay", 20, 20)), List.of()));
        assertTrue(violations.isEmpty(), "violations: " + violations);
    }

}
