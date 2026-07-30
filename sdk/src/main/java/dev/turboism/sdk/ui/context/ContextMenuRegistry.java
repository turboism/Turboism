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
        int priority,
        Target target,
        Operation operation
    ) {
        public ContextMenuContribution(
            final String id,
            final String label,
            final String icon,
            final String context,
            final int priority
        ) {
            this(id, label, icon, context, priority, Target.SELECTION, Operation.ACTION);
        }
    }

    enum Target {
        SELECTION,
        PANEL_TAB
    }

    enum Operation {
        ACTION,
        TOGGLE_PANEL_FLOATING
    }
}
