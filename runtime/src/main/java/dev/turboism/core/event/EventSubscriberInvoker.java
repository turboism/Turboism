package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;

import java.lang.reflect.InvocationTargetException;

/** Invokes one validated subscriber while preserving the original failure. */
public final class EventSubscriberInvoker {

    public void invoke(
        final EventSubscriberDescriptor descriptor,
        final EventBus.TurboismEvent event
    ) throws Throwable {
        try {
            descriptor.method().invoke(descriptor.entrypoint(), event);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(
                "Validated event subscriber became inaccessible: "
                    + descriptor.canonicalSignature(),
                failure
            );
        }
    }
}
