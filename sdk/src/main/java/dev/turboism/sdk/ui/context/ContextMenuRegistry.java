package dev.turboism.sdk.ui.context;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.permission.RequiresPermission;
import dev.turboism.sdk.plugin.Registration;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.function.Predicate;

/**
 * Registry for plugin-contributed Cubism context-menu entries.
 *
 * <p>Contributions are declarative: a plugin describes where an entry belongs and which action it
 * invokes, and the runtime owns attachment, ordering, reconciliation and removal. Plugins never
 * touch host menu widgets.</p>
 *
 * <p>A contribution is validated when it is constructed, not when the menu opens, so an entry that
 * is impossible for its location fails at registration rather than silently never appearing.</p>
 */
@PreviewApi
@RequiresPermission("turboism.ui.context-menu.contribute")
public interface ContextMenuRegistry {

    /**
     * Contributes one context-menu entry.
     *
     * @param contribution the entry to attach
     * @return a registration whose closure removes the entry; closing it is the only supported
     *     way to withdraw a contribution, and plugin disable closes it automatically
     */
    Registration contribute(ContextMenuContribution contribution);

    /** Host menu a contribution attaches to, and the object kinds that menu can carry. */
    enum Location {
        DEFORMER_TAB(EnumSet.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH)),
        PARAMETER_TAB(EnumSet.of(ObjectKind.PARAMETER, ObjectKind.PARAMETER_FOLDER)),
        PART_TAB(EnumSet.of(
            ObjectKind.PART,
            ObjectKind.PART_FOLDER,
            ObjectKind.GLUE,
            ObjectKind.WARP_DEFORMER,
            ObjectKind.ROTATION_DEFORMER,
            ObjectKind.ART_MESH
        )),
        WORKSPACE_OBJECT(EnumSet.allOf(ObjectKind.class));

        private final Set<ObjectKind> supportedKinds;

        Location(final Set<ObjectKind> supportedKinds) {
            this.supportedKinds = Set.copyOf(supportedKinds);
        }

        /**
         * Returns the object kinds this location's menu can select.
         *
         * @return an immutable set; a selection contribution naming any other kind is rejected
         */
        public Set<ObjectKind> supportedKinds() {
            return supportedKinds;
        }

        static Location legacy(final String context) {
            return switch (Objects.requireNonNull(context, "context").trim().toLowerCase()) {
                case "deformer" -> DEFORMER_TAB;
                case "parameter" -> PARAMETER_TAB;
                case "part", "parts" -> PART_TAB;
                case "workspace" -> WORKSPACE_OBJECT;
                default -> throw new IllegalArgumentException("unsupported context-menu location: " + context);
            };
        }

        String context() {
            return switch (this) {
                case DEFORMER_TAB -> "deformer";
                case PARAMETER_TAB -> "parameter";
                case PART_TAB -> "part";
                case WORKSPACE_OBJECT -> "workspace";
            };
        }
    }

    enum ObjectKind {
        WARP_DEFORMER,
        ROTATION_DEFORMER,
        ART_MESH,
        PART,
        PART_FOLDER,
        GLUE,
        PARAMETER,
        PARAMETER_FOLDER
    }

    /** Kind of a single menu entry. */
    enum EntryKind {
        /** A clickable entry bound to an action. */
        ITEM,
        /** A visual divider carrying no label or action. */
        SEPARATOR,
        /** A nested menu carrying children and no action of its own. */
        SUBMENU
    }

    /** How an entry is positioned relative to the host menu's existing entries. */
    enum PlacementKind {
        /** Before every existing entry. */
        FIRST,
        /** After every existing entry. */
        LAST,
        /** Immediately before the anchor entry. */
        BEFORE,
        /** Immediately after the anchor entry. */
        AFTER
    }

    /**
     * Where an entry sits in its host menu.
     *
     * @param kind absolute or anchor-relative placement
     * @param anchorId the entry to anchor against, empty for absolute placement
     */
    record Placement(PlacementKind kind, String anchorId) {
        /**
         * Validates that the anchor matches the placement kind.
         *
         * @throws IllegalArgumentException when a relative placement has no anchor, or an
         *     absolute placement supplies one
         */
        public Placement {
            kind = Objects.requireNonNull(kind, "kind");
            anchorId = anchorId == null ? "" : anchorId.trim();
            if ((kind == PlacementKind.BEFORE || kind == PlacementKind.AFTER) && anchorId.isEmpty()) {
                throw new IllegalArgumentException("anchorId is required for relative placement");
            }
            if ((kind == PlacementKind.FIRST || kind == PlacementKind.LAST) && !anchorId.isEmpty()) {
                throw new IllegalArgumentException("anchorId is not valid for absolute placement");
            }
        }

        /**
         * Places the entry before every existing entry.
         *
         * @return an absolute first placement
         */
        public static Placement first() { return new Placement(PlacementKind.FIRST, ""); }

        /**
         * Places the entry after every existing entry.
         *
         * @return an absolute last placement
         */
        public static Placement last() { return new Placement(PlacementKind.LAST, ""); }

        /**
         * Places the entry immediately before an anchor.
         *
         * @param anchorId the entry to anchor against
         * @return a relative placement
         */
        public static Placement before(final String anchorId) { return new Placement(PlacementKind.BEFORE, anchorId); }

        /**
         * Places the entry immediately after an anchor.
         *
         * @param anchorId the entry to anchor against
         * @return a relative placement
         */
        public static Placement after(final String anchorId) { return new Placement(PlacementKind.AFTER, anchorId); }
    }

    /**
     * One entry in a contributed context menu.
     *
     * @param kind item, separator or submenu
     * @param id plugin-scoped entry identity
     * @param label display text, empty for separators
     * @param actionId action invoked on click, empty for separators and submenus
     * @param children nested entries, non-empty only for submenus
     * @param placement position within the host menu
     */
    record ContextMenuEntry(
        EntryKind kind,
        String id,
        String label,
        String actionId,
        List<ContextMenuEntry> children,
        Placement placement
    ) {
        public ContextMenuEntry {
            kind = Objects.requireNonNull(kind, "kind");
            id = requireText(id, "id");
            label = label == null ? "" : label;
            actionId = actionId == null ? "" : actionId;
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            placement = Objects.requireNonNull(placement, "placement");
            switch (kind) {
                case ITEM -> {
                    requireText(label, "label");
                    requireText(actionId, "actionId");
                    if (!children.isEmpty()) throw new IllegalArgumentException("items cannot have children");
                }
                case SEPARATOR -> {
                    if (!label.isEmpty() || !actionId.isEmpty() || !children.isEmpty()) {
                        throw new IllegalArgumentException("separators cannot have content");
                    }
                }
                case SUBMENU -> {
                    requireText(label, "label");
                    if (!actionId.isEmpty()) throw new IllegalArgumentException("submenus cannot have actions");
                    if (children.isEmpty()) throw new IllegalArgumentException("submenus must have children");
                }
            }
        }

        /**
         * Creates a clickable entry placed last.
         *
         * @param id plugin-scoped entry identity
         * @param label display text
         * @param actionId action invoked on click
         * @return the entry
         */
        public static ContextMenuEntry item(final String id, final String label, final String actionId) {
            return item(id, label, actionId, Placement.last());
        }

        /**
         * Creates a clickable entry at an explicit position.
         *
         * @param id plugin-scoped entry identity
         * @param label display text
         * @param actionId action invoked on click
         * @param placement position within the host menu
         * @return the entry
         */
        public static ContextMenuEntry item(
            final String id, final String label, final String actionId, final Placement placement
        ) {
            return new ContextMenuEntry(EntryKind.ITEM, id, label, actionId, List.of(), placement);
        }

        /**
         * Creates a divider placed last.
         *
         * @param id plugin-scoped entry identity
         * @return the separator
         */
        public static ContextMenuEntry separator(final String id) {
            return separator(id, Placement.last());
        }

        /**
         * Creates a divider at an explicit position.
         *
         * @param id plugin-scoped entry identity
         * @param placement position within the host menu
         * @return the separator
         */
        public static ContextMenuEntry separator(final String id, final Placement placement) {
            return new ContextMenuEntry(EntryKind.SEPARATOR, id, "", "", List.of(), placement);
        }

        /**
         * Creates a nested menu placed last.
         *
         * @param id plugin-scoped entry identity
         * @param label display text
         * @param children nested entries, must not be empty
         * @return the submenu
         */
        public static ContextMenuEntry submenu(
            final String id, final String label, final List<ContextMenuEntry> children
        ) {
            return submenu(id, label, children, Placement.last());
        }

        /**
         * Creates a nested menu at an explicit position.
         *
         * @param id plugin-scoped entry identity
         * @param label display text
         * @param children nested entries, must not be empty
         * @param placement position within the host menu
         * @return the submenu
         */
        public static ContextMenuEntry submenu(
            final String id,
            final String label,
            final List<ContextMenuEntry> children,
            final Placement placement
        ) {
            return new ContextMenuEntry(EntryKind.SUBMENU, id, label, "", children, placement);
        }

        private static String requireText(final String value, final String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
            return value;
        }
    }

    record ContextMenuContribution(
        String id,
        String actionId,
        String label,
        String icon,
        String context,
        Location location,
        Set<ObjectKind> objectKinds,
        int priority,
        Target target,
        Operation operation,
        ContextMenuEntry entry,
        Placement placement,
        Predicate<ContextMenuSelection> visibleWhen
    ) {
        public ContextMenuContribution {
            id = requireText(id, "id");
            actionId = requireText(actionId, "actionId");
            label = requireText(label, "label");
            context = requireText(context, "context");
            location = Objects.requireNonNull(location, "location");
            objectKinds = Set.copyOf(Objects.requireNonNull(objectKinds, "objectKinds"));
            target = Objects.requireNonNull(target, "target");
            operation = Objects.requireNonNull(operation, "operation");
            entry = Objects.requireNonNull(entry, "entry");
            placement = Objects.requireNonNull(placement, "placement");
            if (target == Target.SELECTION) {
                if (operation != Operation.ACTION) {
                    throw new IllegalArgumentException("selection context menus require ACTION");
                }
                if (objectKinds.isEmpty()) {
                    throw new IllegalArgumentException("objectKinds must not be empty");
                }
                if (!location.supportedKinds().containsAll(objectKinds)) {
                    throw new IllegalArgumentException("objectKinds are not valid for " + location);
                }
            }
        }

        public ContextMenuContribution(
            final String id,
            final String actionId,
            final String label,
            final String icon,
            final Location location,
            final Set<ObjectKind> objectKinds,
            final int priority
        ) {
            this(
                id, actionId, label, icon, location.context(), location, objectKinds, priority,
                Target.SELECTION, Operation.ACTION,
                ContextMenuEntry.item(id, label, actionId), Placement.last(), null
            );
        }

        public ContextMenuContribution(
            final String id,
            final String actionId,
            final String label,
            final String icon,
            final Location location,
            final Set<ObjectKind> objectKinds,
            final int priority,
            final Predicate<ContextMenuSelection> visibleWhen
        ) {
            this(
                id, actionId, label, icon, location.context(), location, objectKinds, priority,
                Target.SELECTION, Operation.ACTION,
                ContextMenuEntry.item(id, label, actionId), Placement.last(), visibleWhen
            );
        }

        public ContextMenuContribution(
            final String id,
            final Location location,
            final Set<ObjectKind> objectKinds,
            final int priority,
            final ContextMenuEntry entry
        ) {
            this(
                id,
                firstActionId(entry),
                entry.label().isBlank() ? id : entry.label(),
                null,
                location.context(),
                location,
                objectKinds,
                priority,
                Target.SELECTION,
                Operation.ACTION,
                entry,
                entry.placement(),
                null
            );
        }

        public ContextMenuContribution(
            final String id,
            final String actionId,
            final String label,
            final String icon,
            final String context,
            final Location location,
            final Set<ObjectKind> objectKinds,
            final int priority,
            final Target target,
            final Operation operation,
            final ContextMenuEntry entry,
            final Placement placement
        ) {
            this(
                id, actionId, label, icon, context, location, objectKinds, priority,
                target, operation, entry, placement, null
            );
        }

        /** Compatibility constructor for the earlier context-string Preview shape. */
        public ContextMenuContribution(
            final String id,
            final String label,
            final String icon,
            final String context,
            final int priority
        ) {
            this(id, label, icon, context, priority, Target.SELECTION, Operation.ACTION);
        }

        /** Compatibility constructor retained for panel-tab host contributions. */
        public ContextMenuContribution(
            final String id,
            final String label,
            final String icon,
            final String context,
            final int priority,
            final Target target,
            final Operation operation
        ) {
            this(
                id,
                id,
                label,
                icon,
                context,
                target == Target.SELECTION ? Location.legacy(context) : Location.WORKSPACE_OBJECT,
                target == Target.SELECTION
                    ? Location.legacy(context).supportedKinds()
                    : Set.of(),
                priority,
                target,
                operation,
                ContextMenuEntry.item(id, label, id),
                Placement.last(),
                null
            );
        }

        private static String firstActionId(final ContextMenuEntry entry) {
            Objects.requireNonNull(entry, "entry");
            if (entry.kind() == EntryKind.ITEM) return entry.actionId();
            for (ContextMenuEntry child : entry.children()) {
                final String actionId = firstActionId(child);
                if (!actionId.startsWith("context-menu.")) return actionId;
            }
            return "context-menu." + entry.id();
        }

        private static String requireText(final String value, final String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
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
