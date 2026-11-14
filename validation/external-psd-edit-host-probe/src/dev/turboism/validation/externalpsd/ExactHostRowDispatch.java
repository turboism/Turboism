package dev.turboism.validation.externalpsd;

import static dev.turboism.validation.externalpsd.GuiRowDispatch.componentIdentity;
import static dev.turboism.validation.externalpsd.GuiRowDispatch.dismissPopup;
import static dev.turboism.validation.externalpsd.GuiRowDispatch.isLiveComponent;
import static dev.turboism.validation.externalpsd.GuiRowDispatch.validBounds;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact-host row resolution that never falls back to a display-name key.
 *
 * <p>The reviewed object tables expose one authoritative identity per row. Resolving by that
 * identity keeps a dispatch bound to the task fixture even when a second document shows a row
 * with the same visible name.</p>
 */
final class ExactHostRowDispatch {
    static void collectReviewedTables(final Container container, final Window window,
        final List<ExactTableRef> tables) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTable table && GuiRowDispatch.isLiveComponent(table)) {
                final javax.swing.table.TableModel model = table.getModel();
                if (model != null && ExactHostRowTarget.isReviewedTableModelClass(
                    model.getClass())) {
                    tables.add(new ExactTableRef(table, model.getClass(),
                        GuiRowDispatch.componentIdentity(table), componentIdentity(window),
                        objectIdentity(model)));
                }
            }
            if (component instanceof Container child) {
                collectReviewedTables(child, window, tables);
            }
        }
    }

    static ExactCapturedRow exactCapturedRow(final JTable table,
        final ExactTableRef tableRef, final ExactHostRowTarget.Target target) {
        return new ExactCapturedRow(target.identity(), tableRef.widgetIdentity(),
            tableRef.windowIdentity(), objectIdentity(table.getModel()), target.viewRow(),
            target.modelRow(), target.nameCell(), target.state());
    }

    static GuiRowDispatch.RowAttempt exactRowAttempt(final ExactDispatchCapture captured)
        throws Exception {
        final AtomicReference<GuiRowDispatch.RowDispatchResult> rowOutcome = new AtomicReference<>();
        final AtomicReference<GuiRowDispatch.RightClickDispatchException> dispatchFailure =
            new AtomicReference<>();
        final AtomicReference<GuiRowDispatch.PopupAttempt> popupAttempt = new AtomicReference<>(GuiRowDispatch.dismissPopup());
        final List<String> retryReasons = new ArrayList<>();
        final int attempts = GuiRowDispatch.runBoundedRowDispatches(attemptNumber -> {
            rowOutcome.set(null);
            try {
                final boolean triggerOnPress = GuiRowDispatch.popupTriggerOnPress(
                    System.getProperty("os.name", ""));
                SwingUtilities.invokeAndWait(() -> rowOutcome.set(
                    dispatchExactCapturedRow(captured, triggerOnPress)));
            } catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof GuiRowDispatch.RightClickDispatchException failure) {
                    dispatchFailure.set(failure);
                    return false;
                }
                throw wrapped;
            }
            final GuiRowDispatch.RowDispatchResult result = rowOutcome.get();
            if (result == null || !result.retryable()) return false;
            retryReasons.add("attempt=" + attemptNumber + " phase=" + result.phase()
                + " reason=" + result.reason());
            if (attemptNumber < GuiRowDispatch.MAX_ROW_DISPATCH_ATTEMPTS) {
                popupAttempt.set(GuiRowDispatch.mergePopupAttempts(popupAttempt.get(), dismissPopup()));
            }
            return true;
        });
        final GuiRowDispatch.RowDispatchResult rowResult = rowOutcome.get();
        final GuiRowDispatch.PopupCapture popup = rowResult != null && rowResult.terminalRejection()
            ? new GuiRowDispatch.PopupCapture(null, 0, 0, "popupPolls=0 terminal exact target rejection")
            : GuiRowDispatch.awaitPopup(popupAttempt.get());
        final GuiRowDispatch.RightClickDispatchException failure = dispatchFailure.get();
        final String dispatchDiagnostic = rowResult != null
            ? rowResult.dispatch().diagnostic()
            : failure != null
                ? failure.dispatch().diagnostic() : "not-dispatched";
        final String failureTrace = failure == null ? "" : GuiRowDispatch.stackTrace(failure);
        final String diagnostic = "exactCapture=" + captured.diagnostic()
            + " attempts=" + attempts
            + " dispatchRow=" + (rowResult == null ? -1 : rowResult.row())
            + " dispatchComponent=" + (rowResult == null ? "" : rowResult.component())
            + " retryLimit=" + GuiRowDispatch.MAX_ROW_DISPATCH_ATTEMPTS
            + " retryReasons=" + retryReasons
            + " " + dispatchDiagnostic
            + (failureTrace.isBlank() ? "" : " dispatchExceptionTrace=" + failureTrace)
            + " popupCount=" + popup.popupCount()
            + " popupMaxCount=" + popup.maxPopupCount()
            + " " + popup.diagnostic();
        return new GuiRowDispatch.RowAttempt(popup.popup(), popup.popupCount(), diagnostic, failureTrace,
            rowResult == null ? -1 : rowResult.row(),
            rowResult == null ? "" : rowResult.component(),
            rowResult != null && rowResult.terminalRejection());
    }

    static GuiRowDispatch.RowDispatchResult dispatchExactCapturedRow(
        final ExactDispatchCapture captured, final boolean triggerOnPress) {
        final ExactRowResolution selection = resolveExactActiveRow(captured.captured(),
            captured.context());
        if (!selection.available()) {
            return GuiRowDispatch.RowDispatchResult.retry(captured.diagnostic(), "before-left",
                selection.reason());
        }
        return dispatchExactResolvedSelection(captured.captured(), selection.row(),
            triggerOnPress, () -> resolveExactActiveRow(captured.captured(), captured.context()));
    }

    static GuiRowDispatch.RowDispatchResult dispatchExactResolvedSelection(
        final ExactCapturedRow captured, final ExactCurrentRow selected,
        final boolean triggerOnPress, final java.util.function.Supplier<ExactRowResolution> refreshed) {
        if (!ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
            captured.identity().sourceClass())) {
            return GuiRowDispatch.RowDispatchResult.rejected(captured.diagnostic(), "before-left",
                "resolved row source class is not the supported ArtMesh class");
        }
        if (selected == null || !captured.identity().equals(selected.descriptor().identity())) {
            return GuiRowDispatch.RowDispatchResult.rejected(captured.diagnostic(), "before-left",
                "resolved row identity differs from captured exact target");
        }
        if (!GuiRowDispatch.isLiveComponent(selected.component())
            || !GuiRowDispatch.validBounds(selected.descriptor().nameCell().bounds())) {
            return GuiRowDispatch.RowDispatchResult.retry(captured.diagnostic(), "before-left",
                "resolved exact table is not live or name bounds are invalid");
        }
        if (!sameExactState(captured, selected)) {
            return GuiRowDispatch.RowDispatchResult.rejected(captured.diagnostic(), "before-left",
                exactStateChange(captured, selected));
        }

        final String prefix = exactRowDispatchPrefix(captured, selected);
        final AtomicReference<ExactRowResolution> afterSelection = new AtomicReference<>();
        final AtomicReference<String> stateChange = new AtomicReference<>("");
        final AtomicReference<String> identityChange = new AtomicReference<>("");
        try {
            final Point point = selected.descriptor().nameCell().clickPoint();
            final GuiRowDispatch.RightClickDispatch dispatch = GuiRowDispatch.dispatchRightClickAfterSelection(
                selected.component(), point.x, point.y, triggerOnPress, prefix, () -> {
                    final ExactRowResolution resolved = refreshed.get();
                    afterSelection.set(resolved);
                    if (!resolved.available()) return null;
                    final ExactCurrentRow current = resolved.row();
                    if (!captured.identity().equals(current.descriptor().identity())) {
                        identityChange.set("after-left exact row identity differs: captured="
                            + captured.identity() + " after=" + current.descriptor().identity());
                        return null;
                    }
                    if (!sameExactState(captured, current)) {
                        stateChange.set(exactStateChange(captured, current));
                        return null;
                    }
                    if (!GuiRowDispatch.isLiveComponent(current.component())
                        || !GuiRowDispatch.validBounds(current.descriptor().nameCell().bounds())) {
                        return null;
                    }
                    final Point freshPoint = current.descriptor().nameCell().clickPoint();
                    return new GuiRowDispatch.DispatchTarget(current.component(), freshPoint.x, freshPoint.y,
                        "afterLeft=" + current.diagnostic());
                });
            final ExactCurrentRow dispatched = afterSelection.get() == null
                ? null : afterSelection.get().row();
            if (dispatched == null) {
                throw new GuiRowDispatch.RowRelocationException(
                    "active exact row was not recorded after the selection click");
            }
            return GuiRowDispatch.RowDispatchResult.success(dispatch, dispatched);
        } catch (GuiRowDispatch.RowRelocationException retry) {
            final ExactRowResolution resolved = afterSelection.get();
            final String reason = !identityChange.get().isBlank() ? identityChange.get()
                : !stateChange.get().isBlank() ? stateChange.get()
                : resolved == null ? retry.getMessage() : resolved.reason();
            final String detail = captured.diagnostic() + " afterLeft="
                + (resolved == null ? "unavailable" : resolved.diagnostic());
            if (!identityChange.get().isBlank() || !stateChange.get().isBlank()) {
                return GuiRowDispatch.RowDispatchResult.rejected(detail, "after-left", reason);
            }
            return GuiRowDispatch.RowDispatchResult.retry(detail, "after-left", reason);
        }
    }

    static ExactRowResolution resolveExactActiveRow(
        final ExactCapturedRow captured, final ExactHostRowTarget.HostAccessContext context) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return ExactRowResolution.unavailable("exact row resolution must run on EDT");
        }
        if (captured == null) {
            return ExactRowResolution.unavailable("captured exact row is unavailable");
        }
        final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .getActiveWindow();
        if (active == null || !active.isShowing() || !active.isDisplayable()
            || !GuiRowDispatch.componentIdentity(active).equals(captured.windowIdentity())) {
            return ExactRowResolution.unavailable(
                "captured target window is no longer the active showing window");
        }
        final List<ExactCurrentRow> rows = new ArrayList<>();
        for (final Window window : Window.getWindows()) {
            if (!window.isShowing() || !GuiRowDispatch.componentIdentity(window).equals(captured.windowIdentity())) {
                continue;
            }
            collectExactCurrentRows(window, captured, context, rows);
        }
        return resolveExactActiveRow(captured, rows);
    }

    static void collectExactCurrentRows(final Container container,
        final ExactCapturedRow captured, final ExactHostRowTarget.HostAccessContext context,
        final List<ExactCurrentRow> rows) {
        for (final Component component : container.getComponents()) {
            if (component instanceof JTable table && GuiRowDispatch.isLiveComponent(table)) {
                final Object model = table.getModel();
                if (model != null && ExactHostRowTarget.isReviewedTableModelClass(
                    model.getClass())) {
                    for (int row = 0; row < table.getRowCount(); row++) {
                        final ExactHostRowTarget.Resolution resolution =
                            ExactHostRowTarget.resolve(table, row, context);
                        if (!resolution.available()
                            || !resolution.target().identity().equals(captured.identity())) {
                            continue;
                        }
                        final ExactHostRowTarget.Target target = resolution.target();
                        final ExactCapturedRow descriptor = new ExactCapturedRow(
                            target.identity(), componentIdentity(table),
                            GuiRowDispatch.componentIdentity(SwingUtilities.getWindowAncestor(table)),
                            objectIdentity(model), target.viewRow(), target.modelRow(),
                            target.nameCell(), target.state());
                        rows.add(new ExactCurrentRow(descriptor, table));
                    }
                }
            }
            if (component instanceof Container child) {
                collectExactCurrentRows(child, captured, context, rows);
            }
        }
    }

    /** Package-private seam for the exact GUI resolver; unlike the legacy resolver it has no key fallback. */
    static ExactRowResolution resolveExactActiveRowForTest(final ExactCapturedRow captured,
        final List<ExactCurrentRow> currentRows) {
        return resolveExactActiveRow(captured, currentRows);
    }

    static ExactRowResolution resolveExactActiveRow(final ExactCapturedRow captured,
        final List<ExactCurrentRow> currentRows) {
        if (captured == null) return ExactRowResolution.unavailable("captured exact row is unavailable");
        if (captured.identity() == null) {
            return ExactRowResolution.unavailable("captured exact row identity is unavailable");
        }
        if (!ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
            captured.identity().sourceClass())) {
            return ExactRowResolution.unavailable(
                "captured exact row source class is not the supported ArtMesh class");
        }
        final List<ExactCurrentRow> all = currentRows == null ? List.of() : currentRows;
        final List<ExactCurrentRow> sameWindow = all.stream()
            .filter(row -> row != null && row.descriptor() != null)
            .filter(row -> captured.windowIdentity().equals(row.descriptor().windowIdentity()))
            .filter(row -> captured.identity().equals(row.descriptor().identity()))
            .filter(row -> isLiveComponent(row.component()))
            .filter(row -> validBounds(row.descriptor().nameCell().bounds()))
            .toList();
        if (sameWindow.isEmpty()) {
            final boolean otherWindow = all.stream()
                .filter(row -> row != null && row.descriptor() != null)
                .anyMatch(row -> captured.identity().equals(row.descriptor().identity())
                    && !captured.windowIdentity().equals(row.descriptor().windowIdentity()));
            return ExactRowResolution.unavailable(otherWindow
                ? "matching exact row exists only in another window"
                : "no live exact row matches family/source/domain identity");
        }
        if (sameWindow.size() > 1) {
            return ExactRowResolution.unavailable(
                "exact row identity is ambiguous in captured window: " + sameWindow.size());
        }
        return ExactRowResolution.available(sameWindow.get(0));
    }

    /**
     * Selects the exact entrances for one proven active target window. Parts and Deformer are
     * independent host entry points, so they may both be returned; duplicate identities within a
     * family remain ambiguous and are rejected.
     */
    static ExactTargetSelection selectExactTargetRowsForTest(
        final List<ExactCapturedRow> rows, final String targetWindowIdentity,
        final String expectedDomainId) {
        return selectExactTargetRows(rows, targetWindowIdentity, expectedDomainId);
    }

    static ExactTargetSelection selectExactTargetRows(
        final List<ExactCapturedRow> rows, final String targetWindowIdentity,
        final String expectedDomainId) {
        if (targetWindowIdentity == null || targetWindowIdentity.isBlank()) {
            return ExactTargetSelection.rejected(
                "active target window cannot be proven: window identity is unavailable");
        }
        if (expectedDomainId == null || expectedDomainId.isBlank()) {
            return ExactTargetSelection.rejected(
                "expected ArtMesh domain ID is unavailable");
        }
        final List<ExactCapturedRow> all = rows == null ? List.of() : rows.stream()
            .filter(Objects::nonNull)
            .filter(row -> row.identity() != null)
            .toList();
        final List<ExactCapturedRow> matching = all.stream()
            .filter(row -> targetWindowIdentity.equals(row.windowIdentity()))
            .filter(row -> ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
                row.identity().sourceClass()))
            .filter(row -> expectedDomainId.equals(row.identity().domainId()))
            .toList();
        if (matching.isEmpty()) {
            final boolean otherWindow = all.stream()
                .filter(row -> ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME.equals(
                    row.identity().sourceClass()))
                .anyMatch(row -> expectedDomainId.equals(row.identity().domainId())
                    && !targetWindowIdentity.equals(row.windowIdentity()));
            return otherWindow
                ? ExactTargetSelection.rejected(
                    "matching exact ArtMesh row exists only in another window")
                : ExactTargetSelection.notFound(
                    "no exact ArtMesh row matched domain ID " + expectedDomainId);
        }

        final Map<ExactTargetGroup, List<ExactCapturedRow>> groups = new LinkedHashMap<>();
        for (final ExactCapturedRow row : matching) {
            final ExactTargetGroup group = new ExactTargetGroup(row.windowIdentity(),
                row.identity());
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        for (final Map.Entry<ExactTargetGroup, List<ExactCapturedRow>> entry
            : groups.entrySet()) {
            if (entry.getValue().size() > 1) {
                return ExactTargetSelection.ambiguous(
                    "same row family has multiple active exact candidates: "
                        + entry.getKey() + " count=" + entry.getValue().size());
            }
        }

        final List<ExactCapturedRow> ordered = new ArrayList<>(matching);
        ordered.sort(Comparator.comparingInt(row -> exactFamilyOrder(
            row.identity().rowFamily())));
        return ExactTargetSelection.available(ordered);
    }

    static int exactFamilyOrder(final ExactHostRowTarget.RowFamily family) {
        return family == ExactHostRowTarget.RowFamily.DEFORMER ? 0 : 1;
    }

    static boolean sameExactState(final ExactCapturedRow captured,
        final ExactCurrentRow current) {
        return current != null && captured.state().equals(current.descriptor().state());
    }

    static boolean sameExactStateForTest(final ExactCapturedRow captured,
        final ExactCurrentRow current) {
        return sameExactState(captured, current);
    }

    /** Test seam for the same identity/state/coordinate gate used by the live exact dispatcher. */
    static GuiRowDispatch.RightClickDispatch dispatchExactResolvedRowForTest(
        final ExactCapturedRow captured, final ExactCurrentRow selected,
        final ExactCurrentRow refreshed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            return GuiRowDispatch.RightClickDispatch.notDispatched("exact test dispatch must run on EDT");
        }
        return dispatchExactResolvedSelection(captured, selected, true,
            () -> refreshed == null ? ExactRowResolution.unavailable("refreshed row unavailable")
                : ExactRowResolution.available(refreshed)).dispatch();
    }

    static String exactStateChange(final ExactCapturedRow captured,
        final ExactCurrentRow current) {
        return "visible/locked changed: before=" + captured.state()
            + " after=" + (current == null ? "unavailable" : current.descriptor().state());
    }

    static String exactRowDispatchPrefix(final ExactCapturedRow captured,
        final ExactCurrentRow selection) {
        return "capture=" + captured.diagnostic()
            + " selection=" + selection.diagnostic()
            + " selectionStateUnchanged=" + sameExactState(captured, selection) + " ";
    }

    static String objectIdentity(final Object value) {
        if (value == null) return "null";
        return value.getClass().getName() + '@'
            + Integer.toHexString(System.identityHashCode(value));
    }

    static String requireIdentityText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    record ReviewedTables(List<ExactTableRef> tables, String windowIdentity,
        Window window, boolean proven, String diagnostic) {
        ReviewedTables {
            tables = List.copyOf(tables);
            windowIdentity = windowIdentity == null ? "" : windowIdentity;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static ReviewedTables unavailable(final String diagnostic) {
            return new ReviewedTables(List.of(), "", null, false, diagnostic);
        }
    }

    record ExactTableRef(JTable table, Class<?> modelClass, String widgetIdentity,
        String windowIdentity, String modelIdentity) {
        String diagnostic() {
            return "widget=" + widgetIdentity + " window=" + windowIdentity
                + " model=" + modelIdentity + " modelClass=" + modelClass.getName();
        }
    }

    record ExactCapturedRow(ExactHostRowTarget.Identity identity,
        String widgetIdentity, String windowIdentity, String modelIdentity, int viewRow,
        int modelRow, ExactHostRowTarget.NameCell nameCell,
        ExactHostRowTarget.VisibilityLockState state) {
        ExactCapturedRow {
            Objects.requireNonNull(identity, "identity");
            widgetIdentity = requireIdentityText(widgetIdentity, "widgetIdentity");
            windowIdentity = requireIdentityText(windowIdentity, "windowIdentity");
            modelIdentity = requireIdentityText(modelIdentity, "modelIdentity");
            if (viewRow < 0 || modelRow < 0) {
                throw new IllegalArgumentException("exact row coordinates must not be negative");
            }
            Objects.requireNonNull(nameCell, "nameCell");
            Objects.requireNonNull(state, "state");
        }

        String diagnostic() {
            return "identity=" + identity
                + " widget=" + widgetIdentity
                + " window=" + windowIdentity
                + " model=" + modelIdentity
                + " viewRow=" + viewRow
                + " modelRow=" + modelRow
                + " nameCell=" + nameCell.bounds()
                + " click=" + nameCell.clickPoint()
                + " state=" + state;
        }
    }

    enum ExactTargetSelectionStatus {
        AVAILABLE,
        NOT_FOUND,
        AMBIGUOUS,
        REJECTED
    }

    record ExactTargetSelection(List<ExactCapturedRow> rows,
        ExactTargetSelectionStatus status, String reason) {
        ExactTargetSelection {
            rows = rows == null ? List.of() : List.copyOf(rows);
            status = Objects.requireNonNull(status, "status");
            reason = reason == null ? "" : reason;
        }

        static ExactTargetSelection available(final List<ExactCapturedRow> rows) {
            return new ExactTargetSelection(rows, ExactTargetSelectionStatus.AVAILABLE, "");
        }

        static ExactTargetSelection notFound(final String reason) {
            return new ExactTargetSelection(List.of(), ExactTargetSelectionStatus.NOT_FOUND,
                reason);
        }

        static ExactTargetSelection ambiguous(final String reason) {
            return new ExactTargetSelection(List.of(), ExactTargetSelectionStatus.AMBIGUOUS,
                reason);
        }

        static ExactTargetSelection rejected(final String reason) {
            return new ExactTargetSelection(List.of(), ExactTargetSelectionStatus.REJECTED,
                reason);
        }

        boolean available() { return status == ExactTargetSelectionStatus.AVAILABLE; }
    }

    record ExactTargetGroup(String windowIdentity,
        ExactHostRowTarget.Identity identity) { }

    record ExactCurrentRow(ExactCapturedRow descriptor, Component component) {
        ExactCurrentRow {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(component, "component");
        }

        String diagnostic() {
            return descriptor.diagnostic() + " componentState=" + GuiRowDispatch.componentState(component);
        }
    }

    record ExactRowResolution(ExactCurrentRow row, String reason) {
        ExactRowResolution {
            reason = reason == null ? "" : reason;
            if (row == null && reason.isBlank()) reason = "exact row unavailable";
        }

        static ExactRowResolution available(final ExactCurrentRow row) {
            return new ExactRowResolution(Objects.requireNonNull(row, "row"), "");
        }

        static ExactRowResolution unavailable(final String reason) {
            return new ExactRowResolution(null, reason);
        }

        boolean available() { return row != null; }

        String diagnostic() {
            return row == null ? "unavailable=" + reason : row.diagnostic();
        }
    }

    record ExactDispatchCapture(ExactCapturedRow captured,
        ExactHostRowTarget.HostAccessContext context) {
        String diagnostic() { return captured.diagnostic(); }
    }

    record ExactCapture(List<ExactDispatchCapture> rows, List<String> tableDiagnostics,
        String diagnostic, boolean hostAvailable, ExactTargetSelectionStatus targetStatus) {
        ExactCapture {
            rows = List.copyOf(rows);
            tableDiagnostics = List.copyOf(tableDiagnostics);
            diagnostic = diagnostic == null ? "" : diagnostic;
            targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        }
    }
}
