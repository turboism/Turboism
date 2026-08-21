package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.mesh.MeshEdgeKind;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditResult;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshPointPosition;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Manual-test-only public-SDK probe for exact-host direct mesh authoring. */
public final class WindowsMeshEditValidationProbe implements CubismPlugin {

    static final String MODE_PROPERTY = "turboism.meshEditValidation.mode";
    static final String EXIT_PROPERTY = "turboism.validation.exitOnComplete";
    static final long SNAPSHOT_TIMEOUT_MILLIS = 120_000L;
    static final long SAVE_TIMEOUT_MILLIS = 30_000L;
    static final long SAVE_POLL_MILLIS = 100L;
    static final int SAVE_STABLE_SAMPLES = 3;
    private static final float EPSILON = 0.0001F;

    private final AtomicReference<MeshSnapshot> failureBaseline = new AtomicReference<>();
    private final AtomicReference<byte[]> persistenceFileBaseline = new AtomicReference<>();
    private volatile boolean persistenceFileWritten;
    private PluginContext context;
    private Thread worker;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Windows mesh edit validation probe initialized");
    }

    @Override
    public void enable() {
        worker = new Thread(this::run, "turboism-mesh-edit-validation");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void disable() {
        if (worker != null) worker.interrupt();
    }

    private void run() {
        final String mode = System.getProperty(MODE_PROPERTY, "matrix");
        final Path result = context.paths().stateDir().resolve("mesh-edit-host-validation.properties");
        final List<String> report = new ArrayList<>();
        report.add("schemaVersion=1");
        report.add("runId=" + safe(System.getProperty("turboism.validation.runId", "unknown")));
        report.add("mode=" + safe(mode));
        boolean passed = false;
        try {
            switch (mode) {
                case "matrix" -> runMatrix(report);
                case "persistence" -> runPersistence(report);
                default -> throw new IllegalArgumentException("unsupported mesh validation mode: " + mode);
            }
            passed = true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            report.add("error=" + safe(failureDescription(failure)));
            cleanupAfterFailure(report);
        }
        report.add("status=" + (passed ? "PASS" : "FAIL"));
        try {
            Files.createDirectories(result.getParent());
            Files.writeString(
                result,
                String.join(System.lineSeparator(), report) + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            context.logger().info(
                "MESH_EDIT_HOST_VALIDATION_RESULT status=" + (passed ? "PASS" : "FAIL")
                    + " mode=" + mode + " result=" + result
            );
        } catch (Exception publicationFailure) {
            context.logger().error("Mesh edit validation result could not be written", publicationFailure);
        } finally {
            if (Boolean.getBoolean(EXIT_PROPERTY)) requestHostClose();
        }
    }

    private void runMatrix(final List<String> report) throws Exception {
        final MeshEditService edit = context.meshEdit();
        final MeshSnapshot original = awaitEditableMesh(report);
        failureBaseline.set(original);
        require(report, "fixture.points", original.points().size() >= 2, original.toString());
        require(report, "fixture.edges", !original.edges().isEmpty(), original.toString());
        final MeshPointPosition addedPosition = distinctPosition(original);

        final MeshSnapshot afterAddPoint = mutate(
            report, "addPoint", original,
            () -> edit.addPoints(List.of(addedPosition)),
            snapshot -> isExactPointAddition(original, snapshot, addedPosition)
        );
        final MeshPointRef added = discoverAddedPoint(original, afterAddPoint, addedPosition);
        undoRedo(report, "addPoint", original, afterAddPoint);

        final MeshPointRef moveSource = pointWithConnectedEdge(original);
        final MeshPointRef moved = movedPoint(moveSource, afterAddPoint);
        final MeshSnapshot afterMove = mutate(
            report, "movePoint", afterAddPoint,
            () -> edit.movePoints(List.of(moved)),
            snapshot -> isExactPointMove(afterAddPoint, snapshot, moved)
        );
        undoRedo(report, "movePoint", afterAddPoint, afterMove);
        require(
            report,
            "movePoint.connectedEdges",
            connectedEdges(original, moveSource.id()).equals(connectedEdges(afterMove, moveSource.id())),
            "before=" + connectedEdges(original, moveSource.id())
                + " after=" + connectedEdges(afterMove, moveSource.id())
        );

        final MeshPointRef addEdgePartner = chooseEdgePartner(afterMove, added.id());
        final MeshEdgeRef addedEdge = new MeshEdgeRef(added.id(), addEdgePartner.id(), MeshEdgeKind.INNER);
        final MeshSnapshot afterAddEdge = mutate(
            report, "addEdge", afterMove,
            () -> edit.addEdges(List.of(addedEdge)),
            snapshot -> isExactEdgeAddition(afterMove, snapshot, addedEdge)
        );
        undoRedo(report, "addEdge", afterMove, afterAddEdge);

        final MeshSnapshot afterDeleteEdge = mutate(
            report, "deleteEdge", afterAddEdge,
            () -> edit.deleteEdges(List.of(addedEdge)),
            snapshot -> isExactEdgeDeletion(afterAddEdge, snapshot, addedEdge)
        );
        undoRedo(report, "deleteEdge", afterAddEdge, afterDeleteEdge);

        final MeshSnapshot afterDeletePoint = mutate(
            report, "deletePoint", afterDeleteEdge,
            () -> edit.deletePoints(List.of(added)),
            snapshot -> isExactPointDeletion(afterDeleteEdge, snapshot, added.id())
        );
        undoRedo(report, "deletePoint", afterDeleteEdge, afterDeletePoint);

        executeCommand(report, "cleanup.deletePoint", EditorCommand.UNDO);
        require(
            report,
            "cleanup.afterDeletePointUndo",
            awaitSnapshot(afterDeleteEdge::equals, "cleanup delete point undo").equals(afterDeleteEdge),
            afterDeleteEdge.toString()
        );
        executeCommand(report, "cleanup.deleteEdge", EditorCommand.UNDO);
        require(
            report,
            "cleanup.afterDeleteEdgeUndo",
            awaitSnapshot(afterAddEdge::equals, "cleanup delete edge undo").equals(afterAddEdge),
            afterAddEdge.toString()
        );
        executeCommand(report, "cleanup.addEdge", EditorCommand.UNDO);
        require(
            report,
            "cleanup.afterAddEdgeUndo",
            awaitSnapshot(afterMove::equals, "cleanup add edge undo").equals(afterMove),
            afterMove.toString()
        );
        executeCommand(report, "cleanup.movePoint", EditorCommand.UNDO);
        require(
            report,
            "cleanup.afterMoveUndo",
            awaitSnapshot(afterAddPoint::equals, "cleanup move point undo").equals(afterAddPoint),
            afterAddPoint.toString()
        );
        executeCommand(report, "cleanup.addPoint", EditorCommand.UNDO);
        final MeshSnapshot restored = awaitSnapshot(original::equals, "cleanup add point undo");
        require(report, "cleanup.restored", restored.equals(original), restored.toString());
        finishMeshEditIfActive(report);
        require(
            report,
            "cleanup.meshEditorExited",
            context.meshEdit().snapshot().points().isEmpty(),
            context.meshEdit().snapshot().toString()
        );
        report.add("original.points=" + original.points().size());
        report.add("original.edges=" + original.edges().size());
        report.add("assignedPointId=" + added.id());
        failureBaseline.set(null);
    }

    private void cleanupAfterFailure(final List<String> report) {
        try {
            MeshSnapshot current = context.meshEdit().snapshot();
            if (current.points().isEmpty()) return;
            final MeshSnapshot baseline = failureBaseline.get();
            if (baseline != null) {
                for (int attempts = 0; attempts < 12 && !current.equals(baseline); attempts++) {
                    final EditorCommandResult undo = context.editorCommands().execute(EditorCommand.UNDO);
                    report.add("failureCleanup.undo." + attempts + "=" + undo.status());
                    if (!undo.executed()) break;
                    current = awaitCleanupSnapshotChange(current, 5_000L);
                }
                report.add("failureCleanup.restored=" + current.equals(baseline));
                if (!current.equals(baseline)) {
                    report.add("failureCleanup.exitSkipped=baseline-not-restored");
                    return;
                }
            }
            final EditorCommandResult exit = context.editorCommands().execute(
                EditorCommand.START_OR_END_MESH_EDITOR
            );
            report.add("failureCleanup.meshExit=" + exit.status());
            final long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline && !context.meshEdit().snapshot().points().isEmpty()) {
                Thread.sleep(100L);
            }
            report.add("failureCleanup.meshInactive=" + context.meshEdit().snapshot().points().isEmpty());
        } catch (Exception failure) {
            report.add("failureCleanup.error=" + safe(failureDescription(failure)));
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            restorePersistenceFileAfterFailure(report);
            failureBaseline.set(null);
        }
    }

    private void restorePersistenceFileAfterFailure(final List<String> report) {
        final byte[] original = persistenceFileBaseline.getAndSet(null);
        try {
            if (persistenceFileWritten && original != null) {
                Files.write(
                    fixturePath(), original,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
                );
                report.add("failureCleanup.fixtureBytesRestored=true");
            }
        } catch (Exception failure) {
            report.add("failureCleanup.fixtureRestoreError=" + safe(failureDescription(failure)));
        } finally {
            persistenceFileWritten = false;
        }
    }

    private MeshSnapshot awaitCleanupSnapshotChange(
        final MeshSnapshot before,
        final long timeoutMillis
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        MeshSnapshot current = context.meshEdit().snapshot();
        while (System.nanoTime() < deadline && current.equals(before)) {
            Thread.sleep(100L);
            current = context.meshEdit().snapshot();
        }
        return current;
    }

    private void runPersistence(final List<String> report) throws Exception {
        final Path fixture = fixturePath();
        persistenceFileBaseline.set(Files.readAllBytes(fixture));
        persistenceFileWritten = false;
        final MeshEditService edit = context.meshEdit();
        final MeshSnapshot before = awaitEditableMesh(report);
        failureBaseline.set(before);
        final MeshPointPosition position = distinctPosition(before);
        final MeshEditResult addedResult = edit.addPoints(List.of(position));
        requireApplied(report, "persist.addPoint", addedResult);
        final MeshSnapshot after = awaitSnapshot(
            snapshot -> snapshot.points().size() == before.points().size() + 1,
            "persist point addition"
        );
        final MeshPointRef added = discoverAddedPoint(before, after, position);
        finishMeshEditIfActive(report);
        final SaveConfirmation firstSave = saveFixture(report, "saveWritten");
        persistenceFileWritten = firstSave.confirmed();
        require(report, "persist.saveWritten", firstSave.confirmed(), firstSave.toString());
        report.add(firstSave.report("saveWritten."));

        final EditorCommandResult reloadWritten = context.editorCommands().execute(EditorCommand.RELOAD_MODEL);
        require(report, "persist.reloadWritten", reloadWritten.executed(), reloadWritten.toString());
        final MeshSnapshot persisted = awaitEditableMesh(report);
        require(
            report,
            "persist.reopened",
            persisted.equals(after),
            "expected=" + after + " actual=" + persisted
        );

        final MeshPointRef persistedAdded = point(persisted, added.id()).orElseThrow();
        final MeshEditResult removed = edit.deletePoints(List.of(persistedAdded));
        requireApplied(report, "persist.cleanupDelete", removed);
        final MeshSnapshot restored = awaitSnapshot(
            snapshot -> snapshot.points().size() == before.points().size()
                && snapshot.edges().size() == before.edges().size()
                && point(snapshot, added.id()).isEmpty(),
            "persist cleanup"
        );
        finishMeshEditIfActive(report);
        final SaveConfirmation cleanupSave = saveFixture(report, "saveRestored");
        require(report, "persist.saveRestored", cleanupSave.confirmed(), cleanupSave.toString());
        report.add(cleanupSave.report("saveRestored."));

        final EditorCommandResult reloadRestored = context.editorCommands().execute(EditorCommand.RELOAD_MODEL);
        require(report, "persist.reloadRestored", reloadRestored.executed(), reloadRestored.toString());
        final MeshSnapshot finalState = awaitEditableMesh(report);
        require(
            report,
            "persist.finalRestored",
            finalState.equals(before),
            "expected=" + before + " actual=" + finalState
        );
        finishMeshEditIfActive(report);
        require(
            report,
            "persist.finalMeshEditorExited",
            context.meshEdit().snapshot().points().isEmpty(),
            context.meshEdit().snapshot().toString()
        );
        report.add("persist.addedPointId=" + added.id());
        report.add("persist.restoredPoints=" + restored.points().size());
        report.add("persist.restoredEdges=" + restored.edges().size());
        failureBaseline.set(null);
        persistenceFileBaseline.set(null);
        persistenceFileWritten = false;
    }

    private MeshSnapshot awaitEditableMesh(final List<String> report) throws Exception {
        awaitVisibleModelWindow();
        awaitModelingDocument();
        MeshSnapshot snapshot = context.meshEdit().snapshot();
        if (!snapshot.points().isEmpty()) {
            report.add("meshEntry=already-active");
            return snapshot;
        }
        final SelectionTarget target = selectFirstArtMesh(report);
        report.add("detail.selection.snapshot=" + safe(
            context.cubism().runtime().selection().toString()
        ));
        EditorCommandResult entered = new EditorCommandResult(
            EditorCommandResult.Status.INVALID_STATE,
            EditorCommand.START_OR_END_MESH_EDITOR.id()
        );
        final long entryDeadline = System.nanoTime() + 30_000_000_000L;
        int attempts = 0;
        while (System.nanoTime() < entryDeadline && !entered.executed()) {
            attempts++;
            entered = context.editorCommands().execute(EditorCommand.START_OR_END_MESH_EDITOR);
            if (!entered.executed()) {
                onEdt(() -> {
                    selectTreePath(target.displayName());
                    return null;
                });
                Thread.sleep(500L);
            }
        }
        report.add("meshEntry.attempts=" + attempts);
        require(report, "meshEntry.command", entered.executed(), entered.toString());
        report.add("meshEntry=command");
        return awaitSnapshot(value -> !value.points().isEmpty(), "mesh editor entry");
    }

    private void awaitVisibleModelWindow() throws Exception {
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        Exception unavailable = null;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                final boolean visible = onEdt(() -> {
                    for (Frame frame : Frame.getFrames()) {
                        if (frame.isShowing() && frame.isDisplayable()
                            && frame.getTitle() != null && frame.getTitle().contains(".cmo3")) {
                            return true;
                        }
                    }
                    return false;
                });
                if (visible) return;
            } catch (IllegalStateException failure) {
                unavailable = failure;
            }
            Thread.sleep(250L);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("model-window wait interrupted");
        }
        throw unavailable == null
            ? new IllegalStateException("visible Cubism model window did not become ready")
            : unavailable;
    }

    private void awaitModelingDocument() throws Exception {
        Exception unavailable = null;
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                onEdt(() -> context.cubism().model().active().drawables().all().size());
                return;
            } catch (Exception failure) {
                unavailable = failure;
                Thread.sleep(250L);
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("modeling-document wait interrupted");
        }
        throw unavailable == null
            ? new IllegalStateException("modeling document did not become active")
            : unavailable;
    }

    private SelectionTarget selectFirstArtMesh(final List<String> report) throws Exception {
        executeCommand(report, "selection.showPartsPalette", EditorCommand.SHOW_PARTS_PALETTE);
        final SelectionTarget target = onEdt(() -> uniqueSelectionTarget(
            context.cubism().model().active().drawables().all()
        ));
        SelectionAttempt attempt = new SelectionAttempt(
            false, "none", target.displayName(), -1, -1, -1, -1, -1
        );
        final long selectionDeadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < selectionDeadline && !attempt.selected()) {
            attempt = onEdt(() -> selectTreePath(target.displayName()));
            if (!attempt.selected()) Thread.sleep(250L);
        }
        require(report, "selection.treePath", attempt.selected(), attempt.toString());
        Thread.sleep(1_000L);
        report.add("selection.requestedArtMeshId=" + safe(target.id()));
        report.add("selection.requestedDisplayName=" + safe(target.displayName()));
        report.add("selection.tree=" + safe(attempt.treeDescription()));
        return target;
    }

    static SelectionTarget uniqueSelectionTarget(final List<? extends Drawable> drawables) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (Drawable drawable : drawables) {
            final String name = drawable.name();
            if (name != null && !name.isBlank()) counts.merge(name, 1, Integer::sum);
        }
        return drawables.stream()
            .map(drawable -> new SelectionTarget(drawable.id().value(), drawable.name()))
            .filter(target -> target.displayName() != null && !target.displayName().isBlank())
            .filter(target -> counts.getOrDefault(target.displayName(), 0) == 1)
            .min(Comparator.comparing(SelectionTarget::id))
            .orElseThrow(() -> new IllegalStateException(
                "fixture has no ArtMesh with a unique nonblank display name"
            ));
    }

    private static SelectionAttempt selectTreePath(final String displayName) {
        final List<String> observed = new ArrayList<>();
        final List<JTree> candidates = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (!window.isVisible()) continue;
            final List<JTree> trees = new ArrayList<>();
            collectTrees(window, trees, observed);
            for (JTree tree : trees) {
                final String listeners = listenerClasses(tree);
                final javax.swing.JTable owner = findTreeTableOwner(tree);
                final String ownerName = owner == null ? "" : owner.getClass().getName();
                final boolean project = listeners.contains(".palette.project.");
                final boolean deformer = ownerName.contains("palette.deformer");
                final boolean parts = listeners.contains(".palette.parts.")
                    || ownerName.contains("palette.parts");
                if (parts || project) {
                    candidates.add(tree);
                    observed.add((parts ? "candidate-parts:" : "candidate-project:")
                        + describeTree(tree) + ":listeners=" + listeners);
                    continue;
                }
                if (deformer) {
                    observed.add("skip-deformer:" + describeTree(tree) + ":owner=" + ownerName);
                    continue;
                }
                observed.add("skip-unknown:" + describeTree(tree) + ":listeners=" + listeners
                    + ":owner=" + (ownerName.isEmpty() ? "none" : ownerName));
            }
        }
        for (JTree tree : candidates) {
            final List<TreePath> paths = findTreePaths(tree, displayName);
            if (paths.size() != 1) {
                if (paths.size() > 1) {
                    observed.add("ambiguous:" + describeTree(tree) + ":matches=" + paths.size());
                }
                continue;
            }
            final TreePath path = paths.get(0);
            tree.expandPath(path.getParentPath());
            tree.scrollPathToVisible(path);
            tree.clearSelection();
            tree.setSelectionPath(path);
            tree.setLeadSelectionPath(path);
            tree.setAnchorSelectionPath(path);
            final Rectangle bounds = tree.getPathBounds(path);
            if (bounds == null) {
                observed.add("no-bounds:" + describeTree(tree));
                continue;
            }
            final javax.swing.JTable owner = findTreeTableOwner(tree);
            final Component clickComponent;
            final Rectangle clickBounds;
            if (owner != null) {
                final int row = tree.getRowForPath(path);
                if (row < 0 || row >= owner.getRowCount()) {
                    observed.add("invalid-table-row:" + describeTree(tree) + ":" + row);
                    continue;
                }
                clickComponent = owner;
                clickBounds = owner.getCellRect(row, 0, true);
            } else {
                clickComponent = tree;
                clickBounds = bounds;
            }
            final Window window = SwingUtilities.getWindowAncestor(clickComponent);
            if (window == null) {
                observed.add("no-window:" + describeTree(tree));
                continue;
            }
            final int localX = clickBounds.x + Math.max(1, clickBounds.width / 2);
            final int localY = clickBounds.y + Math.max(1, clickBounds.height / 2);
            final Point screen = new Point(localX, localY);
            SwingUtilities.convertPointToScreen(screen, clickComponent);
            final Point focus = new Point(
                Math.max(1, window.getWidth() / 2),
                Math.max(1, Math.min(window.getHeight() - 1, window.getHeight() / 3))
            );
            SwingUtilities.convertPointToScreen(focus, window);
            return new SelectionAttempt(
                true,
                describeTree(tree)
                    + " owner=" + (owner == null ? "none" : owner.getClass().getName()
                        + " rows=" + owner.getRowCount())
                    + " click=" + clickComponent.getClass().getName()
                    + " window=" + window.getClass().getName()
                    + " active=" + window.isActive() + " focused=" + window.isFocused()
                    + " listeners=" + listenerClasses(tree),
                String.valueOf(path.getLastPathComponent()),
                screen.x,
                screen.y,
                focus.x,
                focus.y,
                tree.getRowForPath(path)
            );
        }
        return new SelectionAttempt(
            false, String.join("|", observed), displayName, -1, -1, -1, -1, -1
        );
    }

    private static void collectTrees(
        final Component component,
        final List<JTree> trees,
        final List<String> observed
    ) {
        final String componentClass = component.getClass().getName();
        if (componentClass.toLowerCase(java.util.Locale.ROOT).contains("part")) {
            observed.add("part-component:" + componentClass + ":showing=" + component.isShowing());
        }
        if (component instanceof JTree tree && tree.isShowing()) {
            trees.add(tree);
            observed.add("tree:" + describeTree(tree));
        }
        if (component instanceof javax.swing.JTable table) {
            final String className = table.getClass().getName();
            if (className.contains("Parts") || className.contains("parts") || className.contains("TreeTable")) {
                final JTree embedded = extractTree(table);
                observed.add("table:" + className + ":rows=" + table.getRowCount()
                    + ":embedded=" + (embedded == null ? "none" : embedded.getClass().getName()));
                if (embedded != null && !trees.contains(embedded)) trees.add(embedded);
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectTrees(child, trees, observed);
        }
    }

    private static JTree extractTree(final javax.swing.JTable table) {
        for (Component child : table.getComponents()) {
            if (child instanceof JTree tree) return tree;
        }
        Class<?> type = table.getClass();
        for (int depth = 0; type != null && depth < 4; depth++, type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    final Object value = field.get(table);
                    if (value instanceof JTree tree) return tree;
                    if (value instanceof Component nested) {
                        final JTree tree = findNestedTree(nested);
                        if (tree != null) return tree;
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // Continue through the bounded exact tree-table field scan.
                }
            }
        }
        return null;
    }

    private static JTree findNestedTree(final Component component) {
        if (component instanceof JTree tree) return tree;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTree found = findNestedTree(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javax.swing.JTable findTreeTableOwner(final JTree tree) {
        for (Frame frame : Frame.getFrames()) {
            final javax.swing.JTable owner = findTreeTableOwner(frame, tree);
            if (owner != null) return owner;
        }
        return null;
    }

    private static javax.swing.JTable findTreeTableOwner(
        final Component component,
        final JTree tree
    ) {
        if (component instanceof javax.swing.JTable table && extractTree(table) == tree) {
            return table;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final javax.swing.JTable owner = findTreeTableOwner(child, tree);
                if (owner != null) return owner;
            }
        }
        return null;
    }

    private static String listenerClasses(final JTree tree) {
        final java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
        for (java.awt.event.MouseListener listener : tree.getMouseListeners()) {
            classes.add(listener.getClass().getName());
        }
        for (javax.swing.event.TreeSelectionListener listener : tree.getTreeSelectionListeners()) {
            classes.add(listener.getClass().getName());
        }
        return String.join(",", classes);
    }

    static List<TreePath> findTreePaths(final JTree tree, final String displayName) {
        final ArrayList<TreePath> matches = new ArrayList<>();
        final Object root = tree.getModel().getRoot();
        findTreePaths(tree, new TreePath(root), displayName, matches);
        return List.copyOf(matches);
    }

    private static void findTreePaths(
        final JTree tree,
        final TreePath path,
        final String displayName,
        final List<TreePath> matches
    ) {
        final Object node = path.getLastPathComponent();
        final String rendered = tree.convertValueToText(node, false, false, false, 0, false);
        if (displayName.equals(rendered) || displayName.equals(String.valueOf(node))) {
            matches.add(path);
        }
        final int children = tree.getModel().getChildCount(node);
        for (int index = 0; index < children; index++) {
            final Object child = tree.getModel().getChild(node, index);
            findTreePaths(tree, path.pathByAddingChild(child), displayName, matches);
        }
    }

    private static String describeTree(final JTree tree) {
        return tree.getClass().getName() + " rows=" + tree.getRowCount();
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final FutureTask<T> task = new FutureTask<>(call);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(30L, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            task.cancel(false);
            throw new IllegalStateException("EDT operation timed out", failure);
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("EDT operation failed", cause);
        }
    }

    record SelectionTarget(String id, String displayName) { }

    record SelectionAttempt(
        boolean selected,
        String treeDescription,
        String node,
        int screenX,
        int screenY,
        int focusX,
        int focusY,
        int treeRow
    ) { }

    private void finishMeshEditIfActive(final List<String> report) throws Exception {
        if (context.meshEdit().snapshot().points().isEmpty()) {
            report.add("meshExit=already-inactive");
            return;
        }
        final EditorCommandResult result = context.editorCommands().execute(
            EditorCommand.START_OR_END_MESH_EDITOR
        );
        require(report, "meshExit.command", result.executed(), result.toString());
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (context.meshEdit().snapshot().points().isEmpty()) {
                report.add("meshExit=command");
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("mesh editor did not exit within the bounded wait");
    }

    private MeshSnapshot mutate(
        final List<String> report,
        final String name,
        final MeshSnapshot before,
        final Callable<MeshEditResult> mutation,
        final java.util.function.Predicate<MeshSnapshot> expected
    ) throws Exception {
        final MeshEditResult result = mutation.call();
        requireApplied(report, name + ".result", result);
        final MeshSnapshot after = awaitSnapshot(expected, name);
        require(report, name + ".changed", !after.equals(before), after.toString());
        return after;
    }

    private void undoRedo(
        final List<String> report,
        final String name,
        final MeshSnapshot before,
        final MeshSnapshot after
    ) throws Exception {
        executeCommand(report, name + ".undo", EditorCommand.UNDO);
        require(report, name + ".undoState", awaitSnapshot(before::equals, name + " undo").equals(before),
            before.toString());
        executeCommand(report, name + ".redo", EditorCommand.REDO);
        require(report, name + ".redoState", awaitSnapshot(after::equals, name + " redo").equals(after),
            after.toString());
        report.add("assertion." + name + ".oneUndoStep=PASS");
    }

    private void executeCommand(
        final List<String> report,
        final String phase,
        final EditorCommand command
    ) {
        final EditorCommandResult result = context.editorCommands().execute(command);
        require(report, phase + ".command", result.executed(), result.toString());
    }

    private MeshSnapshot awaitSnapshot(
        final java.util.function.Predicate<MeshSnapshot> expected,
        final String phase
    ) throws Exception {
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        MeshSnapshot actual = context.meshEdit().snapshot();
        while (System.nanoTime() < deadline) {
            if (expected.test(actual)) return actual;
            Thread.sleep(100L);
            actual = context.meshEdit().snapshot();
        }
        throw new IllegalStateException("mesh snapshot timed out during " + phase + "; actual=" + actual);
    }

    private SaveConfirmation saveFixture(final List<String> report, final String phase) throws Exception {
        final Path fixture = fixturePath();
        final FileTime beforeMtime = Files.getLastModifiedTime(fixture);
        final long beforeSize = Files.size(fixture);
        final EditorCommandResult command = context.editorCommands().execute(EditorCommand.SAVE);
        require(report, phase + ".command", command.executed(), command.toString());
        return awaitSaveConfirmation(fixture, beforeMtime, beforeSize, SAVE_TIMEOUT_MILLIS, SAVE_POLL_MILLIS);
    }

    static boolean isExactPointAddition(
        final MeshSnapshot before,
        final MeshSnapshot after,
        final MeshPointPosition requested
    ) {
        if (!after.edges().equals(before.edges())
            || after.points().size() != before.points().size() + 1) return false;
        final Map<Integer, MeshPointRef> old = pointsById(before);
        for (MeshPointRef point : before.points()) {
            if (!after.points().contains(point)) return false;
        }
        return after.points().stream()
            .filter(point -> !old.containsKey(point.id()))
            .filter(point -> same(point.x(), requested.x()) && same(point.y(), requested.y()))
            .count() == 1L;
    }

    static boolean isExactPointMove(
        final MeshSnapshot before,
        final MeshSnapshot after,
        final MeshPointRef moved
    ) {
        if (!after.edges().equals(before.edges()) || after.points().size() != before.points().size()) {
            return false;
        }
        for (MeshPointRef point : before.points()) {
            final MeshPointRef expected = point.id() == moved.id() ? moved : point;
            if (!after.points().contains(expected)) return false;
        }
        return true;
    }

    static boolean isExactEdgeAddition(
        final MeshSnapshot before,
        final MeshSnapshot after,
        final MeshEdgeRef added
    ) {
        if (!after.points().equals(before.points())
            || after.edges().size() != before.edges().size() + 1
            || !after.edges().contains(added)) return false;
        return before.edges().stream().allMatch(after.edges()::contains);
    }

    static boolean isExactEdgeDeletion(
        final MeshSnapshot before,
        final MeshSnapshot after,
        final MeshEdgeRef deleted
    ) {
        if (!after.points().equals(before.points())
            || after.edges().size() != before.edges().size() - 1
            || after.edges().contains(deleted)) return false;
        return after.edges().stream().allMatch(before.edges()::contains);
    }

    static boolean isExactPointDeletion(
        final MeshSnapshot before,
        final MeshSnapshot after,
        final int deletedId
    ) {
        if (after.points().size() != before.points().size() - 1
            || point(after, deletedId).isPresent()) return false;
        if (!after.points().stream().allMatch(before.points()::contains)) return false;
        final List<MeshEdgeRef> expectedEdges = before.edges().stream()
            .filter(edge -> !endpoint(edge, deletedId))
            .toList();
        return after.edges().equals(expectedEdges);
    }

    static MeshPointRef discoverAddedPoint(
        final MeshSnapshot before,
        final MeshSnapshot after,
        final MeshPointPosition requested
    ) {
        final Map<Integer, MeshPointRef> old = pointsById(before);
        final List<MeshPointRef> added = after.points().stream()
            .filter(point -> !old.containsKey(point.id()))
            .toList();
        if (added.size() != 1) {
            throw new IllegalStateException("expected exactly one assigned point id; added=" + added);
        }
        final MeshPointRef point = added.get(0);
        if (!same(point.x(), requested.x()) || !same(point.y(), requested.y())) {
            throw new IllegalStateException("assigned point position differs from request: " + point);
        }
        return point;
    }

    static MeshPointPosition distinctPosition(final MeshSnapshot snapshot) {
        if (snapshot.points().isEmpty()) throw new IllegalArgumentException("mesh has no points");
        final float minX = snapshot.points().stream().map(MeshPointRef::x).min(Float::compare).orElseThrow();
        final float maxX = snapshot.points().stream().map(MeshPointRef::x).max(Float::compare).orElseThrow();
        final float minY = snapshot.points().stream().map(MeshPointRef::y).min(Float::compare).orElseThrow();
        final float maxY = snapshot.points().stream().map(MeshPointRef::y).max(Float::compare).orElseThrow();
        final float span = Math.max(Math.max(maxX - minX, maxY - minY), 1.0F);
        return new MeshPointPosition(maxX + span * 0.173F, maxY + span * 0.197F);
    }

    static MeshPointRef movedPoint(final MeshPointRef source, final MeshSnapshot snapshot) {
        final MeshPointPosition anchor = distinctPosition(snapshot);
        return new MeshPointRef(source.id(), anchor.x(), anchor.y());
    }

    static MeshPointRef chooseEdgePartner(final MeshSnapshot snapshot, final int addedId) {
        final Map<Integer, MeshPointRef> points = pointsById(snapshot);
        return snapshot.points().stream()
            .filter(point -> point.id() != addedId)
            .filter(point -> snapshot.edges().stream().noneMatch(edge ->
                edge.equals(new MeshEdgeRef(addedId, point.id(), edge.kind()))))
            .min(Comparator.comparingInt(MeshPointRef::id))
            .orElseGet(() -> points.values().stream()
                .filter(point -> point.id() != addedId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no edge partner is available")));
    }

    static MeshPointRef pointWithConnectedEdge(final MeshSnapshot snapshot) {
        final MeshEdgeRef edge = snapshot.edges().get(0);
        return point(snapshot, edge.startPointId()).orElseThrow();
    }

    static List<MeshEdgeRef> connectedEdges(final MeshSnapshot snapshot, final int pointId) {
        return snapshot.edges().stream().filter(edge -> endpoint(edge, pointId)).toList();
    }

    static Map<Integer, MeshPointRef> pointsById(final MeshSnapshot snapshot) {
        final Map<Integer, MeshPointRef> points = new LinkedHashMap<>();
        for (MeshPointRef point : snapshot.points()) {
            if (points.put(point.id(), point) != null) {
                throw new IllegalArgumentException("duplicate mesh point id: " + point.id());
            }
        }
        return Map.copyOf(points);
    }

    private static java.util.Optional<MeshPointRef> point(final MeshSnapshot snapshot, final int id) {
        return snapshot.points().stream().filter(point -> point.id() == id).findFirst();
    }

    private static boolean endpoint(final MeshEdgeRef edge, final int pointId) {
        return edge.startPointId() == pointId || edge.endPointId() == pointId;
    }

    private static boolean samePosition(final MeshPointRef first, final MeshPointRef second) {
        return same(first.x(), second.x()) && same(first.y(), second.y());
    }

    private static boolean same(final float first, final float second) {
        return Math.abs(first - second) <= EPSILON;
    }

    private static void requireApplied(
        final List<String> report,
        final String name,
        final MeshEditResult result
    ) {
        require(report, name, result.accepted() && result.rejected().isEmpty(), result.toString());
    }

    private static void require(
        final List<String> report,
        final String name,
        final boolean condition,
        final String detail
    ) {
        report.add("assertion." + name + "=" + (condition ? "PASS" : "FAIL"));
        report.add("detail." + name + "=" + safe(detail));
        if (!condition) throw new IllegalStateException(name + ": " + safe(detail));
    }

    static Path fixturePath() {
        final String value = System.getProperty("turboism.validation.fixture");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("turboism.validation.fixture is required");
        }
        return Path.of(value);
    }

    static SaveConfirmation awaitSaveConfirmation(
        final Path fixture,
        final FileTime beforeMtime,
        final long beforeSize,
        final long deadlineMillis,
        final long pollMillis
    ) throws Exception {
        if (!Files.isRegularFile(fixture)) {
            throw new IllegalArgumentException("validation fixture is missing: " + fixture);
        }
        final long deadline = System.nanoTime() + deadlineMillis * 1_000_000L;
        FileTime changedMtime = null;
        long changedSize = -1L;
        int stable = 0;
        while (System.nanoTime() < deadline) {
            final FileTime mtime = Files.getLastModifiedTime(fixture);
            final long size = Files.size(fixture);
            if (!mtime.equals(beforeMtime) || size != beforeSize) {
                if (!Objects.equals(changedMtime, mtime) || changedSize != size) {
                    changedMtime = mtime;
                    changedSize = size;
                    stable = 0;
                }
                if (++stable >= SAVE_STABLE_SAMPLES) {
                    return new SaveConfirmation(
                        true, beforeMtime.toMillis(), beforeSize, mtime.toMillis(), size
                    );
                }
            }
            Thread.sleep(pollMillis);
        }
        return new SaveConfirmation(
            false,
            beforeMtime.toMillis(),
            beforeSize,
            Files.getLastModifiedTime(fixture).toMillis(),
            Files.size(fixture)
        );
    }

    record SaveConfirmation(
        boolean confirmed,
        long beforeMtimeMillis,
        long beforeSize,
        long afterMtimeMillis,
        long afterSize
    ) {
        String report(final String prefix) {
            return prefix + "confirmed=" + confirmed + ";"
                + prefix + "beforeMtimeMillis=" + beforeMtimeMillis + ";"
                + prefix + "beforeSize=" + beforeSize + ";"
                + prefix + "afterMtimeMillis=" + afterMtimeMillis + ";"
                + prefix + "afterSize=" + afterSize;
        }
    }

    private static String failureDescription(final Throwable failure) {
        final StringBuilder result = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) result.append(" <- ");
            result.append(current.getClass().getSimpleName()).append(": ")
                .append(current.getMessage() == null ? "" : current.getMessage());
        }
        return result.toString();
    }

    private static String safe(final String value) {
        return String.valueOf(value).replace('\r', ' ').replace('\n', ' ').replace('=', ':');
    }

    private void requestHostClose() {
        try {
            final Boolean closed = onEdt(() -> {
                for (Frame frame : Frame.getFrames()) {
                    if (!frame.isVisible()) continue;
                    final String title = frame.getTitle();
                    if (title != null && title.contains(".cmo3")) {
                        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            });
            if (!closed) context.logger().error("Automated mesh validation main window was not found");
        } catch (Exception failure) {
            context.logger().error("Automated mesh validation host close failed", failure);
        }
    }
}
