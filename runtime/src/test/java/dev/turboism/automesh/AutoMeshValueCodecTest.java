package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutoMeshValueCodecTest {

    private static final AlgorithmParameterDescriptor.IntParameter DENSITY =
            new AlgorithmParameterDescriptor.IntParameter("density", "Density", "", 1, 9, 2, 3);
    private static final AlgorithmParameterDescriptor.DoubleParameter SCALE =
            new AlgorithmParameterDescriptor.DoubleParameter("scale", "Scale", "", 0.0d, 2.0d, 0.1d, 2, 1.0d);
    private static final AlgorithmParameterDescriptor.BoolParameter FOLLOW =
            new AlgorithmParameterDescriptor.BoolParameter("follow", "Follow", "", true);
    private static final AlgorithmParameterDescriptor.EnumParameter LAYOUT =
            new AlgorithmParameterDescriptor.EnumParameter(
                    "layout",
                    "Layout",
                    "",
                    List.of(
                            new AlgorithmParameterDescriptor.EnumParameter.Option("grid", "Grid"),
                            new AlgorithmParameterDescriptor.EnumParameter.Option("fan", "Fan")),
                    "grid");

    private final AutoMeshValueCodec codec = AutoMeshValueCodec.forAlgorithm(algorithm(List.of()));

    @Test
    void canonicalizesEveryKindAndRefusesUndeclaredParameters() {
        assertEquals("5", codec.canonicalize("density", "5"));
        assertEquals("0.3", codec.canonicalize("scale", "0.30"));
        assertEquals("false", codec.canonicalize("follow", "false"));
        assertEquals("fan", codec.canonicalize("layout", "fan"));
        assertThrows(IllegalArgumentException.class, () -> codec.canonicalize("unknown", "1"));
        assertThrows(IllegalArgumentException.class, () -> codec.canonicalize("density", "4"));
        assertThrows(IllegalArgumentException.class, () -> codec.canonicalize("scale", "0.123"));
        assertThrows(IllegalArgumentException.class, () -> codec.canonicalize("follow", "TRUE"));
        assertThrows(IllegalArgumentException.class, () -> codec.canonicalize("layout", "Grid"));
    }

    @Test
    void resolveFillsDeclaredDefaultsAndKeepsDeclaredOverrides() {
        assertEquals(
                Map.of("density", "5", "scale", "1.2", "follow", "true", "layout", "grid"),
                codec.resolve(Map.of("density", "5", "scale", "1.20")));
        assertThrows(IllegalArgumentException.class, () -> codec.resolve(Map.of("unknown", "1")));
        assertThrows(IllegalArgumentException.class, () -> codec.resolve(Map.of("density", "4")));
    }

    @Test
    void convertsCanonicalTextIntoTheTypedCarrier() {
        assertEquals(new AutoMeshValueCodec.Value.IntegerValue("density", 5), codec.value("density", "5"));
        assertEquals(new AutoMeshValueCodec.Value.DecimalValue("scale", 0.3), codec.value("scale", "0.30"));
        assertEquals(new AutoMeshValueCodec.Value.BooleanValue("follow", false), codec.value("follow", "false"));
        assertEquals(new AutoMeshValueCodec.Value.EnumValue("layout", "fan"), codec.value("layout", "fan"));
        assertThrows(IllegalArgumentException.class, () -> codec.value("layout", "none"));
    }

    @Test
    void canonicalPresetsNormalizesOnlyWhenTheTextChanges() {
        final AutoMeshAlgorithm declared = algorithm(List.of(new AlgorithmPreset("Fine", Map.of("scale", "1.20"))));
        final AutoMeshAlgorithm canonical = AutoMeshValueCodec.canonicalPresets(declared);

        assertEquals(Map.of("scale", "1.2"), canonical.presets().get(0).values());
        assertEquals(declared.id(), canonical.id());
        assertSame(declared.compute(), canonical.compute());
        assertSame(canonical, AutoMeshValueCodec.canonicalPresets(canonical));
    }

    private static AutoMeshAlgorithm algorithm(final List<AlgorithmPreset> presets) {
        return new AutoMeshAlgorithm(
                "mesh",
                "Mesh",
                "",
                List.of(DENSITY, SCALE, FOLLOW, LAYOUT),
                presets,
                false,
                (request, cancellation) -> AutoMeshResult.Cancelled.INSTANCE);
    }
}
