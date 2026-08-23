package dev.turboism.core.event;

import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.event.cubism.DrawableGeometryEvent;
import dev.turboism.sdk.event.cubism.DrawableLockEvent;
import dev.turboism.sdk.event.cubism.DrawableOpacityEvent;
import dev.turboism.sdk.event.cubism.CubismOperationLifecycleEvent;
import dev.turboism.sdk.event.cubism.DrawableVisibilityEvent;
import dev.turboism.sdk.event.cubism.DeformerLockEvent;
import dev.turboism.sdk.event.cubism.DeformerOpacityEvent;
import dev.turboism.sdk.event.cubism.DeformerVisibilityEvent;
import dev.turboism.sdk.event.cubism.ParameterValueEvent;
import dev.turboism.sdk.event.cubism.PartNameEvent;
import dev.turboism.sdk.event.cubism.PartOpacityEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerBaseAngleEvent;
import dev.turboism.sdk.event.cubism.RotationDeformerFormEvent;
import dev.turboism.sdk.event.cubism.WarpDeformerGridEvent;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Domain permissions required by event subscription types. */
public final class EventSubscriptionPermissionCatalog {

    private EventSubscriptionPermissionCatalog() { }

    public static void check(
        final Class<? extends EventBus.TurboismEvent> subscriptionType,
        final PermissionChecker permissionChecker
    ) {
        final Class<? extends EventBus.TurboismEvent> type = Objects.requireNonNull(
            subscriptionType,
            "subscriptionType"
        );
        final PermissionChecker checker = Objects.requireNonNull(
            permissionChecker,
            "permissionChecker"
        );
        for (String permission : requiredPermissions(type)) {
            checker.check(permission, "event.subscribe." + type.getName());
        }
    }

    public static void requireDeclared(
        final PluginDescriptor descriptor,
        final List<EventSubscriberDescriptor> subscribers
    ) {
        final PluginDescriptor plugin = Objects.requireNonNull(descriptor, "descriptor");
        final Set<String> declared = plugin.permissions().stream()
            .map(PluginDescriptor.PermissionRef::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (EventSubscriberDescriptor subscriber : Objects.requireNonNull(
            subscribers,
            "subscribers"
        )) {
            for (String permission : requiredPermissions(subscriber.eventType())) {
                if (!declared.contains(permission)) {
                    throw new IllegalArgumentException(
                        "@SubscribeEvent for " + subscriber.eventType().getName()
                            + " requires " + permission + ": " + plugin.id()
                    );
                }
            }
        }
    }

    static Set<String> requiredPermissions(
        final Class<? extends EventBus.TurboismEvent> subscriptionType
    ) {
        final Set<String> permissions = new LinkedHashSet<>();
        if (subscriptionType.isAssignableFrom(ParameterValueEvent.Before.class)
            || subscriptionType.isAssignableFrom(PartOpacityEvent.Before.class)
            || subscriptionType.isAssignableFrom(PartNameEvent.Before.class)
            || subscriptionType.isAssignableFrom(DrawableOpacityEvent.Before.class)
            || subscriptionType.isAssignableFrom(DrawableVisibilityEvent.Before.class)
            || subscriptionType.isAssignableFrom(DrawableLockEvent.Before.class)
            || subscriptionType.isAssignableFrom(DrawableGeometryEvent.Before.class)
            || subscriptionType.isAssignableFrom(DeformerOpacityEvent.Before.class)
            || subscriptionType.isAssignableFrom(DeformerVisibilityEvent.Before.class)
            || subscriptionType.isAssignableFrom(DeformerLockEvent.Before.class)
            || subscriptionType.isAssignableFrom(WarpDeformerGridEvent.Before.class)
            || subscriptionType.isAssignableFrom(RotationDeformerBaseAngleEvent.Before.class)
            || subscriptionType.isAssignableFrom(RotationDeformerFormEvent.Before.class)
            || subscriptionType.isAssignableFrom(CubismOperationLifecycleEvent.Before.class)) {
            permissions.add(ParameterHookRegistry.INTERCEPT_PERMISSION);
        }
        if (subscriptionType.isAssignableFrom(ParameterValueEvent.On.class)
            || subscriptionType.isAssignableFrom(ParameterValueEvent.After.class)
            || subscriptionType.isAssignableFrom(PartOpacityEvent.On.class)
            || subscriptionType.isAssignableFrom(PartOpacityEvent.After.class)
            || subscriptionType.isAssignableFrom(PartNameEvent.On.class)
            || subscriptionType.isAssignableFrom(PartNameEvent.After.class)
            || subscriptionType.isAssignableFrom(DrawableOpacityEvent.On.class)
            || subscriptionType.isAssignableFrom(DrawableOpacityEvent.After.class)
            || subscriptionType.isAssignableFrom(DrawableVisibilityEvent.On.class)
            || subscriptionType.isAssignableFrom(DrawableVisibilityEvent.After.class)
            || subscriptionType.isAssignableFrom(DrawableLockEvent.On.class)
            || subscriptionType.isAssignableFrom(DrawableLockEvent.After.class)
            || subscriptionType.isAssignableFrom(DrawableGeometryEvent.On.class)
            || subscriptionType.isAssignableFrom(DrawableGeometryEvent.After.class)
            || subscriptionType.isAssignableFrom(DeformerOpacityEvent.On.class)
            || subscriptionType.isAssignableFrom(DeformerOpacityEvent.After.class)
            || subscriptionType.isAssignableFrom(DeformerVisibilityEvent.On.class)
            || subscriptionType.isAssignableFrom(DeformerVisibilityEvent.After.class)
            || subscriptionType.isAssignableFrom(DeformerLockEvent.On.class)
            || subscriptionType.isAssignableFrom(DeformerLockEvent.After.class)
            || subscriptionType.isAssignableFrom(WarpDeformerGridEvent.On.class)
            || subscriptionType.isAssignableFrom(WarpDeformerGridEvent.After.class)
            || subscriptionType.isAssignableFrom(RotationDeformerBaseAngleEvent.On.class)
            || subscriptionType.isAssignableFrom(RotationDeformerBaseAngleEvent.After.class)
            || subscriptionType.isAssignableFrom(RotationDeformerFormEvent.On.class)
            || subscriptionType.isAssignableFrom(RotationDeformerFormEvent.After.class)
            || subscriptionType.isAssignableFrom(CubismOperationLifecycleEvent.On.class)
            || subscriptionType.isAssignableFrom(CubismOperationLifecycleEvent.After.class)) {
            permissions.add(ParameterHookRegistry.OBSERVE_PERMISSION);
        }
        return Set.copyOf(permissions);
    }
}
