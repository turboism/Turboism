package dev.turboism.sdk.i18n;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLocalizationContractTest {

    @Test
    void exposesTheFrozenJava17OnlyInterfaceShape() {
        final Set<String> methods = Arrays.stream(PluginLocalization.class.getDeclaredMethods())
            .map(PluginLocalizationContractTest::signature)
            .collect(Collectors.toSet());

        assertEquals(Set.of(
            "locale():java.util.Locale",
            "text(java.lang.String):java.lang.String",
            "format(java.lang.String,java.lang.Object[]):java.lang.String",
            "contains(java.lang.String):boolean"
        ), methods);
        assertTrue(PluginLocalization.class.isInterface());
        assertEquals(
            Locale.class,
            Arrays.stream(PluginLocalization.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("locale"))
                .findFirst()
                .orElseThrow()
                .getReturnType()
        );
    }

    @Test
    void pluginContextDefaultAccessorFailsWithTheFrozenCompatibilityMessage() {
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            (proxy, method, arguments) -> invokeDefault(proxy, method, arguments)
        );

        final UnsupportedOperationException error = assertThrows(
            UnsupportedOperationException.class,
            context::localization
        );

        assertEquals("localization service is not available", error.getMessage());
    }

    private static Object invokeDefault(
        final Object proxy,
        final Method method,
        final Object[] arguments
    ) throws Throwable {
        if (!method.isDefault()) {
            throw new AssertionError("Unexpected abstract method invocation: " + method);
        }
        return InvocationHandler.invokeDefault(proxy, method, arguments);
    }

    private static String signature(final Method method) {
        final String parameters = Arrays.stream(method.getParameterTypes())
            .map(Class::getTypeName)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + "):" + method.getReturnType().getName();
    }
}
