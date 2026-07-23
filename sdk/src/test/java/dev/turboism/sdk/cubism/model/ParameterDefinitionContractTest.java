package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterDefinitionContractTest {

    @Test
    void definitionNormalizesNameAndCarriesOneAtomicAuthoringEdit() {
        final ParameterDefinition definition = new ParameterDefinition(
            new ParameterId("ParamAngleX2"),
            "  Angle X  ",
            -45.0F,
            0.0F,
            45.0F,
            ParameterType.BLEND_SHAPE,
            true
        );

        assertEquals("ParamAngleX2", definition.id().value());
        assertEquals("Angle X", definition.name());
        assertEquals(-45.0F, definition.minimumValue());
        assertEquals(0.0F, definition.defaultValue());
        assertEquals(45.0F, definition.maximumValue());
        assertEquals(ParameterType.BLEND_SHAPE, definition.type());
        assertEquals(true, definition.repeat());
    }

    @Test
    void definitionRejectsInvalidRangesNonFiniteValuesUnknownTypesAndBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> definition(1.0F, 0.0F, 2.0F));
        assertThrows(IllegalArgumentException.class, () -> definition(-1.0F, 2.0F, 1.0F));
        assertThrows(IllegalArgumentException.class, () -> definition(Float.NaN, 0.0F, 1.0F));
        assertThrows(IllegalArgumentException.class, () -> new ParameterDefinition(
            new ParameterId("ParamA"),
            "A",
            -1.0F,
            0.0F,
            1.0F,
            ParameterType.UNKNOWN,
            false
        ));
        assertThrows(IllegalArgumentException.class, () -> new ParameterDefinition(
            new ParameterId("ParamA"),
            "   ",
            -1.0F,
            0.0F,
            1.0F,
            ParameterType.NORMAL,
            false
        ));
    }

    @Test
    void legacyParameterImplementationsFailExplicitlyWithoutNewMethods() {
        final Parameter legacy = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return 0.0F; }
            @Override public float getMinimumValue() { return -1.0F; }
            @Override public float getMaximumValue() { return 1.0F; }
            @Override public float getDefaultValue() { return 0.0F; }
            @Override public void setValue(final float value) { }
        };

        assertThrows(UnsupportedOperationException.class, () -> legacy.updateDefinition(
            definition(-1.0F, 0.0F, 1.0F)
        ));
    }

    private static ParameterDefinition definition(
        final float minimum,
        final float defaultValue,
        final float maximum
    ) {
        return new ParameterDefinition(
            new ParameterId("ParamA"),
            "A",
            minimum,
            defaultValue,
            maximum,
            ParameterType.NORMAL,
            false
        );
    }
}
