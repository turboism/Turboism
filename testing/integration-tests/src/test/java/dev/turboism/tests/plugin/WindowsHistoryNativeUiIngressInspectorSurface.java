package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.CANVAS_SCAN_DEPTH;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.MIN_CANVAS_EDGE;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.boundsOf;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.fieldCentre;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.hasPackageComponent;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.isExcludedControl;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.screenBounds;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.RESET_STATE_MARKERS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.SHOW_PANELS_MARKERS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.WORKSPACE_MARKERS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.WORKSPACE_SKIP_MARKERS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.menuWidget;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.popupItem;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressMenuShortcuts.windowMenu;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.POLL_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.containsAny;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.onEdt;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.widgetOf;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspector and palette surface discovery for the operator-driven history probe.
 *
 * <p>The probe must find the real host widgets an operator uses, so the census records every
 * candidate with its class, text, and bounds. Selection prefers the most specific visible
 * surface and reports the score it used.</p>
 */
final class WindowsHistoryNativeUiIngressInspectorSurface {
    /** Bound on the component dump written for actor targeting review. */
    static final int UI_MAP_MAX_COMPONENTS = 400;

    static final int UI_MAP_MAX_WINDOWS = 8;
    static final int UI_MAP_MAX_DEPTH = 12;
    static final int UI_MAP_MAX_CHARS = 65_536;

    /**
     * Opens a palette through the host's own Window menu and reports the result.
     *
     * <p>The fixture layout has palettes closed: the only slider on screen is the status-bar
     * zoom. The frame keeps a fully populated {@link javax.swing.JMenuBar} — the palette items
     * are checkbox mirrors whose programmatic {@code doClick} does not fire the host's toggle —
     * so the menu is opened through the {@link javax.swing.MenuSelectionManager} and the item
     * is clicked with a real Robot press, exactly as the operator does.</p>
     */
    static String openPalette(final String[] itemMarkers, final java.util.function.Supplier<Boolean> realized)
            throws Exception {
        final StringBuilder diag = new StringBuilder(320);
        // Palettes dock as tabs — the ui-map shows a 参数 tab (com.live2d.ui.swingImpl.K) in
        // the right dock's tab bar. A real press on the tab realizes the palette.
        final java.awt.Robot robot = new java.awt.Robot();
        for (int attempt = 0; attempt < 6; attempt++) {
            // Several tabs can carry the same palette name in different dock groups —
            // cycle through them instead of pressing the first one four times.
            final List<javax.swing.AbstractButton> tabs = onEdt(() -> paletteTabs(itemMarkers, diag));
            if (tabs.isEmpty()) {
                diag.append("tab-miss;");
                break;
            }
            final javax.swing.AbstractButton tabButton = tabs.get(attempt % tabs.size());
            final int[] tab = onEdt(() -> fieldCentre(tabButton));
            if (tab == null) {
                diag.append("tab-not-showing;");
                break;
            }
            // K tabs are toggles — re-pressing a tab that is already selected switches
            // its palette back off. Only press it when it is not selected.
            if (!tabButton.isSelected()) {
                robot.mouseMove(tab[0], tab[1]);
                Thread.sleep(60L);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(80L);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }
            for (int poll = 0; poll < 8; poll++) {
                Thread.sleep(POLL_MILLIS);
                if (Boolean.TRUE.equals(onEdt(realized::get))) return "tab-click:" + diag;
            }
            diag.append("tab-no-realize:sel=").append(tabButton.isSelected()).append(';');
        }
        final javax.swing.JMenu menu = onEdt(() -> windowMenu(diag));
        if (menu == null) return "no-window-menu:" + diag;
        onEdt(() -> {
            if (menu.getParent() instanceof javax.swing.JMenuBar bar) {
                javax.swing.MenuSelectionManager.defaultManager()
                        .setSelectedPath(new javax.swing.MenuElement[] {bar, menu, menu.getPopupMenu()});
            } else {
                menu.setPopupMenuVisible(true);
            }
            return true;
        });
        Thread.sleep(600L);
        final javax.swing.JMenuItem jmi = onEdt(() -> popupItem(menu, itemMarkers, diag));
        if (jmi == null) {
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return "menu-no-palette-item:" + diag + "|"
                    + onEdt(WindowsHistoryNativeUiIngressMenuShortcuts::windowMenuTexts);
        }
        // The mirror maps back to its real CMenuItem through the host's component→widget
        // registry (com.live2d.ui.k.a). Clicking the CMenuItem fires the Kotlin action.
        final Object widget = widgetOf(jmi, diag);
        if (widget != null) {
            // The check item can be flagged open while its dock node was never built —
            // toggling alone does not realize the palette. Also try the menu's
            // "show panels" entry and its "reset state" entry, which rebuild the dock
            // layout, then re-toggle the palette item.
            final Object showPanels = onEdt(() -> menuWidget(menu, SHOW_PANELS_MARKERS, diag));
            final Object resetState = onEdt(() -> menuWidget(menu, RESET_STATE_MARKERS, diag));
            // The workspace submenu carries layout presets — switching layout rebuilds the
            // whole dock, palettes included. It is a JMenu inside the popup: open it and
            // take its first real item.
            final Object workspace = onEdt(() -> {
                final javax.swing.JMenuItem sub = popupItem(menu, WORKSPACE_MARKERS, diag);
                if (!(sub instanceof javax.swing.JMenu submenu)) return null;
                javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(new javax.swing.MenuElement[] {
                    menu.getParent() instanceof javax.swing.JMenuBar b ? b : menu,
                    menu,
                    menu.getPopupMenu(),
                    submenu,
                    submenu.getPopupMenu()
                });
                diag.append("sub{");
                for (java.awt.Component component : submenu.getPopupMenu().getComponents()) {
                    if (component instanceof javax.swing.JMenu) continue;
                    if (!(component instanceof javax.swing.JMenuItem leaf)
                            || leaf.getText() == null
                            || leaf.getText().isBlank()) continue;
                    diag.append(leaf.getText()).append(',');
                    // Layout presets only — skip save/manage entries that write host state.
                    if (containsAny(leaf.getText(), WORKSPACE_SKIP_MARKERS)) continue;
                    return widgetOf(leaf, diag);
                }
                diag.append('}');
                return null;
            });
            final StringBuilder note =
                    new StringBuilder("menu-doClick:" + widget.getClass().getSimpleName());
            final Object[] sequence = {widget, showPanels, widget, resetState, widget, workspace, widget};
            for (final Object target : sequence) {
                if (target == null) {
                    note.append(":skip-null");
                    continue;
                }
                onEdt(() -> {
                    target.getClass().getMethod("doClick").invoke(target);
                    return true;
                });
                for (int poll = 0; poll < 8; poll++) {
                    Thread.sleep(POLL_MILLIS);
                    if (Boolean.TRUE.equals(onEdt(realized::get))) {
                        onEdt(() -> {
                            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                            return true;
                        });
                        return note.append(":opened").toString();
                    }
                }
                note.append(":sel=").append(jmi.isSelected());
            }
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return note.append(":not-realized:").append(diag).toString();
        }
        // Fallback: a real Robot press on the showing item.
        final int[] at = onEdt(() -> jmi.isShowing() ? fieldCentre(jmi) : null);
        if (at == null) {
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return "menu-item-not-showing:" + diag;
        }
        robot.mouseMove(at[0], at[1]);
        Thread.sleep(80L);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(80L);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(600L);
        return "menu-click:" + diag;
    }

    /**
     * The screen centre of a dock tab button carrying a palette name — the host renders dock
     * tabs as small buttons (com.live2d.ui.swingImpl.K) in a 21px-high strip. Menu-bar titles
     * and popup items are excluded; a tab is small, showing, and below the toolbar band.
     * Runs on the EDT.
     */
    static javax.swing.AbstractButton paletteTab(final String[] markers, final StringBuilder diag) {
        javax.swing.AbstractButton best = null;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.AbstractButton hit = paletteTabIn(window, markers, 0, diag);
            if (hit != null) best = hit;
        }
        return best;
    }

    /** Every showing dock tab whose text carries a marker, across all windows. */
    static List<javax.swing.AbstractButton> paletteTabs(final String[] markers, final StringBuilder diag) {
        final List<javax.swing.AbstractButton> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectPaletteTabs(window, markers, found, 0, diag);
        }
        return found;
    }

    static void collectPaletteTabs(
            final java.awt.Component component,
            final String[] markers,
            final List<javax.swing.AbstractButton> found,
            final int depth,
            final StringBuilder diag) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.AbstractButton button
                && !(component instanceof javax.swing.JMenuItem)
                && button.isShowing()
                && button.getText() != null
                && containsAny(button.getText(), markers)
                && button.getHeight() <= 30
                && button.getWidth() <= 200) {
            final java.awt.Point p = component.getLocationOnScreen();
            if (p.y > 42 && !found.contains(button)) {
                diag.append("tab=")
                        .append(button.getClass().getSimpleName())
                        .append('[')
                        .append(button.getText())
                        .append(']')
                        .append('@')
                        .append(p.x + button.getWidth() / 2)
                        .append(',')
                        .append(p.y + button.getHeight() / 2)
                        .append(';');
                found.add(button);
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectPaletteTabs(child, markers, found, depth + 1, diag);
            }
        }
    }

    static javax.swing.AbstractButton paletteTabIn(
            final java.awt.Component component, final String[] markers, final int depth, final StringBuilder diag) {
        final List<javax.swing.AbstractButton> list = new ArrayList<>();
        collectPaletteTabs(component, markers, list, depth, diag);
        return list.isEmpty() ? null : list.get(0);
    }

    /** Whether a dock tab carrying one of {@code markers} is currently selected. */
    static boolean paletteTabSelected(final String[] markers) {
        return selectedPaletteTab(markers) != null;
    }

    static final String[] INSPECTOR_MARKERS = {"检视", "检查", "インスペクタ", "nspector"};

    /**
     * A bounded census of palette-package components per visible window: which windows exist,
     * and how many {@code palette.parameter} / {@code palette.inspector} components each one
     * holds — visible or not — so a palette that opened collapsed still shows up.
     */
    static String paletteClassCensus() {
        final StringBuilder out = new StringBuilder(480);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final int[] counts = {0, 0};
            censusPaletteClasses(window, counts, 0);
            out.append(window.getClass().getSimpleName())
                    .append('[')
                    .append(window.getWidth())
                    .append('x')
                    .append(window.getHeight())
                    .append(']')
                    .append(":param=")
                    .append(counts[0])
                    .append(":insp=")
                    .append(counts[1])
                    .append(';');
            if (out.length() > 440) break;
        }
        return out.toString();
    }

    static void censusPaletteClasses(final java.awt.Component component, final int[] counts, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH + 4) return;
        final String className = component.getClass().getName();
        if (className.contains(".palette.parameter")) counts[0]++;
        if (className.contains(".palette.inspector")) counts[1]++;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                censusPaletteClasses(child, counts, depth + 1);
            }
        }
    }

    /** Every showing editable text component whose content already looks like a hex colour. */
    static List<javax.swing.text.JTextComponent> colorFields() {
        final List<javax.swing.text.JTextComponent> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectColorFields(window, found, 0);
        }
        return found;
    }

    static void collectColorFields(
            final java.awt.Component component, final List<javax.swing.text.JTextComponent> found, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
                && text.isShowing()
                && text.isEnabled()
                && text.isEditable()) {
            final String value = text.getText();
            if (value != null && value.trim().matches("#?[0-9a-fA-F]{6}")) {
                found.add(text);
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectColorFields(child, found, depth + 1);
            }
        }
    }

    /** Whether any component from the inspector palette package is instantiated and showing. */
    static boolean inspectorShowing() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible() && hasPackageComponent(window, ".palette.inspector", 0)) return true;
        }
        return false;
    }

    /**
     * Small pressable components inside the inspector palette — the multiply/screen colour
     * swatches among them open the host colour editor. When no {@code .palette.inspector}
     * component exists the search falls back to the dock region that holds the currently
     * selected inspector tab: the palette contents may be built from generic classes.
     */
    static List<java.awt.Component> colorSwatches() {
        final List<java.awt.Component> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectColorSwatches(window, found, false, 0);
        }
        if (found.isEmpty()) {
            final javax.swing.AbstractButton tab = selectedPaletteTab(INSPECTOR_MARKERS);
            if (tab != null) {
                // The palette content lives in a sibling cell of the same dock column, not
                // inside the tab's own cell — climb to the column (the narrow container
                // holding every docked palette) and search it, skipping the tab strips.
                java.awt.Container scope = tab.getParent();
                while (scope != null
                        && scope.getParent() != null
                        && scope.getParent().getWidth() <= 200) {
                    scope = scope.getParent();
                }
                if (scope != null) {
                    collectColorSwatches(scope, found, true, null, 0);
                }
            }
        }
        return found;
    }

    static void collectColorSwatches(
            final java.awt.Component component,
            final List<java.awt.Component> found,
            final boolean insideInspector,
            final int depth) {
        collectColorSwatches(component, found, insideInspector, null, depth);
    }

    static void collectColorSwatches(
            final java.awt.Component component,
            final List<java.awt.Component> found,
            final boolean insideInspector,
            final java.awt.Container exclude,
            final int depth) {
        if (depth > CANVAS_SCAN_DEPTH + 4 || !component.isVisible()) return;
        if (exclude != null && component == exclude) return;
        final boolean inInspector =
                insideInspector || component.getClass().getName().contains(".palette.inspector");
        if (inInspector
                && component.isShowing()
                && component.isEnabled()
                && component.getHeight() <= 40
                && component.getHeight() >= 8
                && component.getWidth() <= 120
                && component.getWidth() >= 8
                // Dock tab buttons are not swatches — pressing one just switches palettes.
                && !component.getClass().getName().equals("com.live2d.ui.swingImpl.K")
                && (component instanceof javax.swing.AbstractButton && !(component instanceof javax.swing.JMenuItem)
                        || looksLikeSwatch(component))) {
            found.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectColorSwatches(child, found, inInspector, exclude, depth + 1);
            }
        }
    }

    /**
     * Whether a component plausibly renders a colour swatch: a leaf whose background is a
     * saturated, non-grey, non-default colour.
     */
    static boolean looksLikeSwatch(final java.awt.Component component) {
        if (component instanceof java.awt.Container container && container.getComponentCount() > 0) return false;
        final java.awt.Color bg = component.getBackground();
        if (bg == null) return false;
        final int max = Math.max(bg.getRed(), Math.max(bg.getGreen(), bg.getBlue()));
        final int min = Math.min(bg.getRed(), Math.min(bg.getGreen(), bg.getBlue()));
        return max - min >= 48;
    }

    /**
     * The inspector palette's content panel — the largest showing {@code u} container in the
     * selected inspector tab's dock column. The inspector paints its property rows onto this
     * single component, so it is the click surface for row scanning. Runs on the EDT.
     */
    /**
     * The dock column that hosts the inspector palette — the narrow container holding
     * every docked palette on that edge. Null unless the inspector tab is selected.
     */
    static java.awt.Container inspectorDockScope() {
        return dockScopeFor(INSPECTOR_MARKERS);
    }

    /**
     * The dock column hosting the palette whose tab matches the markers — the narrow
     * container holding every docked palette on that edge. Null unless the tab is
     * selected.
     */
    static java.awt.Container dockScopeFor(final String[] markers) {
        final javax.swing.AbstractButton tab = selectedPaletteTab(markers);
        if (tab == null) return null;
        java.awt.Container scope = tab.getParent();
        while (scope != null && scope.getParent() != null && scope.getParent().getWidth() <= 200) {
            scope = scope.getParent();
        }
        return scope;
    }

    /** The selected dock tab carrying one of {@code markers}, or null. Runs on the EDT. */
    static javax.swing.AbstractButton selectedPaletteTab(final String[] markers) {
        for (final javax.swing.AbstractButton tab : paletteTabs(markers, new StringBuilder(1))) {
            if (tab.isSelected()) return tab;
        }
        return null;
    }

    /**
     * The editable text component inside a CWidget's Swing mirror — e.g. a
     * CSlidableFloat's embedded editor once the row enters edit mode. Null when the
     * mirror hosts none. Runs on the EDT.
     */
    static javax.swing.text.JTextComponent cwidgetEditor(final Object widget) {
        try {
            final Object mirror = widget.getClass().getMethod("getJComponent").invoke(widget);
            if (!(mirror instanceof java.awt.Component component)) return null;
            final javax.swing.text.JTextComponent[] hit = {null};
            collectEditableText(component, hit, 0);
            return hit[0];
        } catch (final Exception e) {
            return null;
        }
    }

    static void collectEditableText(
            final java.awt.Component component, final javax.swing.text.JTextComponent[] hit, final int depth) {
        if (hit[0] != null || depth > 8 || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
                && text.isShowing()
                && text.isEnabled()
                && text.isEditable()) {
            hit[0] = text;
            return;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectEditableText(child, hit, depth + 1);
            }
        }
    }

    /**
     * Finds the custom-widget content belonging to the selected inspector tab.
     *
     * <p>The dock column can contain two stacked panes: the upper tool-details pane and the
     * lower inspector pane. The Swing mirror exposes the whole column as one {@code u}, and the
     * first {@code CBorderPane} in its widget tree is therefore not a reliable content root. We
     * keep the selected tab's actual screen rectangle, enumerate a bounded widget tree, and only
     * accept visible candidates wholly inside the dock and below that tab. A visible-content score
     * makes the chosen candidate the panel rather than one of its small child controls.</p>
     */
    static Object inspectorContentWidget() {
        final java.awt.Container scope = inspectorDockScope();
        final javax.swing.AbstractButton tab = selectedPaletteTab(INSPECTOR_MARKERS);
        if (scope == null || tab == null) return null;
        final java.awt.Rectangle dockRect = screenBounds(scope);
        final Object tabWidget = cwidgetOf(tab);
        final java.awt.Rectangle tabRect = tabWidget == null ? screenBounds(tab) : cwidgetRect(tabWidget);
        final Object root = cwidgetTop(tabWidget);
        if (dockRect == null || tabRect == null || root == null) return null;

        final List<InspectorContentCandidate> candidates = new ArrayList<>();
        for (final Object widget : cwidgetTree(root)) {
            final java.awt.Rectangle rect = cwidgetRect(widget);
            if (rect == null || rect.width < 100 || rect.height < 120) continue;
            final int contentScore = visibleInspectorContentScore(widget, tabRect, dockRect);
            if (contentScore > 0) {
                candidates.add(new InspectorContentCandidate(widget, rect, cwidgetShowing(widget), contentScore));
            }
        }
        final InspectorContentCandidate selected = selectInspectorContentCandidate(candidates, tabRect, dockRect);
        return selected == null ? null : selected.widget();
    }

    /**
     * The smallest seam for the inspector-root rule. It deliberately uses relative widget
     * geometry, selected-tab ownership and visible content rather than a fixed screen coordinate.
     */
    static InspectorContentCandidate selectInspectorContentCandidate(
            final List<InspectorContentCandidate> candidates,
            final java.awt.Rectangle selectedTabRect,
            final java.awt.Rectangle dockRect) {
        if (selectedTabRect == null || dockRect == null) return null;
        final int tabBottom = selectedTabRect.y + selectedTabRect.height;
        InspectorContentCandidate best = null;
        for (final InspectorContentCandidate candidate : candidates) {
            final java.awt.Rectangle rect = candidate.rect();
            if (candidate.widget() == null
                    || !candidate.showing()
                    || rect == null
                    || rect.isEmpty()
                    || candidate.contentScore() <= 0
                    || !dockRect.contains(rect)
                    || rect.y < tabBottom) {
                continue;
            }
            if (best == null || candidate.contentScore() > best.contentScore()) {
                best = candidate;
            }
        }
        return best;
    }

    static int visibleInspectorContentScore(
            final Object widget, final java.awt.Rectangle selectedTabRect, final java.awt.Rectangle dockRect) {
        final int tabBottom = selectedTabRect.y + selectedTabRect.height;
        int score = 0;
        for (final Object child : cwidgetTree(widget)) {
            final java.awt.Rectangle rect = cwidgetRect(child);
            if (rect == null || !cwidgetShowing(child) || !dockRect.contains(rect) || rect.y < tabBottom) continue;
            final String name = child.getClass().getName();
            score += name.endsWith("CColorChooserButton") || name.endsWith("CTextField") ? 4 : 1;
        }
        return score;
    }

    /** The selected inspector content's real screen rectangle, or null. Runs on the EDT. */
    static java.awt.Rectangle inspectorContentBounds() {
        final Object widget = inspectorContentWidget();
        final java.awt.Rectangle rect = widget == null ? null : cwidgetRect(widget);
        return rect == null ? null : new java.awt.Rectangle(rect);
    }

    /** Candidate root from a selected dock's bounded CWidget traversal. */
    record InspectorContentCandidate(Object widget, java.awt.Rectangle rect, boolean showing, int contentScore) {}

    /**
     * The custom {@code CWidget} behind a Swing mirror component, resolved through the
     * host's {@code com.live2d.ui.k} registry. Null when the component is not a mirror.
     */
    static Object cwidgetOf(final java.awt.Component component) {
        if (component == null) return null;
        try {
            final Class<?> registry =
                    Class.forName("com.live2d.ui.k", true, component.getClass().getClassLoader());
            return registry.getMethod("a", java.awt.Component.class).invoke(null, component);
        } catch (final Exception e) {
            return null;
        }
    }

    /** The topmost CWidget ancestor of a widget, or the widget itself. */
    static Object cwidgetTop(final Object widget) {
        Object cursor = widget;
        while (cursor != null) {
            try {
                final Object parent = cursor.getClass().getMethod("getParent").invoke(cursor);
                if (parent == null) return cursor;
                cursor = parent;
            } catch (final Exception e) {
                return cursor;
            }
        }
        return cursor;
    }

    /** Every CWidget under the root, depth-first. Runs on the EDT. */
    static List<Object> cwidgetTree(final Object root) {
        final List<Object> found = new ArrayList<>();
        if (root == null) return found;
        for (final boolean direction : new boolean[] {true, false}) {
            try {
                final java.util.Iterator<?> it = (java.util.Iterator<?>)
                        root.getClass().getMethod("traverse", boolean.class).invoke(root, direction);
                while (it.hasNext()) {
                    found.add(it.next());
                }
            } catch (final Exception ignored) {
            }
            if (!found.isEmpty()) break;
        }
        return found;
    }

    static java.awt.Rectangle cwidgetRect(final Object widget) {
        try {
            final Object rect = widget.getClass().getMethod("getRectOnScreen").invoke(widget);
            return (java.awt.Rectangle) rect.getClass().getMethod("getJrect").invoke(rect);
        } catch (final Exception e) {
            return null;
        }
    }

    static boolean cwidgetShowing(final Object widget) {
        try {
            return Boolean.TRUE.equals(widget.getClass().getMethod("isShowing").invoke(widget));
        } catch (final Exception e) {
            return false;
        }
    }

    static javax.swing.text.JTextComponent cwidgetTextComponent(final Object widget) {
        try {
            final Object text = widget.getClass().getMethod("getJTextComponent").invoke(widget);
            return text instanceof javax.swing.text.JTextComponent tc
                            && tc.isShowing()
                            && tc.isEnabled()
                            && tc.isEditable()
                    ? tc
                    : null;
        } catch (final Exception e) {
            return null;
        }
    }

    static String cwidgetText(final Object widget) {
        for (final String method : new String[] {"getText", "getName", "getToolTipText"}) {
            try {
                final Object value = widget.getClass().getMethod(method).invoke(widget);
                if (value instanceof String s && !s.isBlank()) return s;
            } catch (final Exception ignored) {
            }
        }
        return "";
    }

    /**
     * Whether a widget sits in an inspector row carrying the multiply-colour label —
     * climbs the row containers it belongs to looking for a sibling text widget with
     * the marker text.
     */
    static boolean multiplyMarked(final Object widget) {
        Object cursor = widget;
        for (int up = 0; cursor != null && up < 5; up++) {
            try {
                final Object parent = cursor.getClass().getMethod("getParent").invoke(cursor);
                if (parent == null) return false;
                final Object children =
                        parent.getClass().getMethod("getChildren").invoke(parent);
                if (children instanceof List<?> siblings) {
                    for (final Object sibling : siblings) {
                        if (cwidgetText(sibling).matches(".*(正片叠底|乗算|乘算|[Mm]ultiply).*")) return true;
                    }
                }
                cursor = parent;
            } catch (final Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * A click-surface pseudo-component over the CWidget's on-screen rect so the Robot
     * can press controls that have no visible Swing mirror of their own.
     */
    static java.awt.Component cwidgetSurface(final Object widget) {
        if (!cwidgetShowing(widget)) return null;
        final java.awt.Rectangle rect = cwidgetRect(widget);
        if (rect == null || rect.isEmpty()) return null;
        return new java.awt.Component() {
            @Override
            public boolean isShowing() {
                return true;
            }

            @Override
            public java.awt.Point getLocationOnScreen() {
                return rect.getLocation();
            }

            @Override
            public int getWidth() {
                return rect.width;
            }

            @Override
            public int getHeight() {
                return rect.height;
            }
        };
    }

    /** A compact census of a CWidget subtree for diagnostics. */
    static String cwidgetCensus(final List<Object> widgets) {
        return cwidgetCensus(widgets, 560);
    }

    static String cwidgetCensus(final List<Object> widgets, final int cap) {
        final StringBuilder out = new StringBuilder(cap + 64);
        for (final Object widget : widgets) {
            if (out.length() > cap) {
                out.append("...");
                break;
            }
            out.append(widget.getClass().getSimpleName());
            final java.awt.Rectangle rect = cwidgetRect(widget);
            if (rect != null) {
                out.append('@')
                        .append(rect.x)
                        .append(',')
                        .append(rect.y)
                        .append(' ')
                        .append(rect.width)
                        .append('x')
                        .append(rect.height);
            }
            if (!cwidgetShowing(widget)) out.append(":hidden");
            final String text = cwidgetText(widget);
            if (!text.isBlank()) out.append('[').append(text).append(']');
            out.append(';');
        }
        return out.toString();
    }

    /**
     * Scans a screen rectangle for saturated colour blocks — the centres of small regions
     * whose pixels stay vividly coloured are the inspector's painted colour swatches.
     */
    static List<int[]> saturatedBlocks(final java.awt.Robot robot, final int[] bounds) {
        final List<int[]> points = new ArrayList<>();
        int lastY = -100;
        for (int y = bounds[1] + 6; y < bounds[1] + bounds[3] - 6; y += 4) {
            int runStart = -1;
            int runEnd = -1;
            for (int x = bounds[0] + 6; x < bounds[0] + bounds[2] - 6; x += 3) {
                final java.awt.Color pixel = robot.getPixelColor(x, y);
                final int max = Math.max(pixel.getRed(), Math.max(pixel.getGreen(), pixel.getBlue()));
                final int min = Math.min(pixel.getRed(), Math.min(pixel.getGreen(), pixel.getBlue()));
                if (max - min >= 64 && max >= 90) {
                    if (runStart < 0) runStart = x;
                    runEnd = x;
                } else if (runStart >= 0) {
                    if (runEnd - runStart >= 8 && runEnd - runStart <= 60 && y - lastY > 18) {
                        points.add(new int[] {(runStart + runEnd) / 2, y});
                        lastY = y;
                    }
                    runStart = -1;
                }
            }
            if (runStart >= 0 && runEnd - runStart >= 8 && runEnd - runStart <= 60 && y - lastY > 18) {
                points.add(new int[] {(runStart + runEnd) / 2, y});
                lastY = y;
            }
        }
        return points;
    }

    /**
     * A census of leaf components inside the selected inspector tab's dock region — class,
     * bounds and background — so a missed swatch shows what the palette actually contains.
     * Runs on the EDT.
     */
    static String inspectorLeafCensus() {
        final javax.swing.AbstractButton tab = selectedPaletteTab(INSPECTOR_MARKERS);
        if (tab == null) return "no-tab";
        java.awt.Container scope = tab.getParent();
        while (scope != null && scope.getParent() != null && scope.getParent().getWidth() <= 200) {
            scope = scope.getParent();
        }
        if (scope == null) return "no-scope";
        final StringBuilder out = new StringBuilder(1200);
        collectLeafCensus(scope, out, null, 0);
        return out.length() > 0 ? out.toString() : "empty";
    }

    static void collectLeafCensus(
            final java.awt.Component component,
            final StringBuilder out,
            final java.awt.Container exclude,
            final int depth) {
        if (depth > CANVAS_SCAN_DEPTH + 4 || !component.isVisible() || out.length() > 1100) return;
        if (exclude != null && component == exclude) return;
        if (!(component instanceof java.awt.Container container) || container.getComponentCount() == 0) {
            final java.awt.Color bg = component.getBackground();
            out.append(component.getClass().getSimpleName())
                    .append('[')
                    .append(component.getWidth())
                    .append('x')
                    .append(component.getHeight())
                    .append(']');
            if (bg != null) {
                out.append('#').append(Integer.toHexString(bg.getRGB() & 0xFFFFFF));
            }
            out.append(';');
            return;
        }
        for (java.awt.Component child : container.getComponents()) {
            collectLeafCensus(child, out, exclude, depth + 1);
        }
    }

    /** Every showing editable text component inside one window. */
    static void collectEditableFields(
            final java.awt.Component component, final List<javax.swing.text.JTextComponent> found, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
                && text.isShowing()
                && text.isEnabled()
                && text.isEditable()) {
            found.add(text);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectEditableFields(child, found, depth + 1);
            }
        }
    }

    /**
     * A bounded report of why every plausible canvas leaf was rejected.
     *
     * <p>Only consulted when discovery came back empty: it walks the same windows and records
     * each leaf that was large enough to matter along with the check that excluded it, so an
     * empty candidate list explains itself in the artifact.</p>
     */
    static String canvasRejects() {
        final StringBuilder out = new StringBuilder(4096);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            out.append("W:")
                    .append(window.getClass().getSimpleName())
                    .append(":showing=")
                    .append(window.isShowing())
                    .append(';');
            collectRejects(window, out, 0);
        }
        return out.length() > 0 ? out.substring(0, Math.min(out.length(), 4000)) : "no-windows";
    }

    static void collectRejects(final java.awt.Component component, final StringBuilder out, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible() || out.length() > 3800) return;
        boolean visibleLeaf = true;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (child.isShowing()) {
                    visibleLeaf = false;
                    break;
                }
            }
        }
        if (component.getWidth() >= MIN_CANVAS_EDGE && component.getHeight() >= MIN_CANVAS_EDGE) {
            final String reason = !component.isShowing()
                    ? "not-showing"
                    : isExcludedControl(component) ? "control" : !visibleLeaf ? "container" : "eligible";
            out.append(component.getClass().getName())
                    .append('[')
                    .append(component.getWidth())
                    .append('x')
                    .append(component.getHeight())
                    .append(']')
                    .append('@')
                    .append(depth)
                    .append('=')
                    .append(reason)
                    .append(';');
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectRejects(child, out, depth + 1);
            }
        }
    }

    /**
     * A bounded dump of the visible component tree, written once per automated run.
     *
     * <p>The map exists for actor review: it records which windows, trees, tables, buttons and
     * sliders were actually present, so a future step can be targeted at a real control instead
     * of a guessed one. Class names and bounds only — no text longer than the existing label
     * bound, and nothing a label-only evidence rule would not already carry.</p>
     */
    static String uiMap() throws Exception {
        return onEdt(() -> {
            final StringBuilder out = new StringBuilder(UI_MAP_MAX_CHARS);
            final int[] seen = {0};
            int windows = 0;
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (!window.isVisible()) continue;
                if (windows >= UI_MAP_MAX_WINDOWS || seen[0] >= UI_MAP_MAX_COMPONENTS) break;
                windows++;
                out.append("W:")
                        .append(window.getClass().getSimpleName())
                        .append('(')
                        .append(WindowsHistoryManagerValidationProbe.boundedLabel(window.getName()))
                        .append(')')
                        .append(boundsOf(window))
                        .append('\n');
                mapComponent(window, 0, out, seen);
            }
            return out.toString();
        });
    }

    static void mapComponent(
            final java.awt.Component component, final int depth, final StringBuilder out, final int[] seen) {
        if (seen[0] >= UI_MAP_MAX_COMPONENTS || depth > UI_MAP_MAX_DEPTH || out.length() >= UI_MAP_MAX_CHARS - 256) {
            return;
        }
        seen[0]++;
        out.append("  ".repeat(Math.min(depth, 16)))
                .append(component.getClass().getName())
                .append(boundsOf(component));
        if (!component.isShowing()) out.append(":hidden");
        if (component instanceof javax.swing.JTree tree) {
            out.append(":rows=").append(tree.getRowCount());
        } else if (component instanceof javax.swing.JTable table) {
            out.append(":rows=").append(table.getRowCount()).append("x").append(table.getColumnCount());
        } else if (component instanceof javax.swing.AbstractButton button) {
            out.append(":text=").append(WindowsHistoryManagerValidationProbe.boundedLabel(button.getText()));
        } else if (component instanceof javax.swing.JSlider slider) {
            out.append(":range=")
                    .append(slider.getMinimum())
                    .append('-')
                    .append(slider.getMaximum())
                    .append('=')
                    .append(slider.getValue());
        } else if (component instanceof javax.swing.JComboBox<?> combo) {
            out.append(":items=").append(combo.getItemCount());
        }
        out.append('\n');
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                mapComponent(child, depth + 1, out, seen);
            }
        }
    }
}
