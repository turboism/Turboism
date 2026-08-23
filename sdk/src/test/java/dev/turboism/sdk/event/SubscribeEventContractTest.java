package dev.turboism.sdk.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscribeEventContractTest {

    @Test
    void annotationRetainsSubscriberOptionsAtRuntime() throws Exception {
        final Method method = Subscriber.class.getMethod("onEvent", SampleEvent.class);
        final SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);

        assertEquals(EventPriority.HIGH, annotation.priority());
    }

    @Test
    void topLevelEventRemainsCompatibleWithLegacyEventBusMarker() {
        assertTrue(EventBus.TurboismEvent.class.isAssignableFrom(SampleEvent.class));
    }

    @Test
    void generatedCatalogSpiUsesTypedDirectHandlers() throws Exception {
        assertEquals(
            Class.class,
            GeneratedSubscriberCatalog.class.getMethod("entrypointType").getReturnType()
        );
        assertTrue(EventSubscriberHandler.class.isAnnotationPresent(FunctionalInterface.class));
        assertEquals(
            void.class,
            EventSubscriberRegistrar.class.getMethod(
                "register",
                Class.class,
                EventPriority.class,
                int.class,
                String.class,
                EventSubscriberHandler.class
            ).getReturnType()
        );
    }

    private static final class Subscriber {

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onEvent(final SampleEvent event) {
        }
    }

    private record SampleEvent(String value) implements TurboismEvent {
    }
}
