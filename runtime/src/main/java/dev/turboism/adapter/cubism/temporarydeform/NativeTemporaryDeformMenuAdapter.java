package dev.turboism.adapter.cubism.temporarydeform;

import java.awt.Component;
import java.awt.Window;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

/**
 * Reconciles custom Temporary Deform Tool entries inside Cubism's Modeling → Temporary Deform
 * native submenu on the EDT.
 */
final class NativeTemporaryDeformMenuAdapter {

    static final String UNBIND_COMMAND_ID = "CMD_DELETE_TEMPORARY_HANDLER";
    static final String TURBOISM_MENU_MARKER = "turboism.temporaryDeform.menuItem";

    private static final Comparator<MenuEntry> ENTRY_ORDER =
            Comparator.comparingInt(MenuEntry::order).thenComparing(MenuEntry::qualifiedId);

    private final Supplier<Window[]> windowSupplier;
    private JMenu trackedMenu;

    NativeTemporaryDeformMenuAdapter() {
        this(Window::getWindows);
    }

    NativeTemporaryDeformMenuAdapter(final Supplier<Window[]> windowSupplier) {
        this.windowSupplier = Objects.requireNonNull(windowSupplier, "windowSupplier");
    }

    /** Reconciles the native temporary deform submenu with the active set of custom tools. */
    void reconcile(final List<MenuEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("temporary deform menu reconciliation requires EDT");
        }
        removeInjectedItems(trackedMenu);
        final Optional<JMenu> foundMenu = findTemporaryDeformMenu();
        if (foundMenu.isEmpty()) {
            return;
        }
        final JMenu menu = foundMenu.get();
        if (menu != trackedMenu) {
            removeInjectedItems(menu);
            trackedMenu = menu;
        }
        if (entries.isEmpty()) {
            menu.revalidate();
            menu.repaint();
            return;
        }
        final int insertIndex = resolveInsertIndex(menu);
        if (insertIndex < 0) {
            return;
        }
        final List<MenuEntry> sorted = entries.stream().sorted(ENTRY_ORDER).toList();
        int cursor = insertIndex;
        final JSeparator separator = new JSeparator();
        separator.putClientProperty(TURBOISM_MENU_MARKER, Boolean.TRUE);
        menu.add(separator, cursor++);
        for (final MenuEntry entry : sorted) {
            final JMenuItem item = new JMenuItem(entry.label());
            item.putClientProperty(TURBOISM_MENU_MARKER, entry.qualifiedId());
            item.addActionListener(ignored -> entry.onActivate().run());
            menu.insert(item, cursor++);
        }
        menu.revalidate();
        menu.repaint();
    }

    /** Removes all injected menu items and separators from the tracked native submenu. */
    void clear() {
        if (trackedMenu != null) {
            removeInjectedItems(trackedMenu);
            trackedMenu.revalidate();
            trackedMenu.repaint();
            trackedMenu = null;
        }
        findTemporaryDeformMenu().ifPresent(menu -> {
            removeInjectedItems(menu);
            menu.revalidate();
            menu.repaint();
        });
    }

    private Optional<JMenu> findTemporaryDeformMenu() {
        for (final Window window : windowSupplier.get()) {
            if (!(window instanceof JFrame frame)) {
                continue;
            }
            final JMenuBar menuBar = frame.getJMenuBar();
            if (menuBar == null) {
                continue;
            }
            for (int i = 0; i < menuBar.getMenuCount(); i++) {
                final JMenu topMenu = menuBar.getMenu(i);
                if (topMenu == null) {
                    continue;
                }
                final Optional<JMenu> match = searchMenu(topMenu);
                if (match.isPresent()) {
                    return match;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<JMenu> searchMenu(final JMenu menu) {
        final Component[] components = menu.getMenuComponents();
        for (final Component component : components) {
            if (component instanceof JMenuItem item && UNBIND_COMMAND_ID.equals(item.getActionCommand())) {
                return Optional.of(menu);
            }
        }
        for (final Component component : components) {
            if (component instanceof JMenu subMenu) {
                final Optional<JMenu> nested = searchMenu(subMenu);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private static int resolveInsertIndex(final JMenu menu) {
        final Component[] components = menu.getMenuComponents();
        for (int i = 0; i < components.length; i++) {
            if (components[i] instanceof JMenuItem item && UNBIND_COMMAND_ID.equals(item.getActionCommand())) {
                if (i > 0 && components[i - 1] instanceof JSeparator) {
                    return i - 1;
                }
                return i;
            }
        }
        return -1;
    }

    private static void removeInjectedItems(final JMenu menu) {
        if (menu == null) {
            return;
        }
        final Component[] components = menu.getMenuComponents();
        for (int i = components.length - 1; i >= 0; i--) {
            final Component component = components[i];
            if (component instanceof javax.swing.JComponent jc && jc.getClientProperty(TURBOISM_MENU_MARKER) != null) {
                menu.remove(i);
            }
        }
    }

    /** Custom menu entry descriptor contributed by the coordinator. */
    record MenuEntry(String qualifiedId, String label, int order, Runnable onActivate) {
        MenuEntry {
            Objects.requireNonNull(qualifiedId, "qualifiedId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(onActivate, "onActivate");
        }
    }
}
