package dev.turboism.core.event;

import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.cubism.event.DrawableGeometryEvent;
import dev.turboism.sdk.cubism.event.DrawableLockEvent;
import dev.turboism.sdk.cubism.event.DrawableOpacityEvent;
import dev.turboism.sdk.cubism.event.CubismOperationLifecycleEvent;
import dev.turboism.sdk.cubism.event.DrawableVisibilityEvent;
import dev.turboism.sdk.cubism.event.EditorExitEvent;
import dev.turboism.sdk.cubism.event.EditorStartupEvent;
import dev.turboism.sdk.cubism.event.ModelUpdateEvent;
import dev.turboism.sdk.cubism.event.DeformerLockEvent;
import dev.turboism.sdk.cubism.event.DeformerOpacityEvent;
import dev.turboism.sdk.cubism.event.DeformerVisibilityEvent;
import dev.turboism.sdk.cubism.event.ParameterValueEvent;
import dev.turboism.sdk.cubism.event.PartNameEvent;
import dev.turboism.sdk.cubism.event.PartOpacityEvent;
import dev.turboism.sdk.cubism.event.ProjectFileLifecycleEvent;
import dev.turboism.sdk.cubism.event.RotationDeformerBaseAngleEvent;
import dev.turboism.sdk.cubism.event.RotationDeformerFormEvent;
import dev.turboism.sdk.cubism.event.WarpDeformerGridEvent;
import dev.turboism.sdk.action.ActionInvocationEvent;
import dev.turboism.sdk.appearance.AppearanceChangedEvent;
import dev.turboism.sdk.cubism.backup.BackupCompletedEvent;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.ui.table.SceneTableHeaderClickEvent;
import dev.turboism.sdk.ui.table.SceneTableItemOrderEvent;
import dev.turboism.sdk.ui.table.SceneTableSnapshotEvent;
import dev.turboism.sdk.runtime.CubismLogBatchEvent;
import dev.turboism.sdk.runtime.PluginLifecycleEvent;
import dev.turboism.sdk.performance.PerformanceSampleEvent;

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
            ModelUpdateEvent.class,
            ProjectFileLifecycleEvent.class,
            EditorStartupEvent.class,
            EditorExitEvent.class,
            PartNameEvent.class,
            AppearanceChangedEvent.class,
            BackupCompletedEvent.class,
            SelectionChangedEvent.class,
            SceneTableHeaderClickEvent.class,
            SceneTableSnapshotEvent.class,
            SceneTableItemOrderEvent.class,
            CubismLogBatchEvent.class,
            PerformanceSampleEvent.class,
            ActionInvocationEvent.class,
            PluginLifecycleEvent.class
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
