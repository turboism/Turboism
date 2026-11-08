package dev.turboism.sdk.cubism.mesh.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Contracts of the auto-mesh vocabulary: canonical value text, schema invariants and the sentinel. */
class AutoMeshAlgorithmVocabularyTest {

    private static final AlgorithmParameterDescriptor.IntParameter DENSITY =
            new AlgorithmParameterDescriptor.IntParameter("density", "Density", "Vertices per unit.", 1, 9, 2, 3);
    private static final AlgorithmParameterDescriptor.DoubleParameter SCALE =
            new AlgorithmParameterDescriptor.DoubleParameter("scale", "Scale", "", 0.0d, 2.0d, 0.1d, 2, 1.0d);
    private static final AlgorithmParameterDescriptor.BoolParameter FOLLOW =
            new AlgorithmParameterDescriptor.BoolParameter("follow", "Follow texture", "", true);
    private static final AlgorithmParameterDescriptor.EnumParameter LAYOUT =
            new AlgorithmParameterDescriptor.EnumParameter(
                    "layout",
                    "Layout",
                    "",
                    List.of(
                            new AlgorithmParameterDescriptor.EnumParameter.Option("grid", "Grid"),
                            new AlgorithmParameterDescriptor.EnumParameter.Option("fan", "Fan")),
                    "grid");
    private static final AlgorithmParameterDescriptor.TextParameter TAG =
            new AlgorithmParameterDescriptor.TextParameter("tag", "Tag", "", 16, "default");

    @Test
    void integerKindRejectsValuesOutsideTheStepGrid() {
        assertEquals("5", DENSITY.canonicalize("5"));
        assertEquals("3", DENSITY.canonicalDefaultValue());
        assertThrows(IllegalArgumentException.class, () -> DENSITY.canonicalize("4"));
        assertThrows(IllegalArgumentException.class, () -> DENSITY.canonicalize("11"));
        assertThrows(IllegalArgumentException.class, () -> DENSITY.canonicalize("+5"));
        assertThrows(IllegalArgumentException.class, () -> DENSITY.canonicalize("5 "));
        assertThrows(IllegalArgumentException.class, () -> DENSITY.canonicalize("99999999999999999999"));
    }

    @Test
    void decimalKindNormalizesToPlainTextWithinTheDeclaredScale() {
        assertEquals("0.3", SCALE.canonicalize("0.30"));
        assertEquals("1.2", SCALE.canonicalize("1.20"));
        assertEquals("1", SCALE.canonicalDefaultValue());
        assertThrows(IllegalArgumentException.class, () -> SCALE.canonicalize("0.123"));
        assertThrows(IllegalArgumentException.class, () -> SCALE.canonicalize("0.35"));
        assertThrows(IllegalArgumentException.class, () -> SCALE.canonicalize("1e-3"));
    }

    @Test
    void booleanEnumAndTextKindsAcceptOnlyTheirDeclaredForms() {
        assertEquals("true", FOLLOW.canonicalize("true"));
        assertEquals("false", FOLLOW.canonicalize("false"));
        assertThrows(IllegalArgumentException.class, () -> FOLLOW.canonicalize("TRUE"));

        assertEquals("fan", LAYOUT.canonicalize("fan"));
        assertEquals("grid", LAYOUT.canonicalDefaultValue());
        assertThrows(IllegalArgumentException.class, () -> LAYOUT.canonicalize("Fan"));

        assertEquals("", TAG.canonicalize(""));
        assertEquals("abc", TAG.canonicalize("abc"));
        assertThrows(IllegalArgumentException.class, () -> TAG.canonicalize("x".repeat(17)));
    }

    @Test
    void algorithmSchemaRejectsAmbiguousOrInconsistentDeclarations() {
        assertEquals(List.of(DENSITY, SCALE, FOLLOW, LAYOUT, TAG), algorithm().parameters());
        assertEquals(
                List.of(new AlgorithmPreset("Fine", Map.of("density", "5", "layout", "fan"))),
                algorithm().presets());

        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshAlgorithm(
                        "mesh",
                        "Mesh",
                        "",
                        List.of(
                                DENSITY,
                                new AlgorithmParameterDescriptor.IntParameter(
                                        "density", "Other density", "", 1, 3, 1, 1)),
                        List.of(),
                        compute()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshAlgorithm(
                        "mesh",
                        "Mesh",
                        "",
                        List.of(DENSITY),
                        List.of(new AlgorithmPreset("Fine", Map.of("scale", "1.0"))),
                        compute()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshAlgorithm(
                        "mesh",
                        "Mesh",
                        "",
                        List.of(DENSITY),
                        List.of(new AlgorithmPreset("Fine", Map.of("density", "4"))),
                        compute()));
    }

    @Test
    void nativeAlgorithmLeadsWithoutComputeAndCannotBeDeclaredByAPlugin() {
        final AutoMeshAlgorithm nativeAlgorithm =
                new AutoMeshAlgorithm(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID, "Native", "", List.of(), List.of(), null);
        assertTrue(nativeAlgorithm.isNative());
        assertFalse(algorithm().isNative());

        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshAlgorithm("mesh", "Mesh", "", List.of(), List.of(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshAlgorithm(
                        AutoMeshAlgorithm.NATIVE_ALGORITHM_ID, "Native", "", List.of(DENSITY), List.of(), null));
    }

    @Test
    void requestCarriesAnImmutableTargetedSnapshot() {
        final AutoMeshRequest request = new AutoMeshRequest(
                "ArtMesh1",
                new ArtMeshGeometry(List.of(new Point2(0.0f, 0.0f)), List.of(new Point2(0.0f, 0.0f)), List.of()),
                Optional.empty(),
                2,
                Map.of("density", "5"),
                AutoMeshRequest.UNSPECIFIED_VERTEX_TARGET);

        assertEquals(Map.of("density", "5"), request.parameterValues());
        assertFalse(request.hasVertexTarget());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.parameterValues().put("scale", "1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshRequest("ArtMesh1", request.geometry(), Optional.empty(), 0, Map.of(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AutoMeshRequest(" ", request.geometry(), Optional.empty(), 1, Map.of(), 0));
        assertThrows(
                UnsupportedOperationException.class,
                () -> AutoMeshAlgorithmRegistry.unavailable().register(algorithm()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> AutoMeshAlgorithmRegistry.unavailable().select(AutoMeshAlgorithm.NATIVE_ALGORITHM_ID));
        assertTrue(AutoMeshAlgorithmRegistry.unavailable().algorithms().isEmpty());
        assertFalse(AutoMeshAlgorithmRegistry.unavailable().isAvailable());
    }

    private static AutoMeshAlgorithm algorithm() {
        return new AutoMeshAlgorithm(
                "mesh",
                "Mesh",
                "Places a mesh over the texture.",
                List.of(DENSITY, SCALE, FOLLOW, LAYOUT, TAG),
                List.of(new AlgorithmPreset("Fine", Map.of("density", "5", "layout", "fan"))),
                compute());
    }

    private static AutoMeshCompute compute() {
        return (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE;
    }
}
