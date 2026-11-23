package dev.turboism.adapter.cubism.optimization.warpdeform;

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

    private final EditSubtreeHandles h;
    private final EditSubtreeCoordinator subtreeCoordinator;
    private final WeakIdentityCache<Object, OverlayEntry> entries = new WeakIdentityCache<>();

    final LongAdder overlayElisions = new LongAdder();
    final LongAdder overlayUpdates = new LongAdder();

    private long cachedGlobalActionHash = Long.MIN_VALUE;
    private boolean cachedGlobalValid;
    private Set<Object> cachedRGuids = Set.of();
    private Set<Object> cachedFDeformers = Set.of();
    private Set<Object> cachedGDeformers = Set.of();
    private long legacyLastGlobalSig = Long.MIN_VALUE;

    EditOverlayElisionCoordinator(final EditSubtreeHandles handles, final EditSubtreeCoordinator subtreeCoordinator) {
        this.h = handles;
        this.subtreeCoordinator = subtreeCoordinator;
    }

    void clearCaches() {
        entries.clear();
        cachedGlobalValid = false;
        cachedGlobalActionHash = Long.MIN_VALUE;
        legacyLastGlobalSig = Long.MIN_VALUE;
        cachedRGuids = Set.of();
        cachedFDeformers = Set.of();
        cachedGDeformers = Set.of();
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

        if (!cachedGlobalValid || cachedGlobalActionHash != actionSig) {
            cachedRGuids = extractTargetGuids(rRaw);
            cachedFDeformers = toIdentitySet(fRaw);
            cachedGDeformers = toIdentitySet(gRaw);
            cachedGlobalActionHash = actionSig;
            cachedGlobalValid = true;
        }
        final long combined = actionSig * HASH_PRIME + nonActionSig;
        return new FrameGlobalState(combined, selector, cachedRGuids, cachedFDeformers, cachedGDeformers);
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
            if (epoch != null
                    && (!entry.lastEpochKnown || entry.lastSubtreeCache != recent || entry.lastEpoch != epoch)) {
                return false;
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

        final EditModelTopologyCache recent = subtreeCoordinator.latestCacheSnapshot();
        final Long epoch = recent != null && recent.validBaseline ? recent.deformerEpochs.get(deformer) : null;
        if (epoch != null) {
            entry.lastSubtreeCache = recent;
            entry.lastEpoch = epoch;
            entry.lastEpochKnown = true;
        } else {
            entry.lastSubtreeCache = null;
            entry.lastEpochKnown = false;
        }
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

    private static final class OverlayEntry {
        Object primaryDraw;
        boolean lastShowOverlay;
        boolean lastUseBone;
        long lastGlobalSig;
        Object lastSubtreeCache;
        long lastEpoch;
        boolean lastEpochKnown;
        long lastGeomSig;
        boolean lastGeomSigKnown;
    }
}
