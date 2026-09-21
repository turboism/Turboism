package dev.turboism.mapping.verification;

import java.util.HashSet;
import java.util.Set;

/** Exact selector extension used by typed object context-menu host operations. */
public final class ObjectContextMenuVerificationManifest {

    public static final String CAPABILITY_ID = "ui.object-context-menu.contribute";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "object-context-menu.parameter.group-row.class",
        "object-context-menu.parameter.group-row.source",
        "object-context-menu.parameter.row-parameters",
        "object-context-menu.workspace.selector",
        "object-context-menu.workspace.selected",
        "object-context-menu.workspace.selection.class",
        "object-context-menu.workspace.selection-source",
        "object-context-menu.warp.class",
        "object-context-menu.rotation.class",
        "object-context-menu.art-mesh.class",
        "object-context-menu.part.class",
        "object-context-menu.glue.class",
        "object-context-menu.parameter.class",
        "object-context-menu.parameter-group.class",
        "object-context-menu.object-id",
        "object-context-menu.parameter-id",
        "object-context-menu.parameter-group-id",
        "object-context-menu.id-value",
        "object-context-menu.menu-item.create",
        "object-context-menu.menu.append",
        "object-context-menu.submenu.append",
        "object-context-menu.menu-separator.create",
        "object-context-menu.submenu.create",
        "object-context-menu.menu.items",
        "object-context-menu.menu-item.label",
        "object-context-menu.parameter-point.guid-value",
        "object-context-menu.menu.component"
    );

    private ObjectContextMenuVerificationManifest() {
    }

    static Set<String> capabilities(final Set<String> current) {
        final HashSet<String> values = new HashSet<>(current);
        values.add(CAPABILITY_ID);
        return Set.copyOf(values);
    }

    static Set<String> aliases(final Set<String> current) {
        final HashSet<String> values = new HashSet<>(current);
        values.addAll(REQUIRED_ALIASES);
        return Set.copyOf(values);
    }
}
