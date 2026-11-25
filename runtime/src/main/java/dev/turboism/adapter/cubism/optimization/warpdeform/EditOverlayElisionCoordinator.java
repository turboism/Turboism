package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Unchanged-deformer overlay elision coordinator for Cubism {@code K$b.a} (5.1+) and {@code J.E/F} (5.0.x).
 *
 * <p>Skips {@code draw.update(actionPack, selected)} and child-entity scans on clean, unselected
 * deformers whose geometry epoch and global overlay state match the previous pass, while still
 * keeping {@code setEnabled} and {@code GSceneGraph.setVolatile} synchronized every frame.</p>
 */
final class EditOverlayElisionCoordinator {

    private static final long HASH_PRIME = 31L;
    private static final long BOOL_TRUE_HASH = 1231L;
    private static final long BOOL_FALSE_HASH = 1237L;
    private static final long NULL_SENTINEL_HASH = 17L;
    private static final long UNINIT_LAZY_HASH = -19937L;
    private static final String SELECTION_DRAW_KEY = WarpDeformEditTarget.SELECTION_DRAW_KEY;

    private final EditSubtreeHandles h;
    private final EditSubtreeCoordinator subtreeCoordinator;
    private final WeakIdentityCache<Object, OverlayEntry> entries = new WeakIdentityCache<>();
    private final WeakIdentityCache<Object, SelectionDrawState> selectionDrawStates = new WeakIdentityCache<>();
    private final WeakIdentityCache<Object, ArtMeshSelectionState> artMeshSelectionStates = new WeakIdentityCache<>();

    final LongAdder overlayElisions = new LongAdder();
    final LongAdder overlayUpdates = new LongAdder();
    final LongAdder selectionDrawElisions = new LongAdder();
    final LongAdder selectionDrawUpdates = new LongAdder();
    final LongAdder selectionMeshElisions = new LongAdder();
    final LongAdder selectionMeshCreateElisions = new LongAdder();
    final LongAdder selectionSetVolatileElisions = new LongAdder();

    private long cachedGlobalActionHash = Long.MIN_VALUE;
    private boolean cachedGlobalValid;
    private Set<Object> cachedRGuids = Set.of();
    private Set<Object> cachedFDeformers = Set.of();
    private Set<Object> cachedGDeformers = Set.of();
    private long legacyLastGlobalSig = Long.MIN_VALUE;
    private Object cachedSelMeshPack;
    private boolean cachedSelHide;
    private Object cachedSelHover;
    private Set<?> cachedSelWSet;
    private Set<?> cachedSelXSet;
    private Set<Object> cachedSelGGuids = Set.of();
    private boolean cachedSelHasHover;
    private boolean cachedSelHasW;
    private boolean cachedSelHasX;
    private boolean cachedSelHasG;
    private Object lastSelEvalEntity;
    private Object lastSelEvalPack;
    private boolean lastSelEvalSelected;
    private boolean lastSelEvalGlued;
    private boolean lastSelEvalActive;
    private Object memoActionPack;
    private Object memoEditMode;
    private boolean memoHideSelected;
    private boolean memoIsDeformerVisible;
    private Object memoRotMode;
    private long memoActionSig;
    private long memoNonActionSig;
    private FrameGlobalState memoFrameGlobalState;

    EditOverlayElisionCoordinator(final EditSubtreeHandles handles, final EditSubtreeCoordinator subtreeCoordinator) {
        this.h = handles;
        this.subtreeCoordinator = subtreeCoordinator;
    }

    void clearCaches() {
        entries.clear();
        selectionDrawStates.clear();
        artMeshSelectionStates.clear();
        cachedGlobalValid = false;
        cachedGlobalActionHash = Long.MIN_VALUE;
        legacyLastGlobalSig = Long.MIN_VALUE;
        cachedRGuids = Set.of();
        cachedFDeformers = Set.of();
        cachedGDeformers = Set.of();
        invalidateFrameGlobalMemo();
        resetSelMeshFrameCache();
    }

    private void invalidateFrameGlobalMemo() {
        memoActionPack = null;
        memoEditMode = null;
        memoRotMode = null;
        memoFrameGlobalState = null;
    }

    private void resetSelMeshFrameCache() {
        cachedSelMeshPack = null;
        cachedSelHover = null;
        cachedSelWSet = null;
        cachedSelXSet = null;
        cachedSelGGuids = Set.of();
        cachedSelHasHover = false;
        cachedSelHasW = false;
        cachedSelHasX = false;
        cachedSelHasG = false;
        lastSelEvalEntity = null;
        lastSelEvalPack = null;
        lastSelEvalSelected = false;
        lastSelEvalGlued = false;
        lastSelEvalActive = false;
    }

    /**
     * Entry gate for Cubism 5.1+ {@code K$b.a}. Returns {@code true} when overlay work for
     * {@code deformer} is safely elided (with {@code setEnabled} and {@code setVolatile} synced).
     */
    boolean tryElideOverlay(
            final Object deformer,
            final Object sceneGraph,
            final boolean hideSelected,
            final boolean isDeformerVisible,
            final Object rotMode,
            final Object actionPack) {
        if (deformer == null
                || sceneGraph == null
                || rotMode == null
                || actionPack == null
                || !h.actionPackClass.isInstance(actionPack)) {
            return false;
        }
        try {
            final Object editMode = (Object) h.actionPackGetEditMode.invokeExact(actionPack);
            if (editMode == null) {
                return false;
            }
            final FrameGlobalState frame =
                    captureFrameGlobal(actionPack, editMode, hideSelected, isDeformerVisible, rotMode);
            if (frame == null) {
                return false;
            }
            final OverlayEntry entry = entries.get(deformer);
            if (entry == null || entry.lastGlobalSig != frame.globalSig) {
                return false;
            }
            final Object src = (Object) h.deformerGetSource.invokeExact(deformer);
            if (src == null) {
                return false;
            }
            final boolean visInHier = (boolean) h.sourceIsVisibleHierarchy.invokeExact(src);
            final boolean showOverlay = visInHier && !hideSelected;
            if (showOverlay != entry.lastShowOverlay) {
                return false;
            }

            if (!showOverlay) {
                syncEntityEnabled(deformer, isDeformerVisible);
                overlayElisions.increment();
                return true;
            }

            if (entry.primaryDraw == null || (Object) h.entityGetParent.invokeExact(entry.primaryDraw) != deformer) {
                return false;
            }
            final boolean isWarp = h.warpDeformerClass.isInstance(deformer);
            final boolean isRot = !isWarp && h.rotDeformerClass.isInstance(deformer);
            if (!isWarp && !isRot) {
                return false;
            }
            final boolean useBone = isRot && resolveUseBoneUi(src, rotMode);
            if (useBone != entry.lastUseBone || !matchesDrawType(entry.primaryDraw, isWarp, useBone)) {
                return false;
            }
            if (isDeformerAffectedOrSelected(deformer, src, frame)) {
                return false;
            }
            if (!matchesDeformerGeometry(deformer, isWarp, entry)) {
                return false;
            }

            syncEntityEnabled(deformer, isDeformerVisible);
            syncEntityEnabled(entry.primaryDraw, isDeformerVisible);
            h.sceneGraphSetVolatile.invokeExact(sceneGraph, entry.primaryDraw);
            overlayElisions.increment();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /** Records updated overlay metadata on normal return of {@code K$b.a}. */
    void recordUpdatedOverlay(
            final Object deformer,
            final boolean hideSelected,
            final boolean isDeformerVisible,
            final Object rotMode,
            final Object actionPack) {
        if (deformer == null || rotMode == null || actionPack == null || !h.actionPackClass.isInstance(actionPack)) {
            return;
        }
        try {
            final Object editMode = (Object) h.actionPackGetEditMode.invokeExact(actionPack);
            if (editMode == null) {
                return;
            }
            final FrameGlobalState frame =
                    captureFrameGlobal(actionPack, editMode, hideSelected, isDeformerVisible, rotMode);
            if (frame == null) {
                return;
            }
            final Object src = (Object) h.deformerGetSource.invokeExact(deformer);
            if (src == null) {
                return;
            }
            final boolean showOverlay = (boolean) h.sourceIsVisibleHierarchy.invokeExact(src) && !hideSelected;
            final boolean isWarp = h.warpDeformerClass.isInstance(deformer);
            final boolean useBone =
                    !isWarp && h.rotDeformerClass.isInstance(deformer) && resolveUseBoneUi(src, rotMode);
            final Object draw = showOverlay ? findDrawEntity(deformer, isWarp, useBone) : null;
            if (showOverlay && draw == null) {
                return;
            }
            final OverlayEntry entry = entries.getOrCompute(deformer, OverlayEntry::new);
            recordEntryState(entry, deformer, isWarp, showOverlay, useBone, draw, frame.globalSig);
            overlayUpdates.increment();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Entry gate for Cubism 5.0.x {@code J.E}/{@code J.F}. Elides unchanged unselected deformers
     * and updates only dirty or selected deformers when every deformer already has a cached entity.
     */
    boolean tryElideLegacyOverlayLoop(final Object jView) {
        if (jView == null || !h.legacyOverlayModel) {
            return false;
        }
        try {
            final Object modelingView = (Object) h.jViewGetModelingView.invokeExact(jView);
            if (modelingView == null) {
                return false;
            }
            final Object actionPack = (Object) h.modelingViewGetLastActionPack.invokeExact(modelingView);
            if (actionPack == null || !h.actionPackClass.isInstance(actionPack)) {
                return false;
            }
            final Object editMode = (Object) h.actionPackGetEditMode.invokeExact(actionPack);
            if (editMode == null) {
                return false;
            }
            final Object deformersObj = (Object) h.actionPackGetDeformers.invokeExact(actionPack);
            if (!(deformersObj instanceof List<?> deformers)) {
                return false;
            }

            final Object canvasSetting = (Object) h.appSettingGetCanvas.invokeExact(h.appSettingInstance);
            final boolean hideSelected = (boolean) h.canvasSettingGetHideSelected.invokeExact(canvasSetting);
            final boolean isDeformerVisible = (boolean) h.modelingViewIsDeformerVisible.invokeExact(modelingView);
            final Object defSetting = (Object) h.appSettingGetDeformer.invokeExact(h.appSettingInstance);
            final Object rotSetting = (Object) h.deformerSettingGetRotDeformer.invokeExact(defSetting);
            final Object rotMode = (Object) h.rotSettingGetMode.invokeExact(rotSetting);
            final FrameGlobalState frame =
                    captureFrameGlobal(actionPack, editMode, hideSelected, isDeformerVisible, rotMode);
            if (frame == null || legacyLastGlobalSig != frame.globalSig) {
                return false;
            }
            final Object sceneGraph = (Object) h.modelingViewGetSceneGraph.invokeExact(modelingView);
            if (sceneGraph == null) {
                return false;
            }

            // Verify every deformer already has a valid cached entry matching its current mode & visibility.
            final int count = deformers.size();
            final OverlayEntry[] resolvedEntries = new OverlayEntry[count];
            final boolean[] showFlags = new boolean[count];
            final boolean[] dirtyFlags = new boolean[count];
            for (int i = 0; i < count; i++) {
                final Object d = deformers.get(i);
                final OverlayEntry entry = entries.get(d);
                if (entry == null) {
                    return false;
                }
                final Object src = (Object) h.deformerGetSource.invokeExact(d);
                if (src == null) {
                    return false;
                }
                final boolean show = (boolean) h.sourceIsVisibleHierarchy.invokeExact(src) && !hideSelected;
                if (show != entry.lastShowOverlay) {
                    return false;
                }
                if (show) {
                    if (entry.primaryDraw == null || (Object) h.entityGetParent.invokeExact(entry.primaryDraw) != d) {
                        return false;
                    }
                    final boolean isWarp = h.warpDeformerClass.isInstance(d);
                    final boolean isRot = !isWarp && h.rotDeformerClass.isInstance(d);
                    if (!isWarp && !isRot) {
                        return false;
                    }
                    final boolean useBone = isRot && resolveUseBoneUi(src, rotMode);
                    if (useBone != entry.lastUseBone || !matchesDrawType(entry.primaryDraw, isWarp, useBone)) {
                        return false;
                    }
                    dirtyFlags[i] =
                            isDeformerAffectedOrSelected(d, src, frame) || !matchesDeformerGeometry(d, isWarp, entry);
                }
                resolvedEntries[i] = entry;
                showFlags[i] = show;
            }

            for (int i = 0; i < count; i++) {
                final Object d = deformers.get(i);
                final OverlayEntry entry = resolvedEntries[i];
                if (!showFlags[i]) {
                    overlayElisions.increment();
                    continue;
                }
                syncEntityEnabled(entry.primaryDraw, true);
                if (!dirtyFlags[i]) {
                    h.sceneGraphSetVolatile.invokeExact(sceneGraph, entry.primaryDraw);
                    overlayElisions.increment();
                    continue;
                }
                final Object guid = (Object) h.deformerGetGuid.invokeExact(d);
                final boolean isSelected =
                        guid != null && (boolean) h.selectorIsSelected.invokeExact(frame.selector, guid);
                final boolean isWarp = h.warpDeformerClass.isInstance(d);
                invokeLegacyDrawUpdate(entry.primaryDraw, actionPack, isSelected, isWarp, entry.lastUseBone);
                h.sceneGraphSetVolatile.invokeExact(sceneGraph, entry.primaryDraw);
                recordEntryState(entry, d, isWarp, true, entry.lastUseBone, entry.primaryDraw, frame.globalSig);
                overlayUpdates.increment();
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /** Captures post-native overlay draw entities for Cubism 5.0.x {@code J.E}/{@code J.F}. */
    void recordLegacyOverlayLoop(final Object jView) {
        if (jView == null || !h.legacyOverlayModel) {
            return;
        }
        try {
            final Object modelingView = (Object) h.jViewGetModelingView.invokeExact(jView);
            if (modelingView == null) {
                return;
            }
            final Object actionPack = (Object) h.modelingViewGetLastActionPack.invokeExact(modelingView);
            if (actionPack == null || !h.actionPackClass.isInstance(actionPack)) {
                return;
            }
            final Object editMode = (Object) h.actionPackGetEditMode.invokeExact(actionPack);
            if (editMode == null) {
                return;
            }
            final Object deformersObj = (Object) h.actionPackGetDeformers.invokeExact(actionPack);
            if (!(deformersObj instanceof List<?> deformers)) {
                return;
            }
            final Object canvasSetting = (Object) h.appSettingGetCanvas.invokeExact(h.appSettingInstance);
            final boolean hideSelected = (boolean) h.canvasSettingGetHideSelected.invokeExact(canvasSetting);
            final boolean isDeformerVisible = (boolean) h.modelingViewIsDeformerVisible.invokeExact(modelingView);
            final Object defSetting = (Object) h.appSettingGetDeformer.invokeExact(h.appSettingInstance);
            final Object rotSetting = (Object) h.deformerSettingGetRotDeformer.invokeExact(defSetting);
            final Object rotMode = (Object) h.rotSettingGetMode.invokeExact(rotSetting);
            final FrameGlobalState frame =
                    captureFrameGlobal(actionPack, editMode, hideSelected, isDeformerVisible, rotMode);
            if (frame == null) {
                return;
            }
            legacyLastGlobalSig = frame.globalSig;
            for (final Object d : deformers) {
                final Object src = (Object) h.deformerGetSource.invokeExact(d);
                if (src == null) {
                    continue;
                }
                final boolean show = (boolean) h.sourceIsVisibleHierarchy.invokeExact(src) && !hideSelected;
                final boolean isWarp = h.warpDeformerClass.isInstance(d);
                final boolean useBone = !isWarp && h.rotDeformerClass.isInstance(d) && resolveUseBoneUi(src, rotMode);
                final Object draw = show ? findDrawEntity(d, isWarp, useBone) : null;
                final OverlayEntry entry = entries.getOrCompute(d, OverlayEntry::new);
                recordEntryState(entry, d, isWarp, show, useBone, draw, frame.globalSig);
                overlayUpdates.increment();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private FrameGlobalState captureFrameGlobal(
            final Object actionPack,
            final Object editMode,
            final boolean hideSelected,
            final boolean isDeformerVisible,
            final Object rotMode)
            throws Throwable {
        final Object selector = (Object) h.editModeMainGetSelector.invokeExact(editMode);
        if (selector == null) {
            return null;
        }
        final Object hovering = (Object) h.selectorGetHoveringObject.invokeExact(selector);
        final int selectedCount = (int) h.selectorGetSelectedCount.invokeExact(selector);
        final Object selObjRaw = (Object) h.selectorGetSelectedObjects.invokeExact(selector);
        final Object rRaw = (Object) h.actionPackGetSelected.invokeExact(actionPack);
        final Object fRaw = (Object) h.actionPackGetF.invokeExact(actionPack);
        final Object gRaw = (Object) h.actionPackGetG.invokeExact(actionPack);
        final boolean flagL = (boolean) h.actionPackGetL.invokeExact(actionPack);
        final boolean flagM = (boolean) h.actionPackGetM.invokeExact(actionPack);

        final Object devSetting = (Object) h.appSettingGetDev.invokeExact(h.appSettingInstance);
        final boolean showChildUnderWarp = (boolean) h.devSettingGetShowChildUnderWarp.invokeExact(devSetting)
                && (boolean) h.warpOIsCtrlChildMode.invokeExact(h.warpOInstance);

        final float cameraScale = resolveCameraScale();

        final long selHash = listIdentityHash(selObjRaw);
        final long rHash = listIdentityHash(rRaw);
        final long fHash = listIdentityHash(fRaw);
        final long gHash = listIdentityHash(gRaw);

        long actionSig = System.identityHashCode(selector);
        actionSig = actionSig * HASH_PRIME + System.identityHashCode(hovering);
        actionSig = actionSig * HASH_PRIME + selectedCount;
        actionSig = actionSig * HASH_PRIME + selHash;
        actionSig = actionSig * HASH_PRIME + rHash;
        actionSig = actionSig * HASH_PRIME + fHash;
        actionSig = actionSig * HASH_PRIME + gHash;
        actionSig = actionSig * HASH_PRIME + (flagL ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
        actionSig = actionSig * HASH_PRIME + (flagM ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);

        long nonActionSig = hideSelected ? BOOL_TRUE_HASH : BOOL_FALSE_HASH;
        nonActionSig = nonActionSig * HASH_PRIME + (isDeformerVisible ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
        nonActionSig = nonActionSig * HASH_PRIME + System.identityHashCode(rotMode);
        nonActionSig = nonActionSig * HASH_PRIME + (showChildUnderWarp ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
        nonActionSig = nonActionSig * HASH_PRIME + Float.floatToRawIntBits(cameraScale);

        if (memoFrameGlobalState != null
                && memoActionPack == actionPack
                && memoEditMode == editMode
                && memoHideSelected == hideSelected
                && memoIsDeformerVisible == isDeformerVisible
                && memoRotMode == rotMode
                && memoActionSig == actionSig
                && memoNonActionSig == nonActionSig) {
            return memoFrameGlobalState;
        }
        if (!cachedGlobalValid || cachedGlobalActionHash != actionSig) {
            cachedRGuids = extractTargetGuids(rRaw);
            cachedFDeformers = toIdentitySet(fRaw);
            cachedGDeformers = toIdentitySet(gRaw);
            cachedGlobalActionHash = actionSig;
            cachedGlobalValid = true;
        }
        final long combined = actionSig * HASH_PRIME + nonActionSig;
        final FrameGlobalState built =
                new FrameGlobalState(combined, selector, cachedRGuids, cachedFDeformers, cachedGDeformers);
        memoActionPack = actionPack;
        memoEditMode = editMode;
        memoHideSelected = hideSelected;
        memoIsDeformerVisible = isDeformerVisible;
        memoRotMode = rotMode;
        memoActionSig = actionSig;
        memoNonActionSig = nonActionSig;
        memoFrameGlobalState = built;
        return built;
    }

    /**
     * Entry gate for {@code updateSelectionDraw} ({@code J.D}/{@code K.D}/{@code K.E}).
     * Skips full-canvas selection entity scans when model geometry, selection, camera,
     * cursor, and view settings match the previous recorded pass.
     */
    boolean tryElideSelectionDraw(final Object viewHolder) {
        if (viewHolder == null || !hasSelectionDrawHandles()) {
            return false;
        }
        try {
            final SelectionDrawState state = selectionDrawStates.getOrCompute(viewHolder, SelectionDrawState::new);
            state.pendingRecord = false;
            state.pendingSceneGraph = null;
            state.pendingModel = null;
            state.pendingActionPack = null;
            if (!populateSelectionContext(viewHolder, state)) {
                state.valid = false;
                return false;
            }
            if (state.valid
                    && state.lastActionPack == state.pendingActionPack
                    && state.lastModel == state.pendingModel
                    && state.lastSig == state.pendingSig
                    && state.volatileEntities != null) {
                for (final Object entity : state.volatileEntities) {
                    h.sceneGraphSetVolatile.invokeExact(state.pendingSceneGraph, entity);
                }
                selectionDrawElisions.increment();
                return true;
            }
            state.valid = false;
            state.capturePreWillRender(h.sceneGraphWillRenderField.get(state.pendingSceneGraph));
            state.pendingRecord = true;
            invalidateFrameGlobalMemo();
            resetSelMeshFrameCache();
            return false;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /** Records volatile selection draw entities emitted by a native {@code updateSelectionDraw} pass. */
    void recordSelectionDraw(final Object viewHolder) {
        if (viewHolder == null || !hasSelectionDrawHandles()) {
            return;
        }
        try {
            final SelectionDrawState state = selectionDrawStates.get(viewHolder);
            if (state == null || !state.pendingRecord || state.pendingSceneGraph == null) {
                return;
            }
            final Object sceneGraph = state.pendingSceneGraph;
            state.pendingRecord = false;
            state.pendingSceneGraph = null;
            state.recordAddedVolatiles(h.sceneGraphWillRenderField.get(sceneGraph));
            state.pendingModel = null;
            state.pendingActionPack = null;
            selectionDrawUpdates.increment();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        } finally {
            invalidateFrameGlobalMemo();
            resetSelMeshFrameCache();
        }
    }

    /**
     * Lazy creation gate for {@code new DrawEntity_ArtMesh_Selection(artMesh)} when the overlay is inactive.
     * Returns {@code true} only when all active-overlay predicates are off.
     */
    boolean canElideArtMeshSelectionCreate(
            final Object artMesh, final Object actionPack, final boolean isSelected, final boolean isGlued) {
        if (isSelected || isGlued || artMesh == null || actionPack == null || h.meshSourceGetSelection == null) {
            return false;
        }
        try {
            if (isArtMeshOverlayActiveByMesh(artMesh, actionPack, false, false)) {
                return false;
            }
            selectionMeshCreateElisions.increment();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Per-mesh elision gate for {@code DrawEntity_ArtMesh_Selection.update} during active drag frames.
     * Skips rebuilding unchanged inactive ArtMesh selection entities when all overlay flags remain off.
     */
    boolean tryElideArtMeshSelectionUpdate(
            final Object drawEntity, final Object actionPack, final boolean isSelected, final boolean isGlued) {
        if (drawEntity == null
                || actionPack == null
                || h.artMeshSelectionDrawGetMesh == null
                || h.meshSourceGetSelection == null) {
            return false;
        }
        try {
            final boolean active = isArtMeshOverlayActiveForEntity(drawEntity, actionPack, isSelected, isGlued);
            recordLastSelEval(drawEntity, actionPack, isSelected, isGlued, active);
            final ArtMeshSelectionState state =
                    artMeshSelectionStates.getOrCompute(drawEntity, ArtMeshSelectionState::new);
            if (!state.initialized) {
                state.initialized = true;
                state.cleanlyDisabled = !active;
                return false;
            }
            if (!active && state.cleanlyDisabled) {
                selectionMeshElisions.increment();
                return true;
            }
            state.cleanlyDisabled = !active;
            return false;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    /**
     * Volatile-registration gate for {@code DrawEntity_ArtMesh_Selection} strictly using inactive-overlay predicates.
     * Never checks {@code !entity.getEnabled()}, because {@code preRender()} disables volatile entities before {@code K.E()}.
     */
    boolean shouldElideArtMeshSetVolatile(
            final Object drawEntity, final Object actionPack, final boolean isSelected, final boolean isGlued) {
        if (isSelected
                || isGlued
                || drawEntity == null
                || actionPack == null
                || h.artMeshSelectionDrawGetMesh == null
                || h.meshSourceGetSelection == null) {
            return false;
        }
        try {
            final boolean active;
            if (lastSelEvalEntity == drawEntity
                    && lastSelEvalPack == actionPack
                    && !lastSelEvalSelected
                    && !lastSelEvalGlued) {
                active = lastSelEvalActive;
            } else {
                active = isArtMeshOverlayActiveForEntity(drawEntity, actionPack, false, false);
            }
            if (!active) {
                selectionSetVolatileElisions.increment();
                return true;
            }
            return false;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private void recordLastSelEval(
            final Object drawEntity,
            final Object actionPack,
            final boolean isSelected,
            final boolean isGlued,
            final boolean active) {
        lastSelEvalEntity = drawEntity;
        lastSelEvalPack = actionPack;
        lastSelEvalSelected = isSelected;
        lastSelEvalGlued = isGlued;
        lastSelEvalActive = active;
    }

    private boolean isArtMeshOverlayActiveForEntity(
            final Object drawEntity, final Object actionPack, final boolean isSelected, final boolean isGlued)
            throws Throwable {
        if (cachedSelMeshPack != actionPack) {
            refreshSelMeshFrameCache(actionPack);
        }
        if (cachedSelHide) {
            return false;
        }
        if (isSelected || isGlued) {
            return true;
        }
        if (!cachedSelHasHover && !cachedSelHasW && !cachedSelHasX && !cachedSelHasG) {
            return false;
        }
        final Object mesh = (Object) h.artMeshSelectionDrawGetMesh.invokeExact(drawEntity);
        return mesh == null || isMeshMatchedByOverlaySets(mesh);
    }

    private boolean isArtMeshOverlayActiveByMesh(
            final Object mesh, final Object actionPack, final boolean isSelected, final boolean isGlued)
            throws Throwable {
        if (cachedSelMeshPack != actionPack) {
            refreshSelMeshFrameCache(actionPack);
        }
        if (cachedSelHide) {
            return false;
        }
        if (isSelected || isGlued) {
            return true;
        }
        if (!cachedSelHasHover && !cachedSelHasW && !cachedSelHasX && !cachedSelHasG) {
            return false;
        }
        return isMeshMatchedByOverlaySets(mesh);
    }

    private boolean isMeshMatchedByOverlaySets(final Object mesh) throws Throwable {
        if (cachedSelHasHover) {
            final Object src = h.acc.meshGetSource(mesh);
            final Object selection = src == null ? null : (Object) h.meshSourceGetSelection.invokeExact(src);
            if (cachedSelHover.equals(selection)) {
                return true;
            }
        }
        if (cachedSelHasW || cachedSelHasX) {
            final Object guid = h.acc.meshGetGuid(mesh);
            if (guid != null
                    && ((cachedSelHasW && cachedSelWSet.contains(guid))
                            || (cachedSelHasX && cachedSelXSet.contains(guid)))) {
                return true;
            }
        }
        if (cachedSelHasG) {
            final Object targetGuid = h.acc.meshGetTargetGuid(mesh);
            return targetGuid != null && cachedSelGGuids.contains(targetGuid);
        }
        return false;
    }

    private void refreshSelMeshFrameCache(final Object actionPack) throws Throwable {
        if (cachedSelMeshPack == actionPack) {
            return;
        }
        final Object canvasSetting = (Object) h.appSettingGetCanvas.invokeExact(h.appSettingInstance);
        cachedSelHide = (boolean) h.canvasSettingGetHideSelected.invokeExact(canvasSetting);
        final Object editMode = (Object) h.actionPackGetEditMode.invokeExact(actionPack);
        final Object selector = editMode == null ? null : (Object) h.editModeMainGetSelector.invokeExact(editMode);
        cachedSelHover = selector == null ? null : (Object) h.selectorGetHoveringObject.invokeExact(selector);
        cachedSelWSet = h.actionPackGetW == null ? null : (Set<?>) (Object) h.actionPackGetW.invokeExact(actionPack);
        cachedSelXSet =
                h.actionPackGetXSet == null ? null : (Set<?>) (Object) h.actionPackGetXSet.invokeExact(actionPack);
        final Object devSetting = (Object) h.appSettingGetDev.invokeExact(h.appSettingInstance);
        final boolean showChildUnderWarp = (boolean) h.devSettingGetShowChildUnderWarp.invokeExact(devSetting)
                && (boolean) h.warpOIsCtrlChildMode.invokeExact(h.warpOInstance);
        final Object gRaw = showChildUnderWarp ? (Object) h.actionPackGetG.invokeExact(actionPack) : null;
        if (gRaw instanceof List<?> gList && !gList.isEmpty()) {
            final Set<Object> guids = new HashSet<>(gList.size());
            for (final Object gd : gList) {
                final Object g = h.acc.deformerGetGuid(gd);
                if (g != null) {
                    guids.add(g);
                }
            }
            cachedSelGGuids = guids;
        } else {
            cachedSelGGuids = Set.of();
        }
        cachedSelHasHover = cachedSelHover != null
                && (h.artMeshSelectionClass == null || h.artMeshSelectionClass.isInstance(cachedSelHover));
        cachedSelHasW = cachedSelWSet != null && !cachedSelWSet.isEmpty();
        cachedSelHasX = cachedSelXSet != null && !cachedSelXSet.isEmpty();
        cachedSelHasG = !cachedSelGGuids.isEmpty();
        cachedSelMeshPack = actionPack;
    }

    private boolean hasSelectionDrawHandles() {
        return h.overlayViewGetModelingView != null
                && h.overlayViewGetCompletePack != null
                && h.overlayViewGetModel != null
                && h.completePackGetCurrentViewContext != null
                && h.modelingViewGetDevelopSetting != null
                && h.developSettingGetC != null
                && h.modelingViewGetAppearanceSetting != null
                && h.appearanceSettingGetA != null
                && h.modelingViewIsDrawableLocked != null
                && h.modelingViewIsDrawableEditable != null
                && h.modelingViewIsBoundingBoxVisible != null
                && h.modelingViewIsGridVisible != null
                && h.modelingViewIsControllerVisible != null
                && h.sceneGraphWillRenderField != null
                && (h.actionPackGetQ != null || h.actionPackGetS != null);
    }

    private boolean populateSelectionContext(final Object viewHolder, final SelectionDrawState state) throws Throwable {
        final Object modelingView = (Object) h.overlayViewGetModelingView.invokeExact(viewHolder);
        final Object completePack = (Object) h.overlayViewGetCompletePack.invokeExact(viewHolder);
        final Object model = (Object) h.overlayViewGetModel.invokeExact(viewHolder);
        if (modelingView == null || completePack == null || model == null) {
            return false;
        }
        if (h.formAnimViewClass != null && h.formAnimViewClass.isInstance(modelingView)) {
            return false;
        }
        final Object devSetting = (Object) h.modelingViewGetDevelopSetting.invokeExact(modelingView);
        if (devSetting == null || !(boolean) h.developSettingGetC.invokeExact(devSetting)) {
            return false;
        }
        final Object appSettingView = (Object) h.modelingViewGetAppearanceSetting.invokeExact(modelingView);
        if (appSettingView == null || !(boolean) h.appearanceSettingGetA.invokeExact(appSettingView)) {
            return false;
        }
        final Object actionPack = (Object) h.modelingViewGetLastActionPack.invokeExact(modelingView);
        if (actionPack == null || !h.actionPackClass.isInstance(actionPack)) {
            return false;
        }
        final Object editMode = (Object) h.actionPackGetEditMode.invokeExact(actionPack);
        if (editMode == null || !h.editModeMainClass.isInstance(editMode)) {
            return false;
        }
        final Object selector = (Object) h.editModeMainGetSelector.invokeExact(editMode);
        if (selector == null) {
            return false;
        }
        final Object sceneGraph = (Object) h.modelingViewGetSceneGraph.invokeExact(modelingView);
        if (sceneGraph == null) {
            return false;
        }

        final Object paramSet = h.acc.modelGetParameterSet(model);
        long sig = h.acc.modelGetLastUpdatedTime(model);
        sig = sig * HASH_PRIME + (paramSet == null ? 0 : h.acc.paramSetGetUpdateVersion(paramSet));
        final EditModelTopologyCache subtreeCache = subtreeCoordinator.latestCacheSnapshot();
        sig = sig * HASH_PRIME + (subtreeCache == null ? 0L : subtreeCache.nextEpoch);
        sig = sig * HASH_PRIME
                + ((Object) h.completePackGetCurrentViewContext.invokeExact(completePack) == modelingView
                        ? BOOL_TRUE_HASH
                        : BOOL_FALSE_HASH);
        sig = sig * HASH_PRIME
                + ((boolean) h.modelingViewIsDrawableLocked.invokeExact(modelingView)
                        ? BOOL_TRUE_HASH
                        : BOOL_FALSE_HASH);
        sig = sig * HASH_PRIME
                + ((boolean) h.modelingViewIsDrawableEditable.invokeExact(modelingView)
                        ? BOOL_TRUE_HASH
                        : BOOL_FALSE_HASH);
        sig = sig * HASH_PRIME
                + ((boolean) h.modelingViewIsDeformerVisible.invokeExact(modelingView)
                        ? BOOL_TRUE_HASH
                        : BOOL_FALSE_HASH);
        sig = sig * HASH_PRIME
                + ((boolean) h.modelingViewIsBoundingBoxVisible.invokeExact(modelingView)
                        ? BOOL_TRUE_HASH
                        : BOOL_FALSE_HASH);
        sig = sig * HASH_PRIME
                + ((boolean) h.modelingViewIsGridVisible.invokeExact(modelingView) ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
        sig = sig * HASH_PRIME
                + ((boolean) h.modelingViewIsControllerVisible.invokeExact(modelingView)
                        ? BOOL_TRUE_HASH
                        : BOOL_FALSE_HASH);

        final Object canvasSetting = (Object) h.appSettingGetCanvas.invokeExact(h.appSettingInstance);
        sig = sig * HASH_PRIME
                + ((boolean) h.canvasSettingGetHideSelected.invokeExact(canvasSetting)
                        ? BOOL_TRUE_HASH
                        : BOOL_FALSE_HASH);
        if (h.canvasSettingGetShowPopupHovering != null) {
            sig = sig * HASH_PRIME
                    + ((boolean) h.canvasSettingGetShowPopupHovering.invokeExact(canvasSetting)
                            ? BOOL_TRUE_HASH
                            : BOOL_FALSE_HASH);
        }
        final Object rotSetting = (Object) h.deformerSettingGetRotDeformer.invokeExact(
                (Object) h.appSettingGetDeformer.invokeExact(h.appSettingInstance));
        sig = sig * HASH_PRIME + System.identityHashCode((Object) h.rotSettingGetMode.invokeExact(rotSetting));
        if (h.appArtMeshInstance != null && h.artMeshSettingGetShowPoint != null) {
            sig = sig * HASH_PRIME
                    + ((boolean) h.artMeshSettingGetShowPoint.invokeExact(h.appArtMeshInstance)
                            ? BOOL_TRUE_HASH
                            : BOOL_FALSE_HASH);
        }

        sig = sig * HASH_PRIME + System.identityHashCode(selector);
        sig = sig * HASH_PRIME + System.identityHashCode((Object) h.viewGetEditMode.invokeExact(modelingView));
        final Object hovering = (Object) h.selectorGetHoveringObject.invokeExact(selector);
        sig = sig * HASH_PRIME + System.identityHashCode(hovering);
        sig = sig * HASH_PRIME + (int) h.selectorGetSelectedCount.invokeExact(selector);
        sig = sig * HASH_PRIME + listIdentityHash((Object) h.selectorGetSelectedObjects.invokeExact(selector));
        if (!h.actionPackQIsAbstract && h.actionPackGetQ != null) {
            sig = sig * HASH_PRIME + hashLazyActionList(actionPack, h.actionPackLazyEField, h.actionPackGetQ);
        }
        if (h.actionPackGetS != null) {
            final HostHandles.HostField sLazy =
                    h.actionPackQIsAbstract ? h.actionPackLazyEField : h.actionPackLazyGField;
            sig = sig * HASH_PRIME + hashLazyActionList(actionPack, sLazy, h.actionPackGetS);
        }
        if (h.actionPackGetU != null) {
            final HostHandles.HostField uLazy =
                    h.actionPackQIsAbstract ? h.actionPackLazyGField : h.actionPackLazyIField;
            sig = sig * HASH_PRIME + hashLazyActionList(actionPack, uLazy, h.actionPackGetU);
        }
        sig = sig * HASH_PRIME + listIdentityHash((Object) h.actionPackGetF.invokeExact(actionPack));
        sig = sig * HASH_PRIME + listIdentityHash((Object) h.actionPackGetG.invokeExact(actionPack));
        if (h.actionPackGetDeformers != null) {
            sig = sig * HASH_PRIME + listIdentityHash((Object) h.actionPackGetDeformers.invokeExact(actionPack));
        }
        if (h.actionPackGetK != null) {
            sig = sig * HASH_PRIME + hashLazyActionList(actionPack, h.actionPackLazyAField, h.actionPackGetK);
        }
        if (h.actionPackGetD != null) {
            sig = sig * HASH_PRIME + hashLazyActionList(actionPack, h.actionPackLazyQField, h.actionPackGetD);
        }
        if (h.actionPackGetLList != null) {
            sig = sig * HASH_PRIME + hashLazyActionList(actionPack, h.actionPackLazyBField, h.actionPackGetLList);
        }
        if (h.actionPackGetAm != null) {
            sig = sig * HASH_PRIME + System.identityHashCode((Object) h.actionPackGetAm.invokeExact(actionPack));
        }
        if (h.actionPackGetW != null) {
            final Object wObj = (Object) h.actionPackGetW.invokeExact(actionPack);
            sig = sig * HASH_PRIME + (wObj instanceof Set<?> s ? s.size() : 0);
        }
        if (h.actionPackGetXSet != null) {
            final Object xObj = (Object) h.actionPackGetXSet.invokeExact(actionPack);
            sig = sig * HASH_PRIME + (xObj instanceof Set<?> s ? s.size() : 0);
        }
        sig = sig * HASH_PRIME
                + ((boolean) h.actionPackGetL.invokeExact(actionPack) ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
        sig = sig * HASH_PRIME
                + ((boolean) h.actionPackGetM.invokeExact(actionPack) ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
        if (h.actionPackGetP != null) {
            sig = sig * HASH_PRIME
                    + ((boolean) h.actionPackGetP.invokeExact(actionPack) ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
        }

        final Object camMgr = (Object) h.modelingViewGetCameraManager.invokeExact(modelingView);
        if (camMgr != null) {
            sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.cameraGetScale.invokeExact(camMgr));
            if (h.cameraGetWrapper != null && h.cameraWrapperGetLookAt != null && h.vec3GetX != null) {
                final Object wrapper = (Object) h.cameraGetWrapper.invokeExact(camMgr);
                final Object lookAt = wrapper == null ? null : (Object) h.cameraWrapperGetLookAt.invokeExact(wrapper);
                if (lookAt != null) {
                    sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.vec3GetX.invokeExact(lookAt));
                    sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.vec3GetY.invokeExact(lookAt));
                    sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.vec3GetZ.invokeExact(lookAt));
                }
            }
        }
        if (h.completePackGetMainViewPanel != null && h.glPanelGetWidth != null && h.glPanelGetHeight != null) {
            Object panel = null;
            try {
                panel = (Object) h.completePackGetMainViewPanel.invokeExact(completePack);
            } catch (final NullPointerException ignored) {
                // Early-init or headless contexts without an attached MainFrameCtrl return null panel.
            }
            if (panel != null) {
                sig = sig * HASH_PRIME + (int) h.glPanelGetWidth.invokeExact(panel);
                sig = sig * HASH_PRIME + (int) h.glPanelGetHeight.invokeExact(panel);
            }
        }
        if (h.utilBInstance != null && h.utilBGetMousePos != null && h.pointGetX != null && h.pointGetY != null) {
            Object pt = null;
            try {
                pt = (Object) h.utilBGetMousePos.invokeExact(h.utilBInstance);
            } catch (final java.awt.HeadlessException | NullPointerException ignored) {
                // Headless JVMs or uninitialized pointer state skip pointer hash.
            }
            if (pt != null) {
                sig = sig * HASH_PRIME + (int) h.pointGetX.invokeExact(pt);
                sig = sig * HASH_PRIME + (int) h.pointGetY.invokeExact(pt);
            }
        }
        state.pendingModel = model;
        state.pendingActionPack = actionPack;
        state.pendingSceneGraph = sceneGraph;
        state.pendingSig = sig;
        return true;
    }

    private long hashLazyActionList(
            final Object actionPack, final HostHandles.HostField lazyField, final java.lang.invoke.MethodHandle getter)
            throws Throwable {
        if (lazyField != null && h.kotlinLazyClass != null && h.lazyIsInitialized != null) {
            final Object lazyObj = lazyField.get(actionPack);
            if (lazyObj != null
                    && h.kotlinLazyClass.isInstance(lazyObj)
                    && !(boolean) h.lazyIsInitialized.invokeExact(lazyObj)) {
                return UNINIT_LAZY_HASH;
            }
        }
        return listIdentityHash((Object) getter.invokeExact(actionPack));
    }

    private float resolveCameraScale() throws Throwable {
        final EditModelTopologyCache recent = subtreeCoordinator.latestCacheSnapshot();
        if (recent != null && h.modelingViewClass.isInstance(recent.viewContext)) {
            final Object camMgr = (Object) h.modelingViewGetCameraManager.invokeExact(recent.viewContext);
            if (camMgr != null) {
                return (float) h.cameraGetScale.invokeExact(camMgr);
            }
        }
        return 0.0f;
    }

    private boolean isDeformerAffectedOrSelected(final Object deformer, final Object src, final FrameGlobalState frame)
            throws Throwable {
        if (frame.fDeformers.contains(deformer) || frame.gDeformers.contains(deformer)) {
            return true;
        }
        final Object guid = (Object) h.deformerGetGuid.invokeExact(deformer);
        if (guid != null) {
            if ((boolean) h.selectorIsSelected.invokeExact(frame.selector, guid)) {
                return true;
            }
            if (frame.rGuids.contains(guid)) {
                return true;
            }
        }
        final Object targetGuid = (Object) h.deformerGetTargetGuid.invokeExact(deformer);
        if (targetGuid != null && !frame.gDeformers.isEmpty()) {
            // Check ShowChildUnderWarp match by comparing targetGuid against G elements' guid.
            for (final Object gd : frame.gDeformers) {
                if (targetGuid.equals((Object) h.deformerGetGuid.invokeExact(gd))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesDeformerGeometry(final Object deformer, final boolean isWarp, final OverlayEntry entry)
            throws Throwable {
        if ((boolean) h.deformerGetDirtyDeformedForm.invokeExact(deformer)) {
            return false;
        }
        final EditModelTopologyCache recent = subtreeCoordinator.latestCacheSnapshot();
        if (recent != null && recent.validBaseline) {
            final Long epoch = recent.deformerEpochs.get(deformer);
            if (epoch != null) {
                if (!entry.lastEpochKnown || entry.lastSubtreeCache != recent || entry.lastEpoch != epoch) {
                    return false;
                }
                if (!isWarp) {
                    final long geomSig = computeDeformerGeomSig(deformer, false);
                    return entry.lastGeomSigKnown && entry.lastGeomSig == geomSig;
                }
                final Object calcForm = (Object) h.deformerGetCalculatedForm.invokeExact(deformer);
                final Object defForm = (Object) h.deformerGetDeformedForm.invokeExact(deformer);
                final Object interpForm = (Object) h.deformerGetInterpolatedForm.invokeExact(deformer);
                final int interpEditVer = interpForm == null ? -1 : (int) h.formGetEditVersion.invokeExact(interpForm);
                return entry.lastGeomFormsKnown
                        && entry.lastCalcForm == calcForm
                        && entry.lastDefForm == defForm
                        && entry.lastInterpForm == interpForm
                        && entry.lastInterpEditVer == interpEditVer;
            }
        }
        final long geomSig = computeDeformerGeomSig(deformer, isWarp);
        return entry.lastGeomSigKnown && entry.lastGeomSig == geomSig;
    }

    private void recordEntryState(
            final OverlayEntry entry,
            final Object deformer,
            final boolean isWarp,
            final boolean showOverlay,
            final boolean useBone,
            final Object draw,
            final long globalSig)
            throws Throwable {
        entry.lastShowOverlay = showOverlay;
        entry.lastUseBone = useBone;
        entry.primaryDraw = draw;
        entry.lastGlobalSig = globalSig;

        final Object calcForm = (Object) h.deformerGetCalculatedForm.invokeExact(deformer);
        final Object defForm = (Object) h.deformerGetDeformedForm.invokeExact(deformer);
        final Object interpForm = (Object) h.deformerGetInterpolatedForm.invokeExact(deformer);
        entry.lastCalcForm = calcForm;
        entry.lastDefForm = defForm;
        entry.lastInterpForm = interpForm;
        entry.lastInterpEditVer = interpForm == null ? -1 : (int) h.formGetEditVersion.invokeExact(interpForm);
        entry.lastGeomFormsKnown = true;

        final EditModelTopologyCache recent = subtreeCoordinator.latestCacheSnapshot();
        final Long epoch = recent != null && recent.validBaseline ? recent.deformerEpochs.get(deformer) : null;
        if (epoch != null) {
            entry.lastSubtreeCache = recent;
            entry.lastEpoch = epoch;
            entry.lastEpochKnown = true;
            if (!isWarp) {
                entry.lastGeomSig = computeDeformerGeomSig(deformer, false);
                entry.lastGeomSigKnown = true;
            } else {
                // Skip float[] hashing on WarpDeformers when active subtree epochs cover this model frame.
                entry.lastGeomSigKnown = false;
            }
            return;
        }
        entry.lastSubtreeCache = null;
        entry.lastEpochKnown = false;
        entry.lastGeomSig = computeDeformerGeomSig(deformer, isWarp);
        entry.lastGeomSigKnown = true;
    }

    private long computeDeformerGeomSig(final Object deformer, final boolean isWarp) throws Throwable {
        final Object calcForm = (Object) h.deformerGetCalculatedForm.invokeExact(deformer);
        final Object defForm = (Object) h.deformerGetDeformedForm.invokeExact(deformer);
        final Object interpForm = (Object) h.deformerGetInterpolatedForm.invokeExact(deformer);
        long sig = System.identityHashCode(calcForm);
        sig = sig * HASH_PRIME + System.identityHashCode(defForm);
        sig = sig * HASH_PRIME + System.identityHashCode(interpForm);
        if (interpForm != null) {
            sig = sig * HASH_PRIME + (int) h.formGetEditVersion.invokeExact(interpForm);
        }
        if (calcForm != null) {
            if (isWarp) {
                final float[] pos = (float[]) h.warpFormGetPositions.invokeExact(calcForm);
                subtreeCoordinator.fullArrayHashes.increment();
                sig = sig * HASH_PRIME + (pos == null ? NULL_SENTINEL_HASH : Arrays.hashCode(pos));
            } else {
                sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetOriginX.invokeExact(calcForm));
                sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetOriginY.invokeExact(calcForm));
                sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetAngle.invokeExact(calcForm));
                sig = sig * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetScale.invokeExact(calcForm));
                sig = sig * HASH_PRIME
                        + ((boolean) h.rotFormIsReflectX.invokeExact(calcForm) ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
                sig = sig * HASH_PRIME
                        + ((boolean) h.rotFormIsReflectY.invokeExact(calcForm) ? BOOL_TRUE_HASH : BOOL_FALSE_HASH);
            }
        }
        return sig;
    }

    private Object findDrawEntity(final Object deformer, final boolean isWarp, final boolean useBone) throws Throwable {
        final Object childrenObj = (Object) h.deformerGetChildren.invokeExact(deformer);
        if (!(childrenObj instanceof Iterable<?> children)) {
            return null;
        }
        final Class<?> targetDrawClass =
                isWarp ? h.warpDrawClass : (useBone ? h.rotDrawBoneClass : h.rotDrawNormalClass);
        for (final Object child : children) {
            if (targetDrawClass.isInstance(child)) {
                return child;
            }
        }
        return null;
    }

    private void invokeLegacyDrawUpdate(
            final Object draw,
            final Object actionPack,
            final boolean isSelected,
            final boolean isWarp,
            final boolean useBone)
            throws Throwable {
        if (isWarp) {
            h.warpDrawUpdate.invokeExact(draw, actionPack, isSelected);
        } else if (useBone) {
            h.rotBoneDrawUpdate.invokeExact(draw, actionPack, isSelected);
        } else {
            h.rotNormalDrawUpdate.invokeExact(draw, actionPack, isSelected);
        }
    }

    private boolean resolveUseBoneUi(final Object src, final Object rotMode) throws Throwable {
        if (rotMode == h.rotModeBone) {
            return true;
        }
        return rotMode == h.rotModeAuto && (boolean) h.rotSourceGetUseBoneUi.invokeExact(src);
    }

    private boolean matchesDrawType(final Object draw, final boolean isWarp, final boolean useBone) {
        if (isWarp) {
            return h.warpDrawClass.isInstance(draw);
        }
        return useBone ? h.rotDrawBoneClass.isInstance(draw) : h.rotDrawNormalClass.isInstance(draw);
    }

    private void syncEntityEnabled(final Object entity, final boolean enabled) throws Throwable {
        if ((boolean) h.entityGetEnabled.invokeExact(entity) != enabled) {
            h.entitySetEnabled.invokeExact(entity, enabled);
        }
    }

    private Set<Object> extractTargetGuids(final Object rawList) throws Throwable {
        if (!(rawList instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        final Set<Object> guids = new HashSet<>(list.size());
        for (final Object controllable : list) {
            if (controllable == null) {
                continue;
            }
            final Object ctrlSrc = (Object) h.controllableGetSource.invokeExact(controllable);
            final Object targetGuid =
                    ctrlSrc == null ? null : (Object) h.controllableSourceGetTargetGuid.invokeExact(ctrlSrc);
            if (targetGuid != null) {
                guids.add(targetGuid);
            }
        }
        return guids;
    }

    private static Set<Object> toIdentitySet(final Object rawList) {
        if (!(rawList instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        final IdentityHashMap<Object, Boolean> map = new IdentityHashMap<>(list.size());
        for (final Object item : list) {
            if (item != null) {
                map.put(item, Boolean.TRUE);
            }
        }
        return map.keySet();
    }

    private static long listIdentityHash(final Object raw) {
        if (!(raw instanceof List<?> list)) {
            return NULL_SENTINEL_HASH;
        }
        long hash = list.size();
        for (int i = 0; i < list.size(); i++) {
            hash = hash * HASH_PRIME + System.identityHashCode(list.get(i));
        }
        return hash;
    }

    private record FrameGlobalState(
            long globalSig, Object selector, Set<Object> rGuids, Set<Object> fDeformers, Set<Object> gDeformers) {}

    private static final class SelectionDrawState {
        final IdentityHashMap<Object, Boolean> preWillRender = new IdentityHashMap<>();
        Object lastModel;
        Object lastActionPack;
        long lastSig;
        Object[] volatileEntities;
        boolean valid;
        Object pendingModel;
        Object pendingActionPack;
        Object pendingSceneGraph;
        long pendingSig;
        boolean pendingRecord;

        void capturePreWillRender(final Object willRenderRaw) {
            preWillRender.clear();
            if (willRenderRaw instanceof Iterable<?> entities) {
                for (final Object entity : entities) {
                    if (entity != null) {
                        preWillRender.put(entity, Boolean.TRUE);
                    }
                }
            }
        }

        void recordAddedVolatiles(final Object willRenderRaw) {
            if (!(willRenderRaw instanceof java.util.Collection<?> entities)) {
                volatileEntities = null;
                valid = false;
                preWillRender.clear();
                return;
            }
            int count = 0;
            for (final Object entity : entities) {
                if (entity != null && !preWillRender.containsKey(entity)) {
                    count++;
                }
            }
            final Object[] added = new Object[count];
            int k = 0;
            for (final Object entity : entities) {
                if (entity != null && !preWillRender.containsKey(entity)) {
                    added[k++] = entity;
                }
            }
            preWillRender.clear();
            this.volatileEntities = added;
            this.lastModel = pendingModel;
            this.lastActionPack = pendingActionPack;
            this.lastSig = pendingSig;
            this.valid = true;
        }
    }

    private static final class ArtMeshSelectionState {
        boolean initialized;
        boolean cleanlyDisabled;
    }

    private static final class OverlayEntry {
        Object primaryDraw;
        boolean lastShowOverlay;
        boolean lastUseBone;
        long lastGlobalSig;
        Object lastSubtreeCache;
        long lastEpoch;
        boolean lastEpochKnown;
        Object lastCalcForm;
        Object lastDefForm;
        Object lastInterpForm;
        int lastInterpEditVer;
        boolean lastGeomFormsKnown;
        long lastGeomSig;
        boolean lastGeomSigKnown;
    }
}
