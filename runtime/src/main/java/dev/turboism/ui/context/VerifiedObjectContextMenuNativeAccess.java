package dev.turboism.ui.context;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.ObjectKind;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exact-selector adapter from Cubism menu callbacks to typed selection snapshots and native items. */
public final class VerifiedObjectContextMenuNativeAccess
    implements VerifiedObjectContextMenuHostOperations.SelectionResolver,
    VerifiedObjectContextMenuHostOperations.NativeAppender {

    private static final String PARAMETER_GROUP_ROW = "object-context-menu.parameter.group-row.class";
    private static final String PARAMETER_GROUP_SOURCE = "object-context-menu.parameter.group-row.source";
    private static final String PARAMETER_ROW_PARAMETERS = "object-context-menu.parameter.row-parameters";
    private static final String WORKSPACE_SELECTOR = "object-context-menu.workspace.selector";
    private static final String WORKSPACE_SELECTED = "object-context-menu.workspace.selected";
    private static final String WORKSPACE_SELECTION = "object-context-menu.workspace.selection.class";
    private static final String WORKSPACE_SELECTION_SOURCE = "object-context-menu.workspace.selection-source";
    private static final String OBJECT_ID = "object-context-menu.object-id";
    private static final String PARAMETER_ID = "object-context-menu.parameter-id";
    private static final String PARAMETER_GROUP_ID = "object-context-menu.parameter-group-id";
    private static final String ID_VALUE = "object-context-menu.id-value";
    private static final String MENU_ITEM_CREATE = "object-context-menu.menu-item.create";
    private static final String MENU_APPEND = "object-context-menu.menu.append";
    private static final String SUBMENU_APPEND = "object-context-menu.submenu.append";
    private static final String MENU_SEPARATOR_CREATE = "object-context-menu.menu-separator.create";
    private static final String SUBMENU_CREATE = "object-context-menu.submenu.create";
    private static final String MENU_ITEMS = "object-context-menu.menu.items";
    private static final String MENU_ITEM_LABEL = "object-context-menu.menu-item.label";
    private static final String PARAMETER_POINT_GUID_VALUE = "object-context-menu.parameter-point.guid-value";
    private static final String MENU_COMPONENT = "object-context-menu.menu.component";

    private final VerifiedMemberResolver resolver;
    private final long hostGeneration;
    private final String documentId;

    public VerifiedObjectContextMenuNativeAccess(
        final VerifiedMemberResolver resolver,
        final long hostGeneration,
        final String documentId
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        if (hostGeneration <= 0) throw new IllegalArgumentException("hostGeneration must be positive");
        this.hostGeneration = hostGeneration;
        this.documentId = requireText(documentId, "documentId");
    }

    @Override
    public ContextMenuSelection resolve(final Location location, final Object source) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(source, "source");
        final List<?> selected = switch (location) {
            case DEFORMER_TAB, PART_TAB -> list(source, "palette selection");
            case PARAMETER_TAB -> parameterSources(source);
            case WORKSPACE_OBJECT -> workspaceSources(source);
        };
        final List<ContextMenuSelection.Item> items = new ArrayList<>(selected.size());
        for (Object value : selected) items.add(item(value));
        return new ContextMenuSelection(hostGeneration, documentId, location, items);
    }

    /** Builds the typed parameter selection carried by a persistent parameter-point Q context. */
    public ContextMenuSelection resolveParameterPoint(final Object context) {
        Objects.requireNonNull(context, "context");
        final Object value = resolver.invoke(PARAMETER_POINT_GUID_VALUE, context);
        final Object id = resolver.invoke("cubism.editor-model.guid.value", value);
        if (!(id instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("parameter-point GUID is unavailable");
        }
        return new ContextMenuSelection(
            hostGeneration,
            documentId,
            Location.PARAMETER_TAB,
            List.of(new ContextMenuSelection.Item(ObjectKind.PARAMETER, text))
        );
    }

    @Override
    public void append(
        final Object menu,
        final ContextMenuContributionDescriptor contribution,
        final java.util.function.Consumer<String> action
    ) {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(action, "action");
        place(menu, nativeEntry(contribution.entry(), action), contribution.placement());
    }

    /** Appends to a persistent native Q menu and returns reversible Swing removal. */
    public dev.turboism.sdk.plugin.Registration appendPersistent(
        final Object menu,
        final ContextMenuContributionDescriptor contribution,
        final java.util.function.Consumer<String> action
    ) {
        final Object item = nativeEntry(contribution.entry(), action);
        place(menu, item, contribution.placement());
        final Object component = resolver.invoke(MENU_COMPONENT, item);
        if (!(component instanceof java.awt.Component awt)) {
            throw new IllegalStateException("persistent context-menu component is unavailable");
        }
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            final java.awt.Container parent = awt.getParent();
            if (parent != null) {
                parent.remove(awt);
                parent.revalidate();
                parent.repaint();
            }
        };
    }

    private Object nativeEntry(
        final dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuEntry entry,
        final java.util.function.Consumer<String> action
    ) {
        return switch (entry.kind()) {
            case ITEM -> {
                final Object callback = resolver.createFunctionalConstructorArgumentProxy(
                    MENU_ITEM_CREATE,
                    2,
                    ignored -> {
                        action.accept(entry.actionId());
                        return kotlinUnit();
                    }
                );
                yield resolver.construct(MENU_ITEM_CREATE, entry.label(), null, callback);
            }
            case SEPARATOR -> resolver.construct(MENU_SEPARATOR_CREATE);
            case SUBMENU -> {
                final Object submenu = resolver.construct(SUBMENU_CREATE, entry.label());
                final List<Object> children = new ArrayList<>();
                for (var child : entry.children()) {
                    placeInList(children, nativeEntry(child, action), child.placement());
                }
                for (Object child : children) resolver.invoke(SUBMENU_APPEND, submenu, child);
                yield submenu;
            }
        };
    }

    private void place(
        final Object menu,
        final Object item,
        final dev.turboism.sdk.ui.context.ContextMenuRegistry.Placement placement
    ) {
        if (menu instanceof List<?> raw) {
            @SuppressWarnings("unchecked") final List<Object> items = (List<Object>) raw;
            placeInList(items, item, placement);
            return;
        }
        if (placement.kind() == dev.turboism.sdk.ui.context.ContextMenuRegistry.PlacementKind.FIRST
            || placement.kind() == dev.turboism.sdk.ui.context.ContextMenuRegistry.PlacementKind.LAST) {
            resolver.invoke(MENU_APPEND, menu, item);
            return;
        }
        final Object value = resolver.invoke(MENU_ITEMS, menu);
        if (!(value instanceof List<?> raw)) {
            throw new IllegalStateException("context-menu items are unavailable");
        }
        @SuppressWarnings("unchecked") final List<Object> items = (List<Object>) raw;
        placeInList(items, item, placement);
    }

    private void placeInList(
        final List<Object> items,
        final Object item,
        final dev.turboism.sdk.ui.context.ContextMenuRegistry.Placement placement
    ) {
        switch (placement.kind()) {
            case FIRST -> items.add(0, item);
            case LAST -> items.add(item);
            case BEFORE, AFTER -> {
                final int anchor = anchorIndex(items, placement.anchorId());
                if (anchor < 0) {
                    throw new IllegalStateException("context-menu placement anchor is unavailable");
                }
                items.add(placement.kind() ==
                    dev.turboism.sdk.ui.context.ContextMenuRegistry.PlacementKind.BEFORE
                    ? anchor : anchor + 1, item);
            }
        }
    }

    private int anchorIndex(final List<Object> items, final String anchorId) {
        for (int index = 0; index < items.size(); index++) {
            final Object label = resolver.invoke(MENU_ITEM_LABEL, items.get(index));
            if (anchorId.equals(label)) return index;
        }
        return -1;
    }

    private List<?> parameterSources(final Object row) {
        if (resolver.isInstance(PARAMETER_GROUP_ROW, row)) {
            return List.of(Objects.requireNonNull(resolver.invoke(PARAMETER_GROUP_SOURCE, row), "parameter group"));
        }
        return list(resolver.invoke(PARAMETER_ROW_PARAMETERS, row), "parameter row sources");
    }

    private List<?> workspaceSources(final Object source) {
        final Object selector = resolver.invoke(WORKSPACE_SELECTOR, source);
        final List<?> selected = list(resolver.invoke(WORKSPACE_SELECTED, selector), "workspace selections");
        final List<Object> values = new ArrayList<>(selected.size());
        for (Object selection : selected) {
            if (!resolver.isInstance(WORKSPACE_SELECTION, selection)) {
                throw new IllegalStateException("workspace selection type is unsupported");
            }
            values.add(Objects.requireNonNull(
                resolver.invoke(WORKSPACE_SELECTION_SOURCE, selection),
                "workspace selection source"
            ));
        }
        return values;
    }

    private ContextMenuSelection.Item item(final Object source) {
        final ObjectKind kind;
        final String idAlias;
        if (resolver.isInstance("object-context-menu.warp.class", source)) {
            kind = ObjectKind.WARP_DEFORMER;
            idAlias = OBJECT_ID;
        } else if (resolver.isInstance("object-context-menu.rotation.class", source)) {
            kind = ObjectKind.ROTATION_DEFORMER;
            idAlias = OBJECT_ID;
        } else if (resolver.isInstance("object-context-menu.art-mesh.class", source)) {
            kind = ObjectKind.ART_MESH;
            idAlias = OBJECT_ID;
        } else if (resolver.isInstance("object-context-menu.part.class", source)) {
            kind = ObjectKind.PART;
            idAlias = OBJECT_ID;
        } else if (resolver.isInstance("object-context-menu.glue.class", source)) {
            kind = ObjectKind.GLUE;
            idAlias = OBJECT_ID;
        } else if (resolver.isInstance("object-context-menu.parameter.class", source)) {
            kind = ObjectKind.PARAMETER;
            idAlias = PARAMETER_ID;
        } else if (resolver.isInstance("object-context-menu.parameter-group.class", source)) {
            kind = ObjectKind.PARAMETER_FOLDER;
            idAlias = PARAMETER_GROUP_ID;
        } else {
            throw new IllegalStateException("context-menu object type is unsupported");
        }
        final Object id = resolver.invoke(idAlias, source);
        final Object value = resolver.invoke(ID_VALUE, id);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("context-menu object ID is unavailable");
        }
        return new ContextMenuSelection.Item(kind, text);
    }

    private Object kotlinUnit() {
        try {
            return Class.forName("kotlin.Unit", false, resolver.hostClassLoader()).getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    private static List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException(label + " are unavailable");
        return List.copyOf(list);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
