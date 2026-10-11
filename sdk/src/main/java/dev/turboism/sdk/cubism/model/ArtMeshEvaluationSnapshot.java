package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import dev.turboism.sdk.CubismEditor;

/**
 * Detached observation of the Editor's last calculated ArtMesh form, not its stored current keyform.
 * This does not trigger evaluation or assert freshness/success. The consumption stamp is written
 * at the start of a native pass; a later per-mesh failure can leave an older calculated form.
 * Coordinate labels are native labels, not a promise of Core coordinates or units.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record ArtMeshEvaluationSnapshot(
    ArtMeshId artMeshId,
    String sourceGuid,
    Optional<Form> calculatedForm,
    ParameterState liveParameters,
    ParameterState consumedParameters,
    long passEntryTimeMillis
) {
    public ArtMeshEvaluationSnapshot {
        Objects.requireNonNull(artMeshId, "artMeshId");
        requireText(sourceGuid, "sourceGuid");
        Objects.requireNonNull(calculatedForm, "calculatedForm");
        Objects.requireNonNull(liveParameters, "liveParameters");
        Objects.requireNonNull(consumedParameters, "consumedParameters");
        if (passEntryTimeMillis < -1) throw new IllegalArgumentException("invalid native pass entry time");
    }

    /** interpolated records native object identity equality, not successful interpolation. */
    public record Form(List<Point2> positions, int drawOrder, String coordinateType, boolean interpolated) {
        public Form {
            positions = List.copyOf(positions);
            requireText(coordinateType, "coordinateType");
        }
    }

    /**
     * Native signed counter, including possible overflow. Each parameter set has its own counter;
     * live and consumed versions must not be compared for equality. Consumed values may be old/empty.
     */
    public record ParameterState(int updateVersion, List<ParameterValue> parameters) {
        public ParameterState {
            parameters = List.copyOf(parameters);
            final var guids = new HashSet<String>();
            for (final var parameter : parameters) {
                if (!guids.add(parameter.parameterGuid())) {
                    throw new IllegalArgumentException("duplicate parameter GUID");
                }
            }
        }
    }

    /** One observed parameter identity and copied value; IDs need not be unique across GUIDs. */
    public record ParameterValue(String parameterGuid, ParameterId parameterId, float value) {
        public ParameterValue {
            requireText(parameterGuid, "parameterGuid");
            Objects.requireNonNull(parameterId, "parameterId");
            if (!Float.isFinite(value)) throw new IllegalArgumentException("non-finite parameter value");
        }
    }

    private static void requireText(final String value, final String name) {
        if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException("empty " + name);
    }
}
