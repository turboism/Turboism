package dev.turboism.sdk.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.PluginContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PluginLocalizationContractTest {

    @Test
    void exposesTheFrozenJava17OnlyInterfaceShape() {
        final Set<String> methods = Arrays.stream(PluginLocalization.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(PluginLocalizationContractTest::signature)
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "locale():java.util.Locale",
                        "text(java.lang.String):java.lang.String",
                        "text(java.lang.String,java.lang.String):java.lang.String",
                        "format(java.lang.String,java.lang.Object[]):java.lang.String",
                        "contains(java.lang.String):boolean",
                        "isAvailable():boolean",
                        "unavailable():dev.turboism.sdk.i18n.PluginLocalization"),
                methods);
        assertTrue(PluginLocalization.class.isInterface());
        assertEquals(
                Locale.class,
                Arrays.stream(PluginLocalization.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("locale"))
                        .findFirst()
                        .orElseThrow()
                        .getReturnType());
    }

    @Test
    void pluginContextDefaultAccessorReturnsTheUnavailableSentinel() {
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> invokeDefault(proxy, method, arguments));

        final PluginLocalization localization = context.localization();

        assertSame(PluginLocalization.unavailable(), localization);
        assertFalse(localization.isAvailable());
        final UnsupportedOperationException error =
                assertThrows(UnsupportedOperationException.class, () -> localization.text("key"));
        assertEquals("localization service is not available", error.getMessage());
    }

    @Test
    void textWithFallbackReturnsFallbackOnTheUnavailableSentinel() {
        final PluginLocalization localization = PluginLocalization.unavailable();

        assertEquals("fallback", localization.text("key", "fallback"));
    }

    @Test
    void textWithFallbackReturnsFallbackForMissingAndBlankKeys() {
        final PluginLocalization localization = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return "blank".equals(key) ? " " : "⟦" + key + "⟧";
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return text(key);
            }

            @Override
            public boolean contains(final String key) {
                return "present".equals(key) || "blank".equals(key);
            }
        };

        assertEquals(
                "resolved",
                new PluginLocalization() {
                    @Override
                    public Locale locale() {
                        return Locale.ENGLISH;
                    }

                    @Override
                    public String text(final String key) {
                        return "resolved";
                    }

                    @Override
                    public String format(final String key, final Object... arguments) {
                        return text(key);
                    }

                    @Override
                    public boolean contains(final String key) {
                        return true;
                    }
                }.text("present", "fallback"));
        assertEquals("fallback", localization.text("missing", "fallback"));
        assertEquals("fallback", localization.text("blank", "fallback"));
    }

    @Test
    void textWithFallbackIsTotalForNullAndBlankKeysAndNullFallback() {
        final PluginLocalization localization = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return "value";
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return text(key);
            }

            @Override
            public boolean contains(final String key) {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("key must be non-blank");
                }
                return "present".equals(key);
            }
        };

        assertEquals("fallback", localization.text(null, "fallback"), "a null key yields the fallback");
        assertEquals("fallback", localization.text("  ", "fallback"), "a blank key yields the fallback");
        assertEquals("value", localization.text("present", "fallback"), "a present key resolves normally");
        assertNull(localization.text("missing", null), "a null fallback may be returned as null");
    }

    private static Object invokeDefault(final Object proxy, final Method method, final Object[] arguments)
            throws Throwable {
        if (!method.isDefault()) {
            throw new AssertionError("Unexpected abstract method invocation: " + method);
        }
        return InvocationHandler.invokeDefault(proxy, method, arguments);
    }

    private static String signature(final Method method) {
        final String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getTypeName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + "):"
                + method.getReturnType().getName();
    }
}
