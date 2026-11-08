package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
}
