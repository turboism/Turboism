package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import java.math.BigDecimal;
import java.util.*;

/** Diagnostics of the bilinear proxy of stored control cells, never the native evaluated surface. */
final class McpWarpQuality {
    static final int MAX_CELL_SAMPLES = 8192;
    static final List<String> IMPLEMENTED =
            List.of("BILINEAR_CELL_DEGENERACY", "BILINEAR_CELL_LOCAL_FOLD", "BILINEAR_CELL_ORIENTATION_VS_REFERENCE");
    static final List<String> OMITTED = List.of(
            "NATIVE_WARP_INTERPOLATION",
            "EVALUATED_GEOMETRY",
            "GLOBAL_CELL_OVERLAP",
            "PARENT_TRANSFORMS",
            "POSE_CONTINUITY",
            "VISUAL_ART_QUALITY");

    record Cell(double minimumRatio, int sign, boolean folded, boolean degenerate) {
        boolean regular() {
            return !folded && !degenerate;
        }
    }

    record Report(
            List<Map<String, Object>> findings,
            long findingCount,
            long skippedComparisons,
            int referenceIrregularCells,
            int cellCount) {}

    static Report analyze(
            WarpAuthoringSnapshot snapshot,
            String referenceGuid,
            List<String> selected,
            double minimumRatio,
            int maxFindings) {
        if (!Double.isFinite(minimumRatio)
                || minimumRatio < 0
                || minimumRatio > 1
                || maxFindings < 1
                || maxFindings > 200
                || selected.isEmpty()
                || selected.size() > 32
                || new HashSet<>(selected).size() != selected.size()) throw new IllegalArgumentException();
        long count = (long) snapshot.rows() * snapshot.columns();
        if (count * (selected.size() + 1L) > MAX_CELL_SAMPLES) throw new BudgetExceeded();
        var forms = new HashMap<String, List<Point2>>();
        snapshot.forms().forEach(f -> forms.put(f.formGuid(), f.controlPoints()));
        if (!forms.containsKey(referenceGuid) || !forms.keySet().containsAll(selected))
            throw new NoSuchElementException();
        var ordinary = new HashSet<String>();
        snapshot.cells().forEach(c -> ordinary.add(c.formGuid()));
        if (!ordinary.contains(referenceGuid) || !ordinary.containsAll(selected))
            throw new UnsupportedOperationException();
        var findings = new ArrayList<Map<String, Object>>();
        long findingCount = 0, skipped = 0;
        int irregular = 0;
        for (int row = 0; row < snapshot.rows(); row++)
            for (int col = 0; col < snapshot.columns(); col++) {
                int a = row * (snapshot.columns() + 1) + col;
                var indices = List.of(a, a + 1, a + snapshot.columns() + 1, a + snapshot.columns() + 2);
                var reference = cell(forms.get(referenceGuid), indices, minimumRatio);
                if (!reference.regular()) {
                    irregular++;
                    findingCount++;
                    add(
                            findings,
                            maxFindings,
                            "REFERENCE_CELL_NOT_REGULAR",
                            "reference",
                            referenceGuid,
                            row,
                            col,
                            indices,
                            reference.minimumRatio(),
                            minimumRatio);
                }
                for (String guid : selected) {
                    var target = cell(forms.get(guid), indices, minimumRatio);
                    if (target.degenerate()) {
                        findingCount++;
                        add(
                                findings,
                                maxFindings,
                                "DEGENERATE_BILINEAR_CELL",
                                "target",
                                guid,
                                row,
                                col,
                                indices,
                                target.minimumRatio(),
                                minimumRatio);
                    }
                    if (target.folded()) {
                        findingCount++;
                        add(
                                findings,
                                maxFindings,
                                "FOLDED_BILINEAR_CELL",
                                "target",
                                guid,
                                row,
                                col,
                                indices,
                                target.minimumRatio(),
                                minimumRatio);
                    }
                    if (!reference.regular() || !target.regular()) skipped++;
                    else if (target.sign() != reference.sign()) {
                        findingCount++;
                        add(
                                findings,
                                maxFindings,
                                "CELL_ORIENTATION_REVERSED",
                                "target",
                                guid,
                                row,
                                col,
                                indices,
                                target.minimumRatio(),
                                minimumRatio);
                    }
                }
            }
        return new Report(List.copyOf(findings), findingCount, skipped, irregular, (int) count);
    }

    private static void add(
            List<Map<String, Object>> findings,
            int max,
            String code,
            String scope,
            String guid,
            int row,
            int col,
            List<Integer> indices,
            double observed,
            double threshold) {
        if (findings.size() < max)
            findings.add(Map.of(
                    "code",
                    code,
                    "severity",
                    "WARNING",
                    "scope",
                    scope,
                    "formGuid",
                    guid,
                    "row",
                    row,
                    "column",
                    col,
                    "controlPointIndices",
                    indices,
                    "observedMinimumAbsJacobianRatio",
                    observed,
                    "threshold",
                    threshold));
    }

    static Cell cell(List<Point2> points, List<Integer> ids, double threshold) {
        var p = ids.stream().map(points::get).toList();
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (var v : p) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            maxX = Math.max(maxX, v.x());
            maxY = Math.max(maxY, v.y());
        }
        double dx = maxX - minX, dy = maxY - minY, scale = dx * dx + dy * dy;
        BigDecimal[] j = {
            cross(p.get(0), p.get(1), p.get(0), p.get(2)),
            cross(p.get(0), p.get(1), p.get(1), p.get(3)),
            cross(p.get(2), p.get(3), p.get(0), p.get(2)),
            cross(p.get(2), p.get(3), p.get(1), p.get(3))
        };
        boolean positive = false, negative = false, zero = false;
        double minimum = Double.POSITIVE_INFINITY;
        for (var value : j) {
            positive |= value.signum() > 0;
            negative |= value.signum() < 0;
            zero |= value.signum() == 0;
            minimum = Math.min(minimum, scale == 0 ? 0 : Math.abs(value.doubleValue()) / scale);
        }
        return new Cell(minimum, positive ? 1 : negative ? -1 : 0, positive && negative, zero || minimum <= threshold);
    }

    private static BigDecimal cross(Point2 a, Point2 b, Point2 c, Point2 d) {
        return exact(b.x())
                .subtract(exact(a.x()))
                .multiply(exact(d.y()).subtract(exact(c.y())))
                .subtract(exact(b.y())
                        .subtract(exact(a.y()))
                        .multiply(exact(d.x()).subtract(exact(c.x()))));
    }

    private static BigDecimal exact(float value) {
        return new BigDecimal((double) value);
    }

    static final class BudgetExceeded extends IllegalArgumentException {}
}
