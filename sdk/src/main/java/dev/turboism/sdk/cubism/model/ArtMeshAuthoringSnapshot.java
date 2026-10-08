package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Detached stored authoring values, not an evaluated shape or a writable target handle.
 * Collection order and shared form identities are preserved. A default value need not be a
 * declared key; consumers must not infer a default cell from the first key or a nearest key.
 * This snapshot does not classify morph keys or convert their stored positions to absolute ones.
 */
public record ArtMeshAuthoringSnapshot(
    ArtMeshId artMeshId,
    String sourceGuid,
    ArtMeshGeometry sourceGeometry,
    List<Axis> axes,
    List<Cell> cells,
    List<Form> forms,
    List<MorphKey> morphKeys
) {
    public ArtMeshAuthoringSnapshot {
        Objects.requireNonNull(artMeshId, "artMeshId");
        sourceGuid = identity(sourceGuid);
        Objects.requireNonNull(sourceGeometry, "sourceGeometry");
        axes = List.copyOf(axes);
        cells = List.copyOf(cells);
        forms = List.copyOf(forms);
        morphKeys = List.copyOf(morphKeys);
        final var axisByGuid = new HashMap<String, Axis>();
        for (final Axis axis : axes) {
            if (axisByGuid.putIfAbsent(axis.parameterGuid(), axis) != null) {
                throw new IllegalArgumentException("duplicate axis GUID");
            }
        }
        final var formGuids = new HashSet<String>();
        for (final Form form : forms) {
            if (!formGuids.add(form.formGuid())) {
                throw new IllegalArgumentException("duplicate form GUID");
            }
            if (form.positions().size() != sourceGeometry.positions().size()) {
                throw new IllegalArgumentException("stored form vertex count differs from source");
            }
        }
        for (final Cell cell : cells) {
            requireForm(formGuids, cell.formGuid());
            final var dimensions = new HashSet<String>();
            for (final Coordinate coordinate : cell.coordinates()) {
                final Axis axis = axisByGuid.get(coordinate.parameterGuid());
                if (axis == null || !dimensions.add(coordinate.parameterGuid())) {
                    throw new IllegalArgumentException("unknown or duplicate cell dimension");
                }
                if (!axis.keys().contains(coordinate.value())) {
                    throw new IllegalArgumentException("cell coordinate is not a declared key");
                }
            }
            if (dimensions.size() != axes.size()) {
                throw new IllegalArgumentException("incomplete cell coordinates");
            }
        }
        for (final MorphKey key : morphKeys) requireForm(formGuids, key.formGuid());
    }

    /** An ordinary grid axis. Different GUIDs may legitimately have the same parameter ID. */
    public record Axis(
        String parameterGuid, ParameterId parameterId,
        float minimum, float defaultValue, float maximum, List<Float> keys
    ) {
        public Axis {
            parameterGuid = identity(parameterGuid);
            Objects.requireNonNull(parameterId, "parameterId");
            finite(minimum);
            finite(defaultValue);
            finite(maximum);
            if (minimum > maximum || defaultValue < minimum || defaultValue > maximum) {
                throw new IllegalArgumentException("invalid parameter range");
            }
            keys = List.copyOf(keys);
            keys.forEach(ArtMeshAuthoringSnapshot::finite);
        }
    }

    /** A coordinate uses GUID identity, never an ambiguous parameter ID. */
    public record Coordinate(String parameterGuid, float value) {
        public Coordinate {
            parameterGuid = identity(parameterGuid);
            finite(value);
        }
    }

    /** One actual grid row; multiple rows may reference the same form. */
    public record Cell(String formGuid, List<Coordinate> coordinates) {
        public Cell {
            formGuid = identity(formGuid);
            coordinates = List.copyOf(coordinates);
        }
    }

    /** Positions are the native stored values, not the currently evaluated form. */
    public record Form(String formGuid, List<Point2> positions, int drawOrder) {
        public Form {
            formGuid = identity(formGuid);
            positions = List.copyOf(positions);
        }
    }

    /** Empty keyValue preserves a native null. Zero does not imply basic-key identity. */
    public record MorphKey(String parameterGuid, String formGuid, Optional<Float> keyValue) {
        public MorphKey {
            parameterGuid = identity(parameterGuid);
            formGuid = identity(formGuid);
            Objects.requireNonNull(keyValue, "keyValue").ifPresent(ArtMeshAuthoringSnapshot::finite);
        }
    }

    private static String identity(final String value) {
        if (Objects.requireNonNull(value, "identity").isBlank()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
        return value;
    }

    private static void finite(final float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("value must be finite");
    }

    private static void requireForm(final HashSet<String> guids, final String guid) {
        if (!guids.contains(guid)) throw new IllegalArgumentException("missing stored form: " + guid);
    }
}
