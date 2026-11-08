package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Selection the native Auto Mesh Generate entry shows: the running algorithm plus the parameter
 * values and preset name last used for each algorithm.
 *
 * <p>The algorithm choice is session state and delegates to {@link AutoMeshSessionAlgorithms}, so
 * it survives a plugin reload exactly like the catalog's own selection. The values are UI state
 * owned here and kept as canonical text, because one codec — {@link AutoMeshValueCodec} — must own
 * every conversion between declared bounds and the text a compute function receives.</p>
 *
 * <p>Mutated and read on the Editor UI thread only. {@link AutoMeshDispatch} snapshots the resolved
 * values there, before the compute leaves the thread, so recomputation never mutates this state.</p>
 */
public final class AutoMeshSelectionState {

    private final AutoMeshSessionAlgorithms session;
    private final Map<String, Map<String, String>> lastValues = new HashMap<>();
    private final Map<String, String> presetNames = new HashMap<>();

    public AutoMeshSelectionState(final AutoMeshSessionAlgorithms session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** @return the algorithm the native entry runs now, or empty for the Editor's own generator */
    public Optional<String> selectedAlgorithmId() {
        return session.selectedAlgorithmId();
    }

    /**
     * Resolves one algorithm of this session.
     *
     * @param algorithmId the algorithm id to resolve
     * @return the live algorithm, or empty when the id is unregistered
     */
    public Optional<AutoMeshAlgorithm> find(final String algorithmId) {
        return session.find(algorithmId);
    }

    /**
     * Requests the algorithm the native entry runs.
     *
     * @param algorithmId the algorithm to select; a native value restores the Editor's generator
     */
    public void selectAlgorithm(final String algorithmId) {
        session.select(algorithmId);
    }

    /**
     * Returns the complete canonical value set a run of {@code algorithm} receives.
     *
     * @param algorithm the algorithm whose schema bounds every value
     * @return every declared parameter with its last-used or default canonical value
     */
    public Map<String, String> parameterValues(final AutoMeshAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        return AutoMeshValueCodec.forAlgorithm(algorithm).resolve(valuesOf(algorithm.id()));
    }

    /**
     * Reports the preset the user chose for {@code algorithmId}, while its values are unchanged.
     *
     * @param algorithmId the algorithm to inspect
     * @return the chosen preset name, or empty when the values are hand-edited or never chosen
     */
    public Optional<String> selectedPresetName(final String algorithmId) {
        return Optional.ofNullable(presetNames.get(algorithmId));
    }

    /**
     * Applies one preset of {@code algorithm} and records it as the chosen preset.
     *
     * @param algorithm the algorithm that declares the preset
     * @param presetName the preset to apply
     * @throws IllegalArgumentException when the algorithm declares no preset with that name
     */
    public void selectPreset(final AutoMeshAlgorithm algorithm, final String presetName) {
        Objects.requireNonNull(algorithm, "algorithm");
        final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        final AlgorithmPreset preset = algorithm.presets().stream()
                .filter(candidate -> candidate.name().equals(presetName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "algorithm " + algorithm.id() + " declares no preset named " + presetName));
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        preset.values()
                .forEach((parameterId, value) -> values.put(parameterId, codec.canonicalize(parameterId, value)));
        lastValues.put(algorithm.id(), Collections.unmodifiableMap(values));
        presetNames.put(algorithm.id(), preset.name());
    }

    /**
     * Records one hand-edited value and drops the preset name it diverges from.
     *
     * @param algorithm the algorithm that declares the parameter
     * @param parameterId the declared parameter to change
     * @param value the value text; it is canonicalized and validated against the descriptor
     * @throws IllegalArgumentException when the parameter is undeclared or the value is invalid
     */
    public void setParameterValue(final AutoMeshAlgorithm algorithm, final String parameterId, final String value) {
        Objects.requireNonNull(algorithm, "algorithm");
        final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        final LinkedHashMap<String, String> values = new LinkedHashMap<>(valuesOf(algorithm.id()));
        values.put(parameterId, codec.canonicalize(parameterId, value));
        lastValues.put(algorithm.id(), Collections.unmodifiableMap(values));
        presetNames.remove(algorithm.id());
    }

    private Map<String, String> valuesOf(final String algorithmId) {
        return lastValues.getOrDefault(algorithmId, Map.of());
    }
}
