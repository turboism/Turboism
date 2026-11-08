package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decisions the native Auto Mesh selector row makes, with no Swing type.
 *
 * <p>The row itself is built from the host's own widgets, which cannot be constructed off a real
 * display. Every choice the row makes — whether it exists at all, which entries the two combos
 * list, which entry is selected, what a selection change does — is therefore decided here, so the
 * contract is testable headlessly while the widget layer stays a thin renderer.</p>
 *
 * <p>The row exists only when at least one plugin algorithm is registered. A user without an
 * algorithm plugin sees the native Auto Mesh Generate popup exactly as before, which is the whole
 * point of keeping the native tool as the default.</p>
 */
public final class AutoMeshSelectorController {

    /** One entry of the algorithm combo: the Editor's generator, or a registered algorithm. */
    public record AlgorithmEntry(String id, String label) {

        public AlgorithmEntry {
            id = Objects.requireNonNull(id, "id");
            label = Objects.requireNonNull(label, "label");
        }
    }

    private static final String NATIVE_LABEL_KEY = "selector.algorithm.native";
    private static final String NO_PRESET_KEY = "selector.preset.none";
    private static final int NATIVE_INDEX = 0;
    private static final int NO_PRESET_INDEX = 0;

    private final AutoMeshSelectorSource source;
    private final Locale locale;

    /**
     * Creates the controller of one container.
     *
     * @param source the session listing and selection the row reads and writes
     * @param locale the host-effective locale for the framework's own text
     */
    public AutoMeshSelectorController(final AutoMeshSelectorSource source, final Locale locale) {
        this.source = Objects.requireNonNull(source, "source");
        this.locale = locale;
    }

    /**
     * Reports whether the row belongs in the native popup.
     *
     * @return {@code true} when at least one plugin algorithm is registered
     */
    public boolean rowVisible() {
        return source.algorithms().stream().anyMatch(algorithm -> !algorithm.isNative());
    }

    /** @return every combo entry: the Editor's generator first, then the live registrations */
    public List<AlgorithmEntry> algorithmEntries() {
        final List<AlgorithmEntry> entries = new ArrayList<>();
        for (final AutoMeshAlgorithm algorithm : source.algorithms()) {
            entries.add(new AlgorithmEntry(algorithm.id(), labelOf(algorithm)));
        }
        return List.copyOf(entries);
    }

    /** @return the index of the entry the session selection names, or {@code 0} for the native one */
    public int selectedAlgorithmIndex() {
        final List<AlgorithmEntry> entries = algorithmEntries();
        final String selected = source.selection().selectedAlgorithmId().orElse(null);
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id().equals(selected)) {
                return index;
            }
        }
        return NATIVE_INDEX;
    }

    /**
     * Applies one algorithm choice.
     *
     * @param index the chosen combo index; an index outside the listing selects the Editor's
     *     generator
     * @return the algorithm that is now selected, or empty for the Editor's own generator
     */
    public Optional<AutoMeshAlgorithm> selectAlgorithm(final int index) {
        final List<AlgorithmEntry> entries = algorithmEntries();
        final String id = index <= NATIVE_INDEX || index >= entries.size()
                ? AutoMeshAlgorithm.NATIVE_ALGORITHM_ID
                : entries.get(index).id();
        source.selection().selectAlgorithm(id);
        return source.selection().find(id).filter(algorithm -> !algorithm.isNative());
    }

    /**
     * Lists the preset combo entries of the selected algorithm.
     *
     * <p>The first entry is always the "custom" placeholder, because hand-edited values are not a
     * preset. An algorithm that declares no preset therefore shows one entry instead of an empty
     * combo.</p>
     *
     * @return the preset entries, the custom placeholder first
     */
    public List<String> presetEntries() {
        final List<String> entries = new ArrayList<>();
        entries.add(AutoMeshText.text(locale, NO_PRESET_KEY));
        selectedAlgorithm()
                .ifPresent(algorithm ->
                        algorithm.presets().stream().map(AlgorithmPreset::name).forEach(entries::add));
        return List.copyOf(entries);
    }

    /** @return the index of the preset the selection names, or {@code 0} for hand-edited values */
    public int selectedPresetIndex() {
        final Optional<AutoMeshAlgorithm> algorithm = selectedAlgorithm();
        if (algorithm.isEmpty()) {
            return NO_PRESET_INDEX;
        }
        final Optional<String> chosen = source.selection().selectedPresetName(algorithm.get().id());
        if (chosen.isEmpty()) {
            return NO_PRESET_INDEX;
        }
        final List<AlgorithmPreset> presets = algorithm.get().presets();
        for (int index = 0; index < presets.size(); index++) {
            if (presets.get(index).name().equals(chosen.get())) {
                return index + 1;
            }
        }
        return NO_PRESET_INDEX;
    }

    /**
     * Applies one preset choice.
     *
     * @param index the chosen preset index; {@code 0} is the custom placeholder and changes nothing
     * @return {@code true} when a preset was applied
     */
    public boolean selectPreset(final int index) {
        final Optional<AutoMeshAlgorithm> algorithm = selectedAlgorithm();
        if (algorithm.isEmpty() || index <= NO_PRESET_INDEX) {
            return false;
        }
        final List<AlgorithmPreset> presets = algorithm.get().presets();
        if (index > presets.size()) {
            return false;
        }
        source.selection().selectPreset(algorithm.get(), presets.get(index - 1).name());
        return true;
    }

    /**
     * Reports whether the parameter button can open a dialog.
     *
     * @return {@code true} when the selected algorithm declares at least one parameter
     */
    public boolean parametersAvailable() {
        return selectedAlgorithm().map(algorithm -> !algorithm.parameters().isEmpty()).orElse(false);
    }

    /** @return the algorithm the row currently describes, or empty for the Editor's generator */
    public Optional<AutoMeshAlgorithm> selectedAlgorithm() {
        return source.selection().selectedAlgorithmId().flatMap(source.selection()::find);
    }

    /**
     * Builds the parameter model of the selected algorithm.
     *
     * @return the model seeded with the session values, or empty when no algorithm is selected
     */
    public Optional<AutoMeshParameterModel> parameterModel() {
        return selectedAlgorithm()
                .map(algorithm -> new AutoMeshParameterModel(
                        algorithm, source.selection().parameterValues(algorithm)));
    }

    /**
     * Stores the accepted values of one dialog session.
     *
     * @param algorithm the algorithm the values belong to
     * @param values the canonical values to store
     * @throws IllegalArgumentException when a value fails its descriptor
     */
    public void storeParameters(final AutoMeshAlgorithm algorithm, final Map<String, String> values) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(values, "values");
        values.forEach((parameterId, value) -> source.selection().setParameterValue(algorithm, parameterId, value));
    }

    private String labelOf(final AutoMeshAlgorithm algorithm) {
        return algorithm.isNative() ? AutoMeshText.text(locale, NATIVE_LABEL_KEY) : algorithm.displayName();
    }
}
