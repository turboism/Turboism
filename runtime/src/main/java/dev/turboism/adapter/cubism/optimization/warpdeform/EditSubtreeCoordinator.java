package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.runtime.work.FatalErrors;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Edit-mode dirty-subtree incremental evaluator and single-dispatch DAG parallel transform coordinator.
 *
 * <p>Detects which root deformers changed between {@code updateModel} frames when parameters,
 * topology, and non-deformer drawables stayed invariant, propagates dirt strictly down the DAG,
 * re-interpolates dirty roots on EDT with zero-allocation morph blending and buffer reuse,
 * dispatches all dirty deformers and dirty ArtMeshes under one DAG barrier, and elides clean
 * ArtMesh appearance uploads. Any unrecognized mutation falls back fail-closed to host code.</p>
 */
final class EditSubtreeCoordinator {

    private static final float ZERO_F = 0.0f;
    private static final float UNIT_SCALE = 1.0f;
    private static final float NEG_ONE = -1.0f;
    private static final float ROT_ANGLE_MIN = -3600.0f;
    private static final float ROT_ANGLE_MAX = 3600.0f;
    private static final float ROT_SCALE_MIN = 1.0e-4f;
    private static final float ROT_SCALE_MAX = 100.0f;
    private static final int INITIAL_MORPH_ENTRIES_CAP = 16;

    private final EditSubtreeHandles h;
    private final SubtreeParallelExecutor parallelExecutor;
    private final WeakIdentityCache<Object, EditModelTopologyCache> modelCaches = new WeakIdentityCache<>();
    private final ThreadLocal<Boolean> inFastPath = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<IdentityHashMap<Object, Boolean>> activeEditSourcesScratch =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private final ThreadLocal<MorphScratch> morphScratch = ThreadLocal.withInitial(MorphScratch::new);

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
        morphScratch.remove();
        inFastPath.remove();
        activeEditSourcesScratch.remove();
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
                    model,
                    modelSource,
                    doc,
                    viewCtx,
                    updCtx,
                    ctx5,
                    paramSet,
                    curEditing,
                    activeEditSources,
                    h,
                    fullArrayHashes);
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

        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;

        // Verify all dirty root deformers with morph targets have resolved morphTarget helper.
        for (int i = 0; i < nDef; i++) {
            if (needsReinterp[i]) {
                final Object src = acc.deformerGetSource(c.deformers[i]);
                final List<?> targets = acc.sourceGetMorphTargets(src);
                if (targets != null && !targets.isEmpty() && h.morphTargetApply == null) {
                    return false;
                }
            }
        }

        // Update model timestamp without clearing clean siblings' parameter-outside flags.
        acc.modelSetLastUpdatedTime(model, System.currentTimeMillis());

        // Re-interpolate only dirty root deformers whose source version or keyform edited state changed.
        for (int i = 0; i < nDef; i++) {
            if (needsReinterp[i]) {
                reinterpolateDeformer(
                        c.deformers[i], c.deformerParentIdx[i] >= 0, c.isWarp[i], c.isRotation[i], paramSet, updCtx);
            }
        }

        // Execute all dirty deformer subtrees via single-dispatch DAG continuation stealing plus ArtMeshes.
        int dirtyDefCount = 0;
        for (final boolean flag : dirtyDef) {
            if (flag) {
                dirtyDefCount++;
            }
        }
        final int[] dirtyDefIndices = collectTrueIndices(dirtyDef);
        final int[] dirtyMeshIndices = collectTrueIndices(dirtyMesh);
        if (dirtyDefIndices.length > 0 || dirtyMeshIndices.length > 0) {
            if (parallelExecutor.isParallelCapable()
                    && dirtyDefIndices.length >= SubtreeParallelExecutor.MIN_PARALLEL_DEFORMERS) {
                parallelDeformerLevels.increment();
            }
            if (parallelExecutor.isParallelCapable()
                    && dirtyMeshIndices.length >= SubtreeParallelExecutor.MIN_PARALLEL_MESHES) {
                parallelMeshBatches.increment();
            }
            parallelExecutor.executeDagAndMeshes(
                    dirtyDefIndices,
                    dirtyDef,
                    c.deformerChildren,
                    c.deformerParentIdx,
                    defIdx -> evaluateDirtyDeformerUnchecked(c, defIdx, acc),
                    dirtyMeshIndices.length,
                    pos -> acc.prepareAndTransformArtMesh(
                            c.visibleMeshes[dirtyMeshIndices[pos]],
                            parentOrNull(c, c.meshParentDeformerIdx[dirtyMeshIndices[pos]]),
                            h.canvasCoordType));
        }
        dirtyDeformersProcessed.add(dirtyDefCount);
        skippedDeformers.add(nDef - dirtyDefCount);

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
            c.recordMeshSnapshot(m, activeEditSources.containsKey(c.visibleMeshSources[m]), h, fullArrayHashes);
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
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        pruneUnselected(c.selectedDeformerBaselines, activeEditSources);
        for (int i = 0; i < c.deformers.length; i++) {
            final Object d = c.deformers[i];
            if (acc.deformerGetAnimatedForm(d) != null || acc.deformerGetLocalAnimatedForm(d) != null) {
                return false;
            }
            if (!c.isWarp[i] && !c.isRotation[i]) {
                return false;
            }
            if (c.deformerParentIdx[i] >= 0 && acc.deformerGetDeformedForm(d) == null) {
                return false;
            }
            final Object src = c.deformerSources[i];
            final int srcVer = acc.sourceGetUpdateVersion(src);
            final int copiedVer = acc.deformerGetCopiedUpdateVersion(d);
            final int kfStamp = EditModelTopologyCache.computeKeyformVersionStamp(src, h);
            boolean versionOrKeyformChanged = srcVer != c.lastDefSourceUpdateVer[i]
                    || copiedVer != srcVer
                    || kfStamp != c.lastDefKeyformVersionStamp[i];

            boolean formMutated = versionOrKeyformChanged || acc.deformerGetDirtyDeformedForm(d);
            final Object form = acc.deformerGetInterpolatedForm(d);
            if (!formMutated
                    && (form != c.lastDefInterpForm[i]
                            || (form != null && acc.formGetEditVersion(form) != c.lastDefInterpEditVer[i]))) {
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
            final EditModelTopologyCache c, final int nMesh, final IdentityHashMap<Object, Boolean> activeEditSources)
            throws Throwable {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        pruneUnselected(c.selectedMeshBaselines, activeEditSources);
        for (int m = 0; m < nMesh; m++) {
            final Object mesh = c.visibleMeshes[m];
            if (acc.meshGetAnimatedForm(mesh) != null || acc.meshGetLocalAnimatedForm(mesh) != null) {
                return false;
            }
            if (acc.meshGetDeformedForm(mesh) == null && c.meshParentDeformerIdx[m] >= 0) {
                return false;
            }
            final Object src = c.visibleMeshSources[m];
            if (acc.meshSourceGetMeshVersion(src) != c.lastMeshVer[m]
                    || acc.meshGetCopiedMeshVersion(mesh) != c.lastMeshCopiedVer[m]
                    || EditModelTopologyCache.computeKeyformVersionStamp(src, h) != c.lastMeshKeyformVersionStamp[m]
                    || acc.controllableSourceIsVisible(src) != c.lastMeshVisible[m]
                    || acc.controllableSourceIsVisibleHierarchy(src) != c.lastMeshVisHierarchy[m]
                    || acc.meshGetDrawOrder(mesh) != c.lastMeshDrawOrder[m]) {
                return false;
            }
            final Object form = acc.meshGetInterpolatedForm(mesh);
            if (form != c.lastMeshInterpForm[m]
                    || (form != null && acc.formGetEditVersion(form) != c.lastMeshInterpEditVer[m])) {
                return false;
            }
            if (activeEditSources.containsKey(src)) {
                final EditModelTopologyCache.SelectedMeshBaseline mb = c.selectedMeshBaselines.get(src);
                if (mb == null || !mb.matches(src, form, h, fullArrayHashes)) {
                    return false;
                }
            }
            final Object container = acc.meshGetContainer(mesh);
            final Object gmesh = container == null ? null : acc.meshContainerGetMesh(container);
            final float[] gPos = gmesh == null ? null : acc.meshBaseGetPositions(gmesh);
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

    private void reinterpolateDeformer(
            final Object deformer,
            final boolean hasParent,
            final boolean isWarp,
            final boolean isRotation,
            final Object paramSet,
            final Object updCtx)
            throws Throwable {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        final Object src = acc.deformerGetSource(deformer);
        final int srcVer = acc.sourceGetUpdateVersion(src);
        final int copiedVer = acc.deformerGetCopiedUpdateVersion(deformer);
        final Object prevDeformed =
                (copiedVer != srcVer && isWarp && hasParent) ? acc.deformerGetDeformedForm(deformer) : null;
        if (copiedVer != srcVer) {
            h.sourceReinitInstance.invokeExact(src, deformer);
        }
        final Object interpForm = acc.deformerGetInterpolatedForm(deformer);
        if (interpForm != null) {
            Object gridSrc = acc.sourceGetExtKeyformGrid(src);
            if (gridSrc == null) {
                gridSrc = acc.sourceGetKeyformGrid(src);
            }
            if (gridSrc != null) {
                h.deformerFormInterpolate.invokeExact(interpForm, gridSrc, paramSet, updCtx);
                acc.deformerFormSetCoordType(interpForm, acc.sourceGetCoordType(src));
            }
        }
        if (h.morphTargetApply != null) {
            final List<?> targets = acc.sourceGetMorphTargets(src);
            if (targets != null && !targets.isEmpty()) {
                final List<?> params = acc.paramSetGetParameters(paramSet);
                applyMorphTargetsFast(deformer, src, interpForm, targets, params, isWarp, isRotation, acc);
            }
        }
        if (prevDeformed != null && acc.deformerGetDeformedForm(deformer) == null && interpForm != null) {
            // Reattach compatible deformedForm buffer cleared by reinitInstance to avoid deepCopy churn.
            if (acc.formIsCompatible(prevDeformed, interpForm)) {
                acc.deformerSetDeformedForm(deformer, prevDeformed);
            }
        }
        acc.deformerSetDirtyDeformedForm(deformer, true);
    }

    private void applyMorphTargetsFast(
            final Object deformer,
            final Object src,
            final Object interpForm,
            final List<?> targets,
            final List<?> params,
            final boolean isWarp,
            final boolean isRotation,
            final EditSubtreeCompiledAccessors.HostOps acc)
            throws Throwable {
        if (interpForm == null
                || params == null
                || h.morphTargetUtilsInstance == null
                || h.morphTargetUseExtended == null
                || h.morphTargetResolveBaseForm == null
                || h.morphTargetSetGetList == null
                || h.morphTargetSetGetConstraintSet == null
                || h.morphTargetConstraintGetByParam == null
                || h.morphItemGetParameterGuid == null
                || h.morphItemGetKeyValue == null
                || h.morphItemGetKeyform == null
                || h.copyInfoDefault == null) {
            h.morphTargetApply.invokeExact(h.morphTargetInstance, deformer, (Object) params);
            return;
        }
        final boolean useExtended = (boolean) h.morphTargetUseExtended.invokeExact(h.morphTargetUtilsInstance, src);
        Object morphSet = useExtended ? acc.sourceGetExtMorphSet(src) : null;
        if (morphSet == null) {
            morphSet = acc.sourceGetMorphSet(src);
        }
        if (morphSet == null) {
            h.morphTargetApply.invokeExact(h.morphTargetInstance, deformer, (Object) params);
            return;
        }
        final Object constraintSet = h.morphTargetSetGetConstraintSet.invokeExact(morphSet);
        final Object lower = h.morphTargetResolveBaseForm.invokeExact(h.keyFormMorphCompanion, src);
        final MorphScratch scratch = morphScratch.get();
        final int targetCount = targets.size();
        final int paramCount = params.size();

        for (int pIdx = 0; pIdx < paramCount; pIdx++) {
            final Object param = params.get(pIdx);
            final Object paramGuid = acc.paramGetGuid(param);
            if (!targetsContainGuid(targets, targetCount, paramGuid)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final List<Object> kfMorphs =
                    (List<Object>) (Object) h.morphTargetSetGetList.invokeExact(morphSet, paramGuid);
            if (kfMorphs == null || kfMorphs.isEmpty() || lower == null) {
                continue;
            }
            final Object paramSrc = acc.paramGetSource(param);
            final Object paramSrcGuid = acc.paramSourceGetGuid(paramSrc);
            final float paramMaxVal = acc.paramSourceGetMaxValue(paramSrc);
            final float paramVal = acc.paramGetValue(param);
            final int entryCount = buildSortedMorphEntries(scratch, kfMorphs, paramSrcGuid, paramMaxVal, lower);
            final Object blendedMorph;
            if (entryCount < 2) {
                blendedMorph = lower;
            } else {
                blendedMorph = evaluateMorphInterval(
                        scratch, scratch.entries, entryCount, paramVal, lower, isWarp, acc, h.copyInfoDefault);
            }

            float weight = UNIT_SCALE;
            if (constraintSet != null) {
                @SuppressWarnings("unchecked")
                final List<Object> constraints =
                        (List<Object>) (Object) h.morphTargetConstraintGetByParam.invokeExact(constraintSet, paramGuid);
                if (constraints != null && !constraints.isEmpty()) {
                    if (h.morphConstraintEval == null || h.morphConstraintEvalInstance == null) {
                        // Fall back cleanly when constraint evaluator helper is unavailable.
                        h.morphTargetApply.invokeExact(h.morphTargetInstance, deformer, (Object) params);
                        return;
                    }
                    final Float evaluated = (Float) (Object) h.morphConstraintEval.invokeExact(
                            h.morphConstraintEvalInstance, (Object) constraints, (Object) params);
                    if (evaluated != null) {
                        weight = evaluated;
                    }
                }
            }

            final Object delta = scratch.obtainDeltaForm(blendedMorph, isWarp, acc, h.copyInfoDefault);
            acc.formBlend(delta, lower, NEG_ONE);
            acc.formPostBlend(delta);
            acc.formBlend(interpForm, delta, weight);
            acc.formPostBlend(interpForm);
        }

        acc.deformerSetPreClampMorphedForm(deformer, acc.formDeepCopy(interpForm, h.copyInfoDefault));
        clampDeformerForm(interpForm, isRotation, acc);
    }

    private boolean targetsContainGuid(final List<?> targets, final int size, final Object paramGuid) throws Throwable {
        for (int i = 0; i < size; i++) {
            final Object itemGuid = h.morphItemGetParameterGuid.invokeExact(targets.get(i));
            if (paramGuid.equals(itemGuid)) {
                return true;
            }
        }
        return false;
    }

    private int buildSortedMorphEntries(
            final MorphScratch scratch,
            final List<Object> kfMorphs,
            final Object paramSrcGuid,
            final float paramMaxVal,
            final Object lower)
            throws Throwable {
        final int kfSize = kfMorphs.size();
        scratch.ensureCapacity(kfSize + 1);
        int count = 0;
        for (int i = 0; i < kfSize; i++) {
            final Object item = kfMorphs.get(i);
            final Object itemGuid = h.morphItemGetParameterGuid.invokeExact(item);
            if (!paramSrcGuid.equals(itemGuid)) {
                continue;
            }
            final Object kf = h.morphItemGetKeyform.invokeExact(item);
            if (kf == null) {
                continue;
            }
            final Float keyVal = (Float) (Object) h.morphItemGetKeyValue.invokeExact(item);
            final float v = keyVal != null ? keyVal : paramMaxVal;
            scratch.entries[count++].set(kf, v);
        }
        scratch.entries[count++].set(lower, ZERO_F);
        if (count > 1) {
            Arrays.sort(scratch.entries, 0, count, MorphKeyEntry::compareValue);
        }
        return count;
    }

    private static Object evaluateMorphInterval(
            final MorphScratch scratch,
            final MorphKeyEntry[] entries,
            final int count,
            final float paramVal,
            final Object lower,
            final boolean isWarp,
            final EditSubtreeCompiledAccessors.HostOps acc,
            final Object defaultCopyInfo) {
        MorphKeyEntry lowEntry = null;
        for (int i = count - 1; i >= 0; i--) {
            if (entries[i].keyValue <= paramVal) {
                lowEntry = entries[i];
                break;
            }
        }
        MorphKeyEntry highEntry = null;
        for (int i = 0; i < count; i++) {
            if (paramVal < entries[i].keyValue) {
                highEntry = entries[i];
                break;
            }
        }
        if (lowEntry == null && highEntry == null) {
            return lower;
        }
        if (lowEntry == null) {
            return highEntry.form;
        }
        if (highEntry == null) {
            return lowEntry.form;
        }
        final float t = (paramVal - lowEntry.keyValue) / (highEntry.keyValue - lowEntry.keyValue);
        final float oneMinusT = UNIT_SCALE - t;
        final Object blended = scratch.obtainBlendedForm(lowEntry.form, isWarp, acc, defaultCopyInfo);
        acc.formZero(blended);
        acc.formBlend(blended, lowEntry.form, oneMinusT);
        acc.formBlend(blended, highEntry.form, t);
        acc.formPostBlend(blended);
        return blended;
    }

    private static void clampDeformerForm(
            final Object form, final boolean isRotation, final EditSubtreeCompiledAccessors.HostOps acc) {
        acc.deformerFormSetOpacity(form, clampFloat(acc.deformerFormGetOpacity(form), ZERO_F, UNIT_SCALE));
        acc.floatColorClamp(acc.deformerFormGetMultiplyColor(form));
        acc.floatColorClamp(acc.deformerFormGetScreenColor(form));
        if (isRotation) {
            acc.rotFormSetAngle(form, clampFloat(acc.rotFormGetAngle(form), ROT_ANGLE_MIN, ROT_ANGLE_MAX));
            acc.rotFormSetScale(form, clampFloat(acc.rotFormGetScale(form), ROT_SCALE_MIN, ROT_SCALE_MAX));
        }
    }

    private static float clampFloat(final float v, final float min, final float max) {
        if (v < min) {
            return min;
        }
        return v > max ? max : v;
    }

    private static Object parentOrNull(final EditModelTopologyCache c, final int pIdx) {
        return pIdx < 0 ? null : c.deformers[pIdx];
    }

    private static void evaluateDirtyDeformerUnchecked(
            final EditModelTopologyCache c, final int defIdx, final EditSubtreeCompiledAccessors.HostOps acc) {
        final Object parent = parentOrNull(c, c.deformerParentIdx[defIdx]);
        final Object deformer = c.deformers[defIdx];
        acc.updateDeformerTotals(deformer, parent, c.isRotation[defIdx]);
        acc.transformDeformerIfChild(deformer, parent);
    }

    private static void prepareAndTransformArtMeshUnchecked(
            final EditModelTopologyCache c, final int mIdx, final EditSubtreeCompiledAccessors.HostOps acc) {
        final Object parent = parentOrNull(c, c.meshParentDeformerIdx[mIdx]);
        acc.prepareAndTransformArtMesh(c.visibleMeshes[mIdx], parent, null);
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

    private static final class MorphKeyEntry {
        Object form;
        float keyValue;

        void set(final Object form, final float keyValue) {
            this.form = form;
            this.keyValue = keyValue;
        }

        static int compareValue(final MorphKeyEntry a, final MorphKeyEntry b) {
            return Float.compare(a.keyValue, b.keyValue);
        }
    }

    private static final class MorphScratch {
        MorphKeyEntry[] entries;
        Object blendedWarpForm;
        int blendedWarpPosLen = -1;
        Object deltaWarpForm;
        int deltaWarpPosLen = -1;
        Object blendedRotForm;
        Object deltaRotForm;

        MorphScratch() {
            entries = new MorphKeyEntry[INITIAL_MORPH_ENTRIES_CAP];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = new MorphKeyEntry();
            }
        }

        void ensureCapacity(final int needed) {
            if (entries.length >= needed) {
                return;
            }
            final int oldLen = entries.length;
            entries = Arrays.copyOf(entries, Math.max(needed, oldLen * 2));
            for (int i = oldLen; i < entries.length; i++) {
                entries[i] = new MorphKeyEntry();
            }
        }

        Object obtainBlendedForm(
                final Object template,
                final boolean isWarp,
                final EditSubtreeCompiledAccessors.HostOps acc,
                final Object defaultCopyInfo) {
            if (defaultCopyInfo == null) {
                throw new IllegalStateException("Missing default CopyInfo for morph blend copy.");
            }
            if (isWarp) {
                final float[] pos = acc.warpFormGetPositions(template);
                final int len = pos == null ? -1 : pos.length;
                if (blendedWarpForm == null || blendedWarpPosLen != len) {
                    blendedWarpForm = acc.formDeepCopy(template, defaultCopyInfo);
                    blendedWarpPosLen = len;
                }
                return blendedWarpForm;
            }
            if (blendedRotForm == null) {
                blendedRotForm = acc.formDeepCopy(template, defaultCopyInfo);
            }
            return blendedRotForm;
        }

        Object obtainDeltaForm(
                final Object src,
                final boolean isWarp,
                final EditSubtreeCompiledAccessors.HostOps acc,
                final Object defaultCopyInfo) {
            if (isWarp) {
                final float[] srcPos = acc.warpFormGetPositions(src);
                final int len = srcPos == null ? -1 : srcPos.length;
                if (deltaWarpForm == null || deltaWarpPosLen != len) {
                    deltaWarpForm = acc.formDeepCopy(src, defaultCopyInfo);
                    deltaWarpPosLen = len;
                } else {
                    final float[] dstPos = acc.warpFormGetPositions(deltaWarpForm);
                    if (srcPos != null && dstPos != null) {
                        System.arraycopy(srcPos, 0, dstPos, 0, len);
                    }
                    acc.deformerFormSetOpacity(deltaWarpForm, acc.deformerFormGetOpacity(src));
                    copyColor(
                            acc.deformerFormGetMultiplyColor(deltaWarpForm),
                            acc.deformerFormGetMultiplyColor(src),
                            acc);
                    copyColor(acc.deformerFormGetScreenColor(deltaWarpForm), acc.deformerFormGetScreenColor(src), acc);
                }
                return deltaWarpForm;
            }
            if (deltaRotForm == null) {
                deltaRotForm = acc.formDeepCopy(src, defaultCopyInfo);
            } else {
                acc.formReinit(deltaRotForm, src, defaultCopyInfo);
            }
            return deltaRotForm;
        }

        private static void copyColor(
                final Object dst, final Object src, final EditSubtreeCompiledAccessors.HostOps acc) {
            acc.colorSetA(dst, acc.colorGetA(src));
            acc.colorSetR(dst, acc.colorGetR(src));
            acc.colorSetG(dst, acc.colorGetG(src));
            acc.colorSetB(dst, acc.colorGetB(src));
        }
    }
}
