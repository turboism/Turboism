package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Selection the native Auto Mesh Generate entry shows: the running algorithm plus the parameter
 * values, preset list and preset name last used for each algorithm.
 *
 * <p>The algorithm choice is session state and delegates to {@link AutoMeshSessionAlgorithms}, so
 * it survives a plugin reload exactly like the catalog's own selection. The values are UI state
 * owned here and kept as canonical text, because one codec — {@link AutoMeshValueCodec} — must own
 * every conversion between declared bounds and the text a compute function receives.</p>
 *
 * <p>The preset list a plugin declares is a starting point, not the user's own list. As soon as the
 * user adds, saves, renames or deletes one preset, the algorithm's list belongs to the user and
 * {@link #presets} answers that list instead. Every change is written through {@link
 * AutoMeshPresetStore}, because a renamed preset must survive an Editor restart.</p>
 *
 * <p>Mutated and read on the Editor UI thread only. {@link AutoMeshDispatch} snapshots the resolved
 * values there, before the compute leaves the thread, so recomputation never mutates this state.</p>
 */
public final class AutoMeshSelectionState {

    private final AutoMeshSessionAlgorithms session;
    private final AutoMeshPresetStore store;
    private final Map<String, Map<String, String>> lastValues = new HashMap<>();
    private final Map<String, String> presetNames = new HashMap<>();
    private final Map<String, List<AlgorithmPreset>> customizedPresets = new HashMap<>();

    public AutoMeshSelectionState(final AutoMeshSessionAlgorithms session) {
        this(session, AutoMeshPresetStore.inMemory());
    }

    /**
     * Creates the selection of one session and loads the persisted preset state.
     *
     * @param session the session listing and algorithm selection this state delegates to
     * @param store the persistence the preset list and the last-used values are read from and written
     *     to
     */
    public AutoMeshSelectionState(final AutoMeshSessionAlgorithms session, final AutoMeshPresetStore store) {
        this.session = Objects.requireNonNull(session, "session");
        this.store = Objects.requireNonNull(store, "store");
        restore(store.load());
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
        final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        return codec.resolve(declaredValues(codec, valuesOf(algorithm.id())));
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
     * Lists the presets {@code algorithm} offers now.
     *
     * <p>A customized list is sanitized against the live schema, because a plugin update can drop
     * or rename a parameter between two Editor sessions. A stored value the schema no longer
     * declares cannot reach a compute function, so it is discarded instead of failing the list.</p>
     *
     * @param algorithm the algorithm whose presets are listed
     * @return the user's preset list when customized, the declared list otherwise
     */
    public List<AlgorithmPreset> presets(final AutoMeshAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        final List<AlgorithmPreset> stored = customizedPresets.get(algorithm.id());
        if (stored == null) {
            return algorithm.presets();
        }
        final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        final List<AlgorithmPreset> sanitized = new ArrayList<>(stored.size());
        for (final AlgorithmPreset preset : stored) {
            final Map<String, String> declared = declaredValues(codec, preset.values());
            if (declared.isEmpty() && !algorithm.parameters().isEmpty()) {
                // Nothing this schema declares survived, so the preset would run the plain defaults.
                continue;
            }
            sanitized.add(new AlgorithmPreset(preset.name(), codec.resolve(declared)));
        }
        return List.copyOf(sanitized);
    }

    /**
     * Applies one preset of {@code algorithm} and records it as the chosen preset.
     *
     * @param algorithm the algorithm that declares the preset
     * @param presetName the preset to apply
     * @throws IllegalArgumentException when the algorithm lists no preset with that name
     */
    public void selectPreset(final AutoMeshAlgorithm algorithm, final String presetName) {
        Objects.requireNonNull(algorithm, "algorithm");
        final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        final AlgorithmPreset preset = presets(algorithm).stream()
                .filter(candidate -> candidate.name().equals(presetName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "algorithm " + algorithm.id() + " declares no preset named " + presetName));
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        preset.values()
                .forEach((parameterId, value) -> values.put(parameterId, codec.canonicalize(parameterId, value)));
        lastValues.put(algorithm.id(), Collections.unmodifiableMap(values));
        presetNames.put(algorithm.id(), preset.name());
        persist();
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
        persist();
    }

    /**
     * Records a whole accepted value set.
     *
     * <p>A dialog session commits every row at once, so the preset name must survive the commit that
     * did not change any value. Comparing resolved maps keeps a preset selected when the user merely
     * reopened the dialog and pressed OK.</p>
     *
     * @param algorithm the algorithm that declares the parameters
     * @param values the values to store; every id must be declared and every value valid
     * @throws IllegalArgumentException when an id is undeclared or a value is invalid
     */
    public void setParameterValues(final AutoMeshAlgorithm algorithm, final Map<String, String> values) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(values, "values");
        final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        final LinkedHashMap<String, String> canonical = new LinkedHashMap<>();
        values.forEach((parameterId, value) -> canonical.put(parameterId, codec.canonicalize(parameterId, value)));
        lastValues.put(algorithm.id(), Collections.unmodifiableMap(canonical));
        keepSelectedPreset(algorithm, codec);
        persist();
    }

    /**
     * Saves the current values as a preset, replacing the preset of the same name.
     *
     * @param algorithm the algorithm the preset belongs to
     * @param presetName the preset name; it is trimmed and must not be blank
     * @param values the values to save
     * @return the saved preset with its complete canonical value set
     * @throws IllegalArgumentException when the name is blank or a value fails its descriptor
     */
    public AlgorithmPreset savePreset(
            final AutoMeshAlgorithm algorithm, final String presetName, final Map<String, String> values) {
        Objects.requireNonNull(algorithm, "algorithm");
        final String trimmedName = requirePresetName(presetName, "presetName");
        final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm);
        final Map<String, String> canonical = codec.resolve(Objects.requireNonNull(values, "values"));
        final AlgorithmPreset saved = new AlgorithmPreset(trimmedName, canonical);
        final List<AlgorithmPreset> updated = new ArrayList<>(presets(algorithm));
        final int existing = indexOf(updated, trimmedName);
        if (existing >= 0) {
            updated.set(existing, saved);
        } else {
            updated.add(saved);
        }
        customizedPresets.put(algorithm.id(), List.copyOf(updated));
        lastValues.put(algorithm.id(), canonical);
        presetNames.put(algorithm.id(), trimmedName);
        persist();
        return saved;
    }

    /**
     * Renames one preset of {@code algorithm} and keeps the selection pointing at it.
     *
     * @param algorithm the algorithm the preset belongs to
     * @param oldName the preset to rename
     * @param newName the new name; it is trimmed and must not be blank
     * @return the renamed preset, carrying the unchanged values
     * @throws IllegalArgumentException when a name is blank, {@code oldName} is unknown, or
     *     {@code newName} already names another preset
     */
    public AlgorithmPreset renamePreset(final AutoMeshAlgorithm algorithm, final String oldName, final String newName) {
        Objects.requireNonNull(algorithm, "algorithm");
        final String trimmedNewName = requirePresetName(newName, "newName");
        final List<AlgorithmPreset> current = new ArrayList<>(presets(algorithm));
        final int index = indexOf(current, oldName);
        if (index < 0) {
            throw new IllegalArgumentException("algorithm " + algorithm.id() + " declares no preset named " + oldName);
        }
        final int conflicting = indexOf(current, trimmedNewName);
        if (conflicting >= 0 && conflicting != index) {
            throw new IllegalArgumentException(
                    "algorithm " + algorithm.id() + " already declares a preset named " + trimmedNewName);
        }
        final AlgorithmPreset renamed =
                new AlgorithmPreset(trimmedNewName, current.get(index).values());
        current.set(index, renamed);
        customizedPresets.put(algorithm.id(), List.copyOf(current));
        if (oldName != null && oldName.equals(presetNames.get(algorithm.id()))) {
            presetNames.put(algorithm.id(), trimmedNewName);
        }
        persist();
        return renamed;
    }

    /**
     * Deletes one preset of {@code algorithm}.
     *
     * <p>A deleted preset that was selected hands the selection to its remaining neighbour, because
     * the user's intent was to keep the parameter set they saw, not to fall back to the defaults.</p>
     *
     * @param algorithm the algorithm the preset belongs to
     * @param presetName the preset to delete
     * @return {@code true} when the preset existed and is gone
     */
    public boolean deletePreset(final AutoMeshAlgorithm algorithm, final String presetName) {
        Objects.requireNonNull(algorithm, "algorithm");
        final List<AlgorithmPreset> remaining = new ArrayList<>(presets(algorithm));
        final int index = indexOf(remaining, presetName);
        if (index < 0) {
            return false;
        }
        remaining.remove(index);
        customizedPresets.put(algorithm.id(), List.copyOf(remaining));
        if (presetName != null && presetName.equals(presetNames.get(algorithm.id()))) {
            if (remaining.isEmpty()) {
                presetNames.remove(algorithm.id());
            } else {
                selectPreset(algorithm, remaining.get(Math.max(0, index - 1)).name());
            }
        }
        persist();
        return true;
    }

    /**
     * Drops the customized preset list and returns to what the plugin declared.
     *
     * <p>Restoring the defaults is the only way back to a list a plugin update can rotate, so the
     * first declared preset becomes the selection whenever one exists.</p>
     *
     * @param algorithm the algorithm whose preset list is restored
     * @return the restored preset list
     */
    public List<AlgorithmPreset> restoreDefaultPresets(final AutoMeshAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        customizedPresets.remove(algorithm.id());
        if (algorithm.presets().isEmpty()) {
            presetNames.remove(algorithm.id());
            lastValues.remove(algorithm.id());
            persist();
        } else {
            selectPreset(algorithm, algorithm.presets().get(0).name());
        }
        return presets(algorithm);
    }

    private void restore(final AutoMeshPresetStore.Snapshot snapshot) {
        snapshot.algorithms().forEach((algorithmId, state) -> {
            if (state.customized()) {
                customizedPresets.put(algorithmId, List.copyOf(state.presets()));
            }
            state.selectedPreset().ifPresent(name -> presetNames.put(algorithmId, name));
            if (!state.values().isEmpty()) {
                lastValues.put(algorithmId, Map.copyOf(state.values()));
            }
        });
    }

    private void persist() {
        final Set<String> algorithmIds = new HashSet<>(customizedPresets.keySet());
        algorithmIds.addAll(presetNames.keySet());
        algorithmIds.addAll(lastValues.keySet());
        final Map<String, AutoMeshPresetStore.AlgorithmState> states = new HashMap<>();
        for (final String algorithmId : algorithmIds) {
            states.put(
                    algorithmId,
                    new AutoMeshPresetStore.AlgorithmState(
                            customizedPresets.containsKey(algorithmId),
                            customizedPresets.getOrDefault(algorithmId, List.of()),
                            Optional.ofNullable(presetNames.get(algorithmId)),
                            lastValues.getOrDefault(algorithmId, Map.of())));
        }
        store.save(new AutoMeshPresetStore.Snapshot(states));
    }

    /**
     * Keeps the selected preset name while the stored values still equal that preset.
     *
     * <p>The dialog commits every row, including the rows it did not touch, so a preset must not be
     * dropped by a save that changed nothing.</p>
     */
    private void keepSelectedPreset(final AutoMeshAlgorithm algorithm, final AutoMeshValueCodec codec) {
        final String selected = presetNames.get(algorithm.id());
        if (selected == null) {
            return;
        }
        final Optional<AlgorithmPreset> preset = presets(algorithm).stream()
                .filter(candidate -> candidate.name().equals(selected))
                .findFirst();
        final Map<String, String> resolved = codec.resolve(valuesOf(algorithm.id()));
        if (preset.isPresent() && resolved.equals(codec.resolve(preset.get().values()))) {
            return;
        }
        presetNames.remove(algorithm.id());
    }

    /**
     * Keeps only the stored values the live schema declares and can canonicalize.
     *
     * <p>A persisted value outlives the plugin build that wrote it, so an undeclared id or an
     * out-of-range value is discarded instead of failing the whole parameter set.</p>
     */
    private static Map<String, String> declaredValues(
            final AutoMeshValueCodec codec, final Map<String, String> stored) {
        final LinkedHashMap<String, String> declared = new LinkedHashMap<>();
        stored.forEach((parameterId, value) -> {
            try {
                declared.put(parameterId, codec.canonicalize(parameterId, value));
            } catch (IllegalArgumentException unusable) {
                // Undeclared or invalid: the parameter keeps its declared default instead.
            }
        });
        return declared;
    }

    private static int indexOf(final List<AlgorithmPreset> presets, final String name) {
        for (int index = 0; index < presets.size(); index++) {
            if (presets.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static String requirePresetName(final String name, final String field) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return name.trim();
    }

    private Map<String, String> valuesOf(final String algorithmId) {
        return lastValues.getOrDefault(algorithmId, Map.of());
    }
}
