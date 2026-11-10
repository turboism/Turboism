package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contracts of the native selector state: session delegation, canonical values, preset identity. */
class AutoMeshSelectionStateTest {

    private static final AlgorithmParameterDescriptor.DoubleParameter SCALE =
            new AlgorithmParameterDescriptor.DoubleParameter("scale", "Scale", "", 0.0d, 2.0d, 0.1d, 2, 1.0d);
    private static final AlgorithmParameterDescriptor.IntParameter DENSITY =
            new AlgorithmParameterDescriptor.IntParameter("density", "Density", "", 1, 9, 2, 3);
    private static final AlgorithmPreset FINE = new AlgorithmPreset("Fine", Map.of("scale", "1.20"));

    private final AutoMeshAlgorithmCatalog catalog = new AutoMeshAlgorithmCatalog();
    private final AutoMeshSelectionState selection = new AutoMeshSelectionState(catalog);
    private final AutoMeshAlgorithm algorithm = algorithm();

    @TempDir
    Path directory;

    @Test
    void selectionDelegatesToTheSessionCatalog() {
        catalog.register(algorithm, "plugin.test", 1L, () -> true);

        selection.selectAlgorithm(algorithm.id());
        assertEquals(Optional.of("mesh"), selection.selectedAlgorithmId());

        selection.selectAlgorithm(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID);
        assertFalse(selection.selectedAlgorithmId().isPresent());
    }

    @Test
    void presetSelectionResolvesCanonicalValuesAndDeclaredDefaults() {
        selection.selectPreset(algorithm, FINE.name());

        assertEquals(Optional.of("Fine"), selection.selectedPresetName(algorithm.id()));
        assertEquals(Map.of("scale", "1.2", "density", "3"), selection.parameterValues(algorithm));
        assertThrows(IllegalArgumentException.class, () -> selection.selectPreset(algorithm, "Missing"));
    }

    @Test
    void handEditedValueDropsThePresetNameAndStaysCanonical() {
        selection.selectPreset(algorithm, FINE.name());
        selection.setParameterValue(algorithm, "density", "9");

        assertFalse(selection.selectedPresetName(algorithm.id()).isPresent());
        assertEquals(Map.of("scale", "1.2", "density", "9"), selection.parameterValues(algorithm));
        assertThrows(IllegalArgumentException.class, () -> selection.setParameterValue(algorithm, "density", "4"));
        assertThrows(IllegalArgumentException.class, () -> selection.setParameterValue(algorithm, "unknown", "1"));
    }

    private AutoMeshAlgorithm algorithm() {
        return new AutoMeshAlgorithm(
                "mesh",
                "Mesh",
                "",
                List.of(SCALE, DENSITY),
                List.of(FINE),
                false,
                (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
    }

    @Test
    void savingAPresetAppendsItAndSelectsIt() {
        final AlgorithmPreset saved = selection.savePreset(algorithm, "  My preset  ", Map.of("density", "9"));

        assertEquals("My preset", saved.name(), "the name must be trimmed");
        assertEquals(Map.of("scale", "1", "density", "9"), saved.values(), "a saved preset is complete");
        assertEquals(
                List.of("Fine", "My preset"),
                selection.presets(algorithm).stream().map(AlgorithmPreset::name).toList(),
                "a new preset appends after the declared ones");
        assertEquals(Optional.of("My preset"), selection.selectedPresetName(algorithm.id()));
        assertEquals("9", selection.parameterValues(algorithm).get("density"));
    }

    @Test
    void savingAPresetOfAnExistingNameReplacesItInPlace() {
        selection.savePreset(algorithm, "Mine", Map.of("density", "3"));
        selection.savePreset(algorithm, "Mine", Map.of("density", "7"));

        final List<AlgorithmPreset> presets = selection.presets(algorithm);
        assertEquals(2, presets.size(), "replacing a preset must not duplicate it");
        assertEquals("7", presets.get(1).values().get("density"));
    }

    @Test
    void aBlankPresetNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> selection.savePreset(algorithm, "  ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> selection.renamePreset(algorithm, "Fine", null));
    }

    @Test
    void renamingKeepsTheValuesAndTheSelection() {
        selection.selectPreset(algorithm, FINE.name());

        final AlgorithmPreset renamed = selection.renamePreset(algorithm, FINE.name(), "Renamed");

        assertEquals(Map.of("scale", "1.20"), renamed.values(), "renaming must keep the stored values");
        assertEquals(Optional.of("Renamed"), selection.selectedPresetName(algorithm.id()));
        assertEquals(
                List.of("Renamed"),
                selection.presets(algorithm).stream().map(AlgorithmPreset::name).toList());
        assertThrows(IllegalArgumentException.class, () -> selection.renamePreset(algorithm, "Absent", "Other"));
    }

    @Test
    void renamingOntoAnotherPresetNameIsRefused() {
        selection.savePreset(algorithm, "Second", Map.of());

        assertThrows(IllegalArgumentException.class, () -> selection.renamePreset(algorithm, "Second", FINE.name()));
        assertEquals(2, selection.presets(algorithm).size(), "a refused rename must change nothing");
    }

    @Test
    void deletingTheSelectedPresetSelectsItsNeighbour() {
        selection.selectPreset(algorithm, FINE.name());
        selection.savePreset(algorithm, "Second", Map.of("density", "5"));
        selection.selectPreset(algorithm, "Second");

        assertTrue(selection.deletePreset(algorithm, "Second"));
        assertEquals(Optional.of("Fine"), selection.selectedPresetName(algorithm.id()));
        assertEquals("1.2", selection.parameterValues(algorithm).get("scale"));
        assertFalse(selection.deletePreset(algorithm, "Second"), "a second delete finds nothing");
    }

    @Test
    void deletingTheLastPresetLeavesNoSelection() {
        selection.selectPreset(algorithm, FINE.name());

        assertTrue(selection.deletePreset(algorithm, FINE.name()));
        assertTrue(selection.presets(algorithm).isEmpty(), "a customized empty list stays empty");
        assertFalse(selection.selectedPresetName(algorithm.id()).isPresent());
    }

    @Test
    void restoringDefaultsReturnsTheDeclaredListAndSelectsItsFirstEntry() {
        selection.savePreset(algorithm, "Mine", Map.of("density", "9"));

        final List<AlgorithmPreset> restored = selection.restoreDefaultPresets(algorithm);

        assertEquals(
                List.of("Fine"), restored.stream().map(AlgorithmPreset::name).toList());
        assertEquals(Optional.of("Fine"), selection.selectedPresetName(algorithm.id()));
    }

    @Test
    void anAlgorithmWithoutPresetsRestoresToNoSelection() {
        final AutoMeshAlgorithm bare = new AutoMeshAlgorithm(
                "bare",
                "Bare",
                "",
                List.of(DENSITY),
                List.of(),
                false,
                (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
        selection.selectPreset(algorithm, FINE.name());

        assertTrue(selection.restoreDefaultPresets(bare).isEmpty());
        assertFalse(selection.selectedPresetName(bare.id()).isPresent());
    }

    @Test
    void storingAWholeValueSetKeepsThePresetWhenNothingChanged() {
        selection.selectPreset(algorithm, FINE.name());

        selection.setParameterValues(algorithm, selection.parameterValues(algorithm));
        assertEquals(Optional.of("Fine"), selection.selectedPresetName(algorithm.id()));

        selection.setParameterValues(algorithm, Map.of("density", "5"));
        assertFalse(
                selection.selectedPresetName(algorithm.id()).isPresent(),
                "a value set that diverges from the preset must drop the name");
    }

    @Test
    void aStoredParameterTheSchemaNoLongerDeclaresNeverBreaksTheValueSet() {
        selection.setParameterValue(algorithm, "density", "9");
        final AutoMeshAlgorithm narrow = new AutoMeshAlgorithm(
                "mesh",
                "Mesh",
                "",
                List.of(DENSITY),
                List.of(),
                false,
                (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE);

        assertEquals(
                Map.of("density", "9"),
                selection.parameterValues(narrow),
                "an undeclared persisted value must be dropped instead of failing the set");
    }

    @Test
    void aStoredPresetIsSanitizedAgainstTheLiveSchema() {
        selection.savePreset(algorithm, "Wide", Map.of("scale", "1.5", "density", "9"));
        final AutoMeshAlgorithm narrow = new AutoMeshAlgorithm(
                "mesh",
                "Mesh",
                "",
                List.of(DENSITY),
                List.of(),
                false,
                (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE);

        final List<AlgorithmPreset> presets = selection.presets(narrow);

        assertEquals(
                List.of("Wide"), presets.stream().map(AlgorithmPreset::name).toList());
        assertEquals(Map.of("density", "9"), presets.get(0).values());
    }

    @Test
    void aTransientValueSetIsCurrentButWaitsForTheStoreUntilItIsPersisted() {
        final AutoMeshPresetStore store =
                new AutoMeshPresetStore(directory.resolve("automesh-presets.properties"), message -> {});
        final AutoMeshSelectionState first = new AutoMeshSelectionState(catalog, store);

        first.setParameterValuesTransient(algorithm, Map.of("density", "9"));

        assertEquals("9", first.parameterValues(algorithm).get("density"), "a run must read the live value");
        assertFalse(
                Files.exists(directory.resolve("automesh-presets.properties")),
                "a streamed value must not force the preset file");

        first.setParameterValues(algorithm, Map.of("density", "9"));

        assertEquals(
                "9",
                new AutoMeshSelectionState(catalog, store)
                        .parameterValues(algorithm)
                        .get("density"),
                "the final set of one stream must persist");
    }

    @Test
    void presetListAndSelectionSurviveASecondSession() {
        final AutoMeshPresetStore store =
                new AutoMeshPresetStore(directory.resolve("automesh-presets.properties"), message -> {});
        final AutoMeshSelectionState first = new AutoMeshSelectionState(catalog, store);
        first.savePreset(algorithm, "Mine", Map.of("density", "7"));
        first.selectPreset(algorithm, "Mine");

        final AutoMeshSelectionState second = new AutoMeshSelectionState(catalog, store);

        assertEquals(
                List.of("Fine", "Mine"),
                second.presets(algorithm).stream().map(AlgorithmPreset::name).toList(),
                "a restarted session must offer the user's list");
        assertEquals(Optional.of("Mine"), second.selectedPresetName(algorithm.id()));
        assertEquals("7", second.parameterValues(algorithm).get("density"));
    }
}
