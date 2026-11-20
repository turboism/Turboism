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

    private static final String EXPECTED_FROM = "5.0.00";

    @Test
    void independentlyVerifiedParameterLifecycleCallbacksDeclareFromBoundAvailability() throws Exception {
        assertFrom50(ParameterHooks.class.getMethod("beforeSetParameterValue", Parameter.class, float.class));
        assertFrom50(
                ParameterHooks.class.getMethod("onParameterValueChanged", Parameter.class, float.class, float.class));
        assertFrom50(ParameterHooks.class.getMethod("afterSetParameterValue", Parameter.class, float.class));
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

    private static void assertFrom50(final Method method) {
        final CubismEditor declaration = method.getAnnotation(CubismEditor.class);
        assertNotNull(declaration);
        assertArrayEquals(new String[0], declaration.value());
        assertEquals(EXPECTED_FROM, declaration.from());
    }
}
