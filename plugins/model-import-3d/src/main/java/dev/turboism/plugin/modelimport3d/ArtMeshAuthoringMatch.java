package dev.turboism.plugin.modelimport3d;

import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder;
import dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares a written authoring snapshot with the host's readback.
 *
 * <p>The host stores keyform positions as 32-bit floats and recomputes them from its own grid, so a
 * mathematically identical write can read back a few units of least precision away. Exact record
 * equality therefore reported a rollback on a correct import. Identity fields still compare exactly;
 * only positions compare within {@link #POSITION_TOLERANCE}, and a mismatch reports the worst delta
 * so the next failure is diagnosable instead of silent.</p>
 */
final class ArtMeshAuthoringMatch {

    /** Absolute position tolerance; far below one pixel at any supported editor zoom. */
    static final float POSITION_TOLERANCE = 1e-4f;

    private static final String POSITIONS = "positions";
    private static final String DRAW_ORDER = "draw order";
    private static final String FORM = "form";

    private ArtMeshAuthoringMatch() {}

    /**
     * @param expected the values the write intended to store
     * @param actual the snapshot the host reported
     * @return {@code null} when they match, otherwise a description of the first difference
     */
    static String describe(final ArtMeshAuthoringSnapshot expected, final ArtMeshAuthoringSnapshot actual) {
        if (expected == null || actual == null) return "snapshot missing";
        if (!expected.artMeshId().equals(actual.artMeshId())) return "art mesh id";
        if (!expected.sourceGuid().equals(actual.sourceGuid())) return "source guid";
        if (!expected.sourceGeometry().equals(actual.sourceGeometry())) return "source geometry";
        if (!expected.axes().equals(actual.axes())) return "parameter axes";
        if (!expected.cells().equals(actual.cells())) return "keyform cells";
        if (!expected.morphKeys().equals(actual.morphKeys())) return "morph keys";
        final Map<String, ArtMeshAuthoringSnapshot.Form> written = byGuid(expected.forms());
        final Map<String, ArtMeshAuthoringSnapshot.Form> read = byGuid(actual.forms());
        if (!written.keySet().equals(read.keySet())) return FORM + " set";
        for (Map.Entry<String, ArtMeshAuthoringSnapshot.Form> entry : written.entrySet()) {
            final ArtMeshAuthoringSnapshot.Form left = entry.getValue();
            final ArtMeshAuthoringSnapshot.Form right = read.get(entry.getKey());
            if (left.drawOrder() != right.drawOrder()) return entry.getKey() + " " + DRAW_ORDER;
            if (!left.opacity().equals(right.opacity())) return entry.getKey() + " opacity";
            final String positions = describePositions(left.positions(), right.positions());
            if (positions != null) return entry.getKey() + " " + positions;
        }
        return null;
    }

    /**
     * @param expected the values the write intended to store
     * @param actual the snapshot the host reported
     * @return whether the two snapshots describe the same authoring state
     */
    static boolean matches(final ArtMeshAuthoringSnapshot expected, final ArtMeshAuthoringSnapshot actual) {
        return describe(expected, actual) == null;
    }

    /**
     * Compares one compare-and-set batch with the snapshot the host stored.
     *
     * <p>Only the values this batch asked the host to store are compared. The snapshot also carries
     * forms and draw orders the batch never touched, and predicting those turned a correct import
     * into a rollback: the neutral form keeps the order it was created with, not the order the
     * projection would have chosen. The failure text names the field first, because the authoring
     * diagnostic is cut at 128 characters and a leading form GUID pushed the field out of it.</p>
     *
     * @param positions the position edits the batch submitted
     * @param orders the draw-order edits the batch submitted
     * @param actual the snapshot the host reported after the batch
     * @return {@code null} when every submitted edit landed, otherwise the first difference
     */
    static String describeEdits(
            final List<ArtMeshKeyformPositions> positions,
            final List<ArtMeshKeyformDrawOrder> orders,
            final ArtMeshAuthoringSnapshot actual) {
        if (actual == null) return "snapshot missing";
        final Map<String, ArtMeshAuthoringSnapshot.Form> read = byGuid(actual.forms());
        for (ArtMeshKeyformPositions edit : positions) {
            final ArtMeshAuthoringSnapshot.Form form = read.get(edit.formGuid());
            if (form == null) return "form missing " + edit.formGuid();
            final String delta = describePositions(edit.positions(), form.positions());
            if (delta != null) return delta + " form " + edit.formGuid();
        }
        for (ArtMeshKeyformDrawOrder edit : orders) {
            final ArtMeshAuthoringSnapshot.Form form = read.get(edit.formGuid());
            if (form == null) return "form missing " + edit.formGuid();
            if (form.drawOrder() != edit.drawOrder()) {
                return DRAW_ORDER + " " + edit.drawOrder() + "!=" + form.drawOrder() + " form " + edit.formGuid();
            }
        }
        return null;
    }

    private static Map<String, ArtMeshAuthoringSnapshot.Form> byGuid(final List<ArtMeshAuthoringSnapshot.Form> forms) {
        final Map<String, ArtMeshAuthoringSnapshot.Form> result = new LinkedHashMap<>();
        for (ArtMeshAuthoringSnapshot.Form form : forms) {
            result.put(form.formGuid(), form);
        }
        return result;
    }

    private static String describePositions(final List<Point2> expected, final List<Point2> actual) {
        if (expected.size() != actual.size()) {
            return POSITIONS + " count " + expected.size() + "!=" + actual.size();
        }
        float worst = 0.0f;
        for (int index = 0; index < expected.size(); index++) {
            final Point2 left = Objects.requireNonNull(expected.get(index), POSITIONS);
            final Point2 right = Objects.requireNonNull(actual.get(index), POSITIONS);
            worst = Math.max(worst, Math.max(Math.abs(left.x() - right.x()), Math.abs(left.y() - right.y())));
        }
        return worst <= POSITION_TOLERANCE ? null : POSITIONS + " delta " + worst;
    }
}
