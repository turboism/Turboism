package dev.turboism.eventprocessor;

import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.TurboismEvent;

class InheritedSubscriberBase {
    @SubscribeEvent
    public void inherited(final InheritedSubscriberFixture.InheritedEvent event) { }
}

public final class InheritedSubscriberFixture extends InheritedSubscriberBase {
    public record InheritedEvent(String value) implements TurboismEvent { }
}
