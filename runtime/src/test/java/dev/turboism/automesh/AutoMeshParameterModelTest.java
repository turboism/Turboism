package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.plugin.CancellationToken;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Validation contract of the schema-driven parameter dialog.
 *
 * <p>The dialog renders what this model reports, so every rule the user can hit — bounds, step
 * alignment, decimals, option membership, text length — is proven here without a display.</p>
 */
class AutoMeshParameterModelTest {

    private static final AutoMeshAlgorithm ALGORITHM = algorithm();

    @Test
    void everyDeclaredKindMapsToItsControlInDeclarationOrder() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertEquals(
                List.of(
                        AutoMeshParameterModel.Control.STEPPER,
                        AutoMeshParameterModel.Control.SLIDER,
                        AutoMeshParameterModel.Control.CHECKBOX,
                        AutoMeshParameterModel.Control.COMBO,
                        AutoMeshParameterModel.Control.FIELD),
                model.rows().stream().map(AutoMeshParameterModel.Row::control).toList(),
                "each declared kind must map to its own control");
        assertEquals(
                List.of("density", "smoothing", "weld", "mode", "tag"),
                model.rows().stream()
                        .map(AutoMeshParameterModel.Row::parameterId)
                        .toList(),
                "rows must keep declaration order");
    }

    @Test
    void theModelStartsFromTheDeclaredDefaults() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertEquals(
                Map.of("density", "48", "smoothing", "0.5", "weld", "true", "mode", "fast", "tag", ""),
                model.values(),
                "an unset parameter keeps its declared default");
        assertTrue(model.valid(), "the declared defaults are always valid");
    }

    @Test
    void aSeededValueOverridesTheDefault() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of("density", "96"));

        assertEquals("96", model.values().get("density"));
        assertEquals("0.5", model.values().get("smoothing"), "an unseeded parameter keeps its default");
    }

    @Test
    void anOutOfRangeIntegerIsRejectedAndThePreviousValueStays() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertFalse(model.set("density", "999"), "a value above max must be rejected");
        assertEquals("48", model.values().get("density"), "a rejected edit must keep the previous value");
        assertFalse(model.valid(), "a rejected edit must make the model invalid");
        final AutoMeshParameterModel.Row row = rowOf(model, "density");
        assertTrue(row.invalid(), "the rejected row must carry a message");
        assertTrue(row.message().orElseThrow().contains("128"), "the message must name the bound");
    }

    @Test
    void aMisalignedIntegerIsRejected() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertFalse(model.set("density", "50"), "a value off the declared step must be rejected");
        assertTrue(rowOf(model, "density").message().orElseThrow().contains("step"));
    }

    @Test
    void aDecimalBeyondItsDecimalsIsRejected() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertFalse(model.set("smoothing", "0.55"), "a value with too many decimals must be rejected");
        assertTrue(model.set("smoothing", "0.75"), "a step-aligned value must be accepted");
        assertEquals("0.75", model.values().get("smoothing"));
    }

    @Test
    void aBooleanAcceptsOnlyItsTwoSpellings() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertTrue(model.set("weld", "false"), "the false spelling must be accepted");
        assertFalse(model.set("weld", "yes"), "any other spelling must be rejected");
        assertEquals("false", model.values().get("weld"), "the accepted value must stay");
    }

    @Test
    void anEnumAcceptsOnlyItsDeclaredOptions() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertTrue(model.set("mode", "dense"), "a declared option must be accepted");
        assertFalse(model.set("mode", "turbo"), "an undeclared option must be rejected");
        assertEquals("dense", model.values().get("mode"));
    }

    @Test
    void aTextValueIsBoundedByItsDeclaredLength() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertTrue(model.set("tag", "abc"), "a short value must be accepted");
        assertFalse(model.set("tag", "abcdefgh"), "a value past maxLength must be rejected");
        assertEquals("abc", model.values().get("tag"));
    }

    @Test
    void anAcceptedEditClearsTheMessageOfThatRowOnly() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());
        model.set("density", "999");
        model.set("weld", "yes");

        assertTrue(model.set("density", "64"), "a valid value must be accepted");

        assertFalse(rowOf(model, "density").invalid(), "the accepted row must lose its message");
        assertTrue(rowOf(model, "weld").invalid(), "the other rejected row must keep its message");
        assertFalse(model.valid(), "one rejected row still blocks the dialog");
    }

    @Test
    void resetRestoresTheValuesTheDialogOpenedWith() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of("density", "96"));
        model.set("density", "16");
        model.set("weld", "false");

        model.reset();

        assertEquals("96", model.values().get("density"), "reset must restore the opened value");
        assertEquals("true", model.values().get("weld"), "reset must restore every opened value");
        assertTrue(model.valid(), "reset must clear every message");
    }

    @Test
    void applyingAPresetUpdatesTheValuesAndTheResetTarget() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertTrue(model.applyPreset("Dense"), "a declared preset must apply");
        assertEquals("96", model.values().get("density"));
        assertFalse(model.applyPreset("Absent"), "an undeclared preset must be refused");

        model.reset();
        assertEquals("96", model.values().get("density"), "reset must return to the applied preset");
    }

    @Test
    void applyingAPresetFromTheUserListUsesTheGivenList() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());
        final List<AlgorithmPreset> saved =
                List.of(new AlgorithmPreset("Saved", Map.of("density", "64", "weld", "false")));

        assertTrue(model.applyPreset(saved, "Saved"), "a preset the dialog lists must apply");
        assertEquals("64", model.values().get("density"), "the listed preset value must reach the model");
        assertEquals("false", model.values().get("weld"));
        assertFalse(
                model.applyPreset(saved, "Dense"),
                "a name outside the given list must be refused even when the algorithm declares it");
    }

    @Test
    void anUndeclaredParameterIsRefused() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(ALGORITHM, Map.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> model.set("absent", "1"),
                "an undeclared parameter must be refused instead of ignored");
    }

    @Test
    void aModelOfTheNativeGeneratorDeclaresNoRow() {
        final AutoMeshParameterModel model = new AutoMeshParameterModel(
                new AutoMeshAlgorithm(
                        AutoMeshAlgorithm.NATIVE_ALGORITHM_ID,
                        "Native (Cubism)",
                        "The Editor's built-in mesh generator.",
                        List.of(),
                        List.of(),
                        false,
                        null),
                Map.of());

        assertTrue(model.rows().isEmpty(), "the native generator declares no parameter");
        assertTrue(model.valid(), "an empty model is valid");
    }

    private static AutoMeshParameterModel.Row rowOf(final AutoMeshParameterModel model, final String parameterId) {
        return model.rows().stream()
                .filter(row -> row.parameterId().equals(parameterId))
                .findFirst()
                .orElseThrow();
    }

    private static AutoMeshAlgorithm algorithm() {
        return new AutoMeshAlgorithm(
                "plugin.region",
                "Region plan",
                "Plans a mesh from the region outline.",
                List.of(
                        new AlgorithmParameterDescriptor.IntParameter(
                                "density", "Density", "Vertices per region edge.", 8, 128, 8, 48),
                        new AlgorithmParameterDescriptor.DoubleParameter(
                                "smoothing", "Smoothing", "Outline smoothing.", 0.0, 1.0, 0.25, 2, 0.5),
                        new AlgorithmParameterDescriptor.BoolParameter(
                                "weld", "Weld", "Weld coincident vertices.", true),
                        new AlgorithmParameterDescriptor.EnumParameter(
                                "mode",
                                "Mode",
                                "Region detection mode.",
                                List.of(
                                        new AlgorithmParameterDescriptor.EnumParameter.Option("fast", "Fast"),
                                        new AlgorithmParameterDescriptor.EnumParameter.Option("dense", "Dense")),
                                "fast"),
                        new AlgorithmParameterDescriptor.TextParameter("tag", "Tag", "Free label.", 5, "")),
                List.of(new AlgorithmPreset("Dense", Map.of("density", "96"))),
                false,
                (AutoMeshRequest request, CancellationToken cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
    }
}
