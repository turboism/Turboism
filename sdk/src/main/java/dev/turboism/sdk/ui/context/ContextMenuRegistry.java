package dev.turboism.sdk.ui.context;

import dev.turboism.sdk.permission.RequiresPermission;
import dev.turboism.sdk.plugin.Registration;

@RequiresPermission("turboism.ui.context-menu.contribute")
public interface ContextMenuRegistry {

    Registration contribute(ContextMenuContribution contribution);

    record ContextMenuContribution(
        String id,
        String label,
        String icon,
        String context,
        int priority
    ) {}
}
