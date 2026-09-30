package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Parameter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ParameterHooksAvailabilityContractTest {

    private static final String[] REVIEWED_VERSIONS = {"5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"};

    @Test
    void independentlyVerifiedParameterLifecycleCallbacksDeclareExact53Availability()
        throws Exception {
        assertExact53(ParameterHooks.class.getMethod(
            "beforeSetParameterValue",
            Parameter.class,
            float.class
        ));
        assertExact53(ParameterHooks.class.getMethod(
            "onParameterValueChanged",
            Parameter.class,
            float.class,
            float.class
        ));
        assertExact53(ParameterHooks.class.getMethod(
            "afterSetParameterValue",
            Parameter.class,
            float.class
        ));
    }

    @Test
    void mixedHookInterfaceDoesNotClaimTypeLevelAvailability() {
        assertNull(ParameterHooks.class.getAnnotation(CubismEditor.class));
    }

    private static void assertExact53(final Method method) {
        assertArrayEquals(REVIEWED_VERSIONS, method.getAnnotation(CubismEditor.class).value());
    }
}
