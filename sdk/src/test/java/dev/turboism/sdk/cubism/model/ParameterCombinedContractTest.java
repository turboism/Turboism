package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterCombinedContractTest {

    @Test
    void legacyBackendsExposeNoPartnerAndRejectStructuralWrites() {
        Parameter parameter = new Parameter() {
            @Override public ParameterId id() { return new ParameterId("ParamA"); }
            @Override public float getValue() { return 0; }
            @Override public float getMinimumValue() { return -1; }
            @Override public float getMaximumValue() { return 1; }
            @Override public float getDefaultValue() { return 0; }
            @Override public void setValue(final float value) { }
        };

        assertEquals(Optional.empty(), parameter.combinedWith());
        assertThrows(
            UnsupportedOperationException.class,
            () -> parameter.combineWith(new ParameterId("ParamB"))
        );
        assertThrows(UnsupportedOperationException.class, parameter::uncombine);
    }
}
