package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.fullyVisible;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.isDescendant;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.toScreenPoint;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressCanvasActor.within;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.UI_MAP_MAX_DEPTH;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.PARAMETER_ACTOR_POLLS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterChangeObservation;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterStateOutcome;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.ParameterStateSnapshot;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.compareParameterState;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.readParameterSnapshot;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressParameterEvidence.settleParameterObservations;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.ACTION_SETTLE_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.NAVIGATION_SETTLE_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.POLL_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.RelationObservation;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.Step;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.TargetKey;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.Verdict;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.boundedText;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.expandAncestors;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.finite;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.identityHash;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.insetsJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.json;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.layoutJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.onEdt;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.parentOf;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.parentText;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.pointJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.rectangleJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.rowJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.samePathNodeIdentity;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.transformJson;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.treeLabel;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.treePathLabel;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.uniqueName;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.table.TableModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

/**
 * Parts-tree and canvas actor evidence for the operator-driven history probe.
 *
 * <p>Every actor records the pointer path it pressed, the model state before and after, and the
 * undo-manager movement it caused. A press that cannot prove its target reports the reason
 * instead of a silent no-op.</p>
 */
final class WindowsHistoryNativeUiIngressPartActors {
    static final String INTERNAL_ROOT_PART_ID = "__RootPart__";
    static final int MAX_PART_GESTURE_PAIRS = 8;
    static final int PART_GESTURE_SETTLE_POLLS = 12;
    static final int MAX_PART_GESTURE_EVIDENCE_LINES = 64;
    static final int MAX_PART_GESTURE_PATH_COMPONENTS = 32;
    static final int MAX_PART_GESTURE_TEXT_LENGTH = 256;
    /** Maximum number of identical source-point moves admitted before press. */
    static final int MAX_PART_POINTER_ATTEMPTS = 3;

    /** Finds the currently discovered Parts surface without substituting for SDK readback. */
    static PartPrePressCheck currentPartPrePressSurface(final PartPairLayout expected) {
        JTable firstTable = null;
        JTree firstTree = null;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final JTable table = findPartsTable(window);
            if (table == null) continue;
            final JTree tree = WindowsMeshEditValidationProbe.extractTree(table);
            if (firstTable == null) {
                firstTable = table;
                firstTree = tree;
            }
            if (table == expected.table()) return verifyPartPrePress(expected, table, tree);
        }
        return verifyPartPrePress(expected, firstTable, firstTree);
    }

    /**
     * Rechecks the object graph and both points after the Robot move/settle window.
     *
     * <p>The comparisons deliberately use object identity for Swing components, models and tree
     * nodes. Display names are retained as bounded evidence and lookup hints only; they do not
     * establish that a press is on the intended SDK Part.</p>
     */
    static PartPrePressCheck verifyPartPrePress(
            final PartPairLayout expected, final JTable currentTable, final JTree currentTree) {
        if (expected == null
                || expected.table() == null
                || expected.tree() == null
                || expected.tableModel() == null
                || expected.treeModel() == null) {
            return PartPrePressCheck.failure("layout-identity-unavailable", expected, currentTable, currentTree);
        }
        if (currentTable == null || currentTree == null) {
            return PartPrePressCheck.failure("current-parts-surface-unavailable", expected, currentTable, currentTree);
        }
        if (currentTable != expected.table()) {
            return PartPrePressCheck.failure("table-replaced", expected, currentTable, currentTree);
        }
        if (currentTree != expected.tree()) {
            return PartPrePressCheck.failure("tree-replaced", expected, currentTable, currentTree);
        }
        if (currentTable.getModel() != expected.tableModel()) {
            return PartPrePressCheck.failure("table-model-replaced", expected, currentTable, currentTree);
        }
        if (currentTree.getModel() != expected.treeModel()) {
            return PartPrePressCheck.failure("tree-model-replaced", expected, currentTable, currentTree);
        }
        if (currentTable.isShowing() != expected.tableShowing()) {
            return PartPrePressCheck.failure("table-showing-changed", expected, currentTable, currentTree);
        }
        final String sourceFailure = verifyPartRowAtPress(expected.source(), currentTable, currentTree);
        if (sourceFailure != null) {
            return PartPrePressCheck.failure("source-" + sourceFailure, expected, currentTable, currentTree);
        }
        final String targetFailure = verifyPartRowAtPress(expected.target(), currentTable, currentTree);
        if (targetFailure != null) {
            return PartPrePressCheck.failure("target-" + targetFailure, expected, currentTable, currentTree);
        }
        return PartPrePressCheck.success(expected, currentTable, currentTree);
    }

    /** Testable readback seam used by the real gesture runner's EDT guard. */
    static PartPrePressCheck verifyPartPrePress(
            final PartPairLayout expected,
            final JTable currentTable,
            final JTree currentTree,
            final PartPair pair,
            final PartModelSnapshot currentModel) {
        if (currentModel == null) {
            return PartPrePressCheck.failure("part-model-unavailable", expected, currentTable, currentTree);
        }
        if (pair == null || !partPairMatches(currentModel, pair)) {
            return PartPrePressCheck.failure("part-model-changed", expected, currentTable, currentTree);
        }
        return verifyPartPrePress(expected, currentTable, currentTree);
    }

    static String verifyPartRowAtPress(final PartRowLocation expected, final JTable table, final JTree tree) {
        if (expected == null || expected.node() == null) return "node-unavailable";
        final int row = expected.row();
        if (tree.getRowForPath(expected.path()) != row) return "row-changed";
        final TreePath currentPath = tree.getPathForRow(row);
        if (!samePathNodeIdentity(expected.path(), currentPath)) return "path-changed";
        if (currentPath == null || currentPath.getLastPathComponent() != expected.node()) return "node-replaced";
        if (!Objects.equals(expected.label(), treeLabel(tree, expected.node()))) return "label-changed";
        if (row < 0 || row >= table.getRowCount()) return "table-row-unavailable";
        final Rectangle cell = table.getCellRect(row, expected.column(), true);
        final Rectangle viewport = table.getVisibleRect();
        if (!cell.equals(expected.cell())) return "cell-changed";
        if (!viewport.equals(expected.viewport())) return "viewport-changed";
        if (table.rowAtPoint(expected.localPoint()) != row) return "row-at-point-changed";
        if (table.columnAtPoint(expected.localPoint()) != expected.column()) return "column-at-point-changed";
        if (!viewport.contains(cell.x, cell.y)
                || !viewport.contains(cell.x + cell.width - 1, cell.y + cell.height - 1)) {
            return "cell-not-visible";
        }
        if (expected.screenPoint() != null) {
            try {
                final Point currentScreen = new Point(expected.localPoint());
                SwingUtilities.convertPointToScreen(currentScreen, table);
                if (!currentScreen.equals(expected.screenPoint())) return "screen-point-changed";
                final Point fromScreen = new Point(expected.screenPoint());
                SwingUtilities.convertPointFromScreen(fromScreen, table);
                if (!fromScreen.equals(expected.localPoint())) return "screen-hit-changed";
                final int screenRow = table.rowAtPoint(fromScreen);
                final int screenColumn = table.columnAtPoint(fromScreen);
                final TreePath screenPath = tree.getPathForRow(screenRow);
                if (screenRow != row
                        || screenColumn != expected.column()
                        || !samePathNodeIdentity(expected.path(), screenPath)) {
                    return "screen-row-path-changed";
                }
            } catch (java.awt.IllegalComponentStateException notShowing) {
                return "screen-point-unavailable";
            }
        }
        return null;
    }

    /** Creates the real-input endpoint used only by the Parts gesture. */
    static PartGestureInput robotPartGestureInput() throws Exception {
        final java.awt.Robot robot = new java.awt.Robot();
        return new PartGestureInput() {
            @Override
            public void mouseMove(final int x, final int y) {
                robot.mouseMove(x, y);
            }

            @Override
            public void pause(final long millis) throws InterruptedException {
                Thread.sleep(millis);
            }

            @Override
            public void mousePress() {
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }

            @Override
            public void mouseRelease() {
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }
        };
    }

    static PartPointerEvidence readPointer(
            final int attempt,
            final String phase,
            final long commandStartMillis,
            final Point commandedPoint,
            final PartPointerReadback pointerReadback) {
        final long monotonicNanos = System.nanoTime();
        final String thread = Thread.currentThread().getName();
        try {
            final Point readbackPoint = pointerReadback.read();
            if (readbackPoint == null) {
                return new PartPointerEvidence(
                        phase,
                        attempt,
                        commandStartMillis,
                        monotonicNanos,
                        thread,
                        commandedPoint,
                        null,
                        "UNAVAILABLE",
                        "null-pointer-info");
            }
            final Point copy = new Point(readbackPoint);
            final boolean matches = commandedPoint != null && commandedPoint.equals(copy);
            return new PartPointerEvidence(
                    phase,
                    attempt,
                    commandStartMillis,
                    monotonicNanos,
                    thread,
                    commandedPoint,
                    copy,
                    matches ? "MATCH" : "MISMATCH",
                    matches ? "" : "commanded-point-diff");
        } catch (Exception failure) {
            return new PartPointerEvidence(
                    phase,
                    attempt,
                    commandStartMillis,
                    monotonicNanos,
                    thread,
                    commandedPoint,
                    null,
                    "UNAVAILABLE",
                    "exception:" + failure.getClass().getSimpleName());
        }
    }

    static PartGestureAttempt runPartGesture(
            final PartPairLayout layout,
            final String intendedSourceId,
            final String intendedTargetId,
            final PartGestureInput input,
            final PartPrePressGuard guard)
            throws Exception {
        Objects.requireNonNull(layout, "layout");
        return runPartGesture(
                layout,
                intendedSourceId,
                intendedTargetId,
                input,
                () -> layout.source().screenPoint(),
                guard);
    }

    /**
     * Runs one bounded Parts gesture. The input is injectable so cleanup and the pre-press guard
     * can be tested with real Swing components without constructing a headless {@link
     * java.awt.Robot}.
     */
    static PartGestureAttempt runPartGesture(
            final PartPairLayout layout,
            final String intendedSourceId,
            final String intendedTargetId,
            final PartGestureInput input,
            final PartPointerReadback pointerReadback,
            final PartPrePressGuard guard)
            throws Exception {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(pointerReadback, "pointerReadback");
        Objects.requireNonNull(guard, "guard");
        final PartGestureCapture capture = new PartGestureCapture(layout, intendedSourceId, intendedTargetId);
        final AtomicBoolean lifecycleOpen = new AtomicBoolean(true);
        boolean listenersInstalled = false;
        boolean pressed = false;
        PartPrePressCheck prePress = null;
        try {
            listenersInstalled = onEdt(() -> capture.installIfOpen(lifecycleOpen));
            if (!listenersInstalled) throw new IllegalStateException("gesture-lifecycle-closed-before-install");
            final Point source = layout.source().screenPoint();
            final Point target = layout.target().screenPoint();
            if (source == null || target == null) {
                final PartPrePressCheck unavailable =
                        PartPrePressCheck.failure("screen-point-unavailable", layout, layout.table(), layout.tree());
                onEdt(() -> {
                    capture.recordPrePress(unavailable);
                    return null;
                });
                return new PartGestureAttempt(
                        PartGestureStatus.UNRESOLVED, unavailable.code(), unavailable, capture.evidence());
            }
            int matchedAttempt = 0;
            long matchedAttemptStartMillis = 0L;
            for (int attempt = 1; attempt <= MAX_PART_POINTER_ATTEMPTS; attempt++) {
                final long commandStartMillis = System.currentTimeMillis();
                capture.beginPointerAttempt(attempt, commandStartMillis);
                capture.recordPointer(PartPointerEvidence.command(attempt, commandStartMillis, source));
                input.mouseMove(source.x, source.y);
                input.pause(120L);
                final PartPointerEvidence afterMove =
                        readPointer(attempt, "after-move-pointer", commandStartMillis, source, pointerReadback);
                capture.recordPointer(afterMove);
                if ("MATCH".equals(afterMove.outcome())) {
                    matchedAttempt = attempt;
                    matchedAttemptStartMillis = commandStartMillis;
                    break;
                }
                if ("UNAVAILABLE".equals(afterMove.outcome())) {
                    return new PartGestureAttempt(
                            PartGestureStatus.UNRESOLVED, afterMove.failureCode(), prePress, capture.evidence());
                }
                if (attempt == MAX_PART_POINTER_ATTEMPTS) {
                    return new PartGestureAttempt(
                            PartGestureStatus.UNRESOLVED, afterMove.failureCode(), prePress, capture.evidence());
                }
                // A retry is admitted only by the same SDK and Swing guard used before press.
                prePress = onEdt(() -> {
                    final PartPrePressCheck checked = guard.check();
                    capture.recordPrePress(checked);
                    return checked;
                });
                if (prePress == null || !prePress.ok()) {
                    return new PartGestureAttempt(
                            PartGestureStatus.UNRESOLVED,
                            prePress == null ? "pre-press-guard-unavailable" : prePress.code(),
                            prePress,
                            capture.evidence());
                }
            }
            if (matchedAttempt == 0) {
                return new PartGestureAttempt(
                        PartGestureStatus.UNRESOLVED, "after-move-pointer-mismatch", prePress, capture.evidence());
            }
            prePress = onEdt(() -> {
                final PartPrePressCheck checked = guard.check();
                capture.recordPrePress(checked);
                return checked;
            });
            if (prePress == null || !prePress.ok()) {
                return new PartGestureAttempt(
                        PartGestureStatus.UNRESOLVED,
                        prePress == null ? "pre-press-guard-unavailable" : prePress.code(),
                        prePress,
                        capture.evidence());
            }

            final PartPointerEvidence beforePress = readPointer(
                    matchedAttempt, "before-press-pointer", matchedAttemptStartMillis, source, pointerReadback);
            capture.recordPointer(beforePress);
            if (beforePress.failureCode() != null) {
                return new PartGestureAttempt(
                        PartGestureStatus.UNRESOLVED, beforePress.failureCode(), prePress, capture.evidence());
            }

            // Set the flag before calling the injected endpoint: a press that throws may still
            // have reached the native input device, so finally must attempt the release.
            pressed = true;
            input.mousePress();
            input.pause(180L);
            final int segments = 12;
            for (int segment = 1; segment <= segments; segment++) {
                input.mouseMove(
                        source.x + (target.x - source.x) * segment / segments,
                        source.y + (target.y - source.y) * segment / segments);
                input.pause(35L);
            }
            input.pause(180L);
            input.mouseRelease();
            pressed = false;
            // Flush queued Swing callbacks and read the bounded event set on the EDT.
            final PartGestureCheck checked = onEdt(capture::checkEvents);
            return new PartGestureAttempt(checked.status(), checked.code(), prePress, capture.evidence());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new PartGestureAttempt(PartGestureStatus.EXCEPTION, "interrupted", prePress, capture.evidence());
        } catch (Exception failure) {
            if (prePress == null) {
                final PartPrePressCheck guardFailure = PartPrePressCheck.failure(
                        "guard-failed:" + failure.getClass().getSimpleName(), layout, null, null);
                prePress = guardFailure;
                try {
                    onEdt(() -> {
                        capture.recordPrePress(guardFailure);
                        return null;
                    });
                } catch (Exception ignored) {
                    // The input failure remains the actor outcome; layout evidence is retained.
                }
            }
            final String failureCode = (listenersInstalled ? "input-failed:" : "listener-install-failed:")
                    + failure.getClass().getSimpleName();
            return new PartGestureAttempt(PartGestureStatus.EXCEPTION, failureCode, prePress, capture.evidence());
        } finally {
            // Close admission before any cleanup work. If an invokeLater callback timed out and
            // is still queued, its installIfOpen check must observe the closed gesture.
            lifecycleOpen.set(false);
            if (pressed) {
                try {
                    input.mouseRelease();
                } catch (Exception ignored) {
                    // The original input failure remains the actor outcome; cleanup is best effort.
                }
            }
            try {
                onEdt(() -> {
                    capture.remove();
                    return null;
                });
            } catch (Exception ignored) {
                // The actor outcome already records the gesture failure.
            }
        }
    }

    /** Returns only identity-safe source/target pairs; names are lookup hints, never identity. */
    static List<PartPair> safePartPairs(final PartModelSnapshot snapshot) {
        final Map<String, ActorPart> byId = partIndex(snapshot);
        if (byId == null) return List.of();
        final Map<String, Integer> nameCounts = new HashMap<>();
        for (final ActorPart part : byId.values()) {
            if (part.name() != null && !part.name().isBlank()) {
                nameCounts.merge(part.name(), 1, Integer::sum);
            }
        }
        final List<PartPair> pairs = new ArrayList<>();
        for (final ActorPart source : byId.values()) {
            if (!sourceEligible(source) || !uniqueName(source, nameCounts)) continue;
            for (final ActorPart target : byId.values()) {
                if (!targetEligible(target)
                        || !uniqueName(target, nameCounts)
                        || source.id().equals(target.id())
                        || source.parentId().filter(target.id()::equals).isPresent()
                        || isDescendant(source.id(), target.id(), byId)) {
                    continue;
                }
                pairs.add(new PartPair(snapshot.modelId(), source, target));
            }
        }
        return List.copyOf(pairs);
    }

    static boolean partPairMatches(final PartModelSnapshot snapshot, final PartPair pair) {
        if (snapshot == null || pair == null || !Objects.equals(snapshot.modelId(), pair.modelId())) return false;
        final Map<String, ActorPart> byId = partIndex(snapshot);
        if (byId == null) return false;
        return samePartState(pair.source(), byId.get(pair.source().id()))
                && samePartState(pair.target(), byId.get(pair.target().id()));
    }

    static boolean samePartState(final ActorPart expected, final ActorPart actual) {
        return expected != null
                && actual != null
                && expected.id().equals(actual.id())
                && Objects.equals(expected.parentId(), actual.parentId())
                && Objects.equals(expected.childIds(), actual.childIds());
    }

    /** Classifies only the selected source's read-only parent transition. */
    static PartDragCheck assessPartDrag(
            final PartPair pair,
            final PartModelSnapshot before,
            final PartModelSnapshot after,
            final String beforeSignificant,
            final String afterSignificant) {
        final ActorPart beforeSource =
                partOf(before, pair == null ? null : pair.source().id());
        final ActorPart afterSource =
                partOf(after, pair == null ? null : pair.source().id());
        final Optional<String> parentBefore = parentOf(beforeSource);
        final Optional<String> parentAfter = parentOf(afterSource);
        final String sourceId =
                pair == null || pair.source() == null ? "" : pair.source().id();
        final String targetId =
                pair == null || pair.target() == null ? "" : pair.target().id();
        if (pair == null
                || before == null
                || after == null
                || !Objects.equals(before.modelId(), after.modelId())
                || !Objects.equals(pair.modelId(), after.modelId())) {
            return new PartDragCheck(
                    PartDragStatus.MISMATCH, "model-changed", sourceId, targetId, parentBefore, parentAfter);
        }
        if (beforeSource == null || afterSource == null || partOf(after, targetId) == null) {
            return new PartDragCheck(
                    PartDragStatus.UNAVAILABLE,
                    "selected-part-unavailable",
                    sourceId,
                    targetId,
                    parentBefore,
                    parentAfter);
        }
        if (parentBefore.filter(targetId::equals).isPresent()) {
            return new PartDragCheck(
                    PartDragStatus.MISMATCH, "same-parent", sourceId, targetId, parentBefore, parentAfter);
        }
        if (parentAfter.filter(targetId::equals).isPresent() && !Objects.equals(parentBefore, parentAfter)) {
            return new PartDragCheck(
                    PartDragStatus.CHANGED, "selected-parent-changed", sourceId, targetId, parentBefore, parentAfter);
        }
        if (!Objects.equals(parentBefore, parentAfter)) {
            return new PartDragCheck(
                    PartDragStatus.MISMATCH, "wrong-parent", sourceId, targetId, parentBefore, parentAfter);
        }
        if (beforeSignificant == null || afterSignificant == null) {
            return new PartDragCheck(
                    PartDragStatus.UNAVAILABLE, "history-unavailable", sourceId, targetId, parentBefore, parentAfter);
        }
        if (!Objects.equals(beforeSignificant, afterSignificant)) {
            return new PartDragCheck(
                    PartDragStatus.MISMATCH,
                    "unselected-significant-change",
                    sourceId,
                    targetId,
                    parentBefore,
                    parentAfter);
        }
        return new PartDragCheck(
                PartDragStatus.NO_CHANGE, "no-selected-parent-change", sourceId, targetId, parentBefore, parentAfter);
    }

    /**
     * Bounded readback decision used by the Parts actor after one real gesture.
     *
     * <p>A no-change sample is intentionally not a retry signal: the host may commit the parent
     * transition later in the bounded window. Only a complete window of no-change samples permits
     * the caller to try another safe candidate. Unknown readback and mismatches terminate the
     * current actor so an unverified gesture is never sent again.</p>
     */
    static PartDragSettlement settlePartDrag(final int maxPolls, final PartDragReadback readback) throws Exception {
        if (maxPolls <= 0) throw new IllegalArgumentException("maxPolls must be positive");
        Objects.requireNonNull(readback, "readback");
        PartDragCheck lastCheck = null;
        for (int poll = 0; poll < maxPolls; poll++) {
            lastCheck = readback.read(poll);
            if (lastCheck == null) {
                lastCheck = new PartDragCheck(
                        PartDragStatus.UNAVAILABLE,
                        "part-readback-unavailable",
                        "",
                        "",
                        Optional.empty(),
                        Optional.empty());
            }
            if (lastCheck.status() == PartDragStatus.CHANGED) {
                return new PartDragSettlement(PartDragSettlementStatus.CHANGED, lastCheck);
            }
            if (lastCheck.status() == PartDragStatus.MISMATCH) {
                return new PartDragSettlement(PartDragSettlementStatus.MISMATCH, lastCheck);
            }
            if (lastCheck.status() == PartDragStatus.UNAVAILABLE) {
                return new PartDragSettlement(PartDragSettlementStatus.UNAVAILABLE, lastCheck);
            }
        }
        return new PartDragSettlement(PartDragSettlementStatus.RETRY, lastCheck);
    }

    static String partEvidence(
            final PartPair pair, final Optional<String> parentBefore, final Optional<String> parentAfter) {
        return "sourceId=" + pair.source().id() + ":targetId=" + pair.target().id() + ":parentBefore="
                + parentText(parentBefore) + ":parentAfter=" + parentText(parentAfter);
    }

    static ActorPart partOf(final PartModelSnapshot snapshot, final String id) {
        if (snapshot == null || id == null) return null;
        final Map<String, ActorPart> byId = partIndex(snapshot);
        return byId == null ? null : byId.get(id);
    }

    static boolean sourceEligible(final ActorPart part) {
        return part != null
                && !INTERNAL_ROOT_PART_ID.equals(part.id())
                && part.parentId().isPresent();
    }

    static boolean targetEligible(final ActorPart part) {
        return part != null
                && !INTERNAL_ROOT_PART_ID.equals(part.id())
                && part.parentId().isPresent();
    }

    /** Validates the complete read-only relation snapshot before any UI gesture is attempted. */
    static Map<String, ActorPart> partIndex(final PartModelSnapshot snapshot) {
        if (snapshot == null
                || snapshot.modelId() == null
                || snapshot.modelId().isBlank()
                || snapshot.parts() == null
                || snapshot.parts().isEmpty()) {
            return null;
        }
        final Map<String, ActorPart> byId = new LinkedHashMap<>();
        for (final ActorPart part : snapshot.parts()) {
            if (part == null || part.id() == null || part.id().isBlank() || byId.put(part.id(), part) != null) {
                return null;
            }
            final Set<String> childIds = new HashSet<>();
            for (final String childId : part.childIds()) {
                if (childId == null || childId.isBlank() || !childIds.add(childId)) return null;
            }
        }
        for (final ActorPart part : byId.values()) {
            if (part.parentId().filter(parent -> !byId.containsKey(parent)).isPresent()) return null;
            for (final String childId : part.childIds()) {
                final ActorPart child = byId.get(childId);
                if (child == null || child.parentId().filter(part.id()::equals).isEmpty()) return null;
            }
        }
        for (final ActorPart part : byId.values()) {
            final Set<String> visited = new HashSet<>();
            ActorPart current = part;
            while (current.parentId().isPresent()) {
                if (!visited.add(current.id())) return null;
                current = byId.get(current.parentId().orElseThrow());
                if (current == null) return null;
            }
        }
        return byId;
    }

    static PartPairLayout locatePartPair(final String sourceName, final String targetName) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree = WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final PartPairLayout local = locatePartPairRows(table, tree, sourceName, targetName);
            if (local == null) continue;
            try {
                return local.withScreenPoints(table);
            } catch (java.awt.IllegalComponentStateException notShowing) {
                // The palette was replaced between layout and conversion; re-discover it.
            }
        }
        return null;
    }

    /**
     * Computes one final shared viewport layout. Package-private for the headless Swing test: it
     * exercises the same expansion, scrolling, row/path and point checks as the native actor.
     */
    static PartPairLayout locatePartPairRows(
            final javax.swing.JTable table,
            final javax.swing.JTree tree,
            final String sourceName,
            final String targetName) {
        if (table == null || tree == null || sourceName == null || targetName == null || sourceName.equals(targetName))
            return null;
        javax.swing.tree.TreePath sourcePath = uniquePartPath(tree, sourceName);
        javax.swing.tree.TreePath targetPath = uniquePartPath(tree, targetName);
        if (sourcePath == null || targetPath == null || sourcePath.equals(targetPath)) return null;
        expandAncestors(tree, sourcePath);
        expandAncestors(tree, targetPath);
        tree.expandPath(targetPath);

        sourcePath = uniquePartPath(tree, sourceName);
        targetPath = uniquePartPath(tree, targetName);
        PartRowLocation source = partRowLocation(table, tree, sourcePath);
        PartRowLocation target = partRowLocation(table, tree, targetPath);
        if (source == null || target == null) return null;
        table.scrollRectToVisible(target.cell());

        // Target scrolling can move the source. Resolve both again before considering a gesture.
        sourcePath = uniquePartPath(tree, sourceName);
        targetPath = uniquePartPath(tree, targetName);
        source = partRowLocation(table, tree, sourcePath);
        target = partRowLocation(table, tree, targetPath);
        if (source == null || target == null) return null;
        if (!fullyVisible(table, source.cell()) || !fullyVisible(table, target.cell())) {
            final java.awt.Rectangle union = source.cell().union(target.cell());
            if (union.height > table.getVisibleRect().height || union.width > table.getVisibleRect().width) return null;
            table.scrollRectToVisible(union);
            // A final read is required after the shared scroll as well; no point from before it
            // may be reused for Robot input.
            sourcePath = uniquePartPath(tree, sourceName);
            targetPath = uniquePartPath(tree, targetName);
            source = partRowLocation(table, tree, sourcePath);
            target = partRowLocation(table, tree, targetPath);
        }
        if (source == null
                || target == null
                || !fullyVisible(table, source.cell())
                || !fullyVisible(table, target.cell())) {
            return null;
        }
        return new PartPairLayout(table, tree, table.getModel(), tree.getModel(), source, target, table.isShowing());
    }

    /** Returns the already-verified target screen point without applying an unverified offset. */
    static java.awt.Point partGestureTargetPoint(final PartPairLayout layout) {
        if (layout == null || layout.target() == null || layout.target().screenPoint() == null) return null;
        return new java.awt.Point(layout.target().screenPoint());
    }

    static javax.swing.tree.TreePath uniquePartPath(final javax.swing.JTree tree, final String name) {
        final List<javax.swing.tree.TreePath> paths = WindowsMeshEditValidationProbe.findTreePaths(tree, name);
        return paths.size() == 1 ? paths.get(0) : null;
    }

    static PartRowLocation partRowLocation(
            final javax.swing.JTable table, final javax.swing.JTree tree, final javax.swing.tree.TreePath path) {
        if (path == null) return null;
        final int row = tree.getRowForPath(path);
        if (row < 0 || row >= table.getRowCount() || !path.equals(tree.getPathForRow(row))) return null;
        int column = 0;
        int widest = -1;
        for (int index = 0; index < table.getColumnCount(); index++) {
            final int width = table.getColumnModel().getColumn(index).getWidth();
            if (width > widest) {
                widest = width;
                column = index;
            }
        }
        final java.awt.Rectangle cell = table.getCellRect(row, column, true);
        final java.awt.Rectangle viewport = table.getVisibleRect();
        if (cell.isEmpty() || viewport.isEmpty()) return null;
        final java.awt.Point local =
                new java.awt.Point(cell.x + Math.max(1, cell.width / 2), cell.y + Math.max(1, cell.height / 2));
        if (table.rowAtPoint(local) != row) return null;
        final Object node = path.getLastPathComponent();
        return new PartRowLocation(path, node, treeLabel(tree, node), row, column, cell, viewport, local, null);
    }

    /**
     * The Parts tree-table row's centre in screen coordinates on its widest column.
     *
     * <p>Runs on the EDT. The embedded {@code JTree} is a renderer component — never showing —
     * so the row index comes from its model and the press point from the owner table's cell
     * rect; the widest column is the name column, the only place a row drag can start.</p>
     *
     * @return {@code [x, y, rowHeight]} in screen coordinates, or {@code null} when no Parts
     *     tree-table row renders {@code displayName}
     */
    static int[] partsRowPoint(final String displayName) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree = WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final java.util.List<javax.swing.tree.TreePath> paths =
                    WindowsMeshEditValidationProbe.findTreePaths(tree, displayName);
            if (paths.size() != 1) continue;
            // Collapsed parents give the path no row at all — expand first.
            tree.expandPath(paths.get(0).getParentPath());
            final int row = tree.getRowForPath(paths.get(0));
            if (row < 0 || row >= table.getRowCount()) continue;
            int column = 0;
            int widest = -1;
            for (int index = 0; index < table.getColumnCount(); index++) {
                final int width = table.getColumnModel().getColumn(index).getWidth();
                if (width > widest) {
                    widest = width;
                    column = index;
                }
            }
            final java.awt.Rectangle rect = table.getCellRect(row, column, true);
            if (rect.isEmpty()) continue;
            table.scrollRectToVisible(rect);
            final java.awt.Point centre =
                    new java.awt.Point(rect.x + Math.max(1, rect.width / 2), rect.y + Math.max(1, rect.height / 2));
            SwingUtilities.convertPointToScreen(centre, table);
            return new int[] {centre.x, centre.y, rect.height};
        }
        return null;
    }

    static javax.swing.JTable findPartsTable(final java.awt.Component component) {
        if (component instanceof javax.swing.JTable table
                && table.getClass().getName().contains("PartsTreeTable")) {
            return table;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JTable found = findPartsTable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Reports whether the Parts tree supports Swing drag-and-drop at all.
     *
     * <p>Only consulted when both drops missed: if the host's tree has no drag gesture or no
     * drop handler, no amount of pointer accuracy will produce an entry and the step needs a
     * different interaction. The report is bounded to the trees that look like the Parts
     * palette.</p>
     */
    static String partsTreeDnD() {
        final StringBuilder out = new StringBuilder(2048);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectTreeDnD(window, out, 0);
        }
        return out.length() > 0 ? out.toString() : "no-trees";
    }

    static void collectTreeDnD(final java.awt.Component component, final StringBuilder out, final int depth) {
        if (depth > UI_MAP_MAX_DEPTH || !component.isVisible() || out.length() > 1800) return;
        if (component instanceof javax.swing.JTree tree) {
            final java.awt.Container owner = SwingUtilities.getAncestorOfClass(javax.swing.JTable.class, tree);
            out.append("tree:")
                    .append(tree.getClass().getName())
                    .append(":drag=")
                    .append(tree.getDragEnabled())
                    .append(":drop=")
                    .append(tree.getDropTarget() != null)
                    .append(":handler=")
                    .append(
                            tree.getTransferHandler() == null
                                    ? "none"
                                    : tree.getTransferHandler().getClass().getSimpleName())
                    .append(":owner=")
                    .append(owner == null ? "none" : owner.getClass().getName())
                    .append(';');
        } else if (component instanceof javax.swing.JTable table
                && table.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("treetable")) {
            // A TreeTable embeds its JTree as a cell renderer, so the drag gesture and the
            // transfer handler live on the table — checking only the tree would report a DnD
            // that can never fire.
            out.append("table:")
                    .append(table.getClass().getName())
                    .append(":drag=")
                    .append(table.getDragEnabled())
                    .append(":drop=")
                    .append(table.getDropTarget() != null)
                    .append(":mode=")
                    .append(table.getDropMode())
                    .append(":handler=")
                    .append(
                            table.getTransferHandler() == null
                                    ? "none"
                                    : table.getTransferHandler().getClass().getName())
                    .append(';');
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectTreeDnD(child, out, depth + 1);
            }
        }
    }

    /** Bounded post-gesture readback used to stop the actor on the first actual value change. */
    static ParameterChangeObservation awaitParameterGesture(
            final ParameterStateSnapshot before, final java.util.function.Supplier<ParameterStateSnapshot> edtReadback)
            throws Exception {
        ParameterStateSnapshot latest = before;
        for (int poll = 0; poll < PARAMETER_ACTOR_POLLS; poll++) {
            Thread.sleep(POLL_MILLIS);
            final ParameterStateSnapshot current = readParameterSnapshot(edtReadback);
            final ParameterStateOutcome outcome = compareParameterState(before, current);
            if (outcome != ParameterStateOutcome.UNCHANGED) {
                return new ParameterChangeObservation(outcome, current, outcome.code());
            }
            latest = current;
        }
        return new ParameterChangeObservation(ParameterStateOutcome.UNCHANGED, latest, "gesture-window-expired");
    }

    /**
     * Selects the Parts-tree row for {@code displayName} through the table/tree selection
     * models. The palette forwards tree selection to the Editor selection, which is what
     * the inspector palette renders. Runs on the EDT; returns whether a row was set.
     */
    static boolean selectPartsTreeRow(final String displayName) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree = WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final java.util.List<javax.swing.tree.TreePath> paths =
                    WindowsMeshEditValidationProbe.findTreePaths(tree, displayName);
            if (paths.size() != 1) continue;
            tree.expandPath(paths.get(0).getParentPath());
            final int row = tree.getRowForPath(paths.get(0));
            if (row < 0) continue;
            tree.setSelectionPath(paths.get(0));
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
            return true;
        }
        return false;
    }

    /** The Parts palette table's selected row, or {@code -1}. Runs on the EDT. */
    static int partsTableSelectedRow() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table != null && table.isShowing() && table.getSelectedRow() >= 0) return table.getSelectedRow();
        }
        return -1;
    }

    /** A bounded sample of the labels the Parts tree actually renders. Runs on the EDT. */
    static String partsTreeLabels() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null) continue;
            final javax.swing.JTree tree = WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final ArrayList<String> labels = new ArrayList<>();
            final ArrayDeque<Object> pending = new ArrayDeque<>();
            pending.add(tree.getModel().getRoot());
            while (!pending.isEmpty() && labels.size() < 28) {
                final Object node = pending.removeFirst();
                labels.add(tree.convertValueToText(node, false, false, false, 0, false));
                final int children = tree.getModel().getChildCount(node);
                for (int index = 0; index < children; index++) {
                    pending.addLast(tree.getModel().getChild(node, index));
                }
            }
            return "[" + String.join(",", labels) + "]";
        }
        return "no-parts-tree";
    }

    /** One Robot drag between two screen points, interpolated so the host sees real motion. */
    static void robotDrag(final int fromX, final int fromY, final int toX, final int toY) throws Exception {
        final java.awt.Robot robot = new java.awt.Robot();
        robot.mouseMove(fromX, fromY);
        Thread.sleep(120L);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(180L);
        final int segments = 12;
        for (int segment = 1; segment <= segments; segment++) {
            robot.mouseMove(fromX + (toX - fromX) * segment / segments, fromY + (toY - fromY) * segment / segments);
            Thread.sleep(35L);
        }
        Thread.sleep(180L);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    }

    /**
     * Keeps an automated actor failure terminal while preserving the raw semantic verdict. Manual
     * steps have {@link PartActorOutcome#NOT_RUN} and therefore retain the existing semantic gate.
     */
    static Verdict admitPartStep(final PartActorResult actor, final Verdict semantic) {
        if (semantic == null) return new Verdict(false, "semantic-verdict-unavailable", "");
        if (actor == null || actor.outcome() == PartActorOutcome.NOT_RUN || actor.accepted()) return semantic;
        return new Verdict(
                false,
                "actor-" + actor.code(),
                "actorOutcome=" + actor.outcome().name() + ",actorEvidence=" + actor.evidence() + ",semantic="
                        + semantic.code());
    }

    record PartCandidate(
            String entryId,
            boolean structured,
            boolean complete,
            boolean unstableTarget,
            Set<TargetKey> targetKeys,
            List<RelationObservation> relations,
            boolean hasNonRelationChange) {
        PartCandidate {
            targetKeys = Set.copyOf(targetKeys);
            relations = List.copyOf(relations);
        }

        boolean hasRelation() {
            return !relations.isEmpty();
        }
    }

    static boolean movesToward(final Float oldValue, final Float newValue, final float start, final float end) {
        if (!finite(oldValue) || !finite(newValue) || !within(oldValue, start, end) || !within(newValue, start, end)) {
            return false;
        }
        return end > start ? newValue > oldValue && newValue <= end : newValue < oldValue && newValue >= end;
    }

    static ParameterChangeObservation preserveParameterActorOutcome(
            final ParameterChangeObservation actorObservation, final ParameterChangeObservation followupObservation) {
        return settleParameterObservations(
                actorObservation, followupObservation == null ? List.of() : List.of(followupObservation));
    }

    /**
     * Whether the operator's step has produced what that step is supposed to produce.
     *
     * <p>An ACTION step is closed by the <em>significant</em> entries changing, never by the undo
     * position moving: the host records a selection as an ordinary entry, so closing on the entry
     * count or the position lets the click that precedes an action satisfy the step, and the real
     * action then lands in the next step's window. Undo and Redo are the opposite case — they
     * change the position and add no entry at all — so those steps are closed by the position.</p>
     */
    static boolean hasMoved(
            final Step step,
            final String currentSignificant,
            final long currentPosition,
            final String knownSignificant,
            final long knownPosition) {
        if (currentSignificant == null) return false;
        if (step.kind().equals("ACTION")) return !currentSignificant.equals(knownSignificant);
        // Navigation is only closed by a move in its own direction, measured against the last
        // observed position. A selection also moves the position forwards, so accepting any change
        // would let the click before the operator's real action close an Undo step; and comparing
        // against the window's opening position would miss an undo that follows an edit made
        // inside the same window.
        if (step.kind().equals("UNDO")) return currentPosition < knownPosition;
        if (step.kind().equals("REDO")) return currentPosition > knownPosition;
        return false;
    }

    /** How long this step settles after its signal appears. */
    static long settleMillis(final Step step) {
        return step.kind().equals("ACTION") ? ACTION_SETTLE_MILLIS : NAVIGATION_SETTLE_MILLIS;
    }

    static String paired(final WindowsHistoryManagerValidationProbe.Snapshot snapshot, final String phase) {
        if (snapshot == null) {
            return "{\"type\":\"paired-snapshot\",\"phase\":\"" + json(phase) + "\",\"unavailable\":true}\n";
        }
        return snapshot.pairedJson(phase) + "\n";
    }

    @FunctionalInterface
    interface PartPrePressGuard {
        PartPrePressCheck check() throws Exception;
    }

    @FunctionalInterface
    interface PartPointerReadback {
        Point read() throws Exception;
    }

    record PartPointerEvidence(
            String phase,
            int attempt,
            long commandStartMillis,
            long monotonicNanos,
            String thread,
            Point commandedPoint,
            Point readbackPoint,
            String outcome,
            String reason) {
        PartPointerEvidence {
            if (attempt < 1 || attempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("pointer attempt out of bounds: " + attempt);
            }
            phase = boundedText(phase, MAX_PART_GESTURE_TEXT_LENGTH);
            thread = boundedText(thread, MAX_PART_GESTURE_TEXT_LENGTH);
            commandedPoint = commandedPoint == null ? null : new Point(commandedPoint);
            readbackPoint = readbackPoint == null ? null : new Point(readbackPoint);
            outcome = boundedText(outcome, MAX_PART_GESTURE_TEXT_LENGTH);
            reason = boundedText(reason, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        PartPointerEvidence(String phase, long nanos, String thread, Point cmd, Point read, String out, String rsn) {
            this(phase, 1, System.currentTimeMillis(), nanos, thread, cmd, read, out, rsn);
        }

        PartPointerEvidence(
                String phase, int attempt, long nanos, String thread, Point cmd, Point read, String out, String rsn) {
            this(phase, attempt, System.currentTimeMillis(), nanos, thread, cmd, read, out, rsn);
        }

        static PartPointerEvidence command(final int attempt, final long commandStartMillis, final Point point) {
            return new PartPointerEvidence(
                    "source-command",
                    attempt,
                    commandStartMillis,
                    System.nanoTime(),
                    Thread.currentThread().getName(),
                    point,
                    null,
                    "COMMANDED",
                    "");
        }

        static PartPointerEvidence command(final int attempt, final Point point) {
            return command(attempt, System.currentTimeMillis(), point);
        }

        static PartPointerEvidence command(final Point point) {
            return command(1, point);
        }

        String failureCode() {
            return switch (outcome) {
                case "COMMANDED", "MATCH" -> null;
                case "UNAVAILABLE" -> phase + "-unavailable";
                default -> phase + "-mismatch";
            };
        }

        String json() {
            return "{\"type\":\"part-pointer\",\"phase\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(phase)
                    + "\",\"attempt\":" + attempt + ",\"commandStartMillis\":" + commandStartMillis
                    + ",\"monotonicNanos\":" + monotonicNanos + ",\"thread\":\""
                    + WindowsHistoryNativeUiIngressVerdicts.json(thread)
                    + "\",\"commandedPoint\":" + pointJson(commandedPoint) + ",\"readbackPoint\":"
                    + pointJson(readbackPoint)
                    + ",\"outcome\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(outcome)
                    + "\",\"reason\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(reason) + "\"}\n";
        }
    }

    interface PartGestureInput {
        void mouseMove(int x, int y) throws Exception;

        void pause(long millis) throws Exception;

        void mousePress() throws Exception;

        void mouseRelease() throws Exception;
    }

    enum PartGestureStatus {
        ACCEPTED,
        MISMATCH,
        UNRESOLVED,
        EXCEPTION
    }

    record PartGestureAttempt(
            PartGestureStatus status, String code, PartPrePressCheck prePress, List<String> evidence) {
        PartGestureAttempt {
            Objects.requireNonNull(status, "status");
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    record PartGestureCheck(PartGestureStatus status, String code) {
        PartGestureCheck {
            Objects.requireNonNull(status, "status");
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
        }
    }

    record PartPrePressCheck(boolean ok, String code, PartPairLayout expected, JTable currentTable, JTree currentTree) {
        PartPrePressCheck {
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        static PartPrePressCheck success(
                final PartPairLayout expected, final JTable currentTable, final JTree currentTree) {
            return new PartPrePressCheck(true, "stable-structure", expected, currentTable, currentTree);
        }

        static PartPrePressCheck failure(
                final String code, final PartPairLayout expected, final JTable currentTable, final JTree currentTree) {
            return new PartPrePressCheck(false, code, expected, currentTable, currentTree);
        }
    }

    record PointerAttemptWindow(int attempt, long startMillis, Long endMillis) {
        PointerAttemptWindow {
            if (attempt < 1 || attempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("pointer attempt out of bounds: " + attempt);
            }
        }

        PointerAttemptWindow closedAt(final long endMillis) {
            return new PointerAttemptWindow(attempt, startMillis, endMillis);
        }
    }

    record MouseMovedAttribution(
            Integer observedAttempt,
            int currentAttempt,
            PointerAttemptWindow observedWindow,
            PointerAttemptWindow currentWindow,
            List<PointerAttemptWindow> boundaryWindows,
            String admission,
            boolean firstForAttempt) {
        MouseMovedAttribution {
            if (currentAttempt < 0 || currentAttempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("current pointer attempt out of bounds: " + currentAttempt);
            }
            boundaryWindows = boundaryWindows == null ? List.of() : List.copyOf(boundaryWindows);
            admission = boundedText(admission, MAX_PART_GESTURE_TEXT_LENGTH);
        }
    }

    /**
     * Temporary, non-consuming evidence listener for one Parts gesture. It is deliberately not a
     * global AWT listener: only the discovered JTable and its extracted JTree are observed, and
     * both listeners are removed in the gesture's finally block.
     */
    static final class PartGestureCapture {
        final PartPairLayout layout;
        final String intendedSourceId;
        final String intendedTargetId;
        final List<PartGestureEvent> events = new ArrayList<>();
        final List<String> evidence = new ArrayList<>();
        final Map<Integer, PartGestureEvent> firstMouseMovedByAttempt = new LinkedHashMap<>();
        final Map<Integer, PointerAttemptWindow> pointerAttemptWindows = new LinkedHashMap<>();
        int pointerAttempt;
        final MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent event) {
                recordOnce("actual-press", event);
            }

            @Override
            public void mouseDragged(final MouseEvent event) {
                recordOnce("first-drag", event);
            }

            @Override
            public void mouseMoved(final MouseEvent event) {
                recordMouseMovedOnce(event);
            }

            @Override
            public void mouseReleased(final MouseEvent event) {
                recordOnce("release", event);
            }
        };
        boolean tableInstalled;
        boolean treeInstalled;

        PartGestureCapture(final PartPairLayout layout, final String intendedSourceId, final String intendedTargetId) {
            this.layout = Objects.requireNonNull(layout, "layout");
            this.intendedSourceId = boundedText(intendedSourceId, MAX_PART_GESTURE_TEXT_LENGTH);
            this.intendedTargetId = boundedText(intendedTargetId, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        void recordLayout() {
            addEvidence(layoutJson(layout, intendedSourceId, intendedTargetId));
        }

        void recordPrePress(final PartPrePressCheck check) {
            addEvidence(prePressJson(check, intendedSourceId, intendedTargetId));
        }

        void recordPointer(final PartPointerEvidence pointer) {
            if (pointer != null) addEvidence(pointer.json());
        }

        void beginPointerAttempt(final int attempt) {
            beginPointerAttempt(attempt, System.currentTimeMillis());
        }

        void beginPointerAttempt(final int attempt, final long commandStartMillis) {
            if (attempt < 1 || attempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("pointer attempt out of bounds: " + attempt);
            }
            synchronized (events) {
                final PointerAttemptWindow previous = pointerAttemptWindows.get(pointerAttempt);
                if (previous != null && previous.endMillis() == null) {
                    pointerAttemptWindows.put(pointerAttempt, previous.closedAt(commandStartMillis));
                }
                pointerAttempt = attempt;
                pointerAttemptWindows.put(attempt, new PointerAttemptWindow(attempt, commandStartMillis, null));
            }
        }

        boolean installIfOpen(final AtomicBoolean lifecycleOpen) {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("Parts gesture listeners must install on the EDT");
            }
            if (lifecycleOpen == null || !lifecycleOpen.get()) return false;
            recordLayout();
            // The caller can time out while this EDT callback is queued. Recheck after evidence
            // capture so a late callback cannot install listeners after the gesture has closed.
            if (!lifecycleOpen.get()) return false;
            install();
            return true;
        }

        void install() {
            if (!SwingUtilities.isEventDispatchThread())
                throw new IllegalStateException("Parts gesture listeners must install on the EDT");
            final JTable table = layout.table();
            if (table == null) throw new IllegalStateException("Parts table identity is unavailable");
            tableInstalled = true;
            table.addMouseListener(listener);
            table.addMouseMotionListener(listener);
            if (layout.tree() != null) {
                treeInstalled = true;
                layout.tree().addMouseListener(listener);
                layout.tree().addMouseMotionListener(listener);
            }
        }

        void remove() {
            if (!SwingUtilities.isEventDispatchThread())
                throw new IllegalStateException("Parts gesture listeners must remove on the EDT");
            if (tableInstalled && layout.table() != null) {
                layout.table().removeMouseListener(listener);
                layout.table().removeMouseMotionListener(listener);
                tableInstalled = false;
            }
            if (treeInstalled && layout.tree() != null) {
                layout.tree().removeMouseListener(listener);
                layout.tree().removeMouseMotionListener(listener);
                treeInstalled = false;
            }
        }

        void recordMouseEventForTest(final String phase, final MouseEvent event) {
            if ("mouse-moved".equals(phase)) recordMouseMovedOnce(event);
            else record(phase, event);
        }

        PartGestureCheck checkEvents() {
            synchronized (events) {
                final PartGestureEvent press = eventLocked("actual-press");
                if (press == null) return new PartGestureCheck(PartGestureStatus.UNRESOLVED, "actual-press-missing");
                if (!press.componentMatch()
                        || !press.tableModelMatch()
                        || !press.treeModelMatch()
                        || !press.sourceNodeMatch()
                        || press.row() != layout.source().row()) {
                    return new PartGestureCheck(PartGestureStatus.MISMATCH, "actual-press-target-mismatch");
                }
                final PartGestureEvent drag = eventLocked("first-drag");
                if (drag == null) return new PartGestureCheck(PartGestureStatus.UNRESOLVED, "first-drag-missing");
                if (!drag.componentMatch() || !drag.tableModelMatch() || !drag.treeModelMatch()) {
                    return new PartGestureCheck(PartGestureStatus.MISMATCH, "first-drag-surface-mismatch");
                }
                final PartGestureEvent release = eventLocked("release");
                if (release == null) return new PartGestureCheck(PartGestureStatus.UNRESOLVED, "release-missing");
                if (!release.componentMatch()
                        || !release.tableModelMatch()
                        || !release.treeModelMatch()
                        || !release.targetNodeMatch()) {
                    return new PartGestureCheck(PartGestureStatus.MISMATCH, "release-target-mismatch");
                }
                return new PartGestureCheck(PartGestureStatus.ACCEPTED, "press-drag-release-matched");
            }
        }

        List<String> evidence() {
            synchronized (events) {
                return List.copyOf(evidence);
            }
        }

        void recordOnce(final String phase, final MouseEvent event) {
            synchronized (events) {
                if (eventLocked(phase) != null) return;
            }
            record(phase, event);
        }

        void recordMouseMovedOnce(final MouseEvent event) {
            if (event == null) return;
            synchronized (events) {
                final MouseMovedAttribution attribution = mouseMovedAttribution(event.getWhen());
                final PartGestureEvent firstMouseMoved = describe("mouse-moved", event);
                if (attribution.observedAttempt() == null) {
                    addEvidence(firstMouseMoved.json(layout, intendedSourceId, intendedTargetId, attribution));
                    return;
                }
                if (firstMouseMovedByAttempt.containsKey(attribution.observedAttempt())) return;
                firstMouseMovedByAttempt.put(attribution.observedAttempt(), firstMouseMoved);
                addEvidence(firstMouseMoved.json(layout, intendedSourceId, intendedTargetId, attribution));
            }
        }

        MouseMovedAttribution mouseMovedAttribution(final long eventWhenMillis) {
            final PointerAttemptWindow currentWindow = pointerAttemptWindows.get(pointerAttempt);
            final List<PointerAttemptWindow> containing = new ArrayList<>();
            final List<PointerAttemptWindow> boundary = new ArrayList<>();
            for (final PointerAttemptWindow window : pointerAttemptWindows.values()) {
                final boolean atStart = eventWhenMillis == window.startMillis();
                final boolean atEnd = window.endMillis() != null && eventWhenMillis == window.endMillis();
                if (atStart || atEnd) boundary.add(window);
                if (eventWhenMillis > window.startMillis()
                        && (window.endMillis() == null || eventWhenMillis < window.endMillis())) {
                    containing.add(window);
                }
            }
            if (!boundary.isEmpty() || containing.size() != 1) {
                return new MouseMovedAttribution(
                        null,
                        pointerAttempt,
                        null,
                        currentWindow,
                        boundary,
                        boundary.isEmpty() ? "outside-attempt-window" : "window-boundary-uncertain",
                        false);
            }
            final PointerAttemptWindow observedWindow = containing.get(0);
            return new MouseMovedAttribution(
                    observedWindow.attempt(),
                    pointerAttempt,
                    observedWindow,
                    currentWindow,
                    List.of(),
                    "within-attempt-window",
                    true);
        }

        void record(final String phase, final MouseEvent event) {
            if (event == null) return;
            final PartGestureEvent observed = describe(phase, event);
            synchronized (events) {
                if (eventLocked(phase) != null || events.size() >= 3) return;
                events.add(observed);
                addEvidence(observed.json(layout, intendedSourceId, intendedTargetId));
            }
        }

        PartGestureEvent eventLocked(final String phase) {
            return events.stream()
                    .filter(value -> phase.equals(value.phase()))
                    .findFirst()
                    .orElse(null);
        }

        PartGestureEvent describe(final String phase, final MouseEvent event) {
            final Component source = event.getComponent();
            final JTable table = layout.table();
            final JTree tree = layout.tree();
            Point local = null;
            TreePath path = null;
            int row = -1, column = -1;
            if (source == table && table != null) {
                local = event.getPoint();
                row = table.rowAtPoint(local);
                column = table.columnAtPoint(local);
                path = tree == null ? null : tree.getPathForRow(row);
            } else if (source == tree && tree != null) {
                row = tree.getRowForLocation(event.getX(), event.getY());
                path = tree.getPathForRow(row);
            }
            final Object node = path == null ? null : path.getLastPathComponent();
            Rectangle cell = new Rectangle(), viewport = new Rectangle();
            if (table != null
                    && row >= 0
                    && column >= 0
                    && row < table.getRowCount()
                    && column < table.getColumnCount()) {
                cell = table.getCellRect(row, column, true);
                viewport = table.getVisibleRect();
            }
            final Object sourceModel = source instanceof JTable sourceTable
                    ? sourceTable.getModel()
                    : source instanceof JTree sourceTree ? sourceTree.getModel() : null;
            final boolean tableModelMatch =
                    source instanceof JTable sourceTable && sourceTable.getModel() == layout.tableModel();
            final boolean treeModelMatch = layout.treeModel() != null
                    && layout.tree() != null
                    && layout.tree().getModel() == layout.treeModel();
            return new PartGestureEvent(
                    phase,
                    SwingUtilities.isEventDispatchThread(),
                    Thread.currentThread().getName(),
                    source,
                    sourceModel,
                    event.getWhen(),
                    row,
                    column,
                    path,
                    node,
                    local,
                    new Point(event.getXOnScreen(), event.getYOnScreen()),
                    cell,
                    viewport,
                    source == table,
                    tableModelMatch,
                    treeModelMatch,
                    node == layout.source().node(),
                    node == layout.target().node());
        }

        void addEvidence(final String line) {
            synchronized (events) {
                if (evidence.size() < MAX_PART_GESTURE_EVIDENCE_LINES) evidence.add(line);
            }
        }
    }

    record PartGestureEvent(
            String phase,
            boolean edt,
            String thread,
            Component source,
            Object sourceModel,
            long eventWhenMillis,
            int row,
            int column,
            TreePath path,
            Object node,
            Point local,
            Point screen,
            Rectangle cell,
            Rectangle viewport,
            boolean componentMatch,
            boolean tableModelMatch,
            boolean treeModelMatch,
            boolean sourceNodeMatch,
            boolean targetNodeMatch) {
        PartGestureEvent {
            phase = boundedText(phase, MAX_PART_GESTURE_TEXT_LENGTH);
            thread = boundedText(thread, MAX_PART_GESTURE_TEXT_LENGTH);
            local = local == null ? null : new Point(local);
            screen = screen == null ? null : new Point(screen);
            cell = cell == null ? new Rectangle() : new Rectangle(cell);
            viewport = viewport == null ? new Rectangle() : new Rectangle(viewport);
        }

        String json(final PartPairLayout layout, final String intendedSourceId, final String intendedTargetId) {
            return jsonWithFields(layout, intendedSourceId, intendedTargetId, "", null);
        }

        String json(
                final PartPairLayout layout,
                final String intendedSourceId,
                final String intendedTargetId,
                final int attempt) {
            if (attempt < 1 || attempt > MAX_PART_POINTER_ATTEMPTS) {
                throw new IllegalArgumentException("pointer attempt out of bounds: " + attempt);
            }
            return jsonWithFields(layout, intendedSourceId, intendedTargetId, ",\"attempt\":" + attempt, null);
        }

        String json(
                final PartPairLayout layout,
                final String intendedSourceId,
                final String intendedTargetId,
                final MouseMovedAttribution attribution) {
            Objects.requireNonNull(attribution, "attribution");
            final Integer observedAttempt = attribution.observedAttempt();
            final String attemptField = observedAttempt == null ? "" : ",\"attempt\":" + observedAttempt;
            return jsonWithFields(layout, intendedSourceId, intendedTargetId, attemptField, attribution);
        }

        String jsonWithFields(
                final PartPairLayout layout,
                final String intendedSourceId,
                final String intendedTargetId,
                final String attemptField,
                final MouseMovedAttribution attribution) {
            final JTree tree = layout.tree();
            return "{\"type\":\"part-gesture\",\"phase\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(phase) + "\""
                    + attemptField
                    + ",\"threadEDT\":" + edt + ",\"thread\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(thread)
                    + "\",\"eventSource\":\""
                    + WindowsHistoryNativeUiIngressVerdicts.json(
                            source == null ? "" : source.getClass().getName()) + "\",\"eventSourceIdentityHash\":"
                    + identityHash(source)
                    + ",\"eventSourceModelIdentityHash\":" + identityHash(sourceModel) + ",\"tableIdentityHash\":"
                    + identityHash(layout.table())
                    + ",\"treeIdentityHash\":" + identityHash(layout.tree()) + ",\"tableModelIdentityHash\":"
                    + identityHash(layout.tableModel())
                    + ",\"treeModelIdentityHash\":" + identityHash(layout.treeModel()) + ",\"intendedSourceId\":\""
                    + WindowsHistoryNativeUiIngressVerdicts.json(intendedSourceId)
                    + "\",\"intendedTargetId\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(intendedTargetId)
                    + "\",\"row\":" + row + ",\"column\":" + column
                    + ",\"pathLabel\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(treePathLabel(tree, path))
                    + "\",\"lastNodeLabel\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(treeLabel(tree, node))
                    + "\",\"lastNodeIdentityHash\":" + identityHash(node) + ",\"componentMatch\":" + componentMatch
                    + ",\"tableModelMatch\":" + tableModelMatch + ",\"treeModelMatch\":" + treeModelMatch
                    + ",\"sourceNodeObjectMatch\":" + sourceNodeMatch + ",\"targetNodeObjectMatch\":" + targetNodeMatch
                    + ",\"local\":" + pointJson(local) + ",\"screen\":" + pointJson(screen) + ",\"cell\":"
                    + rectangleJson(cell)
                    + ",\"viewport\":" + rectangleJson(viewport) + ",\"eventWhenMillis\":" + eventWhenMillis
                    + motionEvidenceJson(attribution) + "}\n";
        }

        String motionEvidenceJson(final MouseMovedAttribution attribution) {
            if (attribution == null) return "";
            final Integer obs = attribution.observedAttempt();
            return ",\"observedAttempt\":" + (obs == null ? "null" : obs)
                    + ",\"currentAttempt\":" + attribution.currentAttempt()
                    + ",\"observedAttemptWindow\":" + pointerAttemptWindowJson(attribution.observedWindow())
                    + ",\"currentAttemptWindow\":" + pointerAttemptWindowJson(attribution.currentWindow())
                    + ",\"boundaryAttemptWindows\":" + pointerAttemptWindowsJson(attribution.boundaryWindows())
                    + ",\"motionAdmission\":\"" + WindowsHistoryNativeUiIngressVerdicts.json(attribution.admission())
                    + "\",\"firstForAttempt\":" + attribution.firstForAttempt()
                    + ",\"correlation\":\"callback-window-only\"";
        }
    }

    static String prePressJson(
            final PartPrePressCheck check, final String intendedSourceId, final String intendedTargetId) {
        final PartPairLayout layout = check == null ? null : check.expected();
        final JTable currentTable = check == null ? null : check.currentTable();
        final JTree currentTree = check == null ? null : check.currentTree();
        return "{\"type\":\"part-gesture\",\"phase\":\"pre-press\",\"threadEDT\":"
                + SwingUtilities.isEventDispatchThread()
                + ",\"thread\":\"" + json(Thread.currentThread().getName())
                + "\",\"ok\":" + (check != null && check.ok())
                + ",\"code\":\"" + json(check == null ? "missing-check" : check.code())
                + "\",\"intendedSourceId\":\"" + json(intendedSourceId) + "\",\"intendedTargetId\":\""
                + json(intendedTargetId)
                + "\",\"expectedTableIdentityHash\":" + identityHash(layout == null ? null : layout.table())
                + ",\"expectedTreeIdentityHash\":" + identityHash(layout == null ? null : layout.tree())
                + ",\"expectedTableModelIdentityHash\":" + identityHash(layout == null ? null : layout.tableModel())
                + ",\"expectedTreeModelIdentityHash\":" + identityHash(layout == null ? null : layout.treeModel())
                + ",\"expectedTableShowing\":" + (layout != null && layout.table() != null && layout.tableShowing())
                + ",\"currentTableIdentityHash\":" + identityHash(currentTable)
                + ",\"currentTreeIdentityHash\":" + identityHash(currentTree)
                + ",\"currentTableModelIdentityHash\":"
                + identityHash(currentTable == null ? null : currentTable.getModel())
                + ",\"currentTreeModelIdentityHash\":"
                + identityHash(currentTree == null ? null : currentTree.getModel())
                + ",\"currentTableShowing\":" + (currentTable != null && currentTable.isShowing())
                + ",\"source\":"
                + rowJson(layout == null ? null : layout.source(), layout == null ? null : layout.tree())
                + ",\"target\":"
                + rowJson(layout == null ? null : layout.target(), layout == null ? null : layout.tree()) + "}\n";
    }

    static String partSurfaceJson(final JTable table) {
        Window window = null;
        try {
            window = table == null ? null : SwingUtilities.getWindowAncestor(table);
        } catch (RuntimeException ignored) {
        }
        Point tableLocation = null;
        if (table != null && table.isShowing()) {
            try {
                tableLocation = new Point(table.getLocationOnScreen());
            } catch (RuntimeException ignored) {
            }
        }
        Rectangle windowBounds = null;
        Insets windowInsets = null;
        if (window != null) {
            try {
                windowBounds = new Rectangle(window.getBounds());
            } catch (RuntimeException ignored) {
            }
            try {
                windowInsets = window.getInsets();
            } catch (RuntimeException ignored) {
            }
        }
        GraphicsConfiguration gc = null;
        try {
            if (table != null) gc = table.getGraphicsConfiguration();
            if (gc == null && window != null) gc = window.getGraphicsConfiguration();
        } catch (RuntimeException ignored) {
        }
        Rectangle graphicsBounds = null;
        AffineTransform defaultTransform = null;
        if (gc != null) {
            try {
                graphicsBounds = new Rectangle(gc.getBounds());
            } catch (RuntimeException ignored) {
            }
            try {
                defaultTransform = new AffineTransform(gc.getDefaultTransform());
            } catch (RuntimeException ignored) {
            }
        }
        return "{\"tableLocationOnScreen\":" + pointJson(tableLocation)
                + ",\"windowBounds\":" + rectangleJson(windowBounds) + ",\"windowInsets\":" + insetsJson(windowInsets)
                + ",\"graphicsConfigurationBounds\":" + rectangleJson(graphicsBounds)
                + ",\"graphicsConfigurationDefaultTransform\":" + transformJson(defaultTransform) + "}";
    }

    static String pointerAttemptWindowJson(final PointerAttemptWindow window) {
        if (window == null) return "null";
        return "{\"attempt\":" + window.attempt() + ",\"startMillis\":" + window.startMillis() + ",\"endMillis\":"
                + (window.endMillis() == null ? "null" : window.endMillis()) + "}";
    }

    static String pointerAttemptWindowsJson(final List<PointerAttemptWindow> windows) {
        if (windows == null || windows.isEmpty()) return "[]";
        final StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < windows.size(); index++) {
            if (index > 0) json.append(',');
            json.append(pointerAttemptWindowJson(windows.get(index)));
        }
        return json.append(']').toString();
    }

    /** Immutable read-only Part facts used by the Parts actor. */
    record ActorPart(String id, String name, Optional<String> parentId, List<String> childIds) {
        ActorPart {
            Objects.requireNonNull(id, "id");
            parentId = parentId == null ? Optional.empty() : parentId;
            childIds = childIds == null ? List.of() : List.copyOf(childIds);
        }
    }

    record PartModelSnapshot(String modelId, List<ActorPart> parts) {
        PartModelSnapshot {
            Objects.requireNonNull(modelId, "modelId");
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    record PartPair(String modelId, ActorPart source, ActorPart target) {
        PartPair {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }
    }

    enum PartDragStatus {
        CHANGED,
        NO_CHANGE,
        MISMATCH,
        UNAVAILABLE
    }

    @FunctionalInterface
    interface PartDragReadback {
        PartDragCheck read(int poll) throws Exception;
    }

    enum PartDragSettlementStatus {
        CHANGED,
        MISMATCH,
        UNAVAILABLE,
        RETRY
    }

    record PartDragSettlement(PartDragSettlementStatus status, PartDragCheck lastCheck) {
        PartDragSettlement {
            Objects.requireNonNull(status, "status");
        }
    }

    record PartDragCheck(
            PartDragStatus status,
            String code,
            String sourceId,
            String targetId,
            Optional<String> parentBefore,
            Optional<String> parentAfter) {
        PartDragCheck {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(code, "code");
            parentBefore = parentBefore == null ? Optional.empty() : parentBefore;
            parentAfter = parentAfter == null ? Optional.empty() : parentAfter;
        }

        String evidence() {
            return "code=" + code + ":sourceId=" + sourceId + ":targetId=" + targetId + ":parentBefore="
                    + parentText(parentBefore) + ":parentAfter=" + parentText(parentAfter);
        }
    }

    enum PartActorOutcome {
        NOT_RUN,
        CHANGED,
        MISMATCH,
        UNRESOLVED,
        EXCEPTION
    }

    record PartActorResult(PartActorOutcome outcome, String code, String evidence) {
        PartActorResult {
            Objects.requireNonNull(outcome, "outcome");
            code = boundedText(code, MAX_PART_GESTURE_TEXT_LENGTH);
            evidence = boundedText(evidence, MAX_PART_GESTURE_TEXT_LENGTH);
        }

        static PartActorResult notRun() {
            return new PartActorResult(PartActorOutcome.NOT_RUN, "not-run", "");
        }

        static PartActorResult fromActorResult(final String result) {
            final String safe = result == null ? "" : result;
            if (safe.startsWith("changed:")) return new PartActorResult(PartActorOutcome.CHANGED, "changed", safe);
            if (safe.startsWith("mismatch:")) return new PartActorResult(PartActorOutcome.MISMATCH, "mismatch", safe);
            if (safe.startsWith("unresolved:")) {
                return new PartActorResult(PartActorOutcome.UNRESOLVED, "unresolved", safe);
            }
            if (safe.startsWith("failed:") || "interrupted".equals(safe)) return exception("actor-exception", safe);
            return exception("actor-result-unclassified", safe);
        }

        static PartActorResult exception(final String code, final String evidence) {
            return new PartActorResult(PartActorOutcome.EXCEPTION, code, evidence);
        }

        boolean accepted() {
            return outcome == PartActorOutcome.CHANGED;
        }

        String json(final String step) {
            return "{\"type\":\"part-actor-outcome\",\"step\":\"" + json(step)
                    + "\",\"outcome\":\"" + json(outcome.name()) + "\",\"code\":\"" + json(code)
                    + "\",\"accepted\":" + accepted() + ",\"evidence\":\"" + json(evidence) + "\"}\n";
        }
    }

    record PartGesturePreparation(
            PartModelSnapshot model, PartPairLayout layout, String significantSequence, String reason) {
        boolean ready() {
            return layout != null && reason != null && reason.isBlank();
        }

        static PartGesturePreparation unavailable(final PartModelSnapshot model, final String reason) {
            return new PartGesturePreparation(model, null, null, reason);
        }
    }

    record PartRowLocation(
            TreePath path,
            Object node,
            String label,
            int row,
            int column,
            Rectangle cell,
            Rectangle viewport,
            Point localPoint,
            Point screenPoint) {
        PartRowLocation {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(node, "node");
            label = boundedText(label, MAX_PART_GESTURE_TEXT_LENGTH);
            cell = new Rectangle(Objects.requireNonNull(cell, "cell"));
            viewport = new Rectangle(Objects.requireNonNull(viewport, "viewport"));
            localPoint = new Point(Objects.requireNonNull(localPoint, "localPoint"));
            screenPoint = screenPoint == null ? null : new Point(screenPoint);
        }

        PartRowLocation(TreePath p, int r, int col, Rectangle cell, Rectangle vp, Point loc, Point scr) {
            this(
                    p,
                    p == null ? null : p.getLastPathComponent(),
                    p == null ? "" : String.valueOf(p.getLastPathComponent()),
                    r,
                    col,
                    cell,
                    vp,
                    loc,
                    scr);
        }

        PartRowLocation withScreenPoint(final Point point) {
            return new PartRowLocation(path, node, label, row, column, cell, viewport, localPoint, point);
        }
    }

    record PartPairLayout(
            JTable table,
            JTree tree,
            TableModel tableModel,
            TreeModel treeModel,
            PartRowLocation source,
            PartRowLocation target,
            boolean tableShowing) {
        PartPairLayout {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(target, "target");
        }

        PartPairLayout(final PartRowLocation source, final PartRowLocation target) {
            this(null, null, null, null, source, target, false);
        }

        PartPairLayout withScreenPoints(final JTable table) {
            return new PartPairLayout(
                    this.table,
                    this.tree,
                    this.tableModel,
                    this.treeModel,
                    source.withScreenPoint(toScreenPoint(table, source.localPoint())),
                    target.withScreenPoint(toScreenPoint(table, target.localPoint())),
                    tableShowing);
        }
    }
}
