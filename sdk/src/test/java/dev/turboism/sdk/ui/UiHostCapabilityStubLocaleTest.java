package dev.turboism.sdk.ui;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UiHostCapabilityStubLocaleTest {

    @Test
    void stubHostLocaleReturnsNonNullDefaultLocaleWithoutThrowing() {
        final UiHostCapabilityService stub = stub();
        final Locale locale = stub.hostLocale();
        assertNotNull(locale);
        assertEquals(Locale.getDefault(Locale.Category.DISPLAY), locale);
    }

    private static UiHostCapabilityService stub() {
        return (UiHostCapabilityService) Proxy.newProxyInstance(
            UiHostCapabilityService.class.getClassLoader(),
            new Class<?>[] { UiHostCapabilityService.class },
            (InvocationHandler) (proxy, method, args) -> {
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
