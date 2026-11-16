package dev.turboism.adapter.cubism.optimization.paramrow;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Runs one incremental Parameter Palette pass over one controller and reports what it touched.
 *
 * <p>A pass compares the live palette against the state recorded by the previous pass. It rebuilds
 * that state when the palette structure, the document or the model changed, and otherwise touches
 * only the rows whose selection, lock state, keyform geometry or painted appearance moved.</p>
 *
 * <p>Keyform signatures are recomputed only when the recorded edit identity no longer proves them
 * current. A document that is mid-edit, or that holds an open undo transaction, keeps changing
 * geometry without moving the undo cursor, so such a pass always rehashes.</p>
 *
 * <p>The pass owns no lifecycle and publishes nothing: the caller decides when a pass runs and
 * accumulates the counters it returns.</p>
 */
final class ParameterRowPass {

    private static final int SIGNATURE_SLOT = 0;
    private static final int CACHED_VALUE_PAIR_SIZE = 2;
    private static final long INITIAL_PASS_ID = 1L;

    private final ParameterRowHostAccess host;
    private final ParameterRowKeyformHasher keyformHasher;
    private final ParameterRowVisualSync visualSync;

    private final long[] rowSignature = new long[1];

    // Counters of the pass in flight; the caller accumulates them once the pass returns.
    private long repaintedRows;
    private long elidedRows;
    private long resizedDoubles;

    ParameterRowPass(
            final ParameterRowHostAccess host,
            final ParameterRowKeyformHasher keyformHasher,
            final ParameterRowVisualSync visualSync) {
        this.host = Objects.requireNonNull(host, "host");
        this.keyformHasher = Objects.requireNonNull(keyformHasher, "keyformHasher");
        this.visualSync = Objects.requireNonNull(visualSync, "visualSync");
    }

    /** One row's recorded state: when it was last visited and what it last painted. */
    private static final class RowSnapshot {
        long lastVisitedPass;
        long visualHash;
        long doubleResizeHash;
        final int[] primaryValueBits = new int[CACHED_VALUE_PAIR_SIZE];
        final int[] secondaryValueBits = new int[CACHED_VALUE_PAIR_SIZE];
    }

    /** One controller's recorded state: the structure, selection and edit identity it last saw. */
    static final class ControllerSnapshot {
        final WeakReference<Object> docRef;
        final WeakReference<Object> modelRef;
        final IdentityHashMap<Object, RowSnapshot> rows = new IdentityHashMap<>();
        final HashMap<Object, long[]> keyformHashes = new HashMap<>();
        Object[] selectedObjects = ParameterRowVisualSync.EMPTY_REFERENCES;
        Object[] selectedPaletteRows = ParameterRowVisualSync.EMPTY_REFERENCES;
        boolean showKeyforms;
        int rootChildrenCount;
        int flatRowsCount;
        long passId = INITIAL_PASS_ID;
        boolean keyformHashesValid;
        WeakReference<Object> editModeRef;
        long lastModifiedTimeMs;
        long allObjectsCount;
        WeakReference<Object> undoManagerRef;
        long undoPos;
        long undoSize;
        boolean hasTopUndo;
        WeakReference<Object> topUndoRef;
        boolean hasLinkedUndo;
        WeakReference<Object> linkedUndoManagerRef;
        long linkedUndoPos;
        long linkedUndoSize;
        boolean hasLinkedTopUndo;
        WeakReference<Object> linkedTopUndoRef;

        ControllerSnapshot(final Object doc, final Object model) {
            docRef = new WeakReference<>(doc);
            modelRef = new WeakReference<>(model);
        }

        /**
         * Returns whether the recorded edit identity still describes the live document.
         *
         * <p>An invalidated keyform cache answers {@code false} unconditionally: a recorded identity
         * without a recorded signature cannot prove the signatures are current.</p>
         */
        boolean matchesEditState(final ParameterRowHostAccess.EditStateProbe probe) {
            if (!keyformHashesValid) {
                return false;
            }
            if (!sameRef(editModeRef, true, probe.editMode())
                    || !sameRef(undoManagerRef, true, probe.undoManager())
                    || !sameRef(topUndoRef, hasTopUndo, probe.topUndo())
                    || !sameRef(linkedUndoManagerRef, hasLinkedUndo, probe.linkedUndoManager())
                    || !sameRef(linkedTopUndoRef, hasLinkedTopUndo, probe.linkedTopUndo())) {
                return false;
            }
            return lastModifiedTimeMs == probe.lastModifiedTimeMs()
                    && allObjectsCount == probe.allObjectsCount()
                    && undoPos == probe.undoPos()
                    && undoSize == probe.undoSize()
                    && linkedUndoPos == probe.linkedUndoPos()
                    && linkedUndoSize == probe.linkedUndoSize();
        }

        void recordEditState(final ParameterRowHostAccess.EditStateProbe probe) {
            lastModifiedTimeMs = probe.lastModifiedTimeMs();
            allObjectsCount = probe.allObjectsCount();
            undoPos = probe.undoPos();
            undoSize = probe.undoSize();
            linkedUndoPos = probe.linkedUndoPos();
            linkedUndoSize = probe.linkedUndoSize();
            editModeRef = updateRef(editModeRef, probe.editMode());
            undoManagerRef = updateRef(undoManagerRef, probe.undoManager());
            hasTopUndo = probe.topUndo() != null;
            topUndoRef = updateRef(topUndoRef, probe.topUndo());
            hasLinkedUndo = probe.linkedUndoManager() != null;
            linkedUndoManagerRef = updateRef(linkedUndoManagerRef, probe.linkedUndoManager());
            hasLinkedTopUndo = probe.linkedTopUndo() != null;
            linkedTopUndoRef = updateRef(linkedTopUndoRef, probe.linkedTopUndo());
            keyformHashesValid = true;
        }

        void clearEditState() {
            keyformHashesValid = false;
            editModeRef = null;
            undoManagerRef = null;
            hasTopUndo = false;
            topUndoRef = null;
            hasLinkedUndo = false;
            linkedUndoManagerRef = null;
            hasLinkedTopUndo = false;
            linkedTopUndoRef = null;
        }

        private static boolean sameRef(final WeakReference<Object> cached, final boolean hadValue, final Object live) {
            if (!hadValue) {
                return live == null;
            }
            return cached != null && live != null && cached.refersTo(live);
        }

        private static WeakReference<Object> updateRef(final WeakReference<Object> existing, final Object target) {
            if (target == null) {
                return null;
            }
            if (existing != null && existing.refersTo(target)) {
                return existing;
            }
            return new WeakReference<>(target);
        }
    }

    /** The facts one pass compares every row against. */
    private record PassContext(
            Object paramSet,
            List<?> selectedObjects,
            Object[] selectedPaletteRows,
            boolean showKeyforms,
            boolean selectionChanged,
            Object highlightGuid,
            ControllerSnapshot snapshot,
            long passId,
            boolean baseline) {}

    /**
     * What one pass did, so the caller can accumulate counters without inspecting the walk.
     *
     * @param snapshot the controller state the pass left behind
     * @param baseline whether the pass rebuilt the controller state
     * @param keyformHashed whether the pass recomputed the keyform signatures
     * @param repaintedRows how many rows repainted or wrote a value
     * @param elidedRows how many rows the pass left untouched
     * @param resizedDoubles how many double rows were resized
     */
    record Outcome(
            ControllerSnapshot snapshot,
            boolean baseline,
            boolean keyformHashed,
            long repaintedRows,
            long elidedRows,
            long resizedDoubles) {}

    /**
     * Runs one pass for one controller.
     *
     * @param controller the palette controller
     * @param doc the modeling document the pass belongs to
     * @param model the model whose parameters are displayed
     * @param cached the state recorded by the previous pass, or {@code null} for a first pass
     * @return what the pass did, or {@code null} when the palette has no root row layout
     */
    Outcome run(final Object controller, final Object doc, final Object model, final ControllerSnapshot cached)
            throws Throwable {
        final Object highlightGuid = host.consumeHighlightGuid(controller);
        final Object childrenBox = host.childrenBox(controller);
        if (childrenBox == null) {
            return null;
        }
        final List<?> rootChildren = host.rowChildren(childrenBox);
        final List<?> flatRows = host.flatRows(controller);
        final List<?> selectedObjects = host.selectedObjects(controller);
        final List<?> paletteSelectedRows = host.selectedPaletteRows(controller);
        final boolean showKeyforms = host.showKeyforms(controller);
        final Object paramSet = host.parameterSet(model);

        final boolean baseline = isStructuralMismatch(cached, doc, model, rootChildren.size(), flatRows.size());
        final ControllerSnapshot snapshot;
        if (baseline) {
            snapshot = new ControllerSnapshot(doc, model);
            snapshot.rootChildrenCount = rootChildren.size();
            snapshot.flatRowsCount = flatRows.size();
        } else {
            snapshot = cached;
        }

        final long passId = ++snapshot.passId;
        final boolean selectionChanged =
                baseline || !ParameterRowVisualSync.sameElements(snapshot.selectedObjects, selectedObjects);
        if (selectionChanged) {
            snapshot.selectedObjects = ParameterRowVisualSync.copyReferences(selectedObjects);
        }
        final boolean paletteSelectionChanged =
                baseline || !ParameterRowVisualSync.sameElements(snapshot.selectedPaletteRows, paletteSelectedRows);
        if (paletteSelectionChanged) {
            snapshot.selectedPaletteRows = ParameterRowVisualSync.copyReferences(paletteSelectedRows);
        }
        snapshot.showKeyforms = showKeyforms;

        repaintedRows = 0L;
        elidedRows = 0L;
        resizedDoubles = 0L;

        final boolean keyformHashed = refreshKeyformSignatures(snapshot, doc, model, baseline);

        final PassContext context = new PassContext(
                paramSet,
                selectedObjects,
                snapshot.selectedPaletteRows,
                showKeyforms,
                selectionChanged,
                highlightGuid,
                snapshot,
                passId,
                baseline);

        processWidgetList(rootChildren, context);
        if (snapshot.rows.size() > countVisitedRows(snapshot, passId)) {
            pruneUnvisitedRows(snapshot, passId);
        }
        syncRemainingFlatRows(flatRows, paramSet, snapshot, passId);
        return new Outcome(snapshot, baseline, keyformHashed, repaintedRows, elidedRows, resizedDoubles);
    }

    /**
     * Recomputes the keyform signatures unless the recorded edit identity proves they still hold.
     *
     * <p>Elision needs three conditions: a previous pass recorded an identity, the document is not
     * mid-edit, and no undo transaction is open.</p>
     *
     * @return {@code true} when the pass recomputed the signatures
     */
    private boolean refreshKeyformSignatures(
            final ControllerSnapshot snapshot, final Object doc, final Object model, final boolean baseline)
            throws Throwable {
        final ParameterRowHostAccess.EditStateProbe probe = host.readEditState(doc, model);
        final boolean editIdle = probe != null && !probe.editing() && probe.currentUndo() == null;
        if (!baseline && editIdle && snapshot.matchesEditState(probe)) {
            return false;
        }
        ParameterRowKeyformHasher.clearSignatures(snapshot.keyformHashes);
        keyformHasher.hashKeyforms(model, snapshot.keyformHashes);
        if (editIdle) {
            snapshot.recordEditState(probe);
        } else {
            snapshot.clearEditState();
        }
        return true;
    }

    private static boolean isStructuralMismatch(
            final ControllerSnapshot snapshot,
            final Object doc,
            final Object model,
            final int rootChildrenCount,
            final int flatRowsCount) {
        if (snapshot == null) {
            return true;
        }
        return snapshot.docRef.get() != doc
                || snapshot.modelRef.get() != model
                || snapshot.rootChildrenCount != rootChildrenCount
                || snapshot.flatRowsCount != flatRowsCount;
    }

    private void processWidgetList(final List<?> widgets, final PassContext context) throws Throwable {
        final int size = widgets.size();
        for (int index = 0; index < size; index++) {
            final Object widget = widgets.get(index);
            if (widget == null) {
                continue;
            }
            switch (host.rowKind(widget)) {
                case ParameterRowHostAccess.ROW_KIND_SINGLE -> processSingleRow(widget, context);
                case ParameterRowHostAccess.ROW_KIND_DOUBLE -> processDoubleRow(widget, context);
                case ParameterRowHostAccess.ROW_KIND_GROUP -> processGroupRow(widget, context);
                case ParameterRowHostAccess.ROW_KIND_WIDGET -> processGenericWidget(widget, context);
                default -> {}
            }
        }
    }

    private void processSingleRow(final Object widget, final PassContext context) throws Throwable {
        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final boolean needSelectedObjectsUpdate = context.baseline() || isNew || context.selectionChanged();
        host.applySelectedObjects(widget, needSelectedObjectsUpdate ? context.selectedObjects() : null);
        final ParameterRowHostAccess.SingleRowProbe probe = host.readSingleRow(widget);

        rowSignature[SIGNATURE_SLOT] = ParameterRowKeyformHasher.singleRowSeed();
        final Object primaryGuid = keyformHasher.accumulateSource(rowSignature, probe.paramSource());
        final boolean rowSelected = ParameterRowVisualSync.containsReference(context.selectedPaletteRows(), widget);
        final long keyformSignature =
                ParameterRowKeyformHasher.keyformSignature(context.snapshot().keyformHashes, primaryGuid);
        final boolean highlightChanged = primaryGuid != null && Objects.equals(primaryGuid, context.highlightGuid());

        long hash = rowSignature[SIGNATURE_SLOT];
        hash = ParameterRowKeyformHasher.mix(hash, keyformSignature);
        hash = ParameterRowKeyformHasher.mixFlag(hash, context.showKeyforms());
        hash = ParameterRowKeyformHasher.mixFlag(hash, probe.locked());
        hash = ParameterRowKeyformHasher.mixFlag(hash, rowSelected);
        hash = visualSync.mixWidget(hash, widget);
        if (probe.mainWidget() != null) {
            hash = visualSync.mixWidget(hash, probe.mainWidget());
        }

        final boolean visualChanged = context.baseline()
                || isNew
                || highlightChanged
                || context.selectionChanged()
                || state.visualHash != hash;
        state.visualHash = hash;

        final boolean forceSync = context.baseline() || isNew;
        final boolean valueChanged =
                visualSync.syncValue(widget, context.paramSet(), false, state.primaryValueBits, forceSync);
        if (visualChanged && !valueChanged) {
            host.repaintWidget(widget);
        }
        countRow(visualChanged || valueChanged);
    }

    private void processDoubleRow(final Object widget, final PassContext context) throws Throwable {
        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final ParameterRowHostAccess.DoubleRowProbe probe = host.readDoubleRow(widget);

        rowSignature[SIGNATURE_SLOT] = ParameterRowKeyformHasher.doubleRowSeed();
        final Object primaryGuid = keyformHasher.accumulateSource(rowSignature, probe.primary());
        final Object secondaryGuid = keyformHasher.accumulateSource(rowSignature, probe.secondary());
        final long primaryKeyform =
                ParameterRowKeyformHasher.keyformSignature(context.snapshot().keyformHashes, primaryGuid);
        final long secondaryKeyform =
                ParameterRowKeyformHasher.keyformSignature(context.snapshot().keyformHashes, secondaryGuid);

        long resizeHash = ParameterRowKeyformHasher.mix(primaryKeyform, secondaryKeyform);
        resizeHash = ParameterRowKeyformHasher.mixFlag(resizeHash, probe.combinedEnable());
        resizeHash = ParameterRowKeyformHasher.mixFlag(resizeHash, context.showKeyforms());
        if (context.baseline() || isNew || state.doubleResizeHash != resizeHash) {
            host.resizeDoubleRow(widget);
            state.doubleResizeHash = resizeHash;
            resizedDoubles++;
        }

        final boolean rowSelected = ParameterRowVisualSync.containsReference(context.selectedPaletteRows(), widget);
        final boolean highlightChanged = (primaryGuid != null && Objects.equals(primaryGuid, context.highlightGuid()))
                || (secondaryGuid != null && Objects.equals(secondaryGuid, context.highlightGuid()));

        long hash = ParameterRowKeyformHasher.mix(rowSignature[SIGNATURE_SLOT], resizeHash);
        hash = ParameterRowKeyformHasher.mixFlag(hash, probe.locked());
        hash = ParameterRowKeyformHasher.mixFlag(hash, rowSelected);
        hash = visualSync.mixWidget(hash, widget);
        if (probe.mainWidget() != null) {
            hash = visualSync.mixWidget(hash, probe.mainWidget());
        }

        final boolean visualChanged = context.baseline()
                || isNew
                || highlightChanged
                || context.selectionChanged()
                || state.visualHash != hash;
        state.visualHash = hash;

        final boolean forceSync = context.baseline() || isNew;
        final boolean primaryChanged =
                visualSync.syncValue(widget, context.paramSet(), false, state.primaryValueBits, forceSync);
        final boolean secondaryChanged =
                visualSync.syncValue(widget, context.paramSet(), true, state.secondaryValueBits, forceSync);
        final boolean valueChanged = primaryChanged || secondaryChanged;

        if (visualChanged && !valueChanged) {
            host.repaintWidget(widget);
        }
        countRow(visualChanged || valueChanged);
    }

    private void processGroupRow(final Object widget, final PassContext context) throws Throwable {
        final ParameterRowHostAccess.GroupRowProbe probe = host.readGroupRow(widget);
        if (probe.children() != null) {
            processWidgetList(probe.children(), context);
        }

        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final boolean rowSelected = ParameterRowVisualSync.containsReference(context.selectedPaletteRows(), widget);
        long hash = ParameterRowKeyformHasher.groupRowSeed();
        hash = ParameterRowKeyformHasher.mixFlag(hash, probe.expanded());
        hash = ParameterRowKeyformHasher.mixFlag(hash, rowSelected);
        if (probe.hasGroupData()) {
            hash = ParameterRowKeyformHasher.mixHashCode(hash, probe.groupName());
            hash = ParameterRowKeyformHasher.mix(hash, probe.groupColorArgb());
            hash = ParameterRowKeyformHasher.mixFlag(hash, probe.folderOpened());
        }
        hash = visualSync.mixWidget(hash, widget);
        if (probe.mainWidget() != null) {
            hash = visualSync.mixWidget(hash, probe.mainWidget());
        }

        final boolean changed = context.baseline() || isNew || state.visualHash != hash;
        state.visualHash = hash;
        if (changed) {
            host.repaintWidget(widget);
        }
        countRow(changed);
    }

    private void processGenericWidget(final Object widget, final PassContext context) throws Throwable {
        final boolean isNew = !context.snapshot().rows.containsKey(widget);
        final RowSnapshot state = context.snapshot().rows.computeIfAbsent(widget, ignored -> new RowSnapshot());
        state.lastVisitedPass = context.passId();

        final long hash = visualSync.mixWidget(ParameterRowKeyformHasher.genericRowSeed(), widget);
        final boolean changed = context.baseline() || isNew || state.visualHash != hash;
        state.visualHash = hash;
        if (changed) {
            host.repaintWidget(widget);
        }
        countRow(changed);
    }

    /**
     * Synchronizes rows the widget walk did not visit.
     *
     * <p>A row can be active in the flat row list before its widget enters the layout tree, and such
     * a row still needs its displayed value. Rows the walk already visited keep their recorded state
     * and are skipped.</p>
     */
    private void syncRemainingFlatRows(
            final List<?> flatRows, final Object paramSet, final ControllerSnapshot snapshot, final long passId)
            throws Throwable {
        if (paramSet == null) {
            return;
        }
        final int count = flatRows.size();
        for (int index = 0; index < count; index++) {
            final Object row = flatRows.get(index);
            if (row == null) {
                continue;
            }
            final RowSnapshot existing = snapshot.rows.get(row);
            if (existing != null && existing.lastVisitedPass == passId) {
                continue;
            }
            final boolean isNew = existing == null;
            final RowSnapshot state =
                    isNew ? snapshot.rows.computeIfAbsent(row, ignored -> new RowSnapshot()) : existing;
            state.lastVisitedPass = passId;
            visualSync.syncValue(row, paramSet, false, state.primaryValueBits, isNew);
            visualSync.syncValue(row, paramSet, true, state.secondaryValueBits, isNew);
        }
    }

    /** Counts one row as repainted or as elided. */
    private void countRow(final boolean repainted) {
        if (repainted) {
            repaintedRows++;
        } else {
            elidedRows++;
        }
    }

    private static int countVisitedRows(final ControllerSnapshot snapshot, final long passId) {
        int visited = 0;
        for (final RowSnapshot state : snapshot.rows.values()) {
            if (state.lastVisitedPass == passId) {
                visited++;
            }
        }
        return visited;
    }

    private static void pruneUnvisitedRows(final ControllerSnapshot snapshot, final long passId) {
        snapshot.rows.entrySet().removeIf(entry -> entry.getValue().lastVisitedPass != passId);
    }
}
