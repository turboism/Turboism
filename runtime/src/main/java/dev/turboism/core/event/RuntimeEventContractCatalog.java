package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.cubism.DrawableGeometryEvent;
import dev.turboism.sdk.event.cubism.DrawableLockEvent;
import dev.turboism.sdk.event.cubism.DrawableOpacityEvent;
import dev.turboism.sdk.event.cubism.CubismOperationLifecycleEvent;
import dev.turboism.sdk.event.cubism.DrawableVisibilityEvent;
import dev.turboism.sdk.event.cubism.EditorExitEvent;
import dev.turboism.sdk.event.cubism.EditorStartupEvent;
import dev.turboism.sdk.event.cubism.DeformerLockEvent;
import dev.turboism.sdk.event.cubism.DeformerOpacityEvent;
import dev.turboism.sdk.event.cubism.DeformerVisibilityEvent;
import dev.turboism.sdk.event.cubism.ParameterValueEvent;
import dev.turboism.sdk.event.cubism.PartNameEvent;
import dev.turboism.sdk.event.cubism.PartOpacityEvent;
import dev.turboism.sdk.event.cubism.ProjectFileLifecycleEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerBaseAngleEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerFormEvent;
import dev.turboism.sdk.event.cubism.WarpDeformerGridEvent;

import java.util.List;
import java.util.Objects;

/** Runtime authority for event ownership and publication rules. */
final class RuntimeEventContractCatalog {

    private static final List<Class<? extends EventBus.TurboismEvent>> RUNTIME_OWNED_FAMILIES =
        List.of(
            ParameterValueEvent.class,
            PartOpacityEvent.class,
            DrawableOpacityEvent.class,
            DrawableVisibilityEvent.class,
            DrawableLockEvent.class,
            DrawableGeometryEvent.class,
            DeformerOpacityEvent.class,
            DeformerVisibilityEvent.class,
            DeformerLockEvent.class,
            WarpDeformerGridEvent.class,
            RotationDeformerBaseAngleEvent.class,
            RotationDeformerFormEvent.class,
            CubismOperationLifecycleEvent.class,
            ProjectFileLifecycleEvent.class,
            EditorStartupEvent.class,
            EditorExitEvent.class,
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
