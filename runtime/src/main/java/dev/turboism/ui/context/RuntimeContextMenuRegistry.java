package dev.turboism.ui.context;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RuntimeContextMenuRegistry implements ContextMenuRegistry {

    private final PermissionChecker permissionChecker;
    private final String pluginId;
    private final CopyOnWriteArrayList<ContextMenuContribution> contributions = new CopyOnWriteArrayList<>();

    public RuntimeContextMenuRegistry(final PermissionChecker permissionChecker, final String pluginId) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    @Override
    public Registration contribute(final ContextMenuContribution contribution) {
        Objects.requireNonNull(contribution, "contribution");
        permissionChecker.check(PermissionIds.TURBOISM_UI_CONTEXT_MENU_CONTRIBUTE, contribution.id());
        contributions.add(contribution);
        return () -> contributions.remove(contribution);
    }

    public List<ContextMenuContribution> contributions() {
        return List.copyOf(contributions);
    }

    public String pluginId() {
        return pluginId;
    }
}
