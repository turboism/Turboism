package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.cubism.DrawableOpacityEvent;
import dev.turboism.sdk.event.cubism.ParameterValueEvent;
import dev.turboism.sdk.event.cubism.PartNameEvent;
import dev.turboism.sdk.event.cubism.PartOpacityEvent;

import java.util.List;
import java.util.Objects;

/** Runtime authority for event ownership and publication rules. */
final class RuntimeEventContractCatalog {

    private static final List<Class<? extends EventBus.TurboismEvent>> RUNTIME_OWNED_FAMILIES =
        List.of(
            ParameterValueEvent.class,
            PartOpacityEvent.class,
            DrawableOpacityEvent.class,
            PartNameEvent.class
        );

    void requirePluginPublicationAllowed(
        final PluginEventOwnerKey publisher,
        final EventBus.TurboismEvent event
    ) {
        Objects.requireNonNull(publisher, "publisher");
        final Class<?> eventType = Objects.requireNonNull(event, "event").getClass();
        if (RUNTIME_OWNED_FAMILIES.stream().anyMatch(family ->
            family.isAssignableFrom(eventType)
        )) {
            throw new IllegalArgumentException(
                "Plugin cannot publish Runtime-owned event contract: "
                    + eventType.getName()
            );
        }
    }
}
