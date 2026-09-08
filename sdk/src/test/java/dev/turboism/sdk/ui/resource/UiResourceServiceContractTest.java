package dev.turboism.sdk.ui.resource;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiResourceServiceContractTest {
    @Test
    void referencesAreClosedImmutableKeysNotNativeHandles() {
        assertEquals(List.of("ART_MESH", "WARP_DEFORMER", "ROTATION_DEFORMER"),
            Arrays.stream(CubismIcon.values()).map(Enum::name).toList());
        assertTrue(UiIconRef.class.isRecord());
        assertEquals(1, UiIconRef.class.getRecordComponents().length);
        assertEquals(CubismIcon.class, UiIconRef.class.getRecordComponents()[0].getType());
        for (CubismIcon key : CubismIcon.values()) {
            assertEquals(new UiIconRef(key), UiResourceService.unavailable().cubismIcon(key));
        }
        assertThrows(NullPointerException.class, () -> new UiIconRef(null));
        assertThrows(NullPointerException.class, () -> UiResourceService.unavailable().cubismIcon(null));
    }

    @Test
    void referenceConstructionDoesNotClaimHostAvailability() {
        final UiResourceService service = UiResourceService.unavailable();
        assertSame(service, UiResourceService.unavailable());
        for (CubismIcon key : CubismIcon.values()) {
            assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE,
                service.availability(service.cubismIcon(key)));
        }
        assertThrows(NullPointerException.class, () -> service.availability(null));
    }

    @Test
    void oldPluginContextsDefaultToUnavailableWithoutRequiringAnOverride() throws Throwable {
        final var accessor = PluginContext.class.getMethod("uiResources");
        assertTrue(accessor.isDefault());
        assertEquals(UiResourceService.class, accessor.getReturnType());
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(), new Class<?>[] {PluginContext.class},
            (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args)
        );
        assertSame(UiResourceService.unavailable(), context.uiResources());
    }
}
