package dev.turboism.sdk.cubism.hook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Parameter;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

final class ParameterHooksAvailabilityContractTest {

    private static final String[] REVIEWED_VERSIONS = {
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    };

    @Test
    void independentlyVerifiedParameterLifecycleCallbacksDeclareExact53Availability() throws Exception {
        assertExact53(ParameterHooks.class.getMethod("beforeSetParameterValue", Parameter.class, float.class));
        assertExact53(
                ParameterHooks.class.getMethod("onParameterValueChanged", Parameter.class, float.class, float.class));
        assertExact53(ParameterHooks.class.getMethod("afterSetParameterValue", Parameter.class, float.class));
    }

    @Test
    void mixedHookInterfaceDoesNotClaimTypeLevelAvailability() {
        assertUnrestrictive(ParameterHooks.class);
    }

    private static void assertUnrestrictive(final Class<?> type) {
        final CubismEditor declaration = type.getAnnotation(CubismEditor.class);
        assertNotNull(declaration);
        assertEquals(0, declaration.value().length);
        assertTrue(declaration.from().isEmpty());
        assertTrue(declaration.to().isEmpty());
        assertEquals(0, declaration.exclude().length);
    }

    private static void assertExact53(final Method method) {
        assertArrayEquals(
                REVIEWED_VERSIONS, method.getAnnotation(CubismEditor.class).value());
    }
}
