package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.TempExportTracker.addSuppressed;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native Swing row discovery, popup capture, and synthetic click dispatch for the host UI.
 *
 * <p>The probe drives the same widgets an operator clicks, so every dispatch records the widget
 * identity, the popup it opened, and the diagnostic text of the attempt. Failures carry that
 * evidence instead of a bare boolean.</p>
 */
final class GuiRowDispatch {
    static void runOnEdt(final Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        SwingUtilities.invokeAndWait(action);
    }

    static void recordGuiTargetState(final Properties result, final String phase,
        final GuiTargetState state) {
        final String prefix = "gui." + phase + ".";
        result.setProperty(prefix + "observed", Boolean.toString(state.observed()));
        result.setProperty(prefix + "documentId", state.documentId());
        result.setProperty(prefix + "modelId", state.modelId());
        result.setProperty(prefix + "binding", state.binding());
        result.setProperty(prefix + "generation", Long.toString(state.generation()));
        result.setProperty(prefix + "modelImageId", state.modelImageId());
        result.setProperty(prefix + "artMeshId", state.artMeshId());
        result.setProperty(prefix + "raw", state.raw());
        result.setProperty(prefix + "rawReplaced", Boolean.toString(state.rawReplaced()));
        result.remove(prefix + "diagnostic");
        if (!state.diagnostic().isBlank()) {
            result.setProperty(prefix + "diagnostic", state.diagnostic());
        }
    }

    static boolean acceptsAutoImport(final GuiTargetState before,
        final GuiTargetState after, final String expectedRaw) {
        // Kept as a named negative seam for old result consumers. The wrapper's replaced flag is
        // not native application evidence, even when the raw ID happens to remain unchanged.
        return false;
    }

    /**
     * Accepts GUI application only when the stable target anchor survives and a fresh native
     * export of the observed current raw has the exact RGB fingerprint written to the issued
     * session file. The wrapper's replaced bit is intentionally not part of this proof.
     */
    static boolean acceptsNativeGuiApplication(final GuiTargetState before,
        final GuiTargetState after, final TargetResolution.TargetIdentity expectedStable,
        final PsdValidationContent.Fingerprint written,
        final PsdValidationContent.Fingerprint freshCurrent) {
        return before != null && after != null && before.observed() && after.observed()
            && !before.rawReplaced()
            && TargetResolution.targetIdentityMatches(expectedStable, before.identity())
            && TargetResolution.stableTargetIdentityMatches(expectedStable, after.identity())
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(after.raw())
            && written != null && freshCurrent != null && written.equals(freshCurrent);
    }

    static boolean sameGuiTarget(final GuiTargetState before,
        final GuiTargetState after, final TargetResolution.TargetIdentity expectedStable) {
        return before.observed() && after.observed()
            && TargetResolution.stableTargetIdentityMatches(expectedStable, before.identity())
            && TargetResolution.stableTargetIdentityMatches(expectedStable, after.identity())
            && !RawExportCoordination.UNAVAILABLE_VALUE.equals(after.raw());
    }

    /**
     * Offline-only generic resolver seams retained for regression tests. {@link #runGui} never
     * calls these widgets or their label/value keys; the live GUI path is exact-table-only.
     */
    sealed interface RowWidget {
        String name();
        List<CapturedRow> captureRows() throws Exception;
        RowAttempt rightClick(CapturedRow row) throws Exception;
    }

    record TreeWidget(JTree tree) implements RowWidget {
        public String name() { return tree.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                tree, RowKind.TREE)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    record TableWidget(javax.swing.JTable table) implements RowWidget {
        public String name() { return table.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                table, RowKind.TABLE)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    record ListWidget(javax.swing.JList<?> list) implements RowWidget {
        public String name() { return list.getClass().getName(); }
        public List<CapturedRow> captureRows() throws Exception {
            final AtomicReference<List<CapturedRow>> rows = new AtomicReference<>(List.of());
            SwingUtilities.invokeAndWait(() -> rows.set(captureRowsOnEdt(
                list, RowKind.LIST)));
            return rows.get();
        }
        public RowAttempt rightClick(final CapturedRow row) throws Exception {
            return rightClickRow(row);
        }
    }

    static final int MAX_ROW_DISPATCH_ATTEMPTS = 3;

    @FunctionalInterface
    interface RowDispatchAttempt {
        boolean retry(int attemptNumber) throws Exception;
    }

    /** Runs the same bounded retry policy used by the live row dispatcher. */
    static int runBoundedRowDispatches(final RowDispatchAttempt operation) throws Exception {
        if (operation == null) throw new IllegalArgumentException("row dispatch operation is required");
        int attempts = 0;
        while (attempts < MAX_ROW_DISPATCH_ATTEMPTS) {
            attempts++;
            if (!operation.retry(attempts)) break;
        }
        return attempts;
    }

    static RowAttempt rightClickRow(final CapturedRow captured) throws Exception {
        return rowAttempt(dismissPopup(), captured);
    }

    /**
     * Runs one captured row through a bounded stale-widget recovery loop. Every invocation of
     * {@link #dispatchCapturedRow(CapturedRow, boolean)} is made on the EDT; returning to the
     * worker between retries gives a replacement table a chance to become visible.
     */
    static RowAttempt rowAttempt(PopupAttempt attempt, final CapturedRow captured)
        throws Exception {
        final AtomicReference<RowDispatchResult> rowOutcome = new AtomicReference<>();
        final AtomicReference<RightClickDispatchException> dispatchFailure = new AtomicReference<>();
        final AtomicReference<PopupAttempt> popupAttempt = new AtomicReference<>(attempt);
        final List<String> retryReasons = new ArrayList<>();
        final int attempts = runBoundedRowDispatches(attemptNumber -> {
            rowOutcome.set(null);
            try {
                final boolean triggerOnPress = popupTriggerOnPress(
                    System.getProperty("os.name", ""));
                SwingUtilities.invokeAndWait(() -> rowOutcome.set(
                    dispatchCapturedRow(captured, triggerOnPress)));
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof RightClickDispatchException failure) {
                    dispatchFailure.set(failure);
                    return false;
                }
                throw wrapped;
            }

            final RowDispatchResult result = rowOutcome.get();
            if (result == null || !result.retryable()) return false;
            retryReasons.add("attempt=" + attemptNumber + " phase=" + result.phase()
                + " reason=" + result.reason());
            if (attemptNumber < MAX_ROW_DISPATCH_ATTEMPTS) {
                final PopupAttempt retryPopup = dismissPopup();
                popupAttempt.set(mergePopupAttempts(popupAttempt.get(), retryPopup));
            }
            return true;
        });
        final PopupCapture popup = awaitPopup(popupAttempt.get());
        final RowDispatchResult rowResult = rowOutcome.get();
        final RightClickDispatchException failure = dispatchFailure.get();
        final String dispatchDiagnostic = rowResult != null
            ? rowResult.dispatch().diagnostic()
            : failure != null
                ? failure.dispatch().diagnostic()
                : "not-dispatched";
        final String failureTrace = failure == null ? "" : stackTrace(failure);
        final String diagnostic = "capture=" + captured.diagnostic()
            + " attempts=" + attempts
            + " dispatchRow=" + (rowResult == null ? -1 : rowResult.row())
            + " dispatchComponent=" + (rowResult == null ? "" : rowResult.component())
            + " retryLimit=" + MAX_ROW_DISPATCH_ATTEMPTS
            + " retryReasons=" + retryReasons
            + " " + dispatchDiagnostic
            + (failureTrace.isBlank() ? "" : " dispatchExceptionTrace=" + failureTrace)
            + " popupCount=" + popup.popupCount()
            + " popupMaxCount=" + popup.maxPopupCount()
            + " " + popup.diagnostic();
        return new RowAttempt(popup.popup(), popup.popupCount(), diagnostic, failureTrace,
            rowResult == null ? -1 : rowResult.row(),
            rowResult == null ? "" : rowResult.component(), false);
    }

    static RowDispatchResult dispatchCapturedRow(final CapturedRow captured,
        final boolean triggerOnPress) {
        RowResolution selection = resolveActiveRow(captured);
        if (!selection.available()) {
            return RowDispatchResult.retry(captured, "before-left", selection.reason());
        }

        // Preserve the old tree behavior, but re-resolve because expansion itself can rebuild the
        // host widget before the selection click.
        if (selection.row().component() instanceof JTree tree) {
            tree.expandRow(selection.row().row());
            selection = resolveActiveRow(captured);
            if (!selection.available()) {
                return RowDispatchResult.retry(captured, "before-left", selection.reason());
            }
        }

        final AtomicReference<RowResolution> afterSelection = new AtomicReference<>();
        final CurrentRow selected = selection.row();
        final String prefix = rowDispatchPrefix(captured, selected);
        try {
            final RightClickDispatch dispatch = dispatchRightClickAfterSelection(
                selected.component(), centerX(selected.bounds()), centerY(selected.bounds()),
                triggerOnPress, prefix, () -> {
                    final RowResolution resolved = resolveActiveRow(captured);
                    afterSelection.set(resolved);
                    if (!resolved.available()) return null;
                    final CurrentRow row = resolved.row();
                    return new DispatchTarget(row.component(), centerX(row.bounds()),
                        centerY(row.bounds()));
                });
            final CurrentRow dispatched = afterSelection.get().row();
            return RowDispatchResult.success(dispatch, dispatched);
        } catch (RowRelocationException retry) {
            final RowResolution resolved = afterSelection.get();
            final String reason = resolved == null
                ? retry.getMessage() : resolved.reason();
            return RowDispatchResult.retry(captured, "after-left", reason);
        }
    }

    static String rowDispatchPrefix(final CapturedRow captured,
        final CurrentRow selection) {
        return "captureWidget=" + captured.widgetIdentity()
            + " captureRow=" + captured.row()
            + " captureWindow=" + captured.windowIdentity()
            + " captureRowKey=" + captured.rowKeyDiagnostic()
            + " captureBounds=" + captured.bounds()
            + " selectionWidget=" + componentIdentity(selection.component())
            + " selectionState=" + componentState(selection.component())
            + " selectionRow=" + selection.row()
            + " selectionBounds=" + boundsMarker(selection.bounds()) + " ";
    }

    static int centerX(final Rectangle bounds) {
        return bounds.x + bounds.width / 2;
    }

    static int centerY(final Rectangle bounds) {
        return bounds.y + bounds.height / 2;
    }

    static void dispatchLeftClick(final Component target, final int x, final int y) {
        final long now = System.currentTimeMillis();
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now,
            InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
    }

    static RightClickDispatch dispatchRightClick(final Component target, final int x, final int y) {
        return dispatchRightClick(target, x, y,
            popupTriggerOnPress(System.getProperty("os.name", "")));
    }

    static RightClickDispatch dispatchRightClick(final Component target, final int x, final int y,
        final boolean triggerOnPress) {
        dispatchLeftClick(target, x, y);
        return dispatchRightClickEvents(target, x, y, triggerOnPress, "");
    }

    /**
     * Dispatches the right-click after the selection click has been completed. The caller supplies
     * a fresh target, so a host table replacement between the two phases cannot receive the right
     * click merely because it was the object captured before selection.
     */
    static RightClickDispatch dispatchRightClickAfterSelection(final Component selectionTarget,
        final int selectionX, final int selectionY, final boolean triggerOnPress,
        final java.util.function.Supplier<DispatchTarget> refreshedTarget) {
        return dispatchRightClickAfterSelection(selectionTarget, selectionX, selectionY,
            triggerOnPress, "", refreshedTarget);
    }

    static RightClickDispatch dispatchRightClickAfterSelection(
        final Component selectionTarget, final int selectionX, final int selectionY,
        final boolean triggerOnPress, final String diagnosticPrefix,
        final java.util.function.Supplier<DispatchTarget> refreshedTarget) {
        try {
            dispatchLeftClick(selectionTarget, selectionX, selectionY);
        } catch (RuntimeException failure) {
            final RightClickDispatch dispatch = new RightClickDispatch(diagnosticPrefix
                + "selectionTarget=" + componentState(selectionTarget)
                + " selectionCoordinates=(" + selectionX + ',' + selectionY + ')');
            throw new RightClickDispatchException(dispatch,
                List.of(new DispatchFailure("MOUSE_LEFT", failure)));
        }
        final DispatchTarget target = refreshedTarget.get();
        if (target == null || target.component() == null) {
            throw new RowRelocationException(
                "active row could not be revalidated after the selection click");
        }
        return dispatchRightClickEvents(target.component(), target.x(), target.y(),
            triggerOnPress, diagnosticPrefix
                + " dispatchWidget=" + componentIdentity(target.component())
                + " dispatchState=" + componentState(target.component())
                + " dispatchCoordinates=(" + target.x() + ',' + target.y() + ") "
                + (target.diagnostic().isBlank() ? "" : target.diagnostic() + " "));
    }

    static RightClickDispatch dispatchRightClickEvents(final Component target,
        final int x, final int y, final boolean triggerOnPress, final String diagnosticPrefix) {
        final long now = System.currentTimeMillis();
        final MouseEvent pressed = new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, triggerOnPress, MouseEvent.BUTTON3);
        final MouseEvent released = new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON3_DOWN_MASK, x, y, 1, !triggerOnPress, MouseEvent.BUTTON3);
        final List<DispatchFailure> failures = new ArrayList<>();
        try {
            target.dispatchEvent(pressed);
        } catch (RuntimeException failure) {
            failures.add(new DispatchFailure("MOUSE_PRESSED", failure));
        }
        try {
            target.dispatchEvent(released);
        } catch (RuntimeException failure) {
            failures.add(new DispatchFailure("MOUSE_RELEASED", failure));
        }
        final RightClickDispatch dispatch = new RightClickDispatch(diagnosticPrefix
            + "synthetic=" + mouseEventMarker(pressed) + "," + mouseEventMarker(released)
                + " triggerPhase=" + (triggerOnPress ? "MOUSE_PRESSED" : "MOUSE_RELEASED")
                + " target=" + componentState(target)
                + " renderer=" + rendererState(target, x, y)
                + " pointer=" + pointerState());
        if (!failures.isEmpty()) throw new RightClickDispatchException(dispatch, failures);
        return dispatch;
    }

    static boolean popupTriggerOnPress(final String osName) {
        return !(osName == null ? "" : osName).toLowerCase(Locale.ROOT).contains("win");
    }

    static PopupCapture awaitPopup(final PopupAttempt attempt) throws Exception {
        // The host may build and show the popup asynchronously after the event returns.
        final List<String> polls = new ArrayList<>();
        PopupSnapshot last = new PopupSnapshot(null, 0, "none", "[]");
        int maxPopupCount = 0;
        for (int poll = 0; poll < 20; poll++) {
            final AtomicReference<PopupSnapshot> snapshot = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                final List<JPopupMenu> visibleAfter = popupMenus(true);
                final JPopupMenu associated = popupForAttempt(
                    attempt.visibleBefore(), attempt.dismissed(), visibleAfter);
                snapshot.set(new PopupSnapshot(associated, visibleAfter.size(),
                    popupAssociation(attempt, associated), popupMarkers(visibleAfter)));
            });
            last = snapshot.get();
            maxPopupCount = Math.max(maxPopupCount, last.popupCount());
            polls.add("poll=" + poll + " popupCount=" + last.popupCount()
                + " association=" + last.association() + " menus=" + last.markers());
            if (last.popup() != null) {
                return new PopupCapture(last.popup(), last.popupCount(), maxPopupCount,
                    popupDiagnostic(polls));
            }
            Thread.sleep(150);
        }
        return new PopupCapture(null, last.popupCount(), maxPopupCount,
            popupDiagnostic(polls));
    }

    static String popupAssociation(final PopupAttempt attempt,
        final JPopupMenu popup) {
        if (popup == null) return "none";
        if (containsIdentity(attempt.dismissed(), popup)) return "reused-dismissed";
        if (containsIdentity(attempt.visibleBefore(), popup)) return "stale-visible-before";
        return "new";
    }

    static String popupDiagnostic(final List<String> polls) {
        if (polls.isEmpty()) return "popupPolls=0";
        return "popupPolls=" + polls.size() + " last=" + polls.get(polls.size() - 1)
            + " history=" + String.join(" || ", polls);
    }

    static String popupMarkers(final List<JPopupMenu> popups) {
        final List<String> markers = new ArrayList<>(popups.size());
        for (JPopupMenu popup : popups) markers.add(popupMarker(popup));
        return markers.toString();
    }

    /** Returns an identity and Swing visibility marker without reading host-private state. */
    static String popupMarker(final JPopupMenu popup) {
        if (popup == null) return "null";
        final StringBuilder marker = new StringBuilder(componentState(popup));
        marker.append(" invoker=").append(componentState(popup.getInvoker()));
        marker.append(" components=");
        appendMenuComponents(popup, marker, 0);
        return marker.toString();
    }

    static void appendMenuComponents(final Container container,
        final StringBuilder marker, final int depth) {
        marker.append('[');
        final Component[] components = container.getComponents();
        for (int index = 0; index < components.length; index++) {
            if (index > 0) marker.append(", ");
            final Component component = components[index];
            marker.append(index).append(':').append(menuComponentMarker(component));
            if (depth < 4 && component instanceof Container child) {
                marker.append(" children=");
                appendMenuComponents(child, marker, depth + 1);
            }
        }
        marker.append(']');
    }

    static String menuComponentMarker(final Component component) {
        final StringBuilder marker = new StringBuilder(componentState(component));
        if (component instanceof JMenuItem item) {
            marker.append(" menuItem=true text=").append(quoted(item.getText()))
                .append(" actionCommand=").append(quoted(item.getActionCommand()));
        }
        return marker.toString();
    }

    static String quoted(final String value) {
        if (value == null) return "<null>";
        return "'" + value.replace("\\", "\\\\")
            .replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    static String componentIdentity(final Component component) {
        if (component == null) return "null";
        return component.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(component));
    }

    static String componentState(final Component component) {
        if (component == null) return "null";
        final Container parent = component.getParent();
        return componentIdentity(component)
            + "{visible=" + component.isVisible()
            + ",showing=" + component.isShowing()
            + ",displayable=" + component.isDisplayable()
            + ",enabled=" + component.isEnabled()
            + ",parent=" + componentIdentity(parent)
            + ",locationOnScreen=" + locationOnScreen(component) + '}';
    }

    static String locationOnScreen(final Component component) {
        try {
            final Point point = component.getLocationOnScreen();
            return '(' + Integer.toString(point.x) + ',' + Integer.toString(point.y) + ')';
        } catch (RuntimeException failure) {
            return "<" + failure + ">";
        }
    }

    static String rendererState(final Component target, final int x, final int y) {
        if (!(target instanceof JTable table)) return "not-JTable";
        // Renderer preparation is host code and may mutate/rebuild a table. It is diagnostic
        // only, so never invoke it for a detached target; in the live path it is deliberately
        // evaluated after the right-click events have completed.
        if (!isLiveComponent(table)) {
            return "target-not-live prepared=<skipped renderer preparation>";
        }
        final Point point = new Point(x, y);
        final int row = table.rowAtPoint(point);
        final int column = table.columnAtPoint(point);
        if (row < 0 || column < 0) {
            return "cell=none row=" + row + " column=" + column;
        }
        try {
            final TableCellRenderer renderer = table.getCellRenderer(row, column);
            final Component prepared = table.prepareRenderer(renderer, row, column);
            return "cell=" + row + "," + column
                + " renderer=" + rendererIdentity(renderer)
                + " prepared=" + componentState(prepared);
        } catch (RuntimeException failure) {
            return "cell=" + row + "," + column
                + " rendererError=" + failure;
        }
    }

    static String rendererIdentity(final TableCellRenderer renderer) {
        if (renderer == null) return "null";
        if (renderer instanceof Component component) return componentIdentity(component);
        return renderer.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(renderer));
    }

    static String pointerState() {
        try {
            final PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null) return "null";
            final Point location = pointer.getLocation();
            return '(' + Integer.toString(location.x) + ',' + Integer.toString(location.y) + ')';
        } catch (RuntimeException failure) {
            return "<" + failure + ">";
        }
    }

    static String mouseEventMarker(final MouseEvent event) {
        return "{" + mouseEventName(event.getID())
            + ",local=(" + event.getX() + ',' + event.getY() + ')'
            + ",screen=(" + event.getXOnScreen() + ',' + event.getYOnScreen() + ')'
            + ",button=" + event.getButton()
            + ",popupTrigger=" + event.isPopupTrigger()
            + ",source=" + componentIdentity(event.getComponent()) + '}';
    }

    static String mouseEventName(final int id) {
        return switch (id) {
            case MouseEvent.MOUSE_PRESSED -> "MOUSE_PRESSED";
            case MouseEvent.MOUSE_RELEASED -> "MOUSE_RELEASED";
            case MouseEvent.MOUSE_CLICKED -> "MOUSE_CLICKED";
            default -> Integer.toString(id);
        };
    }

    static String stackTrace(final Throwable failure) {
        final StringWriter trace = new StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        return trace.toString();
    }

    record GuiClick(boolean clicked, String diagnostic) {
    }

    static List<RowWidget> visibleRowWidgets() throws Exception {
        final AtomicReference<List<RowWidget>> found = new AtomicReference<>(List.of());
        SwingUtilities.invokeAndWait(() -> {
            final List<RowWidget> widgets = new ArrayList<>();
            for (final Frame frame : Frame.getFrames()) {
                collectRowWidgets(frame, widgets);
            }
            found.set(widgets);
        });
        return found.get();
    }

    static void collectRowWidgets(final Container container,
        final List<RowWidget> widgets) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTree tree && isLiveComponent(tree)) {
                widgets.add(new TreeWidget(tree));
            } else if (component instanceof javax.swing.JList<?> list
                && isLiveComponent(list)) {
                widgets.add(new ListWidget(list));
            } else if (component instanceof javax.swing.JTable table
                && isLiveComponent(table)) {
                // CTreeTable hosts a JTree inside a JTable; the table receives the clicks.
                widgets.add(new TableWidget(table));
            }
            if (component instanceof Container child) {
                collectRowWidgets(child, widgets);
            }
        }
    }

    static boolean isLiveComponent(final Component component) {
        return component != null && component.isShowing() && component.isDisplayable()
            && component.getParent() != null;
    }

    /** Captures row keys and bounds without calling a renderer, and must run on the EDT. */
    static List<CapturedRow> captureRowsOnEdt(final Component component,
        final RowKind kind) {
        // The widget was discovered while showing, but may be detached by the time this
        // snapshot runs. Keep its row key/bounds for relocation diagnostics; live validation is
        // intentionally performed again by resolveActiveRow immediately before dispatch.
        if (component == null) return List.of();
        final int count = rowCount(component, kind);
        final List<CapturedRow> rows = new ArrayList<>(Math.max(0, count));
        for (int row = 0; row < count; row++) {
            final Rectangle bounds;
            try {
                bounds = rowBounds(component, kind, row);
            } catch (RuntimeException failure) {
                rows.add(capturedRow(component, kind, row, null, RowKey.unavailable()));
                continue;
            }
            final RowKey key = rowKey(component, kind, row);
            rows.add(capturedRow(component, kind, row, bounds, key));
        }
        return List.copyOf(rows);
    }

    static CapturedRow capturedRow(final Component component, final RowKind kind,
        final int row, final Rectangle bounds, final RowKey key) {
        return new CapturedRow(kind, component.getClass().getName(),
            componentIdentity(component), componentIdentity(
                SwingUtilities.getWindowAncestor(component)), row,
            key.value(), key.available(), boundsMarker(bounds));
    }

    static int rowCount(final Component component, final RowKind kind) {
        return switch (kind) {
            case TREE -> ((JTree) component).getRowCount();
            case TABLE -> ((JTable) component).getRowCount();
            case LIST -> ((javax.swing.JList<?>) component).getModel().getSize();
        };
    }

    static Rectangle rowBounds(final Component component, final RowKind kind,
        final int row) {
        return switch (kind) {
            case TREE -> ((JTree) component).getRowBounds(row);
            case TABLE -> {
                final JTable table = (JTable) component;
                yield table.getColumnCount() == 0 ? null : table.getCellRect(row, 0, true);
            }
            case LIST -> ((javax.swing.JList<?>) component).getCellBounds(row, row);
        };
    }

    static boolean validBounds(final Rectangle bounds) {
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    static RowKey rowKey(final Component component, final RowKind kind, final int row) {
        try {
            return switch (kind) {
                case TREE -> treeRowKey((JTree) component, row);
                case TABLE -> tableRowKey((JTable) component, row);
                case LIST -> listRowKey((javax.swing.JList<?>) component, row);
            };
        } catch (RuntimeException failure) {
            return RowKey.unavailable();
        }
    }

    /** Package-private seam for exercising the same row-key calculation as captureRowsOnEdt. */
    static RowKey rowKeyForTest(final Component component, final RowKind kind, final int row) {
        return rowKey(component, kind, row);
    }

    static RowKey treeRowKey(final JTree tree, final int row) {
        final javax.swing.tree.TreePath path = tree.getPathForRow(row);
        if (path == null) return RowKey.unavailable();
        final StringBuilder key = new StringBuilder("tree:");
        for (final Object value : path.getPath()) {
            final Optional<String> stable = stableRowValue(value);
            if (stable.isEmpty()) return RowKey.unavailable();
            key.append(stable.get());
        }
        return RowKey.of(key.toString());
    }

    static RowKey tableRowKey(final JTable table, final int row) {
        if (table.getColumnCount() == 0) return RowKey.unavailable();
        final StringBuilder key = new StringBuilder("table:");
        for (int column = 0; column < table.getColumnCount(); column++) {
            final Optional<String> stable = stableRowValue(table.getValueAt(row, column));
            if (stable.isEmpty()) return RowKey.unavailable();
            key.append(stable.get());
        }
        return RowKey.of(key.toString());
    }

    static RowKey listRowKey(final javax.swing.JList<?> list, final int row) {
        final Optional<String> stable = stableRowValue(list.getModel().getElementAt(row));
        return stable.isEmpty() ? RowKey.unavailable() : RowKey.of("list:" + stable.get());
    }

    /**
     * Returns a full, structured value token for the host's row values. The table/tree/list
     * models expose labels and other value objects through toString; identity-only text cannot
     * relocate a row across a host component replacement, so it is deliberately unavailable.
     */
    static Optional<String> stableRowValue(final Object value) {
        if (value == null) return Optional.of(stableToken("null", ""));
        final String type = value.getClass().getName();
        final String text;
        try {
            text = value.toString();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        if (text == null || looksLikeDefaultIdentityText(type, text)) {
            return Optional.empty();
        }
        return Optional.of(stableToken(type, text));
    }

    static String stableToken(final String type, final String value) {
        return lengthPrefix(type) + lengthPrefix(value);
    }

    static String lengthPrefix(final String value) {
        return value.length() + ":" + value;
    }

    static boolean looksLikeDefaultIdentityText(final String type, final String text) {
        final String prefix = type + '@';
        if (!text.startsWith(prefix) || text.length() == prefix.length()) return false;
        for (int index = prefix.length(); index < text.length(); index++) {
            final char character = text.charAt(index);
            final boolean hex = character >= '0' && character <= '9'
                || character >= 'a' && character <= 'f'
                || character >= 'A' && character <= 'F';
            if (!hex) return false;
        }
        return true;
    }

    static List<CurrentRow> currentRowsOnEdt() {
        final List<CurrentRow> rows = new ArrayList<>();
        for (final Frame frame : Frame.getFrames()) {
            if (frame.isShowing()) collectCurrentRows(frame, rows);
        }
        return rows;
    }

    static void collectCurrentRows(final Container container,
        final List<CurrentRow> rows) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTree tree && isLiveComponent(tree)) {
                addCurrentRows(tree, RowKind.TREE, rows);
            } else if (component instanceof JTable table && isLiveComponent(table)) {
                addCurrentRows(table, RowKind.TABLE, rows);
            } else if (component instanceof javax.swing.JList<?> list
                && isLiveComponent(list)) {
                addCurrentRows(list, RowKind.LIST, rows);
            }
            if (component instanceof Container child) collectCurrentRows(child, rows);
        }
    }

    static void addCurrentRows(final Component component, final RowKind kind,
        final List<CurrentRow> rows) {
        for (final CapturedRow row : captureRowsOnEdt(component, kind)) {
            final Rectangle bounds = rowBounds(component, kind, row.row());
            if (validBounds(bounds)) rows.add(new CurrentRow(row, component, bounds));
        }
    }

    /** Resolves a capture to one current live row; ambiguous or unkeyed replacement is rejected. */
    static RowResolution resolveActiveRow(final CapturedRow captured) {
        final List<CurrentRow> all = currentRowsOnEdt();
        return resolveActiveRow(captured, all);
    }

    /** Package-private seam for testing the production resolver with a deterministic live-row set. */
    static RowResolution resolveActiveRowForTest(final CapturedRow captured,
        final List<CurrentRow> currentRows) {
        return resolveActiveRow(captured, currentRows);
    }

    static RowResolution resolveActiveRow(final CapturedRow captured,
        final List<CurrentRow> allRows) {
        if (captured == null) return RowResolution.unavailable("captured row is unavailable");
        final List<CurrentRow> all = allRows == null ? List.of() : allRows;
        final List<CurrentRow> sameWidgetType = all.stream()
            .filter(row -> row.descriptor().kind() == captured.kind())
            .filter(row -> row.descriptor().widgetClass().equals(captured.widgetClass()))
            .toList();
        if (sameWidgetType.isEmpty()) {
            return RowResolution.unavailable("no live widget of captured type");
        }

        final List<CurrentRow> sameWindow = sameWidgetType.stream()
            .filter(row -> row.descriptor().windowIdentity().equals(captured.windowIdentity()))
            .toList();
        if (sameWindow.isEmpty()) {
            return RowResolution.unavailable("no live row in captured window");
        }
        final List<CurrentRow> candidates = sameWindow;
        if (!captured.rowKeyAvailable()) {
            return RowResolution.unavailable(
                "captured row has no stable key; row index reuse is refused");
        }

        final List<CurrentRow> keyed = candidates.stream()
            .filter(row -> row.descriptor().rowKeyAvailable())
            .filter(row -> row.descriptor().rowKey().equals(captured.rowKey()))
            .toList();
        final List<CurrentRow> sameIdentity = keyed.stream()
            .filter(row -> row.descriptor().widgetIdentity().equals(captured.widgetIdentity()))
            .filter(row -> row.row() == captured.row())
            .toList();
        if (sameIdentity.size() == 1) return RowResolution.available(sameIdentity.get(0));
        if (sameIdentity.size() > 1) {
            return RowResolution.unavailable("captured widget has ambiguous matching rows");
        }
        if (keyed.size() == 1) return RowResolution.available(keyed.get(0));
        if (keyed.isEmpty()) {
            return RowResolution.unavailable("no live row matches captured row key");
        }
        return RowResolution.unavailable("live row key matches are ambiguous: " + keyed.size());
    }

    static String boundsMarker(final Rectangle bounds) {
        return bounds == null ? "null"
            : "(" + bounds.x + ',' + bounds.y + ',' + bounds.width + ',' + bounds.height + ')';
    }

    /**
     * Component-hierarchy digest recorded when no row widget could be clicked, so a BLOCKED run
     * still identifies which widget the object list actually is. Lists every showing component
     * in every showing window, deduplicated with counts.
     */
    static String hierarchyDigest() {
        final AtomicReference<String> digest = new AtomicReference<>("");
        try {
            SwingUtilities.invokeAndWait(() -> {
                final StringBuilder text = new StringBuilder();
                final Map<String, Integer> counts = new LinkedHashMap<>();
                for (final Window window : Window.getWindows()) {
                    if (!window.isShowing()) continue;
                    text.append("window(").append(window.getClass().getSimpleName());
                    if (window instanceof Frame frame) text.append(":'").append(frame.getTitle()).append('\'');
                    if (window instanceof Dialog dialog) text.append(":'").append(dialog.getTitle()).append('\'');
                    text.append(") ");
                    collectWidgetNames(window, counts);
                }
                counts.forEach((name, count) -> {
                    if (text.length() < 3500) {
                        text.append('<').append(name).append('x').append(count).append("> ");
                    }
                });
                digest.set(text.toString());
            });
        } catch (Exception ignored) {
        }
        return digest.get();
    }

    static void collectWidgetNames(final Container container,
        final Map<String, Integer> counts) {
        for (final Component component : container.getComponents()) {
            if (component.isShowing()) {
                counts.merge(component.getClass().getName(), 1, Integer::sum);
            }
            if (component instanceof Container child) {
                collectWidgetNames(child, counts);
            }
        }
    }

    static List<JPopupMenu> popupMenus(final boolean visibleOnly) {
        final List<JPopupMenu> popups = new ArrayList<>();
        for (final MenuElement element : MenuSelectionManager.defaultManager().getSelectedPath()) {
            if (element instanceof JPopupMenu popup
                && (!visibleOnly || popup.isVisible())) {
                addPopup(popups, popup);
            }
        }
        for (final Window window : Window.getWindows()) {
            collectPopupMenus(window, popups, visibleOnly);
        }
        return popups;
    }

    static void collectPopupMenus(final Container container,
        final List<JPopupMenu> popups, final boolean visibleOnly) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JPopupMenu popup
                && (!visibleOnly || popup.isVisible())) {
                addPopup(popups, popup);
            }
            if (component instanceof Container child) {
                collectPopupMenus(child, popups, visibleOnly);
            }
        }
    }

    static void addPopup(final List<JPopupMenu> popups, final JPopupMenu candidate) {
        for (final JPopupMenu popup : popups) {
            if (popup == candidate) return;
        }
        popups.add(candidate);
    }

    static JPopupMenu popupForAttempt(final List<JPopupMenu> visibleBefore,
        final List<JPopupMenu> dismissed, final List<JPopupMenu> visibleAfter) {
        for (final JPopupMenu popup : visibleAfter) {
            if (!containsIdentity(visibleBefore, popup) || containsIdentity(dismissed, popup)) {
                return popup;
            }
        }
        return null;
    }

    static boolean containsIdentity(final List<JPopupMenu> popups,
        final JPopupMenu candidate) {
        for (final JPopupMenu popup : popups) {
            if (popup == candidate) return true;
        }
        return false;
    }

    static JMenuItem findItem(final JPopupMenu popup, final Set<String> labels,
        final Set<String> seen) {
        return findItem(popup, labels, seen, 0);
    }

    static JMenuItem findItem(final Container container, final Set<String> labels,
        final Set<String> seen, final int depth) {
        if (depth > 4) return null;
        for (final Component component : container.getComponents()) {
            if (component instanceof JMenuItem item) {
                if (seen.size() < 200) seen.add(item.getText());
                if (labels.contains(item.getText())) return item;
            }
            if (component instanceof Container child) {
                final JMenuItem item = findItem(child, labels, seen, depth + 1);
                if (item != null) return item;
            }
        }
        return null;
    }

    static PopupAttempt mergePopupAttempts(final PopupAttempt first,
        final PopupAttempt second) {
        return new PopupAttempt(mergePopupLists(first.visibleBefore(), second.visibleBefore()),
            mergePopupLists(first.dismissed(), second.dismissed()));
    }

    static List<JPopupMenu> mergePopupLists(final List<JPopupMenu> first,
        final List<JPopupMenu> second) {
        final List<JPopupMenu> merged = new ArrayList<>(first.size() + second.size());
        for (final JPopupMenu popup : first) {
            if (!containsIdentity(merged, popup)) merged.add(popup);
        }
        for (final JPopupMenu popup : second) {
            if (!containsIdentity(merged, popup)) merged.add(popup);
        }
        return List.copyOf(merged);
    }

    static PopupAttempt dismissPopup() throws Exception {
        final AtomicReference<PopupAttempt> attempt = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final List<JPopupMenu> visibleBefore = popupMenus(true);
            for (final JPopupMenu popup : visibleBefore) popup.setVisible(false);
            MenuSelectionManager.defaultManager().clearSelectedPath();
            // Clearing the selection can repost or retain a host-owned popup; enforce the close
            // after the manager has processed the selected path as well.
            for (final JPopupMenu popup : visibleBefore) popup.setVisible(false);
            final List<JPopupMenu> dismissed = visibleBefore.stream()
                .filter(popup -> !popup.isVisible())
                .toList();
            attempt.set(new PopupAttempt(List.copyOf(visibleBefore), dismissed));
        });
        return attempt.get();
    }

    static void clickItem(final JMenuItem item) throws Exception {
        SwingUtilities.invokeAndWait(item::doClick);
    }

    record PopupAttempt(List<JPopupMenu> visibleBefore,
        List<JPopupMenu> dismissed) {}

    record PopupSnapshot(JPopupMenu popup, int popupCount,
        String association, String markers) {}

    record PopupCapture(JPopupMenu popup, int popupCount, int maxPopupCount,
        String diagnostic) {
        PopupCapture {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    record RowAttempt(JPopupMenu popup, int popupCount, String diagnostic,
        String dispatchFailureTrace, int dispatchRow, String dispatchComponent,
        boolean terminalRejection) {
        RowAttempt {
            diagnostic = diagnostic == null ? "" : diagnostic;
            dispatchFailureTrace = dispatchFailureTrace == null ? "" : dispatchFailureTrace;
            dispatchComponent = dispatchComponent == null ? "" : dispatchComponent;
        }
    }

    enum RowKind {
        TREE, TABLE, LIST
    }

    record RowKey(String value, boolean available) {
        RowKey {
            value = value == null ? "" : value;
        }

        static RowKey of(final String value) {
            return new RowKey(value, value != null && !value.isBlank());
        }

        static RowKey unavailable() {
            return new RowKey("", false);
        }
    }

    record CapturedRow(RowKind kind, String widgetClass, String widgetIdentity,
        String windowIdentity, int row, String rowKey, boolean rowKeyAvailable, String bounds) {
        CapturedRow {
            widgetClass = widgetClass == null ? "" : widgetClass;
            widgetIdentity = widgetIdentity == null ? "" : widgetIdentity;
            windowIdentity = windowIdentity == null ? "" : windowIdentity;
            rowKey = rowKey == null ? "" : rowKey;
            bounds = bounds == null ? "null" : bounds;
        }

        String rowKeyDiagnostic() {
            return rowKeyAvailable
                ? "available#" + Integer.toHexString(rowKey.hashCode()) : "unavailable";
        }

        String diagnostic() {
            return "widget=" + widgetIdentity
                + " class=" + widgetClass
                + " window=" + windowIdentity
                + " row=" + row
                + " rowKey=" + rowKeyDiagnostic()
                + " bounds=" + bounds;
        }
    }

    record CurrentRow(CapturedRow descriptor, Component component, Rectangle bounds) {
        int row() { return descriptor.row(); }
    }

    record RowResolution(CurrentRow row, String reason) {
        static RowResolution available(final CurrentRow row) {
            return new RowResolution(row, "");
        }

        static RowResolution unavailable(final String reason) {
            return new RowResolution(null, reason == null ? "unknown" : reason);
        }

        boolean available() { return row != null; }
    }

    /** Coordinates and component chosen immediately before a popup-trigger dispatch. */
    record DispatchTarget(Component component, int x, int y, String diagnostic) {
        DispatchTarget(final Component component, final int x, final int y) {
            this(component, x, y, "");
        }

        DispatchTarget {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }

    record RowDispatchResult(RightClickDispatch dispatch, int row, String component,
        boolean retryable, String phase, String reason, boolean terminalRejection) {
        static RowDispatchResult success(final RightClickDispatch dispatch,
            final CurrentRow row) {
            return new RowDispatchResult(dispatch, row.row(), componentIdentity(row.component()),
                false, "", "", false);
        }

        static RowDispatchResult success(final RightClickDispatch dispatch,
            final ExactHostRowDispatch.ExactCurrentRow row) {
            return new RowDispatchResult(dispatch, row.descriptor().viewRow(),
                componentIdentity(row.component()), false, "", "", false);
        }

        static RowDispatchResult retry(final CapturedRow captured, final String phase,
            final String reason) {
            return retry(captured.diagnostic(), phase, reason);
        }

        static RowDispatchResult retry(final String captureDiagnostic, final String phase,
            final String reason) {
            final String actual = reason == null || reason.isBlank() ? "unknown" : reason;
            return new RowDispatchResult(
                RightClickDispatch.notDispatched("capture=" + captureDiagnostic
                    + " phase=" + phase + " reason=" + actual),
                -1, "", true, phase, actual, false);
        }

        static RowDispatchResult rejected(final String captureDiagnostic, final String phase,
            final String reason) {
            final String actual = reason == null || reason.isBlank() ? "unknown" : reason;
            return new RowDispatchResult(
                RightClickDispatch.notDispatched("capture=" + captureDiagnostic
                    + " phase=" + phase + " reason=" + actual),
                -1, "", false, phase, actual, true);
        }
    }

    static final class RowRelocationException extends RuntimeException {
        RowRelocationException(final String message) {
            super(message);
        }
    }

    static int rowDispatchAttemptLimit() {
        return MAX_ROW_DISPATCH_ATTEMPTS;
    }

    record RightClickDispatch(String diagnostic) {
        RightClickDispatch {
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static RightClickDispatch notDispatched(final String diagnostic) {
            return new RightClickDispatch("not-dispatched=" + diagnostic);
        }
    }

    record DispatchFailure(String phase, RuntimeException error) {}

    /** Right-click handler failures are rethrown after both popup-trigger phases are attempted. */
    static final class RightClickDispatchException extends RuntimeException {
        final RightClickDispatch dispatch;

        RightClickDispatchException(final RightClickDispatch dispatch,
            final List<DispatchFailure> failures) {
            super("right-click dispatch failed phases="
                + failures.stream().map(DispatchFailure::phase).toList()
                + " " + dispatch.diagnostic(), phaseFailure(failures.get(0)));
            this.dispatch = dispatch;
            for (int index = 1; index < failures.size(); index++) {
                addSuppressed(phaseFailure(failures.get(index)));
            }
        }

        RightClickDispatch dispatch() { return dispatch; }
    }

    static RuntimeException phaseFailure(final DispatchFailure failure) {
        return new RuntimeException("right-click " + failure.phase() + " dispatch failed",
            failure.error());
    }

    record GuiTargetState(boolean observed, TargetResolution.TargetIdentity identity,
        boolean rawReplaced, String diagnostic) {
        GuiTargetState {
            identity = identity == null ? TargetResolution.TargetIdentity.unavailable() : identity;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        /** Compact unavailable/negative seam retained for old offline Swing fixtures. */
        GuiTargetState(final boolean observed, final String binding, final long generation,
            final String raw, final boolean rawReplaced, final String diagnostic) {
            this(observed, new TargetResolution.TargetIdentity(
                RawExportCoordination.UNAVAILABLE_VALUE, RawExportCoordination.UNAVAILABLE_VALUE, binding, generation,
                RawExportCoordination.UNAVAILABLE_VALUE, RawExportCoordination.UNAVAILABLE_VALUE, raw), rawReplaced, diagnostic);
        }

        String documentId() { return identity.documentId(); }
        String modelId() { return identity.modelId(); }
        String binding() { return identity.binding(); }
        long generation() { return identity.generation(); }
        String modelImageId() { return identity.modelImageId(); }
        String artMeshId() { return identity.artMeshId(); }
        String raw() { return identity.rawId(); }

        static GuiTargetState unobserved(final String diagnostic) {
            return new GuiTargetState(false, TargetResolution.TargetIdentity.unavailable(), false, diagnostic);
        }
    }

    record GuiNativeApplicationObservation(boolean applied, boolean stale,
        GuiTargetState after, PsdValidationContent.Fingerprint freshCurrentRgb,
        String diagnostic) {
        GuiNativeApplicationObservation {
            after = after == null
                ? GuiTargetState.unobserved("GUI target observation unavailable") : after;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }
    }
}
