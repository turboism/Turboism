package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One mesh-placement algorithm a plugin contributes to the native Auto Mesh Generate entry.
 *
 * <p>An algorithm owns its typed parameters ({@link AlgorithmParameterDescriptor}), its named
 * presets ({@link AlgorithmPreset}) and the compute function that turns a request into geometry.
 * The declaration is validated here, so the runtime never has to defend against a malformed
 * schema when it builds the selector.</p>
 *
 * <p>{@link #NATIVE_ALGORITHM_ID} is reserved for the Editor's own generator, which always leads
 * the listing: it has no parameters and no presets (those live in the native panel) and no compute
 * function, because the host performs the work.</p>
 *
 * <p>{@link #supportsMultiKeyform()} states whether the compute function itself consumes the
 * keyform data of a multi-keyform target. The runtime derives each ordinary form's positions from
 * the one returned layout, so the flag reports a compute capability and never gates a run.</p>
 */
@CubismEditor
public record AutoMeshAlgorithm(
        String id,
        String displayName,
        String description,
        List<AlgorithmParameterDescriptor> parameters,
        List<AlgorithmPreset> presets,
        boolean supportsMultiKeyform,
        AutoMeshCompute compute) {

    /** Reserved id of the Editor's own generator; a plugin can never register it. */
    public static final String NATIVE_ALGORITHM_ID = "native";

    /** Most parameters one algorithm may declare. */
    public static final int MAX_PARAMETERS = 32;

    /** Most presets one algorithm may declare. */
    public static final int MAX_PRESETS = 64;

    /** Longest accepted display name. */
    public static final int MAX_DISPLAY_NAME_LENGTH = 128;

    public AutoMeshAlgorithm {
        id = AutoMeshContracts.requireId(id, "id");
        displayName = AutoMeshContracts.requireText(displayName, "displayName", MAX_DISPLAY_NAME_LENGTH);
        description = AutoMeshContracts.requireDescription(description);
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        presets = List.copyOf(Objects.requireNonNull(presets, "presets"));
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("parameters must contain at most " + MAX_PARAMETERS + " entries");
        }
        if (presets.size() > MAX_PRESETS) {
            throw new IllegalArgumentException("presets must contain at most " + MAX_PRESETS + " entries");
        }
        final Map<String, AlgorithmParameterDescriptor> byId = new HashMap<>();
        for (final AlgorithmParameterDescriptor parameter : parameters) {
            Objects.requireNonNull(parameter, "parameter");
            if (byId.put(parameter.id(), parameter) != null) {
                throw new IllegalArgumentException("parameter ids must be unique: " + parameter.id());
            }
        }
        final HashSet<String> presetNames = new HashSet<>();
        for (final AlgorithmPreset preset : presets) {
            Objects.requireNonNull(preset, "preset");
            if (!presetNames.add(preset.name())) {
                throw new IllegalArgumentException("preset names must be unique: " + preset.name());
            }
            preset.values().forEach((parameterId, value) -> {
                final AlgorithmParameterDescriptor parameter = byId.get(parameterId);
                if (parameter == null) {
                    throw new IllegalArgumentException(
                            "preset " + preset.name() + " names an undeclared parameter: " + parameterId);
                }
                parameter.canonicalize(value);
            });
        }
        final boolean nativeAlgorithm = NATIVE_ALGORITHM_ID.equals(id);
        if (nativeAlgorithm != (compute == null)) {
            throw new IllegalArgumentException("only the reserved native algorithm may omit its compute function");
        }
        if (nativeAlgorithm && (!parameters.isEmpty() || !presets.isEmpty())) {
            throw new IllegalArgumentException("the native algorithm declares no parameters and no presets");
        }
        if (nativeAlgorithm && supportsMultiKeyform) {
            throw new IllegalArgumentException(
                    "the native algorithm declares no keyform capability; the host remaps keyforms itself");
        }
    }

    /** Reports whether this entry delegates to the Editor's own generator. */
    public boolean isNative() {
        return compute == null;
    }
}
