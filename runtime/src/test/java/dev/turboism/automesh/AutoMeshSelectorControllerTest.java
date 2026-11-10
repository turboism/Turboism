package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Row visibility, selection and preset behaviour of the native selector.
 *
 * <p>These are the decisions the row makes before any host widget exists, so the whole contract is
 * proven without a display. The widget layer only renders what these tests pin.</p>
 */
class AutoMeshSelectorControllerTest {

    private static final String ALGORITHM_ID = "plugin.region";

    private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
    private final AutoMeshSelectionState selection = new AutoMeshSelectionState(catalog);

    private AutoMeshSelectorController controller() {
        return new AutoMeshSelectorController(new CatalogSource(catalog, selection), java.util.Locale.ENGLISH);
    }

    @Test
    void aNativeOnlyListingAddsNoRow() {
        final AutoMeshSelectorController controller = controller();

        assertFalse(controller.rowVisible(), "the native-only listing must not add a row");
        assertEquals(1, controller.algorithmEntries().size(), "the native entry still exists for the combo");
        assertEquals(
                AutoMeshAlgorithm.NATIVE_ALGORITHM_ID,
                controller.algorithmEntries().get(0).id());
        assertFalse(controller.parametersAvailable(), "the native generator declares no parameter");
        assertTrue(controller.parameterModel().isEmpty(), "the native generator exposes no parameter model");
    }

    @Test
    void oneRegisteredAlgorithmMakesTheRowVisible() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);

        final AutoMeshSelectorController controller = controller();

        assertTrue(controller.rowVisible(), "a registered algorithm must add the row");
        assertEquals(
                List.of(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID, ALGORITHM_ID),
                controller.algorithmEntries().stream()
                        .map(AutoMeshSelectorController.AlgorithmEntry::id)
                        .toList(),
                "the Editor's generator must lead the listing");
    }

    @Test
    void selectingAnAlgorithmStoresItAndExposesItsParameters() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();

        final Optional<AutoMeshAlgorithm> selected = controller.selectAlgorithm(1);

        assertTrue(selected.isPresent(), "a registered entry must resolve");
        assertEquals(ALGORITHM_ID, catalog.selectedAlgorithmId().orElseThrow());
        assertEquals(1, controller.selectedAlgorithmIndex(), "the combo must show the chosen entry");
        assertTrue(controller.parametersAvailable(), "the chosen algorithm declares parameters");
        final AutoMeshParameterModel model = controller.parameterModel().orElseThrow();
        assertEquals("48", model.values().get("density"), "the model starts from the declared default");
    }

    @Test
    void selectingTheNativeEntryClearsTheAlgorithmSelection() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();
        controller.selectAlgorithm(1);

        assertTrue(controller.selectAlgorithm(0).isEmpty(), "the native entry resolves to no algorithm");
        assertTrue(catalog.selectedAlgorithmId().isEmpty(), "the native entry clears the selection");
        assertFalse(controller.parametersAvailable());
    }

    @Test
    void anOutOfRangeIndexFallsBackToTheNativeEntry() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();

        assertTrue(controller.selectAlgorithm(99).isEmpty(), "an index outside the listing is native");
        assertTrue(catalog.selectedAlgorithmId().isEmpty());
    }

    @Test
    void thePresetComboLeadsWithTheCustomPlaceholder() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();

        assertEquals(List.of("Custom"), controller.presetEntries(), "no algorithm selected means no preset");

        controller.selectAlgorithm(1);
        assertEquals(
                List.of("Custom", "Dense", "Coarse"),
                controller.presetEntries(),
                "the declared presets follow the placeholder in declaration order");
        assertEquals(0, controller.selectedPresetIndex(), "hand-edited values select the placeholder");
    }

    @Test
    void selectingAPresetStoresItsValuesAndItsName() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();
        controller.selectAlgorithm(1);

        assertTrue(controller.selectPreset(1), "the first declared preset must apply");
        assertEquals(1, controller.selectedPresetIndex(), "the combo must show the applied preset");
        assertEquals(
                "Dense",
                catalog.find(ALGORITHM_ID).orElseThrow().presets().get(0).name());
        final AutoMeshParameterModel model = controller.parameterModel().orElseThrow();
        assertEquals("96", model.values().get("density"), "the preset value must reach the model");
    }

    @Test
    void theCustomPlaceholderChangesNothing() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();
        controller.selectAlgorithm(1);
        controller.selectPreset(1);

        assertFalse(controller.selectPreset(0), "the placeholder is not a preset");
        assertEquals(1, controller.selectedPresetIndex(), "the applied preset must stay selected");
    }

    @Test
    void storingParametersDropsThePresetName() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();
        controller.selectAlgorithm(1);
        controller.selectPreset(1);
        final AutoMeshAlgorithm algorithm = controller.selectedAlgorithm().orElseThrow();

        controller.storeParameters(algorithm, Map.of("density", "64"));

        assertEquals(0, controller.selectedPresetIndex(), "a hand-edited value is not a preset");
        assertEquals(
                "64",
                controller.parameterModel().orElseThrow().values().get("density"),
                "the hand-edited value must reach the model");
    }

    @Test
    void aSavedPresetBecomesAnEntryAndStaysSelected() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();
        controller.selectAlgorithm(1);

        assertTrue(controller.savePreset("Mine", Map.of("density", "104")).isPresent());

        assertEquals(List.of("Custom", "Dense", "Coarse", "Mine"), controller.presetEntries());
        assertEquals(Optional.of("Mine"), controller.selectedPresetName());
        assertEquals(3, controller.selectedPresetIndex(), "the combo must show the saved preset");
        assertEquals(
                List.of("Dense", "Coarse", "Mine"),
                controller.presets().stream().map(AlgorithmPreset::name).toList());
    }

    @Test
    void aRenamedPresetIsStillTheSelectedOne() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();
        controller.selectAlgorithm(1);
        controller.selectPreset(1);

        assertTrue(controller.renamePreset("Dense", "Denser").isPresent());

        assertEquals(List.of("Custom", "Denser", "Coarse"), controller.presetEntries());
        assertEquals(1, controller.selectedPresetIndex(), "a renamed preset keeps its position");
        assertEquals(Optional.of("Denser"), controller.selectedPresetName());
    }

    @Test
    void deletingAndRestoringPresetsRewritesTheCombo() {
        catalog.register(algorithm(), "plugin", 1L, () -> true);
        final AutoMeshSelectorController controller = controller();
        controller.selectAlgorithm(1);
        controller.selectPreset(1);

        assertTrue(controller.deletePreset("Dense"), "the selected preset must delete");
        assertEquals(List.of("Custom", "Coarse"), controller.presetEntries());
        assertEquals(Optional.of("Coarse"), controller.selectedPresetName(), "the neighbour takes over");

        assertEquals(
                List.of("Dense", "Coarse"),
                controller.restoreDefaultPresets().stream()
                        .map(AlgorithmPreset::name)
                        .toList(),
                "restoring returns the plugin's own list");
        assertEquals(Optional.of("Dense"), controller.selectedPresetName());
    }

    @Test
    void presetActionsAnswerNothingWithoutASelectedAlgorithm() {
        final AutoMeshSelectorController controller = controller();

        assertTrue(controller.presets().isEmpty());
        assertTrue(controller.selectedPresetName().isEmpty());
        assertTrue(controller.savePreset("Mine", Map.of()).isEmpty());
        assertTrue(controller.renamePreset("Dense", "Denser").isEmpty());
        assertFalse(controller.deletePreset("Dense"));
        assertTrue(controller.restoreDefaultPresets().isEmpty());
    }

    private static AutoMeshAlgorithm algorithm() {
        return new AutoMeshAlgorithm(
                ALGORITHM_ID,
                "Region plan",
                "Plans a mesh from the region outline.",
                List.of(new AlgorithmParameterDescriptor.IntParameter(
                        "density", "Density", "Vertices per region edge.", 8, 128, 8, 48)),
                List.of(
                        new AlgorithmPreset("Dense", Map.of("density", "96")),
                        new AlgorithmPreset("Coarse", Map.of("density", "16"))),
                false,
                (AutoMeshRequest request, dev.turboism.sdk.plugin.CancellationToken cancellation) ->
                        AutoMeshResult.Cancelled.INSTANCE);
    }

    /** Session source over the real catalog, mirroring the production wiring. */
    private static final class CatalogSource implements AutoMeshSelectorSource {

        private final AutoMeshAlgorithmCatalog catalog;
        private final AutoMeshSelectionState selection;

        private CatalogSource(final AutoMeshAlgorithmCatalog catalog, final AutoMeshSelectionState selection) {
            this.catalog = catalog;
            this.selection = selection;
        }

        @Override
        public List<AutoMeshAlgorithm> algorithms() {
            return catalog.algorithms();
        }

        @Override
        public AutoMeshSelectionState selection() {
            return selection;
        }
    }
}
