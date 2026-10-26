package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

class ParameterGuidContractTest {
    @Test
    void legacyImplementationDoesNotInventGuidFromDisplayIdOrCoreIndex() throws Exception {
        Parameter p = new Parameter() {
            public ParameterId id() {
                return new ParameterId("ParamAngleX");
            }

            public int index() {
                return 7;
            }

            public float getValue() {
                return 0;
            }

            public float getMinimumValue() {
                return -30;
            }

            public float getMaximumValue() {
                return 30;
            }

            public float getDefaultValue() {
                return 0;
            }

            public void setValue(float value) {
                throw new AssertionError();
            }
        };
        assertTrue(Parameter.class.getMethod("guid").isDefault());
        assertThrows(UnsupportedOperationException.class, p::guid);
        assertEquals("ParamAngleX", p.id().value());
        assertEquals(7, p.index());
    }
}
