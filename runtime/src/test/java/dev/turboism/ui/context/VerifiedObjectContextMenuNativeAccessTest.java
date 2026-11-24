package dev.turboism.ui.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextMenuSelection;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import org.junit.jupiter.api.Test;

class VerifiedObjectContextMenuNativeAccessTest {

    private static final String WORKSPACE_MENU_APPEND = "object-context-menu.workspace.menu.append";
    private static final int NATIVE_MENU_COLUMN = 0;
    private static final int NATIVE_HEADER_SPAN = 6;
    private static final Dimension NATIVE_HEADER_SIZE = new Dimension(120, 72);

    @Test
    void placesOneLocalizedHeadingAboveWorkspaceContributionsBesideNativeItems() {
        final var access = new VerifiedObjectContextMenuNativeAccess(
                resolver(), 9, () -> "document-a", () -> Locale.SIMPLIFIED_CHINESE);
        final var host = new VerifiedObjectContextMenuHostOperations(access, access);
        final var location = ContextMenuRegistry.Location.WORKSPACE_OBJECT;
        final var source = new ActionManager(new Selector(List.of(new Selection(new Mesh("mesh")))));
        final Menu menu = new Menu();
        final GridBagConstraints nativeRow = new GridBagConstraints();
        nativeRow.gridx = NATIVE_MENU_COLUMN;
        nativeRow.fill = GridBagConstraints.HORIZONTAL;
        final GridBagConstraints headerRow = (GridBagConstraints) nativeRow.clone();
        headerRow.gridwidth = NATIVE_HEADER_SPAN;
        menu.appendRow(new Item("Header"), headerRow);
        menu.popup.getComponent(0).setPreferredSize(NATIVE_HEADER_SIZE);
        host.augment(menu, location, source);
        assertEquals(List.of("Header"), menu.labels());
        final List<String> actions = new ArrayList<>();
        for (final String id : List.of("generate", "edit-psd", "transfer", "trace")) {
            host.addItem(
                    new ContextMenuContributionDescriptor(
                            "plugin", id, id, id, null, location, Set.of(ContextMenuRegistry.ObjectKind.ART_MESH), 1),
                    (selection, actionId) -> actions.add(actionId));
        }
        host.augment(menu, location, source);
        menu.appendRow(new Item("Native action"), nativeRow);
        menu.popup.setSize(menu.popup.getPreferredSize());
        menu.popup.doLayout();

        final var components = menu.popup.getComponents();
        assertEquals(
                List.of("Header", "插件选项", "", "generate", "edit-psd", "transfer", "trace", "Native action"),
                menu.labels());
        final var header = components[0];
        final JLabel heading = assertInstanceOf(JLabel.class, components[1]);
        assertEquals("插件选项", heading.getText());
        assertEquals(Color.BLACK, heading.getForeground());
        assertEquals(SwingConstants.CENTER, heading.getHorizontalAlignment());
        assertEquals(SwingConstants.CENTER, heading.getVerticalAlignment());
        assertInstanceOf(JSeparator.class, components[2]);
        final var nativeAction = components[components.length - 1];
        assertEquals(header.getX(), nativeAction.getX());
        assertEquals(header.getY() + header.getHeight(), nativeAction.getY());
        assertTrue(heading.getX() > header.getX() + header.getWidth());
        assertEquals(header.getY(), heading.getY());
        assertEquals(header.getHeight(), heading.getHeight());
        for (int index = 2; index < components.length - 1; index++) {
            assertEquals(heading.getX(), components[index].getX());
            assertTrue(
                    components[index].getY() > components[index - 1].getY(),
                    menu.labels().get(index));
        }
        menu.items().get(3).click();
        assertEquals(List.of("generate"), actions);
    }

    @Test
    void resolvesPaletteAndWorkspaceSelectionsAndAppendsNativeItems() {
        final VerifiedMemberResolver resolver = resolver();
        final VerifiedObjectContextMenuNativeAccess access =
                new VerifiedObjectContextMenuNativeAccess(resolver, 9, () -> "document-a", () -> Locale.ENGLISH);

        assertEquals(
                List.of(
                        new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.WARP_DEFORMER, "warp"),
                        new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ART_MESH, "mesh")),
                access.resolve(ContextMenuRegistry.Location.DEFORMER_TAB, List.of(new Warp("warp"), new Mesh("mesh")))
                        .items());
        assertEquals(
                List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.PARAMETER_FOLDER, "folder")),
                access.resolve(ContextMenuRegistry.Location.PARAMETER_TAB, new ParameterGroupRow(new Group("folder")))
                        .items());
        assertEquals(
                List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.PART, "part")),
                access.resolve(ContextMenuRegistry.Location.PART_TAB, List.of(new Part("part")))
                        .items());
        assertEquals(
                List.of(new ContextMenuSelection.Item(ContextMenuRegistry.ObjectKind.ROTATION_DEFORMER, "rotation")),
                access.resolve(
                                ContextMenuRegistry.Location.WORKSPACE_OBJECT,
                                new ActionManager(new Selector(List.of(new Selection(new Rotation("rotation"))))))
                        .items());

        final Menu menu = new Menu();
        menu.append(new Item("Native"));
        final List<String> actions = new ArrayList<>();
        access.append(
                menu,
                descriptor(
                        "tools",
                        ContextMenuRegistry.ContextMenuEntry.submenu(
                                "tools",
                                "Tools",
                                List.of(
                                        ContextMenuRegistry.ContextMenuEntry.item("child", "Child", "action.child"),
                                        ContextMenuRegistry.ContextMenuEntry.separator("separator"),
                                        ContextMenuRegistry.ContextMenuEntry.submenu(
                                                "nested",
                                                "Nested",
                                                List.of(ContextMenuRegistry.ContextMenuEntry.item(
                                                        "deep", "Deep", "action.deep"))))),
                        ContextMenuRegistry.Placement.before("Native")),
                actions::add);
        assertEquals(List.of("Tools", "Native"), menu.labels());
        final Item tools = menu.items().get(0);
        assertEquals(
                List.of("Child", "", "Nested"),
                tools.children().stream().map(Item::label).toList());
        tools.children().get(0).click();
        assertEquals(List.of("action.child"), actions);

        final Menu firstMenu = new Menu();
        access.append(
                firstMenu,
                descriptor(
                        "first",
                        ContextMenuRegistry.ContextMenuEntry.item("first", "First", "action.first"),
                        ContextMenuRegistry.Placement.first()),
                actions::add);
        assertEquals(List.of("First"), firstMenu.labels());
    }

    private static ContextMenuContributionDescriptor descriptor(
            final String id,
            final ContextMenuRegistry.ContextMenuEntry entry,
            final ContextMenuRegistry.Placement placement) {
        return new ContextMenuContributionDescriptor(
                "plugin",
                id,
                "action",
                entry.label().isBlank() ? id : entry.label(),
                null,
                ContextMenuRegistry.Location.DEFORMER_TAB,
                Set.of(ContextMenuRegistry.ObjectKind.WARP_DEFORMER),
                1,
                entry,
                placement);
    }

    private static VerifiedMemberResolver resolver() {
        final List<StaticSelector> selectors = List.of(
                classSelector("object-context-menu.parameter.group-row.class", ParameterGroupRow.class),
                method(
                        "object-context-menu.parameter.group-row.source",
                        ParameterGroupRow.class,
                        "group",
                        descriptor(Group.class)),
                method(
                        "object-context-menu.parameter.row-parameters",
                        ParameterRow.class,
                        "parameters",
                        "()Ljava/util/List;"),
                method(
                        "object-context-menu.workspace.selector",
                        ActionManager.class,
                        "selector",
                        descriptor(Selector.class)),
                method("object-context-menu.workspace.selected", Selector.class, "selected", "()Ljava/util/List;"),
                classSelector("object-context-menu.workspace.selection.class", Selection.class),
                method(
                        "object-context-menu.workspace.selection-source",
                        Selection.class,
                        "source",
                        "()Ljava/lang/Object;"),
                classSelector("object-context-menu.warp.class", Warp.class),
                classSelector("object-context-menu.rotation.class", Rotation.class),
                classSelector("object-context-menu.art-mesh.class", Mesh.class),
                classSelector("object-context-menu.part.class", Part.class),
                classSelector("object-context-menu.glue.class", Glue.class),
                classSelector("object-context-menu.parameter.class", Parameter.class),
                classSelector("object-context-menu.parameter-group.class", Group.class),
                method("object-context-menu.object-id", BaseSource.class, "id", descriptor(Id.class)),
                method("object-context-menu.parameter-id", Parameter.class, "id", descriptor(Id.class)),
                method("object-context-menu.parameter-group-id", Group.class, "id", descriptor(Id.class)),
                method("object-context-menu.id-value", Id.class, "value", "()Ljava/lang/String;"),
                constructor(
                        "object-context-menu.menu-item.create",
                        Item.class,
                        "(Ljava/lang/String;Ljava/lang/Object;L"
                                + Callback.class.getName().replace('.', '/') + ";)V"),
                method("object-context-menu.submenu.append", Item.class, "append", "(L" + internal(Item.class) + ";)V"),
                constructor("object-context-menu.menu-separator.create", Item.class, "()V"),
                constructor("object-context-menu.submenu.create", Item.class, "(Ljava/lang/String;)V"),
                method("object-context-menu.menu.items", Menu.class, "mutableItems", "()Ljava/util/List;"),
                method("object-context-menu.menu.append", Menu.class, "append", "(L" + internal(Item.class) + ";)V"),
                method(
                        WORKSPACE_MENU_APPEND,
                        Menu.class,
                        "appendRow",
                        "(L" + internal(Item.class) + ";Ljava/awt/GridBagConstraints;)V"),
                method("object-context-menu.menu-item.label", Item.class, "label", "()Ljava/lang/String;"),
                method("object-context-menu.menu.component", Item.class, "component", descriptor(JComponent.class)));
        return TestVerifiedResolvers.create(
                "adapter.object-context-menu",
                Set.of("ui.object-context-menu.contribute"),
                selectors,
                VerifiedObjectContextMenuNativeAccessTest.class.getClassLoader());
    }

    private static StaticSelector classSelector(final String alias, final Class<?> owner) {
        return StaticSelector.classSelector(alias, internal(owner));
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static StaticSelector constructor(final String alias, final Class<?> owner, final String descriptor) {
        return StaticSelector.constructor(alias, internal(owner), descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String descriptor(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public record Id(String value) {}

    public abstract static class BaseSource {
        private final Id id;

        protected BaseSource(final String id) {
            this.id = new Id(id);
        }

        public Id id() {
            return id;
        }
    }

    public static final class Warp extends BaseSource {
        public Warp(final String id) {
            super(id);
        }
    }

    public static final class Rotation extends BaseSource {
        public Rotation(final String id) {
            super(id);
        }
    }

    public static final class Mesh extends BaseSource {
        public Mesh(final String id) {
            super(id);
        }
    }

    public static final class Part extends BaseSource {
        public Part(final String id) {
            super(id);
        }
    }

    public static final class Glue extends BaseSource {
        public Glue(final String id) {
            super(id);
        }
    }

    public static final class Parameter extends BaseSource {
        public Parameter(final String id) {
            super(id);
        }

        @Override
        public Id id() {
            return super.id();
        }
    }

    public static final class Group extends BaseSource {
        public Group(final String id) {
            super(id);
        }

        @Override
        public Id id() {
            return super.id();
        }
    }

    public record Row(Object source) {}

    public record Palette(List<Row> rows) {}

    public record ParameterListener(Object currentRow) {}

    public record ParameterGroupRow(Group group) {}

    public record ParameterRow(List<Parameter> parameters) {}

    public record Selection(Object source) {}

    public record Selector(List<Selection> selected) {}

    public record ActionManager(Selector selector) {}

    public interface Callback {
        Object invoke(Object event);
    }

    public static final class Item {
        private final String label;
        private final Callback callback;
        private final JComponent component;
        private final List<Item> children = new ArrayList<>();

        public Item() {
            this("", null, null);
        }

        public Item(final String label) {
            this(label, null, null);
        }

        public Item(final String label, final Object icon, final Callback callback) {
            this.label = label;
            this.callback = callback;
            this.component = label.isEmpty() ? new JSeparator() : new JLabel(label);
        }

        public JComponent component() {
            return component;
        }

        public String label() {
            return label;
        }

        public List<Item> children() {
            return List.copyOf(children);
        }

        public void click() {
            if (callback != null) callback.invoke(null);
        }

        public void append(final Item item) {
            children.add(item);
        }
    }

    public static final class Menu {
        private final List<Item> items = new ArrayList<>();
        private final JPopupMenu popup = new JPopupMenu();

        public Menu() {
            popup.setLayout(new GridBagLayout());
        }

        public void append(final Item item) {
            items.add(item);
            popup.add(item.component());
        }

        public void appendRow(final Item item, final GridBagConstraints constraints) {
            items.add(item);
            popup.add(item.component(), constraints);
        }

        public List<Item> mutableItems() {
            return items;
        }

        public List<Item> items() {
            return List.copyOf(items);
        }

        public List<String> labels() {
            return items.stream().map(Item::label).toList();
        }
    }
}
