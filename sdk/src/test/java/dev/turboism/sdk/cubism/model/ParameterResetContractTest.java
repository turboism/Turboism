package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParameterResetContractTest {

    @Test
    void resetToDefaultWritesTheCurrentDefaultValue() {
        final MutableParameter parameter = new MutableParameter(0.75F, -0.25F);

        parameter.resetToDefault();

        assertEquals(-0.25F, parameter.getValue());
        assertEquals(1, parameter.writeCount);
    }

    private static final class MutableParameter implements Parameter {
        private float value;
        private final float defaultValue;
        private int writeCount;

        private MutableParameter(final float value, final float defaultValue) {
            this.value = value;
            this.defaultValue = defaultValue;
        }

        @Override public ParameterId id() { return new ParameterId("ParamA"); }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return -1.0F; }
        @Override public float getMaximumValue() { return 1.0F; }
        @Override public float getDefaultValue() { return defaultValue; }
        @Override public void setValue(final float nextValue) {
            value = nextValue;
            writeCount++;
        }
    }
}
