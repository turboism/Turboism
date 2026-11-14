package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.CANVAS_SCAN_DEPTH;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.containsAny;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.widgetOf;

import java.util.List;

/**
 * Menu, workspace, and shortcut discovery for the operator-driven history probe.
 *
 * <p>Shortcuts are read from the live menu bar rather than assumed, so an accelerator that the
 * host did not register is reported as unresolved instead of being pressed blind.</p>
 */
final class WindowsHistoryNativeUiIngressMenuShortcuts {
    /** The Window-ish top menu of the host frame — the {@code s} JMenu carrying the palette
     * items. Prefers the frame JMenuBar, then scans the title-bar menubar in the layered
     * pane. Runs on the EDT. */
    static javax.swing.JMenu windowMenu(final StringBuilder diag) {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar != null) {
                diag.append("bar=")
                        .append(bar.getClass().getName())
                        .append(':')
                        .append(bar.getMenuCount())
                        .append(';');
                for (int index = 0; index < bar.getMenuCount(); index++) {
                    final javax.swing.JMenu menu = bar.getMenu(index);
                    if (menu != null && menu.getText() != null && containsAny(menu.getText(), MENU_WINDOW_MARKERS))
                        return menu;
                }
            } else {
                diag.append("no-bar;");
            }
            final javax.swing.JMenu inTree = windowMenuInTree(frame, 0);
            if (inTree != null) return inTree;
        }
        return null;
    }

    static javax.swing.JMenu windowMenuInTree(final java.awt.Component component, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return null;
        if (component instanceof javax.swing.JMenu menu
                && menu.getText() != null
                && containsAny(menu.getText(), MENU_WINDOW_MARKERS)) return menu;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JMenu found = windowMenuInTree(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The CWidget behind the popup item whose text matches a marker, or null. The menu must
     * already be open. Runs on the EDT. */
    static Object menuWidget(final javax.swing.JMenu menu, final String[] markers, final StringBuilder diag) {
        final javax.swing.JMenuItem jmi = popupItem(menu, markers, diag);
        return jmi == null ? null : widgetOf(jmi, diag);
    }

    /** The popup item whose text matches a marker — the menu is already open. Runs on the
     * EDT. */
    static javax.swing.JMenuItem popupItem(
            final javax.swing.JMenu menu, final String[] markers, final StringBuilder diag) {
        final javax.swing.JPopupMenu popup = menu == null ? null : menu.getPopupMenu();
        if (popup == null) {
            diag.append("no-popup;");
            return null;
        }
        diag.append("items{");
        for (java.awt.Component component : popup.getComponents()) {
            if (!(component instanceof javax.swing.JMenuItem jmi)) continue;
            diag.append(jmi.getText() == null ? "?" : jmi.getText()).append(',');
            if (jmi.getText() != null && containsAny(jmi.getText(), markers)) {
                diag.append('}');
                return jmi;
            }
        }
        diag.append('}');
        return null;
    }

    /** The texts under the Window-ish top menu, for diagnostics when the palette item is absent. */
    static String windowMenuTexts() {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            final StringBuilder out = new StringBuilder(512);
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenu menu = bar.getMenu(index);
                if (menu == null || menu.getText() == null || !containsAny(menu.getText(), MENU_WINDOW_MARKERS))
                    continue;
                for (java.awt.Component component : menu.getMenuComponents()) {
                    if (component instanceof javax.swing.JMenuItem child) {
                        out.append(child.getText() == null ? "?" : child.getText())
                                .append(';');
                    }
                }
            }
            return out.length() > 0 ? out.toString() : "window-menu-empty";
        }
        return "no-menubar";
    }

    static final String[] MENU_WINDOW_MARKERS = {"视窗", "ウィンドウ", "Window"};

    static final String[] SHOW_PANELS_MARKERS = {"显示面板", "パネルを表示", "how panels", "anels"};
    static final String[] RESET_STATE_MARKERS = {"重置状态", "状態をリセット", "eset state", "eset"};
    static final String[] WORKSPACE_MARKERS = {"工作区", "ワークスペース", "orkspace"};
    static final String[] WORKSPACE_SKIP_MARKERS = {"保存", "管理", "新建", "删除", "編集", "管理", "ave", "anage", "ew ", "elete"};

    /** Clicks the enabled menu item carrying a Ctrl+key accelerator, when one exists. */
    static boolean menuShortcut(final int key) {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenuItem match = findMenuShortcut(bar.getMenu(index), key);
                if (match != null && match.isEnabled()) {
                    // The real action lives on the host's CMenuItem — resolve the mirror
                    // through the component→widget registry before falling back to the
                    // mirror's own doClick, which can be a silent no-op.
                    final Object widget = widgetOf(match, new StringBuilder(1));
                    if (widget != null) {
                        try {
                            widget.getClass().getMethod("doClick").invoke(widget);
                            return true;
                        } catch (Exception ignored) {
                            // fall through to the mirror click
                        }
                    }
                    match.doClick(0);
                    return true;
                }
            }
        }
        return false;
    }

    static javax.swing.JMenuItem findMenuShortcut(final javax.swing.JMenuItem item, final int key) {
        if (item == null) return null;
        final javax.swing.KeyStroke accelerator = item.getAccelerator();
        if (accelerator != null
                && accelerator.getKeyCode() == key
                && (accelerator.getModifiers() & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) {
            return item;
        }
        if (item instanceof javax.swing.JMenu menu) {
            for (java.awt.Component component : menu.getMenuComponents()) {
                if (component instanceof javax.swing.JMenuItem child) {
                    final javax.swing.JMenuItem found = findMenuShortcut(child, key);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    enum ShortcutResolution {
        DELIVERED,
        WRONG_DIRECTION,
        /** The menu action was measured unchanged; Robot may be used as a fallback. */
        FALLBACK,
        /** The baseline position was not valid, so menu delivery cannot be classified. */
        UNKNOWN,
        /** One or more post-menu position samples were unavailable. */
        UNAVAILABLE
    }

    /**
     * Resolves whether a menu-delivered navigation already happened. An opposite move is still a
     * response and must not be repeated; {@link #hasMoved} will reject it for the requested step.
     */
    static ShortcutResolution shortcutResolution(
            final String kind, final long knownPosition, final long currentPosition) {
        if (knownPosition < 0L) return ShortcutResolution.UNKNOWN;
        if (currentPosition < 0L) return ShortcutResolution.UNAVAILABLE;
        if (currentPosition == knownPosition) return ShortcutResolution.FALLBACK;
        final boolean expected = "UNDO".equals(kind)
                ? currentPosition < knownPosition
                : "REDO".equals(kind) && currentPosition > knownPosition;
        return expected ? ShortcutResolution.DELIVERED : ShortcutResolution.WRONG_DIRECTION;
    }

    /**
     * Resolves the bounded sample window after a menu accelerator. A missing sample remains an
     * unknown observation even when another sample was unchanged: without a complete window the
     * probe cannot prove that the menu did not already execute.
     */
    static ShortcutResolution shortcutResolution(
            final String kind, final long knownPosition, final List<Long> currentPositions) {
        if (knownPosition < 0L) return ShortcutResolution.UNKNOWN;
        if (currentPositions == null || currentPositions.isEmpty()) return ShortcutResolution.UNAVAILABLE;
        boolean unavailable = false;
        for (final Long currentPosition : currentPositions) {
            if (currentPosition == null) {
                unavailable = true;
                continue;
            }
            final ShortcutResolution resolution = shortcutResolution(kind, knownPosition, currentPosition.longValue());
            if (resolution == ShortcutResolution.DELIVERED || resolution == ShortcutResolution.WRONG_DIRECTION) {
                return resolution;
            }
            unavailable |= resolution == ShortcutResolution.UNAVAILABLE;
        }
        return unavailable ? ShortcutResolution.UNAVAILABLE : ShortcutResolution.FALLBACK;
    }
}
