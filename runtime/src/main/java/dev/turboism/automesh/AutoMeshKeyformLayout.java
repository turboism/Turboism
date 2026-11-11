package dev.turboism.automesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Axis;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder;
import dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Derives one plugin mesh layout for every ordinary stored form of an ArtMesh.
 *
 * <p>A plugin returns its layout once, in the position space of the form the Editor evaluates now.
 * A keyformed ArtMesh needs the same layout in every ordinary form, or the write collapses the
 * forms the user authored. This class maps each layout uv through the mesh source uvs and
 * triangles together with one form's own stored positions, so every form keeps its own shape.</p>
 *
 * <p>Two calls frame the topology write. {@link #capture} runs before the transaction and keeps
 * only state that survives the write: each form's identity, cell coordinates and stored positions,
 * plus the source uv and triangle arrays. {@link #apply} runs after the write, reads the stored
 * bank again, derives every form from the captured correspondence, and submits one batch against
 * that fresh read. The batch therefore describes the topology the write installed, not the one it
 * replaced.</p>
 *
 * <p>The default form stays out of the batch: the Editor owns it and refuses stored position edits
 * on it. A form that the batch cannot address, a vertex count the layout cannot fill, or a mapping
 * that collapses a form refuses the whole write, because a partial batch leaves the forms
 * inconsistent. {@link #apply} also verifies the stored bank after the topology write, so a host
 * that does not keep every form fails closed instead of receiving a mismatched batch.</p>
 */
final class AutoMeshKeyformLayout {

    /** Form count of an ArtMesh that stores the neutral form only. */
    private static final int PLAIN_FORM_COUNT = 1;

    /** Position span below which a form has collapsed into one point. */
    private static final float COLLAPSED_FORM_SPAN = 1.0e-6f;

    private static final String MESSAGE_NOT_ORDINARY = "The ArtMesh carries a keyform that Auto Mesh cannot rewrite: ";
    private static final String MESSAGE_VERTEX_COUNT =
            "A keyform of this ArtMesh cannot accept the new mesh vertex count: ";
    private static final String MESSAGE_MAPPING = "A keyform of this ArtMesh cannot map the new mesh positions: ";
    private static final String MESSAGE_UNCHANGED =
            "The Editor did not keep every keyform of this ArtMesh after the mesh update: ";
    private static final String MESSAGE_SUFFIX = ".";

    private final String targetId;
    private final List<Point2> sourceUvs;
    private final List<Integer> sourceTriangles;
    private final List<FormCorrespondence> correspondences;
    private final Set<String> formGuids;

    private AutoMeshKeyformLayout(
            final String targetId,
            final List<Point2> sourceUvs,
            final List<Integer> sourceTriangles,
            final List<FormCorrespondence> correspondences,
            final Set<String> formGuids) {
        this.targetId = targetId;
        this.sourceUvs = sourceUvs;
        this.sourceTriangles = sourceTriangles;
        this.correspondences = correspondences;
        this.formGuids = formGuids;
    }

    /**
     * Reads one target before its topology write and captures the per-form correspondence.
     *
     * @param target the ArtMesh the run will replace; must be read on the Editor UI thread
     * @return the captured correspondence, or empty when the host stores one form only and the
     *     topology write already covers it
     * @throws Refusal when the stored forms cannot receive the layout
     */
    static Optional<AutoMeshKeyformLayout> capture(final Drawable target) {
        Objects.requireNonNull(target, "target");
        final ArtMeshAuthoringSnapshot before;
        try {
            before = target.authoringSnapshot();
        } catch (UnsupportedOperationException unavailable) {
            return Optional.empty();
        }
        if (before.cells().isEmpty() && before.morphKeys().isEmpty()) {
            if (before.forms().size() <= PLAIN_FORM_COUNT) {
                return Optional.empty();
            }
            throw refusal(MESSAGE_NOT_ORDINARY, before.artMeshId().value());
        }
        return Optional.of(capture(before));
    }

    private static AutoMeshKeyformLayout capture(final ArtMeshAuthoringSnapshot before) {
        final String targetId = before.artMeshId().value();
        final Map<String, List<Coordinate>> coordinatesByForm = new LinkedHashMap<>();
        for (final Cell cell : before.cells()) {
            if (coordinatesByForm.put(cell.formGuid(), cell.coordinates()) != null) {
                throw refusal(MESSAGE_NOT_ORDINARY, targetId);
            }
        }
        final Set<String> morphForms = new HashSet<>();
        before.morphKeys().forEach(key -> morphForms.add(key.formGuid()));
        final String defaultFormGuid = defaultFormGuid(before, targetId);
        final List<Point2> sourceUvs = before.sourceGeometry().uvs();
        final List<FormCorrespondence> correspondences =
                new ArrayList<>(before.forms().size());
        final Set<String> formGuids = new HashSet<>(before.forms().size());
        for (final Form form : before.forms()) {
            formGuids.add(form.formGuid());
            final List<Coordinate> coordinates = coordinatesByForm.get(form.formGuid());
            if (coordinates == null || morphForms.contains(form.formGuid())) {
                throw refusal(MESSAGE_NOT_ORDINARY, targetId);
            }
            if (form.positions().size() != sourceUvs.size()) {
                throw refusal(MESSAGE_VERTEX_COUNT, targetId);
            }
            if (form.formGuid().equals(defaultFormGuid)) {
                continue;
            }
            requireSpan(form.positions(), targetId);
            correspondences.add(new FormCorrespondence(form.formGuid(), coordinates, form.positions()));
        }
        return new AutoMeshKeyformLayout(
                targetId,
                sourceUvs,
                before.sourceGeometry().triangleIndices(),
                List.copyOf(correspondences),
                Set.copyOf(formGuids));
    }

    private static String defaultFormGuid(final ArtMeshAuthoringSnapshot before, final String targetId) {
        final Map<String, Float> defaults = new HashMap<>();
        for (final Axis axis : before.axes()) {
            defaults.put(axis.parameterGuid(), axis.defaultValue());
        }
        for (final Cell cell : before.cells()) {
            if (cell.coordinates().size() == defaults.size()
                    && cell.coordinates().stream()
                            .allMatch(coordinate -> defaults.containsKey(coordinate.parameterGuid())
                                    && Float.compare(defaults.get(coordinate.parameterGuid()), coordinate.value())
                                            == 0)) {
                return cell.formGuid();
            }
        }
        throw refusal(MESSAGE_NOT_ORDINARY, targetId);
    }

    private static void requireSpan(final List<Point2> positions, final String targetId) {
        float minimumX = Float.MAX_VALUE;
        float maximumX = -Float.MAX_VALUE;
        float minimumY = Float.MAX_VALUE;
        float maximumY = -Float.MAX_VALUE;
        for (final Point2 point : positions) {
            minimumX = Math.min(minimumX, point.x());
            maximumX = Math.max(maximumX, point.x());
            minimumY = Math.min(minimumY, point.y());
            maximumY = Math.max(maximumY, point.y());
        }
        if (maximumX - minimumX <= COLLAPSED_FORM_SPAN && maximumY - minimumY <= COLLAPSED_FORM_SPAN) {
            throw refusal(MESSAGE_MAPPING, targetId);
        }
    }

    /**
     * Writes the derived batch after the topology write.
     *
     * <p>The stored bank is read again because the batch replaces the source geometry the topology
     * write installed: only the state after that write can serve as its compare-and-set base, and
     * only that state reports the draw orders the forms now hold. The read also proves that the
     * host kept every form, so a host that resizes none of them refuses the write instead of
     * receiving positions at the wrong vertex count.</p>
     *
     * @param target the ArtMesh the topology write just replaced
     * @param layout the geometry the topology write installed
     * @throws Refusal when the stored bank no longer matches the captured correspondence
     */
    void apply(final Drawable target, final ArtMeshGeometry layout) {
        if (correspondences.isEmpty()) {
            return;
        }
        final ArtMeshAuthoringSnapshot after;
        try {
            after = target.authoringSnapshot();
        } catch (RuntimeException unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            throw refusal(MESSAGE_UNCHANGED, targetId);
        }
        requireComplete(after, layout);
        final Map<String, FormCorrespondence> byFormGuid =
                correspondences.stream().collect(Collectors.toMap(FormCorrespondence::formGuid, Function.identity()));
        final List<ArtMeshKeyformPositions> positions = new ArrayList<>(correspondences.size());
        final List<ArtMeshKeyformDrawOrder> drawOrders = new ArrayList<>(correspondences.size());
        for (final Form form : after.forms()) {
            final FormCorrespondence correspondence = byFormGuid.get(form.formGuid());
            if (correspondence == null) {
                continue;
            }
            positions.add(new ArtMeshKeyformPositions(
                    correspondence.coordinates(), form.formGuid(), mapped(correspondence.positions(), layout)));
            drawOrders.add(
                    new ArtMeshKeyformDrawOrder(correspondence.coordinates(), form.formGuid(), form.drawOrder()));
        }
        target.setOrdinaryKeyforms(after, List.copyOf(positions), List.copyOf(drawOrders));
    }

    private void requireComplete(final ArtMeshAuthoringSnapshot after, final ArtMeshGeometry layout) {
        final boolean kept = after.artMeshId().value().equals(targetId)
                && after.sourceGeometry().equals(layout)
                && after.forms().stream()
                        .map(Form::formGuid)
                        .collect(Collectors.toSet())
                        .equals(formGuids)
                && after.forms().stream()
                        .allMatch(
                                form -> form.positions().size() == layout.uvs().size());
        if (!kept) {
            throw refusal(MESSAGE_UNCHANGED, targetId);
        }
    }

    private List<Point2> mapped(final List<Point2> formPositions, final ArtMeshGeometry layout) {
        final AutoMeshUvMapping mapping = AutoMeshUvMapping.of(sourceUvs, formPositions, sourceTriangles);
        final List<Point2> mapped = new ArrayList<>(layout.uvs().size());
        try {
            for (final Point2 sample : layout.uvs()) {
                mapped.add(mapping.map(sample.x(), sample.y()));
            }
        } catch (IllegalArgumentException nonFinite) {
            throw refusal(MESSAGE_MAPPING, targetId);
        }
        return List.copyOf(mapped);
    }

    private static Refusal refusal(final String message, final String targetId) {
        return new Refusal(message + targetId + MESSAGE_SUFFIX);
    }

    /** One ordinary stored form: its batch address and the shape it held before the write. */
    private record FormCorrespondence(String formGuid, List<Coordinate> coordinates, List<Point2> positions) {}

    /** Fail-closed signal of one layout that the stored forms cannot accept. */
    static final class Refusal extends RuntimeException {

        private Refusal(final String message) {
            super(message, null, false, false);
        }
    }
}
