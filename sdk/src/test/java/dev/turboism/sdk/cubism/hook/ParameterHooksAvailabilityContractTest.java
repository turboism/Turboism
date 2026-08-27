package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Parameter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ParameterHooksAvailabilityContractTest {

    private static final String[] EXACT_5_3 = {"5.3.02", "5.3.03"};

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
        assertArrayEquals(EXACT_5_3, method.getAnnotation(CubismEditor.class).value());
    }
}
