package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.runtime.work.FatalErrors;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Edit-mode dirty-subtree incremental evaluator and level-order parallel transform coordinator.
 *
 * <p>Detects which root deformer(s) changed between {@code updateModel} frames when parameters,
 * topology, and non-deformer drawables stayed invariant, propagates dirt strictly down the DAG,
 * runs independent deformer depth levels and dirty ArtMeshes across {@link SubtreeParallelExecutor},
 * and calls host {@code updateArtMeshAppearance} (including GL VBO {@code .l()}) strictly on dirty
 * meshes. Any unrecognized mutation falls back fail-closed to native host execution.</p>
 */
final class EditSubtreeCoordinator {

    private static final float ZERO_F = 0.0f;
    private static final float UNIT_SCALE = 1.0f;

    private final EditSubtreeHandles h;
    private final SubtreeParallelExecutor parallelExecutor;
    private final WeakIdentityCache<Object, EditModelTopologyCache> modelCaches = new WeakIdentityCache<>();
    private final ThreadLocal<Boolean> inFastPath = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<IdentityHashMap<Object, Boolean>> activeEditSourcesScratch =
            ThreadLocal.withInitial(IdentityHashMap::new);

    final LongAdder fastModelUpdates = new LongAdder();
    final LongAdder fallbackModelUpdates = new LongAdder();
    final LongAdder dirtyDeformersProcessed = new LongAdder();
    final LongAdder skippedDeformers = new LongAdder();
    final LongAdder dirtyMeshesProcessed = new LongAdder();
    final LongAdder skippedMeshes = new LongAdder();
    final LongAdder appearanceSkipped = new LongAdder();
    final LongAdder fullArrayHashes = new LongAdder();
    final LongAdder parallelDeformerLevels = new LongAdder();
    final LongAdder parallelMeshBatches = new LongAdder();

    EditSubtreeCoordinator(final EditSubtreeHandles handles, final SubtreeParallelExecutor parallelExecutor) {
        this.h = handles;
        this.parallelExecutor = parallelExecutor;
    }

    private volatile EditModelTopologyCache latestCache;

    void clearCaches() {
        final EditModelTopologyCache recent = latestCache;
        if (recent != null) {
            recent.disarmAppearanceSkip();
        }
        modelCaches.clear();
        latestCache = null;
    }

    /** Returns the most recently updated model topology cache, or {@code null} when none exists. */
    EditModelTopologyCache latestCacheSnapshot() {
        return latestCache;
    }

    /**
     * Entry gate for {@code updater.updateCore(CModel, List, updCtx, CEViewContext, ctxParam)}.
     * Returns {@code true} when dirty-subtree evaluation handled the frame and the host method can
     * return immediately (with clean ArtMeshes disarmed from subsequent GL buffer uploads).
     */
    boolean tryFastCoreUpdate(
            final Object updater,
            final Object model,
            final Object visibleMeshesObj,
            final Object updCtx,
            final Object viewCtx,
            final Object ctx5) {
        final EditModelTopologyCache prior = latestCache;
        if (prior != null) {
            prior.disarmAppearanceSkip();
        }
        if (inFastPath.get()
                || model == null
                || updCtx == null
                || !(visibleMeshesObj instanceof List<?> visibleMeshesList)) {
            return false;
        }
        inFastPath.set(Boolean.TRUE);
        try {
            final EditModelTopologyCache cache = modelCaches.getOrCompute(model, EditModelTopologyCache::new);
            latestCache = cache;
            if (cache.hasAnyConflictPolygon) {
                cache.validBaseline = false;
                fallbackModelUpdates.increment();
                return false;
            }
            if (h.updCtxGetSubParam != null && (Object) h.updCtxGetSubParam.invokeExact(updCtx) != null) {
                cache.validBaseline = false;
                fallbackModelUpdates.increment();
                return false;
            }
            final Object paramSet = (Object) h.modelGetParameterSet.invokeExact(model);
            final Object modelSource = (Object) h.modelGetSource.invokeExact(model);
            if (paramSet == null || modelSource == null) {
                cache.validBaseline = false;
                fallbackModelUpdates.increment();
                return false;
            }
            if (viewCtx != null && (boolean) h.formAnimIsActive.invokeExact(h.formAnimTInstance, viewCtx, model)) {
                cache.validBaseline = false;
                fallbackModelUpdates.increment();
                return false;
            }

            final Object doc = viewCtx == null ? null : (Object) h.viewGetDoc.invokeExact(viewCtx);
            final Object editMode = viewCtx == null ? null : (Object) h.viewGetEditMode.invokeExact(viewCtx);
            if (editMode != null && !h.editModeMainClass.isInstance(editMode)) {
                cache.validBaseline = false;
                fallbackModelUpdates.increment();
                return false;
            }
            if (!cache.validBaseline) {
                fallbackModelUpdates.increment();
                return false;
            }
            if (!cache.parametersUnchanged(paramSet, h)
                    || !cache.matchesTopologyAndGlobals(
                            model, modelSource, doc, viewCtx, updCtx, ctx5, visibleMeshesList, h)) {
                cache.validBaseline = false;
                fallbackModelUpdates.increment();
                return false;
            }
            cache.lastEditing = isCurrentlyEditing(modelSource, editMode);

            final IdentityHashMap<Object, Boolean> activeEditSources = activeEditSourcesScratch.get();
            collectActiveEditSources(model, editMode, activeEditSources);
            final boolean handled =
                    executeIncrementalCoreUpdate(cache, updater, model, updCtx, paramSet, activeEditSources);
            if (!handled) {
                cache.disarmAppearanceSkip();
                cache.validBaseline = false;
                fallbackModelUpdates.increment();
                return false;
            }
            fastModelUpdates.increment();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            fallbackModelUpdates.increment();
            final EditModelTopologyCache cache = latestCache;
            if (cache != null) {
                cache.disarmAppearanceSkip();
                cache.validBaseline = false;
            }
            return false;
        } finally {
            activeEditSourcesScratch.get().clear();
            inFastPath.set(Boolean.FALSE);
        }
    }

    /** Normal {@code RETURN} tail hook recording clean post-native state for subsequent drag ticks. */
    void afterNativeCoreUpdate(
            final Object updater,
            final Object model,
            final Object visibleMeshesObj,
            final Object updCtx,
            final Object viewCtx,
            final Object ctx5) {
        if (inFastPath.get() || model == null || updCtx == null) {
            return;
        }
        try {
            if (h.updCtxGetSubParam != null && (Object) h.updCtxGetSubParam.invokeExact(updCtx) != null) {
                return;
            }
            final Object paramSet = (Object) h.modelGetParameterSet.invokeExact(model);
            final Object modelSource = (Object) h.modelGetSource.invokeExact(model);
            if (paramSet == null || modelSource == null) {
                return;
            }
            if (viewCtx != null && (boolean) h.formAnimIsActive.invokeExact(h.formAnimTInstance, viewCtx, model)) {
                return;
            }
            final EditModelTopologyCache cache = modelCaches.getOrCompute(model, EditModelTopologyCache::new);
            cache.disarmAppearanceSkip();
            latestCache = cache;
            final Object doc = viewCtx == null ? null : (Object) h.viewGetDoc.invokeExact(viewCtx);
            final Object editMode = viewCtx == null ? null : (Object) h.viewGetEditMode.invokeExact(viewCtx);
            final boolean curEditing = isCurrentlyEditing(modelSource, editMode);
            final IdentityHashMap<Object, Boolean> activeEditSources = activeEditSourcesScratch.get();
            collectActiveEditSources(model, editMode, activeEditSources);
            cache.captureFullBaseline(
                    model, modelSource, doc, viewCtx, updCtx, ctx5, paramSet, curEditing,
                    activeEditSources, h, fullArrayHashes);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            final EditModelTopologyCache cache = latestCache;
            if (cache != null) {
                cache.disarmAppearanceSkip();
                cache.validBaseline = false;
            }
        } finally {
            activeEditSourcesScratch.get().clear();
        }
    }

    /**
     * Entry gate for {@code updater.updateArtMeshAppearance(CArtMesh, CModel, updCtx, doc)}.
     * Returns {@code true} to skip redundant {@code GMesh2} array copies and {@code .l()} VBO uploads
     * for clean ArtMeshes outside the active dirty subtree.
     */
    boolean shouldSkipCleanArtMeshAppearance(final Object artMesh, final Object model) {
        final EditModelTopologyCache recent = latestCache;
        if (recent == null
                || recent.skipThread != Thread.currentThread()
                || recent.model != model
                || recent.activeDirtyMeshes == null
                || recent.visibleMeshes == null) {
            return false;
        }
        final Integer idx = recent.visibleMeshIndexByRef.get(artMesh);
        if (idx == null) {
            return false;
        }
        final int i = idx;
        final boolean skip = i < recent.activeDirtyMeshes.length && !recent.activeDirtyMeshes[i];
        if (--recent.remainingAppearanceChecks <= 0) {
            recent.disarmAppearanceSkip();
        }
        if (skip) {
            appearanceSkipped.increment();
        }
        return skip;
    }

    private boolean isCurrentlyEditing(final Object modelSource, final Object editMode) throws Throwable {
        if ((boolean) h.modelSourceIsEditing.invokeExact(modelSource)) {
            return true;
        }
        return editMode != null
                && h.editModeClass.isInstance(editMode)
                && (boolean) h.editModeIsEditing.invokeExact(editMode);
    }

    private void collectActiveEditSources(
            final Object model, final Object editMode, final IdentityHashMap<Object, Boolean> dst) throws Throwable {
        dst.clear();
        if (editMode == null || !h.editModeMainClass.isInstance(editMode)) {
            return;
        }
        final Object selector = (Object) h.editModeMainGetSelector.invokeExact(editMode);
        if (selector == null) {
            return;
        }
        final Object selectedRaw = (Object) h.selectorGetSelectedObjects.invokeExact(selector);
        if (selectedRaw instanceof List<?> selectedList) {
            for (final Object src : selectedList) {
                if (src != null) {
                    dst.put(src, Boolean.TRUE);
                }
            }
        }
        final Object hovering = (Object) h.selectorGetHoveringObject.invokeExact(selector);
        if (hovering == null) {
            return;
        }
        final Object guid = (Object) h.selectionGetGuid.invokeExact(hovering);
        if (guid == null) {
            return;
        }
        if (h.deformerGuidClass.isInstance(guid)) {
            final Object deformer = (Object) h.modelGetDeformer.invokeExact(model, guid);
            if (deformer != null) {
                final Object src = (Object) h.deformerGetSource.invokeExact(deformer);
                if (src != null) {
                    dst.put(src, Boolean.TRUE);
                }
            }
        } else if (h.drawableGuidClass.isInstance(guid)) {
            final Object drawable = (Object) h.modelGetDrawable.invokeExact(model, guid);
            if (drawable != null && h.artMeshClass.isInstance(drawable)) {
                final Object src = (Object) h.meshGetSource.invokeExact(drawable);
                if (src != null) {
                    dst.put(src, Boolean.TRUE);
                }
            }
        }
    }

    private boolean executeIncrementalCoreUpdate(
            final EditModelTopologyCache c,
            final Object updater,
            final Object model,
            final Object updCtx,
            final Object paramSet,
            final IdentityHashMap<Object, Boolean> activeEditSources)
            throws Throwable {
        final int nDef = c.deformers.length;
        final boolean[] rootDirty = new boolean[nDef];
        final boolean[] needsReinterp = new boolean[nDef];
        final boolean[] dirtyDef = new boolean[nDef];

        if (!detectDirtyRootDeformers(c, activeEditSources, rootDirty, needsReinterp, dirtyDef)) {
            return false;
        }

        // Verify no local ArtMesh changes occurred (mesh-brush/vertex edits fall back to native).
        final int nMesh = c.visibleMeshes.length;
        if (!verifyLocalMeshesUnchanged(c, nMesh, activeEditSources)) {
            return false;
        }

        // Propagate dirty strictly down the deformer child DAG.
        propagateDeformerDirt(c, rootDirty, dirtyDef);

        // If any ArtPath is attached inside the dirty subtree, fall back to full update safely.
        if (c.hasArtPaths) {
            for (final int depDefIdx : c.artPathDeformerIdx) {
                if (depDefIdx >= 0 && dirtyDef[depDefIdx]) {
                    return false;
                }
            }
        }

        // Check if any dirty glue touches a host version without subset affecter support.
        final boolean[] dirtyMesh = new boolean[nMesh];
        for (int m = 0; m < nMesh; m++) {
            final int p = c.meshParentDeformerIdx[m];
            if (p >= 0 && dirtyDef[p]) {
                dirtyMesh[m] = true;
            }
        }

        final int nAff = c.affecters.length;
        final boolean[] dirtyGlue = new boolean[nAff];
        boolean anyGlueDirty = false;
        if (nAff > 0) {
            if (!c.allAffectersGlue) {
                return false;
            }
            anyGlueDirty = computeDirtyGlueClosure(c, dirtyMesh, dirtyGlue);
            if (anyGlueDirty && h.updaterUpdateAffectersSubset == null) {
                return false;
            }
        }

        // Verify all dirty root deformers with morph targets have resolved morphTarget helper.
        for (int i = 0; i < nDef; i++) {
            if (needsReinterp[i]) {
                final Object src = (Object) h.deformerGetSource.invokeExact(c.deformers[i]);
                final Object targets = (Object) h.sourceGetMorphTargets.invokeExact(src);
                if (targets instanceof List<?> list && !list.isEmpty() && h.morphTargetApply == null) {
                    return false;
                }
            }
        }

        // Update model timestamp without clearing clean siblings' parameter-outside flags.
        h.modelSetLastUpdatedTime.invokeExact(model, System.currentTimeMillis());

        // Re-interpolate only dirty root deformers whose source version or keyform edited state changed.
        for (int i = 0; i < nDef; i++) {
            if (needsReinterp[i]) {
                reinterpolateDeformer(c.deformers[i], paramSet, updCtx);
            }
        }

        // Evaluate dirty deformers strictly depth-by-depth (parallel when a level is wide enough).
        int dirtyDefCount = 0;
        for (int level = 0; level < c.levelDeformers.length; level++) {
            final int[] candidates = c.levelDeformers[level];
            final int[] activeAtLevel = filterDirtyIndices(candidates, dirtyDef);
            if (activeAtLevel.length == 0) {
                continue;
            }
            dirtyDefCount += activeAtLevel.length;
            updateTotalsForLevel(c, activeAtLevel);
            if (activeAtLevel.length >= SubtreeParallelExecutor.MIN_PARALLEL_DEFORMERS
                    && parallelExecutor.isParallelCapable()
                    && level > 0) {
                parallelDeformerLevels.increment();
                parallelExecutor.executeBatch(
                        activeAtLevel.length,
                        SubtreeParallelExecutor.MIN_PARALLEL_DEFORMERS,
                        idx -> transformChildDeformerUnchecked(c, activeAtLevel[idx]));
            } else {
                for (final int defIdx : activeAtLevel) {
                    transformDeformerIfChild(c, defIdx);
                }
            }
        }
        dirtyDeformersProcessed.add(dirtyDefCount);
        skippedDeformers.add(nDef - dirtyDefCount);

        // Ensure all dirty ArtMeshes have compatible target deformedForm buffers before parallel transform.
        final int[] dirtyMeshIndices = collectTrueIndices(dirtyMesh);
        for (final int mIdx : dirtyMeshIndices) {
            prepareArtMeshDeformedForm(c, mIdx);
        }
        if (dirtyMeshIndices.length >= SubtreeParallelExecutor.MIN_PARALLEL_MESHES
                && parallelExecutor.isParallelCapable()) {
            parallelMeshBatches.increment();
            parallelExecutor.executeBatch(
                    dirtyMeshIndices.length,
                    SubtreeParallelExecutor.MIN_PARALLEL_MESHES,
                    idx -> transformArtMeshUnchecked(c, dirtyMeshIndices[idx]));
        } else {
            for (final int mIdx : dirtyMeshIndices) {
                transformArtMesh(c, mIdx);
            }
        }

        // Recompute only dirty CGlue connected components (affecter subset supported on 5.2+).
        if (anyGlueDirty) {
            final List<Object> glueMeshes = new ArrayList<>();
            final List<Object> dirtyGlues = new ArrayList<>();
            for (int g = 0; g < nAff; g++) {
                if (!dirtyGlue[g]) {
                    continue;
                }
                dirtyGlues.add(c.affecters[g]);
                final int a = c.glueMeshAVisibleIdx[g];
                final int b = c.glueMeshBVisibleIdx[g];
                if (a >= 0) {
                    final Object meshA = c.visibleMeshes[a];
                    h.meshSetAffectedForm.invokeExact(meshA, (Object) null);
                    glueMeshes.add(meshA);
                    dirtyMesh[a] = true;
                }
                if (b >= 0) {
                    final Object meshB = c.visibleMeshes[b];
                    h.meshSetAffectedForm.invokeExact(meshB, (Object) null);
                    glueMeshes.add(meshB);
                    dirtyMesh[b] = true;
                }
            }
            if (!dirtyGlues.isEmpty()) {
                h.updaterUpdateAffectersSubset.invokeExact(
                        updater, model, paramSet, (Object) glueMeshes, (Object) dirtyGlues, updCtx, ZERO_F);
            }
        }

        // Refresh snapshots strictly for dirty meshes and arm appearance-skip for clean siblings.
        int finalDirtyMeshes = 0;
        for (int m = 0; m < nMesh; m++) {
            if (!dirtyMesh[m]) {
                continue;
            }
            finalDirtyMeshes++;
            c.recordMeshSnapshot(
                    m, activeEditSources.containsKey(c.visibleMeshSources[m]), h, fullArrayHashes);
        }
        c.armAppearanceSkip(Thread.currentThread(), dirtyMesh);
        dirtyMeshesProcessed.add(finalDirtyMeshes);
        skippedMeshes.add(nMesh - finalDirtyMeshes);

        // Refresh baseline snapshots strictly for dirty deformers & dirty glues, bumping deformer overlay epochs.
        if (dirtyDefCount > 0) {
            c.nextEpoch++;
            for (int i = 0; i < nDef; i++) {
                if (dirtyDef[i]) {
                    c.recordDeformerSnapshot(
                            i, activeEditSources.containsKey(c.deformerSources[i]), h, fullArrayHashes);
                    c.deformerEpochs.put(c.deformers[i], c.nextEpoch);
                    final int pIdx = c.deformerParentIdx[i];
                    if (c.isRotation[i] && pIdx >= 0 && c.isRotation[pIdx]) {
                        c.deformerEpochs.put(c.deformers[pIdx], c.nextEpoch);
                    }
                }
            }
        }
        if (anyGlueDirty) {
            for (int g = 0; g < nAff; g++) {
                if (dirtyGlue[g]) {
                    c.recordGlueSnapshot(g, h);
                }
            }
        }
        return true;
    }

    private boolean detectDirtyRootDeformers(
            final EditModelTopologyCache c,
            final IdentityHashMap<Object, Boolean> activeEditSources,
            final boolean[] rootDirty,
            final boolean[] needsReinterp,
            final boolean[] dirtyDef)
            throws Throwable {
        pruneUnselected(c.selectedDeformerBaselines, activeEditSources);
        for (int i = 0; i < c.deformers.length; i++) {
            final Object d = c.deformers[i];
            if ((Object) h.deformerGetAnimatedForm.invokeExact(d) != null
                    || (Object) h.deformerGetLocalAnimatedForm.invokeExact(d) != null) {
                return false;
            }
            if (!c.isWarp[i] && !c.isRotation[i]) {
                return false;
            }
            if (c.deformerParentIdx[i] >= 0 && (Object) h.deformerGetDeformedForm.invokeExact(d) == null) {
                return false;
            }
            final Object src = c.deformerSources[i];
            final int srcVer = (int) h.sourceGetUpdateVersion.invokeExact(src);
            final int copiedVer = (int) h.deformerGetCopiedUpdateVersion.invokeExact(d);
            final int kfStamp = EditModelTopologyCache.computeKeyformVersionStamp(src, h);
            boolean versionOrKeyformChanged =
                    srcVer != c.lastDefSourceUpdateVer[i]
                            || copiedVer != srcVer
                            || kfStamp != c.lastDefKeyformVersionStamp[i];

            boolean formMutated = versionOrKeyformChanged || (boolean) h.deformerGetDirtyDeformedForm.invokeExact(d);
            final Object form = (Object) h.deformerGetInterpolatedForm.invokeExact(d);
            if (!formMutated
                    && (form != c.lastDefInterpForm[i]
                            || (form != null
                                    && (int) h.formGetEditVersion.invokeExact(form) != c.lastDefInterpEditVer[i]))) {
                formMutated = true;
            }
            if (!versionOrKeyformChanged && activeEditSources.containsKey(src)) {
                final EditModelTopologyCache.SelectedDeformerBaseline sb = c.selectedDeformerBaselines.get(src);
                if (sb == null) {
                    // First frame seeing this selected deformer evaluates its subtree once and records baseline.
                    versionOrKeyformChanged = true;
                    formMutated = true;
                } else {
                    final int diff = sb.diff(src, form, c.isWarp[i], c.isRotation[i], h, fullArrayHashes);
                    if (diff == EditModelTopologyCache.SelectedDeformerBaseline.DIFF_KEYFORM) {
                        versionOrKeyformChanged = true;
                        formMutated = true;
                    } else if (diff == EditModelTopologyCache.SelectedDeformerBaseline.DIFF_LOCAL) {
                        formMutated = true;
                    }
                }
            }
            if (formMutated) {
                rootDirty[i] = true;
                dirtyDef[i] = true;
                needsReinterp[i] = versionOrKeyformChanged
                        || !(boolean) h.updaterOptimizeUpdateParam.invokeExact(h.updaterInstance);
            }
        }
        return true;
    }

    private boolean verifyLocalMeshesUnchanged(
            final EditModelTopologyCache c,
            final int nMesh,
            final IdentityHashMap<Object, Boolean> activeEditSources)
            throws Throwable {
        pruneUnselected(c.selectedMeshBaselines, activeEditSources);
        for (int m = 0; m < nMesh; m++) {
            final Object mesh = c.visibleMeshes[m];
            if ((Object) h.meshGetAnimatedForm.invokeExact(mesh) != null
                    || (Object) h.meshGetLocalAnimatedForm.invokeExact(mesh) != null) {
                return false;
            }
            if ((Object) h.meshGetDeformedForm.invokeExact(mesh) == null && c.meshParentDeformerIdx[m] >= 0) {
                return false;
            }
            final Object src = c.visibleMeshSources[m];
            if ((int) h.meshSourceGetMeshVersion.invokeExact(src) != c.lastMeshVer[m]
                    || (int) h.meshGetCopiedMeshVersion.invokeExact(mesh) != c.lastMeshCopiedVer[m]
                    || EditModelTopologyCache.computeKeyformVersionStamp(src, h) != c.lastMeshKeyformVersionStamp[m]
                    || (boolean) h.meshSourceIsVisible.invokeExact(src) != c.lastMeshVisible[m]
                    || (boolean) h.meshSourceIsVisibleHierarchy.invokeExact(src) != c.lastMeshVisHierarchy[m]
                    || (int) h.meshGetDrawOrder.invokeExact(mesh) != c.lastMeshDrawOrder[m]) {
                return false;
            }
            final Object form = (Object) h.meshGetInterpolatedForm.invokeExact(mesh);
            if (form != c.lastMeshInterpForm[m]
                    || (form != null && (int) h.formGetEditVersion.invokeExact(form) != c.lastMeshInterpEditVer[m])) {
                return false;
            }
            if (activeEditSources.containsKey(src)) {
                final EditModelTopologyCache.SelectedMeshBaseline mb = c.selectedMeshBaselines.get(src);
                if (mb == null || !mb.matches(src, form, h, fullArrayHashes)) {
                    return false;
                }
            }
            final Object container = (Object) h.meshGetContainer.invokeExact(mesh);
            final Object gmesh = container == null ? null : (Object) h.meshContainerGetMesh.invokeExact(container);
            final float[] gPos = gmesh == null ? null : (float[]) h.meshBaseGetPositions.invokeExact(gmesh);
            if (gPos != c.lastMeshGMeshPosRefs[m]) {
                if (c.lastMeshGMeshPosRefs[m] != null
                        && c.lastMeshGMeshPosRefs[m].length == 0
                        && gPos != null
                        && gPos.length > 0) {
                    // Adopt initial GMesh2 allocation made after first post-baseline updateModel loop.
                    c.lastMeshGMeshPosRefs[m] = gPos;
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    private static <V> void pruneUnselected(
            final Map<Object, V> baselines, final IdentityHashMap<Object, Boolean> activeEditSources) {
        if (baselines.isEmpty()) {
            return;
        }
        if (activeEditSources.isEmpty()) {
            baselines.clear();
        } else {
            baselines.keySet().retainAll(activeEditSources.keySet());
        }
    }

    private static void propagateDeformerDirt(
            final EditModelTopologyCache c, final boolean[] rootDirty, final boolean[] dirtyDef) {
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < rootDirty.length; i++) {
            if (rootDirty[i]) {
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            final int parent = queue.removeFirst();
            for (final int child : c.deformerChildren[parent]) {
                if (!dirtyDef[child]) {
                    dirtyDef[child] = true;
                    queue.add(child);
                }
            }
        }
    }

    private boolean computeDirtyGlueClosure(
            final EditModelTopologyCache c, final boolean[] dirtyMesh, final boolean[] dirtyGlue) throws Throwable {
        boolean any = false;
        final ArrayDeque<Integer> meshQueue = new ArrayDeque<>();
        for (int g = 0; g < c.affecters.length; g++) {
            final Object aff = c.affecters[g];
            final Object src = (Object) h.glueGetSource.invokeExact(aff);
            final Object form = (Object) h.glueGetInterpolatedForm.invokeExact(aff);
            final boolean ownDirty = (int) h.glueGetCachedEditVersion.invokeExact(aff) != c.lastGlueCachedVer[g]
                    || (int) h.glueSourceGetEditVersion.invokeExact(src) != c.lastGlueSourceVer[g]
                    || (form != null
                            && Float.floatToRawIntBits((float) h.glueFormGetIntensity.invokeExact(form))
                                    != c.lastGlueIntensityBits[g]);
            final int a = c.glueMeshAVisibleIdx[g];
            final int b = c.glueMeshBVisibleIdx[g];
            if (ownDirty || (a >= 0 && dirtyMesh[a]) || (b >= 0 && dirtyMesh[b])) {
                dirtyGlue[g] = true;
                any = true;
                if (a >= 0) {
                    meshQueue.add(a);
                }
                if (b >= 0) {
                    meshQueue.add(b);
                }
            }
        }
        while (!meshQueue.isEmpty()) {
            final int m = meshQueue.removeFirst();
            final int[] glues = c.meshGlueAdjacency[m];
            if (glues == null) {
                continue;
            }
            for (final int g : glues) {
                if (!dirtyGlue[g]) {
                    dirtyGlue[g] = true;
                    final int a = c.glueMeshAVisibleIdx[g];
                    final int b = c.glueMeshBVisibleIdx[g];
                    if (a >= 0) {
                        meshQueue.add(a);
                    }
                    if (b >= 0) {
                        meshQueue.add(b);
                    }
                }
            }
        }
        return any;
    }

    private void reinterpolateDeformer(final Object deformer, final Object paramSet, final Object updCtx)
            throws Throwable {
        final Object src = (Object) h.deformerGetSource.invokeExact(deformer);
        final int srcVer = (int) h.sourceGetUpdateVersion.invokeExact(src);
        final int copiedVer = (int) h.deformerGetCopiedUpdateVersion.invokeExact(deformer);
        if (copiedVer != srcVer) {
            h.sourceReinitInstance.invokeExact(src, deformer);
        }
        final Object interpForm = (Object) h.deformerGetInterpolatedForm.invokeExact(deformer);
        if (interpForm != null) {
            Object gridSrc = (Object) h.sourceGetExtKeyformGrid.invokeExact(src);
            if (gridSrc == null) {
                gridSrc = (Object) h.sourceGetKeyformGrid.invokeExact(src);
            }
            if (gridSrc != null) {
                h.deformerFormInterpolate.invokeExact(interpForm, gridSrc, paramSet, updCtx);
                h.deformerFormSetCoordType.invokeExact(interpForm, (Object) h.sourceGetCoordType.invokeExact(src));
            }
        }
        if (h.morphTargetApply != null) {
            final Object targets = (Object) h.sourceGetMorphTargets.invokeExact(src);
            if (targets instanceof List<?> list && !list.isEmpty()) {
                final Object params = (Object) h.paramSetGetParameters.invokeExact(paramSet);
                h.morphTargetApply.invokeExact(h.morphTargetInstance, deformer, params);
            }
        }
        h.deformerSetDirtyDeformedForm.invokeExact(deformer, true);
    }

    private void updateTotalsForLevel(final EditModelTopologyCache c, final int[] defIndices) throws Throwable {
        for (final int i : defIndices) {
            final Object d = c.deformers[i];
            final int pIdx = c.deformerParentIdx[i];
            final float localScale = c.isRotation[i]
                    ? (float) h.rotFormGetScale.invokeExact((Object) h.deformerGetInterpolatedForm.invokeExact(d))
                    : UNIT_SCALE;
            final Object localForm = (Object) h.deformerGetInterpolatedForm.invokeExact(d);
            final float localOp = (float) h.deformerFormGetOpacity.invokeExact(localForm);
            final Object localMul = (Object) h.deformerFormGetMultiplyColor.invokeExact(localForm);
            final Object localScr = (Object) h.deformerFormGetScreenColor.invokeExact(localForm);

            final Object totalMul = (Object) h.deformerGetCalcTotalMultiplyColor.invokeExact(d);
            final Object totalScr = (Object) h.deformerGetCalcTotalScreenColor.invokeExact(d);

            if (pIdx < 0) {
                h.deformerSetParamOutsideAnyParents.invokeExact(d, false);
                h.deformerSetCalcTotalScale.invokeExact(d, localScale);
                h.deformerSetCalcTotalOpacity.invokeExact(d, localOp);
                copyFloatColor(totalMul, localMul);
                copyFloatColor(totalScr, localScr);
            } else {
                final Object parent = c.deformers[pIdx];
                h.deformerSetParamOutsideAnyParents.invokeExact(
                        d, (boolean) h.deformerIsParamOutsideHierarchy.invokeExact(parent));
                h.deformerSetCalcTotalScale.invokeExact(
                        d, localScale * (float) h.deformerGetCalcTotalScale.invokeExact(parent));
                h.deformerSetCalcTotalOpacity.invokeExact(
                        d, localOp * (float) h.deformerGetCalcTotalOpacity.invokeExact(parent));

                final Object pMul = (Object) h.deformerGetCalcTotalMultiplyColor.invokeExact(parent);
                final Object pScr = (Object) h.deformerGetCalcTotalScreenColor.invokeExact(parent);
                multiplyFloatColor(totalMul, localMul, pMul);
                screenFloatColor(totalScr, localScr, pScr);
            }
        }
    }

    private void transformDeformerIfChild(final EditModelTopologyCache c, final int defIdx) throws Throwable {
        final Object d = c.deformers[defIdx];
        final int pIdx = c.deformerParentIdx[defIdx];
        if (pIdx >= 0) {
            h.deformerTransformTestImpl.invokeExact(d, c.deformers[pIdx]);
        }
        h.deformerSetDirtyDeformedForm.invokeExact(d, false);
    }

    private void transformChildDeformerUnchecked(final EditModelTopologyCache c, final int defIdx) {
        try {
            transformDeformerIfChild(c, defIdx);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("parallel child deformer transform failed", failure);
        }
    }

    private void prepareArtMeshDeformedForm(final EditModelTopologyCache c, final int mIdx) throws Throwable {
        final int pIdx = c.meshParentDeformerIdx[mIdx];
        final Object mesh = c.visibleMeshes[mIdx];
        if (pIdx < 0) {
            h.meshSetDeformedForm.invokeExact(mesh, (Object) null);
            return;
        }
        final Object preForm = (Object) h.meshGetInterpolatedForm.invokeExact(mesh);
        Object out = (Object) h.meshGetDeformedForm.invokeExact(mesh);
        if (out == null || !(boolean) h.meshFormIsCompatible.invokeExact(out, preForm)) {
            final Object tr = (Object) h.deformerGetCreateTransform.invokeExact(c.deformers[pIdx]);
            out = (Object) h.meshFormTransform.invokeExact(preForm, tr, (Object) null);
            h.meshSetDeformedForm.invokeExact(mesh, out);
        }
    }

    private void transformArtMesh(final EditModelTopologyCache c, final int mIdx) throws Throwable {
        final int pIdx = c.meshParentDeformerIdx[mIdx];
        if (pIdx < 0) {
            return;
        }
        final Object mesh = c.visibleMeshes[mIdx];
        final Object parent = c.deformers[pIdx];
        final Object preForm = (Object) h.meshGetInterpolatedForm.invokeExact(mesh);
        final Object out = (Object) h.meshGetDeformedForm.invokeExact(mesh);
        final Object tr = (Object) h.deformerGetCreateTransform.invokeExact(parent);
        final Object transformed = (Object) h.meshFormTransform.invokeExact(preForm, tr, out);
        h.meshFormSetCoordType.invokeExact(transformed, h.canvasCoordType);
        if (transformed != out) {
            h.meshSetDeformedForm.invokeExact(mesh, transformed);
        }
        h.meshSetParamOutsideAnyParents.invokeExact(
                mesh, (boolean) h.deformerIsParamOutsideHierarchy.invokeExact(parent));
    }

    private void transformArtMeshUnchecked(final EditModelTopologyCache c, final int mIdx) {
        try {
            transformArtMesh(c, mIdx);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("parallel ArtMesh transform failed", failure);
        }
    }

    private void copyFloatColor(final Object dst, final Object src) throws Throwable {
        h.floatColorSetA.invokeExact(dst, (float) h.floatColorGetA.invokeExact(src));
        h.floatColorSetR.invokeExact(dst, (float) h.floatColorGetR.invokeExact(src));
        h.floatColorSetG.invokeExact(dst, (float) h.floatColorGetG.invokeExact(src));
        h.floatColorSetB.invokeExact(dst, (float) h.floatColorGetB.invokeExact(src));
    }

    private void multiplyFloatColor(final Object dst, final Object local, final Object parentTotal) throws Throwable {
        h.floatColorSetA.invokeExact(dst, (float) h.floatColorGetA.invokeExact(local));
        h.floatColorSetR.invokeExact(
                dst, (float) h.floatColorGetR.invokeExact(local) * (float) h.floatColorGetR.invokeExact(parentTotal));
        h.floatColorSetG.invokeExact(
                dst, (float) h.floatColorGetG.invokeExact(local) * (float) h.floatColorGetG.invokeExact(parentTotal));
        h.floatColorSetB.invokeExact(
                dst, (float) h.floatColorGetB.invokeExact(local) * (float) h.floatColorGetB.invokeExact(parentTotal));
    }

    private void screenFloatColor(final Object dst, final Object local, final Object parentTotal) throws Throwable {
        h.floatColorSetA.invokeExact(dst, (float) h.floatColorGetA.invokeExact(local));
        final float lr = (float) h.floatColorGetR.invokeExact(local);
        final float lg = (float) h.floatColorGetG.invokeExact(local);
        final float lb = (float) h.floatColorGetB.invokeExact(local);
        final float pr = (float) h.floatColorGetR.invokeExact(parentTotal);
        final float pg = (float) h.floatColorGetG.invokeExact(parentTotal);
        final float pb = (float) h.floatColorGetB.invokeExact(parentTotal);
        h.floatColorSetR.invokeExact(dst, (lr + pr) - (lr * pr));
        h.floatColorSetG.invokeExact(dst, (lg + pg) - (lg * pg));
        h.floatColorSetB.invokeExact(dst, (lb + pb) - (lb * pb));
    }

    private static int[] filterDirtyIndices(final int[] levelNodes, final boolean[] dirty) {
        int count = 0;
        for (final int idx : levelNodes) {
            if (dirty[idx]) {
                count++;
            }
        }
        if (count == 0) {
            return new int[0];
        }
        if (count == levelNodes.length) {
            return levelNodes;
        }
        final int[] out = new int[count];
        int k = 0;
        for (final int idx : levelNodes) {
            if (dirty[idx]) {
                out[k++] = idx;
            }
        }
        return out;
    }

    private static int[] collectTrueIndices(final boolean[] flags) {
        int count = 0;
        for (final boolean f : flags) {
            if (f) {
                count++;
            }
        }
        final int[] out = new int[count];
        int k = 0;
        for (int i = 0; i < flags.length; i++) {
            if (flags[i]) {
                out[k++] = i;
            }
        }
        return out;
    }
}
