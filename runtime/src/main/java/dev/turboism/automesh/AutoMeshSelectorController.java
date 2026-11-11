package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

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
     * <p>The entries come from the selection state, not from the algorithm's declaration, because
     * the user's added and renamed presets are what the combo must offer.</p>
     *
     * @return the preset entries, the custom placeholder first
     */
    public List<String> presetEntries() {
        final List<String> entries = new ArrayList<>();
        entries.add(AutoMeshText.text(locale, NO_PRESET_KEY));
        presets().stream().map(AlgorithmPreset::name).forEach(entries::add);
        return List.copyOf(entries);
    }

    /** @return the index of the preset the selection names, or {@code 0} for hand-edited values */
    public int selectedPresetIndex() {
        final Optional<AutoMeshAlgorithm> algorithm = selectedAlgorithm();
        if (algorithm.isEmpty()) {
            return NO_PRESET_INDEX;
        }
        final Optional<String> chosen =
                source.selection().selectedPresetName(algorithm.get().id());
        if (chosen.isEmpty()) {
            return NO_PRESET_INDEX;
        }
        final List<AlgorithmPreset> presets = source.selection().presets(algorithm.get());
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
        final List<AlgorithmPreset> presets = source.selection().presets(algorithm.get());
        if (index > presets.size()) {
            return false;
        }
        source.selection().selectPreset(algorithm.get(), presets.get(index - 1).name());
        return true;
    }

    /** @return the presets the selected algorithm offers now, the user's list first */
    public List<AlgorithmPreset> presets() {
        return selectedAlgorithm()
                .map(algorithm -> source.selection().presets(algorithm))
                .orElse(List.of());
    }

    /**
     * Reports the preset the selection state currently names.
     *
     * @return the active preset name, or empty for hand-edited values
     */
    public Optional<String> selectedPresetName() {
        return selectedAlgorithm().flatMap(algorithm -> source.selection().selectedPresetName(algorithm.id()));
    }

    /**
     * Saves the given values as a preset of the selected algorithm.
     *
     * @param presetName the preset name to write; it is trimmed and must not be blank
     * @param values the values to save
     * @return the saved preset, or empty when no algorithm is selected
     * @throws IllegalArgumentException when the name is blank or a value fails its descriptor
     */
    public Optional<AlgorithmPreset> savePreset(final String presetName, final Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        return selectedAlgorithm().map(algorithm -> source.selection().savePreset(algorithm, presetName, values));
    }

    /**
     * Renames one preset of the selected algorithm.
     *
     * @param oldName the preset to rename
     * @param newName the new name; it is trimmed and must not be blank
     * @return the renamed preset, or empty when no algorithm is selected
     * @throws IllegalArgumentException when a name is blank, {@code oldName} is unknown, or
     *     {@code newName} already names another preset
     */
    public Optional<AlgorithmPreset> renamePreset(final String oldName, final String newName) {
        return selectedAlgorithm().map(algorithm -> source.selection().renamePreset(algorithm, oldName, newName));
    }

    /**
     * Deletes one preset of the selected algorithm.
     *
     * @param presetName the preset to delete
     * @return {@code true} when the preset existed and is gone
     */
    public boolean deletePreset(final String presetName) {
        return selectedAlgorithm()
                .map(algorithm -> source.selection().deletePreset(algorithm, presetName))
                .orElse(false);
    }

    /**
     * Returns the selected algorithm to the preset list its plugin declares.
     *
     * @return the restored preset list, empty when no algorithm is selected
     */
    public List<AlgorithmPreset> restoreDefaultPresets() {
        return selectedAlgorithm()
                .map(algorithm -> source.selection().restoreDefaultPresets(algorithm))
                .orElse(List.of());
    }

    /**
     * Reports whether the parameter button can open a dialog.
     *
     * @return {@code true} when the selected algorithm declares at least one parameter
     */
    public boolean parametersAvailable() {
        return selectedAlgorithm()
                .map(algorithm -> !algorithm.parameters().isEmpty())
                .orElse(false);
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
                .map(algorithm ->
                        new AutoMeshParameterModel(algorithm, source.selection().parameterValues(algorithm)));
    }

    /**
     * Stores the accepted values of one dialog session.
     *
     * <p>The whole accepted set goes to the selection state at once, because only the complete set
     * can tell whether the session still equals the preset it opened with.</p>
     *
     * @param algorithm the algorithm the values belong to
     * @param values the canonical values to store
     * @throws IllegalArgumentException when a value fails its descriptor
     */
    public void storeParameters(final AutoMeshAlgorithm algorithm, final Map<String, String> values) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(values, "values");
        source.selection().setParameterValues(algorithm, values);
    }

    /**
     * Stores one intermediate value set without persisting it.
     *
     * <p>An inline control reports every step of a drag. The selection state must follow each step,
     * because the next run reads it, but the preset file must not be forced on every step. The caller
     * persists the final set with {@link #storeParameters}.</p>
     *
     * @param algorithm the algorithm the values belong to
     * @param values the canonical values to store
     * @throws IllegalArgumentException when a value fails its descriptor
     */
    public void storeParametersTransient(final AutoMeshAlgorithm algorithm, final Map<String, String> values) {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(values, "values");
        source.selection().setParameterValuesTransient(algorithm, values);
    }

    /**
     * Places the current Editor selection with the container's own live-update route.
     *
     * <p>The row calls this after it stores a change the Editor would apply immediately: an
     * algorithm or preset choice, or an accepted parameter set. The source owns the native route,
     * so the row never grows a second placement path.</p>
     *
     * @param container the native Auto Mesh container the row decorates
     * @param diagnostics sink for a refused route; it must not throw
     */
    public void placeCurrentSelection(final Object container, final Consumer<String> diagnostics) {
        source.placeCurrentSelection(container, diagnostics);
    }

    private String labelOf(final AutoMeshAlgorithm algorithm) {
        return algorithm.isNative() ? AutoMeshText.text(locale, NATIVE_LABEL_KEY) : algorithm.displayName();
    }
}
