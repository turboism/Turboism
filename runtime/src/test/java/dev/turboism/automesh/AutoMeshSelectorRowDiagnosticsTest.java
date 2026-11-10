package dev.turboism.automesh;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.reflect.ReflectAccess;
import java.lang.reflect.Method;
import java.util.Date;
import org.junit.jupiter.api.Test;

/**
 * The lookup failure message the selector row writes to the Turboism log.
 *
 * <p>A future host build can keep a member name and change its parameter types. The one line the
 * row logs then has to name the wanted signature and the declared ones, because the host log is the
 * only evidence a reviewer has. This test pins that wording.</p>
 */
class AutoMeshSelectorRowDiagnosticsTest {

    private static final String WANTED = "java.lang.StringBuilder#append(java.util.Date)";
    private static final String DECLARED_PREFIX = "; declared: append(";

    @Test
    void aRefusedLookupNamesTheWantedSignatureAndTheDeclaredOnes() throws Exception {
        final StringBuilder target = new StringBuilder("probe");

        final NoSuchMethodException failure =
                assertThrows(NoSuchMethodException.class, () -> invoke(target, "append", new Class<?>[] {Date.class}));

        final String message = failure.getMessage();
        assertTrue(message.startsWith(WANTED), message);
        assertTrue(message.contains(DECLARED_PREFIX), message);
        assertTrue(message.indexOf('\n') < 0, "the diagnostic must stay on one line: " + message);
    }

    private static Object invoke(final Object target, final String name, final Class<?>[] parameterTypes)
            throws Exception {
        final Method method = AutoMeshSelectorRow.class.getDeclaredMethod(
                "invoke", Object.class, String.class, Class[].class, Object[].class);
        ReflectAccess.open(method);
        try {
            return method.invoke(null, target, name, parameterTypes, new Object[0]);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw (Exception) failure.getCause();
        }
    }
}
