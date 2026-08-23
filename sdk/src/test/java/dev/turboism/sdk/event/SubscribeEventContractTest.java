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

    private static final class Subscriber {

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void onEvent(final SampleEvent event) {
        }
    }

    private record SampleEvent(String value) implements TurboismEvent {
    }
}
