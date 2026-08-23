package dev.turboism.core.event;

import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.TurboismEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntrypointSubscriberCatalogTest {

    @Test
    void catalogsAnnotatedMethodsInCanonicalOrder() {
        final List<EventSubscriberDescriptor> descriptors =
            new EntrypointSubscriberCatalog().inspect(List.of(new OrderedSubscriber()));

        assertEquals(2, descriptors.size());
        assertEquals("alpha", descriptors.get(0).method().getName());
        assertEquals(EventPriority.HIGH, descriptors.get(0).priority());
        assertEquals("zeta", descriptors.get(1).method().getName());
        assertEquals(0, descriptors.get(0).entrypointOrdinal());
        assertEquals(0, descriptors.get(0).methodOrdinal());
        assertEquals(1, descriptors.get(1).methodOrdinal());
    }

    @Test
    void rejectsInvalidSubscriberSignaturesBeforeRegistration() {
        final EntrypointSubscriberCatalog catalog = new EntrypointSubscriberCatalog();

        assertThrows(
            IllegalArgumentException.class,
            () -> catalog.inspect(List.of(new InvalidReturnSubscriber()))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> catalog.inspect(List.of(new InvalidParameterSubscriber()))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> catalog.inspect(List.of(new NonPublicSubscriber()))
        );
    }

    public static final class OrderedSubscriber {

        @SubscribeEvent
        public void zeta(final TestEvent event) {
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void alpha(final TestEvent event) {
        }
    }

    public static final class InvalidReturnSubscriber {

        @SubscribeEvent
        public boolean invalid(final TestEvent event) {
            return false;
        }
    }

    public static final class InvalidParameterSubscriber {

        @SubscribeEvent
        public void invalid(final String value) {
        }
    }

    public static final class NonPublicSubscriber {

        @SubscribeEvent
        private void invalid(final TestEvent event) {
        }
    }

    private record TestEvent(String value) implements TurboismEvent {
    }
}
