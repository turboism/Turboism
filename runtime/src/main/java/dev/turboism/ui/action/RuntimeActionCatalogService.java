package dev.turboism.ui.action;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.action.ActionDescriptor;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plugin-facing view of the shared {@link RuntimeEditorUiActionRouter} catalog: enumerates
 * every registered action and routes invocation back through the router.
 *
 * <p>Both operations are gated by {@code turboism.action.invoke} held by the catalog's
 * owner. Enumeration is a detached point-in-time snapshot and follows the router's
 * last-registry-per-owner semantics; invocation carries no UI or selection context and is
 * audited to the calling plugin's log before routing.</p>
 */
public final class RuntimeActionCatalogService implements ActionCatalogService {

    private final String callerPluginId;
    private final RuntimeEditorUiActionRouter actionRouter;
    private final CubismPermissionGate permissionGate;
    private final PluginLogger logger;

    public RuntimeActionCatalogService(
            final String callerPluginId,
            final RuntimeEditorUiActionRouter actionRouter,
            final CubismPermissionGate permissionGate,
            final PluginLogger logger) {
        this.callerPluginId = Objects.requireNonNull(callerPluginId, "callerPluginId");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public List<ActionDescriptor> actions() {
        if (!permissionGate.hasPermission(PermissionIds.TURBOISM_ACTION_INVOKE)) {
            return List.of();
        }
        final List<ActionDescriptor> descriptors = new ArrayList<>();
        actionRouter.snapshot().forEach((pluginId, registries) -> {
            if (registries.isEmpty()) {
                return;
            }
            final ActionRegistry registry = registries.get(registries.size() - 1);
            if (!(registry instanceof RuntimeActionRegistry runtimeRegistry)) {
                return;
            }
            runtimeRegistry.snapshot().forEach((actionId, action) -> {
                final String label = labelOf(action, actionId);
                descriptors.add(new ActionDescriptor(pluginId, actionId, label, action.defaultShortcut()));
            });
        });
        return List.copyOf(descriptors);
    }

    @Override
    public void invoke(final String pluginId, final String actionId) {
        permissionGate.require(PermissionIds.TURBOISM_ACTION_INVOKE, "action-catalog.invoke");
        logger.info("action-catalog: " + callerPluginId + " invoked " + pluginId + "/" + actionId);
        actionRouter.invoke(pluginId, actionId);
    }

    private static String labelOf(final ActionRegistry.Action action, final String actionId) {
        try {
            final String label = action.label();
            return label == null || label.isBlank() ? actionId : label;
        } catch (RuntimeException failure) {
            return actionId;
        }
    }
}
