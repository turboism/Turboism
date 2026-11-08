package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One named parameter set an algorithm offers next to the native preset list.
 *
 * <p>Presets belong to the algorithm that declares them: a preset for one algorithm is
 * meaningless for another, so the framework only supplies the shape and lets the owning plugin
 * decide the names and the values. Every value is canonical text of the matching {@link
 * AlgorithmParameterDescriptor}, and a preset may name only some parameters — an unset parameter
 * keeps its declared default.</p>
 *
 * <p>Values that name no declared parameter are rejected by {@link AutoMeshAlgorithm}, which is
 * the only type that sees the parameter schema.</p>
 */
@CubismEditor
public record AlgorithmPreset(String name, Map<String, String> values) {

    /** Longest accepted preset name. */
    public static final int MAX_NAME_LENGTH = 128;

    public AlgorithmPreset {
        name = AutoMeshContracts.requireText(name, "name", MAX_NAME_LENGTH);
        Objects.requireNonNull(values, "values");
        final LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((parameterId, value) -> copied.put(
                AutoMeshContracts.requireId(parameterId, "values key"),
                AutoMeshContracts.requireValue(value, "values[" + parameterId + "]")));
        values = Collections.unmodifiableMap(copied);
    }
}
