package dev.turboism.adapter.cubism.optimization.warpdeform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-model cached DAG topology, parameter/form snapshots, and deformer overlay epochs.
 *
 * <p>Rebuilt whenever model membership, parent linkage, visible ArtMesh set, or glue wiring
 * changes, so steady edit drag frames detect dirty roots without reconstructing maps.</p>
 */
final class EditModelTopologyCache {

    private static final int INITIAL_LIST_CAPACITY = 4;
    private static final int ROTATION_SCALAR_STRIDE = 4;
    private static final int HASH_PRIME = 31;

    Object model;
    Object modelSource;
    Object document;
    Object viewContext;
    Object renderContext;
    boolean lastEditing;

    int lastParamUpdateVersion;
    float[] lastParamValues;

    int lastModelOpacityBits;
    long lastBaseColorHash;
    Object lastRenderType;
    int lastZOffsetBits;
    boolean lastSubDivision;
    boolean lastSyncPartVis;
    boolean lastOptimizeArtMesh;
    boolean lastUpdCtxSubDiv;
    boolean lastUpdCtxA;
    boolean lastUpdCtxF;
    int lastPartsCount;
    int[] lastPartSignatures;
    int lastAliasesCount;
    boolean hasAnyConflictPolygon;

    // Deformer DAG topology partitioned strictly by tree depth.
    Object[] deformers;
    boolean[] isWarp;
    boolean[] isRotation;
    int[] deformerParentIdx;
    int[][] deformerChildren;
    int[][] levelDeformers;
    int maxDepth;

    // Exact visible ArtMesh snapshot and parent/glue wiring.
    Object[] allArtMeshes;
    Object[] visibleMeshes;
    Object[] visibleMeshSources;
    final IdentityHashMap<Object, Integer> visibleMeshIndexByRef = new IdentityHashMap<>();
    int[] meshParentDeformerIdx;
    boolean hasArtPaths;
    int[] artPathDeformerIdx;

    Object[] affecters;
    boolean allAffectersGlue;
    int[] glueMeshAVisibleIdx;
    int[] glueMeshBVisibleIdx;
    int[][] meshGlueAdjacency;

    // O(1) per-deformer baseline fingerprints (version stamps and form references only).
    Object[] deformerSources;
    int[] lastDefSourceUpdateVer;
    int[] lastDefCopiedUpdateVer;
    int[] lastDefKeyformVersionStamp;
    Object[] lastDefInterpForm;
    int[] lastDefInterpEditVer;

    // O(1) per-visible-ArtMesh baseline fingerprints (version stamps and buffer identity only).
    int[] lastMeshVer;
    int[] lastMeshCopiedVer;
    int[] lastMeshKeyformVersionStamp;
    Object[] lastMeshInterpForm;
    int[] lastMeshInterpEditVer;
    int[] lastMeshDrawOrder;
    boolean[] lastMeshVisible;
    boolean[] lastMeshVisHierarchy;
    float[][] lastMeshGMeshPosRefs;

    // Deep float[]/color baselines tracked strictly for currently selected or hovered objects.
    final IdentityHashMap<Object, SelectedDeformerBaseline> selectedDeformerBaselines = new IdentityHashMap<>();
    final IdentityHashMap<Object, SelectedMeshBaseline> selectedMeshBaselines = new IdentityHashMap<>();

    // Per-glue baseline fingerprints.
    int[] lastGlueCachedVer;
    int[] lastGlueSourceVer;
    int[] lastGlueIntensityBits;

    // Per-deformer geometry epochs shared with overlay elision (identity-indexed).
    final IdentityHashMap<Object, Long> deformerEpochs = new IdentityHashMap<>();
    final IdentityHashMap<Object, Integer> deformerIndexByRef = new IdentityHashMap<>();
    long nextEpoch = 1L;
    boolean validBaseline;

    // One-frame appearance-skip state armed by coreUpdate and drained by artMeshAppearance.
    Thread skipThread;
    int remainingAppearanceChecks;
    boolean[] activeDirtyMeshes;

    void armAppearanceSkip(final Thread thread, final boolean[] dirtyMeshes) {
        this.skipThread = thread;
        this.remainingAppearanceChecks = dirtyMeshes == null ? 0 : dirtyMeshes.length;
        this.activeDirtyMeshes = dirtyMeshes;
    }

    void disarmAppearanceSkip() {
        this.skipThread = null;
        this.remainingAppearanceChecks = 0;
        this.activeDirtyMeshes = null;
    }

    /** Returns true when structural model lists, DAG parent edges, visible meshes, and glues match. */
    boolean matchesTopologyAndGlobals(
            final Object model,
            final Object modelSource,
            final Object doc,
            final Object viewCtx,
            final Object updCtx,
            final Object renderCtx,
            final List<?> visibleMeshesArg,
            final EditSubtreeHandles h)
            throws Throwable {
        if (!validBaseline
                || this.model != model
                || this.modelSource != modelSource
                || this.document != doc
                || this.viewContext != viewCtx
                || this.renderContext != renderCtx) {
            return false;
        }
        if (visibleMeshesArg == null || visibleMeshesArg.size() != visibleMeshes.length) {
            return false;
        }
        for (int i = 0; i < visibleMeshes.length; i++) {
            if (visibleMeshesArg.get(i) != visibleMeshes[i]) {
                return false;
            }
        }
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        final Object baseColor = (Object) h.modelGetBaseColor.invokeExact(model);
        final long baseHash = EditSubtreeHandles.packCColorRgba(h, baseColor);
        final int opacityBits = Float.floatToRawIntBits((float) h.modelGetOpacity.invokeExact(model));
        final Object renderType = (Object) h.modelGetRenderType.invokeExact(model);
        final int zBits = Float.floatToRawIntBits((float) h.modelGetZOffset.invokeExact(model));
        final boolean subDiv = (boolean) h.modelGetSubDivisionEnabled.invokeExact(model);
        final boolean syncPartVis = (boolean) h.modelGetSyncPartVis.invokeExact(model);
        final Object drawSetting = (Object) h.appSettingGetDraw.invokeExact(h.appSettingInstance);
        final boolean optimizeMesh = (boolean) h.drawSettingGetOptimizeArtMesh.invokeExact(drawSetting);
        final boolean ctxSubDiv = (boolean) h.updCtxGetSubDivision.invokeExact(updCtx);
        final boolean ctxA = (boolean) h.updCtxGetBoolA.invokeExact(updCtx);
        final boolean ctxF = (boolean) h.updCtxGetBoolF.invokeExact(updCtx);
        if (opacityBits != lastModelOpacityBits
                || baseHash != lastBaseColorHash
                || renderType != lastRenderType
                || zBits != lastZOffsetBits
                || subDiv != lastSubDivision
                || syncPartVis != lastSyncPartVis
                || optimizeMesh != lastOptimizeArtMesh
                || ctxSubDiv != lastUpdCtxSubDiv
                || ctxA != lastUpdCtxA
                || ctxF != lastUpdCtxF) {
            return false;
        }
        if ((ctxSubDiv && subDiv)) {
            // Subdivision dynamically regenerates geometry; keep stock path when enabled.
            return false;
        }

        final Object aliasesObj =
                h.modelGetAllAliases == null ? null : (Object) h.modelGetAllAliases.invokeExact(model);
        final int aliasSize = aliasesObj instanceof List<?> list ? list.size() : 0;
        if (aliasSize != lastAliasesCount || aliasSize > 0) {
            return false;
        }

        final List<?> parts = acc.modelGetAllParts(model);
        if (parts == null || parts.size() != lastPartsCount) {
            return false;
        }
        for (int i = 0; i < parts.size(); i++) {
            if (partSignature(parts.get(i), acc) != lastPartSignatures[i]) {
                return false;
            }
        }

        final List<?> defList = acc.modelGetAllDeformers(model);
        if (defList == null || defList.size() != deformers.length) {
            return false;
        }
        for (int i = 0; i < deformers.length; i++) {
            final Object d = defList.get(i);
            if (d != deformers[i]) {
                return false;
            }
            final Object pGuid = acc.deformerGetTargetGuid(d);
            final Object resolvedParent =
                    pGuid == null || pGuid.equals(h.emptyDeformerGuid) ? null : acc.modelGetDeformer(model, pGuid);
            final int expectedParent = deformerParentIdx[i];
            if (expectedParent < 0) {
                if (resolvedParent != null) {
                    return false;
                }
            } else if (resolvedParent != deformers[expectedParent]) {
                return false;
            }
        }

        final List<?> meshList = acc.modelGetAllArtMeshes(model);
        if (meshList == null || meshList.size() != allArtMeshes.length) {
            return false;
        }
        int visCursor = 0;
        for (int i = 0; i < meshList.size(); i++) {
            final Object m = meshList.get(i);
            if (m != allArtMeshes[i]) {
                return false;
            }
            final Object src = acc.meshGetSource(m);
            final boolean vis = !optimizeMesh || acc.controllableSourceIsVisibleHierarchy(src);
            if (vis) {
                if (visCursor >= visibleMeshes.length || visibleMeshes[visCursor] != m) {
                    return false;
                }
                final Object pGuid = acc.meshGetTargetGuid(m);
                final Object resolvedParent =
                        pGuid == null || pGuid.equals(h.emptyDeformerGuid) ? null : acc.modelGetDeformer(model, pGuid);
                final int expectedParent = meshParentDeformerIdx[visCursor];
                if (expectedParent < 0) {
                    if (resolvedParent != null) {
                        return false;
                    }
                } else if (resolvedParent != deformers[expectedParent]) {
                    return false;
                }
                visCursor++;
            }
        }
        if (visCursor != visibleMeshes.length) {
            return false;
        }

        final List<?> affList = acc.modelGetAllAffecters(model);
        if (affList == null || affList.size() != affecters.length) {
            return false;
        }
        for (int i = 0; i < affecters.length; i++) {
            if (affList.get(i) != affecters[i]) {
                return false;
            }
        }
        final List<?> pathList = acc.modelGetAllArtPaths(model);
        if (pathList == null) {
            return false;
        }
        if (hasArtPaths != !pathList.isEmpty() || pathList.size() != artPathDeformerIdx.length) {
            return false;
        }
        for (int i = 0; i < pathList.size(); i++) {
            final Object pGuid = (Object) h.artPathGetTargetGuid.invokeExact(pathList.get(i));
            final Object parent =
                    pGuid == null || pGuid.equals(h.emptyDeformerGuid) ? null : acc.modelGetDeformer(model, pGuid);
            final int idx = parent == null ? -1 : deformerIndexByRef.getOrDefault(parent, -2);
            if (idx != artPathDeformerIdx[i]) {
                return false;
            }
        }
        return true;
    }

    /** Captures a full clean baseline immediately after native {@code updateModel} completes. */
    void captureFullBaseline(
            final Object model,
            final Object modelSource,
            final Object doc,
            final Object viewCtx,
            final Object updCtx,
            final Object renderCtx,
            final Object paramSet,
            final boolean editing,
            final IdentityHashMap<Object, Boolean> activeEditSources,
            final EditSubtreeHandles h,
            final LongAdder fullArrayHashes)
            throws Throwable {
        validBaseline = false;
        this.model = model;
        this.modelSource = modelSource;
        this.document = doc;
        this.viewContext = viewCtx;
        this.renderContext = renderCtx;
        this.lastEditing = editing;

        captureParameters(paramSet, h);

        final Object baseColor = (Object) h.modelGetBaseColor.invokeExact(model);
        lastBaseColorHash = EditSubtreeHandles.packCColorRgba(h, baseColor);
        lastModelOpacityBits = Float.floatToRawIntBits((float) h.modelGetOpacity.invokeExact(model));
        lastRenderType = (Object) h.modelGetRenderType.invokeExact(model);
        lastZOffsetBits = Float.floatToRawIntBits((float) h.modelGetZOffset.invokeExact(model));
        lastSubDivision = (boolean) h.modelGetSubDivisionEnabled.invokeExact(model);
        lastSyncPartVis = (boolean) h.modelGetSyncPartVis.invokeExact(model);
        final Object drawSetting = (Object) h.appSettingGetDraw.invokeExact(h.appSettingInstance);
        lastOptimizeArtMesh = (boolean) h.drawSettingGetOptimizeArtMesh.invokeExact(drawSetting);
        lastUpdCtxSubDiv = (boolean) h.updCtxGetSubDivision.invokeExact(updCtx);
        lastUpdCtxA = (boolean) h.updCtxGetBoolA.invokeExact(updCtx);
        lastUpdCtxF = (boolean) h.updCtxGetBoolF.invokeExact(updCtx);

        final Object aliasesObj =
                h.modelGetAllAliases == null ? null : (Object) h.modelGetAllAliases.invokeExact(model);
        lastAliasesCount = aliasesObj instanceof List<?> list ? list.size() : 0;

        final Object partsObj = (Object) h.modelGetAllParts.invokeExact(model);
        if (!(partsObj instanceof List<?> parts)) {
            return;
        }
        lastPartsCount = parts.size();
        lastPartSignatures = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            lastPartSignatures[i] = partSignature(parts.get(i), h.acc);
        }

        hasAnyConflictPolygon = false;
        if (h.conflictPolygonFlag && h.conflictPolyInterface != null) {
            final Object drawablesObj = (Object) h.modelGetAllDrawables.invokeExact(model);
            if (drawablesObj instanceof List<?> drawables) {
                for (final Object dr : drawables) {
                    if (h.conflictPolyInterface.isInstance(dr) && (int) h.conflictGetCount.invokeExact(dr) != 0) {
                        hasAnyConflictPolygon = true;
                        break;
                    }
                }
            }
        }

        buildDeformerDag(model, h);
        buildMeshAndGlueTopology(model, h);
        buildArtPathMapping(model, h);

        selectedDeformerBaselines.clear();
        selectedMeshBaselines.clear();
        snapshotAllDeformerStates(activeEditSources, h, fullArrayHashes);
        snapshotAllMeshStates(activeEditSources, h, fullArrayHashes);
        snapshotAllGlueStates(h);

        // Advance deformer epochs so overlay elision detects refreshed post-native forms.
        nextEpoch++;
        for (final Object d : deformers) {
            deformerEpochs.put(d, nextEpoch);
        }
        validBaseline = true;
    }

    private void buildDeformerDag(final Object model, final EditSubtreeHandles h) throws Throwable {
        final Object defObj = (Object) h.modelGetAllDeformers.invokeExact(model);
        final List<?> defList = defObj instanceof List<?> list ? list : List.of();
        final int n = defList.size();
        deformers = new Object[n];
        deformerSources = new Object[n];
        isWarp = new boolean[n];
        isRotation = new boolean[n];
        deformerParentIdx = new int[n];
        deformerIndexByRef.clear();

        for (int i = 0; i < n; i++) {
            final Object d = defList.get(i);
            deformers[i] = d;
            deformerSources[i] = (Object) h.deformerGetSource.invokeExact(d);
            isWarp[i] = h.warpDeformerClass.isInstance(d);
            isRotation[i] = h.rotDeformerClass.isInstance(d);
            deformerIndexByRef.put(d, i);
        }

        @SuppressWarnings("unchecked")
        final List<Integer>[] childLists = new List[n];
        for (int i = 0; i < n; i++) {
            childLists[i] = new ArrayList<>(INITIAL_LIST_CAPACITY);
        }
        for (int i = 0; i < n; i++) {
            final Object pGuid = (Object) h.deformerGetTargetGuid.invokeExact(deformers[i]);
            final Object parent = pGuid == null || pGuid.equals(h.emptyDeformerGuid)
                    ? null
                    : (Object) h.modelGetDeformer.invokeExact(model, pGuid);
            final int pIdx = parent == null ? -1 : deformerIndexByRef.getOrDefault(parent, -1);
            deformerParentIdx[i] = pIdx;
            if (pIdx >= 0) {
                childLists[pIdx].add(i);
            }
        }
        deformerChildren = new int[n][];
        for (int i = 0; i < n; i++) {
            final List<Integer> kids = childLists[i];
            final int[] arr = new int[kids.size()];
            for (int k = 0; k < kids.size(); k++) {
                arr[k] = kids.get(k);
            }
            deformerChildren[i] = arr;
        }

        final int[] depth = new int[n];
        Arrays.fill(depth, -1);
        int highest = -1;
        for (int i = 0; i < n; i++) {
            final int d = computeDepth(i, depth);
            if (d > highest) {
                highest = d;
            }
        }
        maxDepth = Math.max(0, highest);
        final int levelCount = n == 0 ? 0 : maxDepth + 1;
        @SuppressWarnings("unchecked")
        final List<Integer>[] byLevel = new List[levelCount];
        for (int l = 0; l < levelCount; l++) {
            byLevel[l] = new ArrayList<>();
        }
        for (int i = 0; i < n; i++) {
            byLevel[depth[i]].add(i);
        }
        levelDeformers = new int[levelCount][];
        for (int l = 0; l < levelCount; l++) {
            final List<Integer> group = byLevel[l];
            final int[] arr = new int[group.size()];
            for (int j = 0; j < group.size(); j++) {
                arr[j] = group.get(j);
            }
            levelDeformers[l] = arr;
        }
    }

    private int computeDepth(final int index, final int[] depth) {
        if (depth[index] >= 0) {
            return depth[index];
        }
        final int parent = deformerParentIdx[index];
        if (parent < 0) {
            depth[index] = 0;
            return 0;
        }
        // Guard malformed cycles against unbounded recursion by capping at node count.
        int cursor = parent;
        int d = 1;
        while (cursor >= 0 && d <= depth.length) {
            if (depth[cursor] >= 0) {
                depth[index] = depth[cursor] + d;
                return depth[index];
            }
            cursor = deformerParentIdx[cursor];
            d++;
        }
        depth[index] = d;
        return d;
    }

    private void buildMeshAndGlueTopology(final Object model, final EditSubtreeHandles h) throws Throwable {
        final Object allObj = (Object) h.modelGetAllArtMeshes.invokeExact(model);
        final List<?> allList = allObj instanceof List<?> list ? list : List.of();
        allArtMeshes = allList.toArray();
        final List<Object> visible = new ArrayList<>(allList.size());
        final List<Object> visibleSources = new ArrayList<>(allList.size());
        final List<Integer> parentIndices = new ArrayList<>(allList.size());
        visibleMeshIndexByRef.clear();
        final Map<Object, Integer> visibleIndexByGuid = new java.util.HashMap<>(allList.size());

        for (final Object m : allArtMeshes) {
            final Object src = (Object) h.meshGetSource.invokeExact(m);
            final boolean isVis = !lastOptimizeArtMesh || (boolean) h.meshSourceIsVisibleHierarchy.invokeExact(src);
            if (!isVis) {
                continue;
            }
            final Object pGuid = (Object) h.meshGetTargetGuid.invokeExact(m);
            final Object parent = pGuid == null || pGuid.equals(h.emptyDeformerGuid)
                    ? null
                    : (Object) h.modelGetDeformer.invokeExact(model, pGuid);
            final int pIdx = parent == null ? -1 : deformerIndexByRef.getOrDefault(parent, -1);
            final int visIdx = visible.size();
            visible.add(m);
            visibleSources.add(src);
            parentIndices.add(pIdx);
            visibleMeshIndexByRef.put(m, visIdx);
            visibleIndexByGuid.put((Object) h.meshGetGuid.invokeExact(m), visIdx);
        }
        visibleMeshes = visible.toArray();
        visibleMeshSources = visibleSources.toArray();
        meshParentDeformerIdx = new int[parentIndices.size()];
        for (int i = 0; i < parentIndices.size(); i++) {
            meshParentDeformerIdx[i] = parentIndices.get(i);
        }

        final Object affObj = (Object) h.modelGetAllAffecters.invokeExact(model);
        final List<?> affList = affObj instanceof List<?> list ? list : List.of();
        affecters = affList.toArray();
        allAffectersGlue = true;
        glueMeshAVisibleIdx = new int[affecters.length];
        glueMeshBVisibleIdx = new int[affecters.length];
        @SuppressWarnings("unchecked")
        final List<Integer>[] adj = new List[visibleMeshes.length];
        for (int i = 0; i < affecters.length; i++) {
            final Object aff = affecters[i];
            if (!h.glueClass.isInstance(aff)) {
                allAffectersGlue = false;
                glueMeshAVisibleIdx[i] = -1;
                glueMeshBVisibleIdx[i] = -1;
                continue;
            }
            final Object src = (Object) h.glueGetSource.invokeExact(aff);
            final Object guidA = (Object) h.glueSourceGetMeshAGuid.invokeExact(src);
            final Object guidB = (Object) h.glueSourceGetMeshBGuid.invokeExact(src);
            final Integer idxA = visibleIndexByGuid.get(guidA);
            final Integer idxB = visibleIndexByGuid.get(guidB);
            glueMeshAVisibleIdx[i] = idxA != null ? idxA : -1;
            glueMeshBVisibleIdx[i] = idxB != null ? idxB : -1;
            if (glueMeshAVisibleIdx[i] >= 0) {
                if (adj[glueMeshAVisibleIdx[i]] == null) {
                    adj[glueMeshAVisibleIdx[i]] = new ArrayList<>(INITIAL_LIST_CAPACITY);
                }
                adj[glueMeshAVisibleIdx[i]].add(i);
            }
            if (glueMeshBVisibleIdx[i] >= 0) {
                if (adj[glueMeshBVisibleIdx[i]] == null) {
                    adj[glueMeshBVisibleIdx[i]] = new ArrayList<>(INITIAL_LIST_CAPACITY);
                }
                adj[glueMeshBVisibleIdx[i]].add(i);
            }
        }
        meshGlueAdjacency = new int[visibleMeshes.length][];
        for (int i = 0; i < visibleMeshes.length; i++) {
            final List<Integer> list = adj[i];
            if (list == null) {
                meshGlueAdjacency[i] = null;
            } else {
                final int[] arr = new int[list.size()];
                for (int k = 0; k < list.size(); k++) {
                    arr[k] = list.get(k);
                }
                meshGlueAdjacency[i] = arr;
            }
        }
    }

    private void buildArtPathMapping(final Object model, final EditSubtreeHandles h) throws Throwable {
        final Object pathObj = (Object) h.modelGetAllArtPaths.invokeExact(model);
        final List<?> pathList = pathObj instanceof List<?> list ? list : List.of();
        hasArtPaths = !pathList.isEmpty();
        artPathDeformerIdx = new int[pathList.size()];
        for (int i = 0; i < pathList.size(); i++) {
            final Object pGuid = (Object) h.artPathGetTargetGuid.invokeExact(pathList.get(i));
            final Object parent = pGuid == null || pGuid.equals(h.emptyDeformerGuid)
                    ? null
                    : (Object) h.modelGetDeformer.invokeExact(model, pGuid);
            artPathDeformerIdx[i] = parent == null ? -1 : deformerIndexByRef.getOrDefault(parent, -1);
        }
    }

    private void snapshotAllDeformerStates(
            final IdentityHashMap<Object, Boolean> activeEditSources,
            final EditSubtreeHandles h,
            final LongAdder fullArrayHashes)
            throws Throwable {
        final int n = deformers.length;
        lastDefSourceUpdateVer = new int[n];
        lastDefCopiedUpdateVer = new int[n];
        lastDefKeyformVersionStamp = new int[n];
        lastDefInterpForm = new Object[n];
        lastDefInterpEditVer = new int[n];
        for (int i = 0; i < n; i++) {
            final boolean captureSelected =
                    activeEditSources != null && activeEditSources.containsKey(deformerSources[i]);
            recordDeformerSnapshot(i, captureSelected, h, fullArrayHashes);
        }
    }

    void recordDeformerSnapshot(
            final int i, final boolean captureSelectedDeep, final EditSubtreeHandles h, final LongAdder fullArrayHashes)
            throws Throwable {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        final Object d = deformers[i];
        final Object src = deformerSources[i];
        lastDefSourceUpdateVer[i] = acc.sourceGetUpdateVersion(src);
        lastDefCopiedUpdateVer[i] = acc.deformerGetCopiedUpdateVersion(d);
        lastDefKeyformVersionStamp[i] = computeKeyformVersionStamp(src, h);
        final Object form = acc.deformerGetInterpolatedForm(d);
        lastDefInterpForm[i] = form;
        lastDefInterpEditVer[i] = form == null ? -1 : acc.formGetEditVersion(form);
        if (captureSelectedDeep) {
            selectedDeformerBaselines.put(
                    src, SelectedDeformerBaseline.capture(src, form, isWarp[i], isRotation[i], h, fullArrayHashes));
        }
    }

    private void snapshotAllMeshStates(
            final IdentityHashMap<Object, Boolean> activeEditSources,
            final EditSubtreeHandles h,
            final LongAdder fullArrayHashes)
            throws Throwable {
        final int mCount = visibleMeshes.length;
        lastMeshVer = new int[mCount];
        lastMeshCopiedVer = new int[mCount];
        lastMeshKeyformVersionStamp = new int[mCount];
        lastMeshInterpForm = new Object[mCount];
        lastMeshInterpEditVer = new int[mCount];
        lastMeshDrawOrder = new int[mCount];
        lastMeshVisible = new boolean[mCount];
        lastMeshVisHierarchy = new boolean[mCount];
        lastMeshGMeshPosRefs = new float[mCount][];
        for (int i = 0; i < mCount; i++) {
            final boolean captureSelected =
                    activeEditSources != null && activeEditSources.containsKey(visibleMeshSources[i]);
            recordMeshSnapshot(i, captureSelected, h, fullArrayHashes);
        }
    }

    void recordMeshSnapshot(
            final int i, final boolean captureSelectedDeep, final EditSubtreeHandles h, final LongAdder fullArrayHashes)
            throws Throwable {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        final Object m = visibleMeshes[i];
        final Object src = visibleMeshSources[i];
        lastMeshVer[i] = acc.meshSourceGetMeshVersion(src);
        lastMeshCopiedVer[i] = acc.meshGetCopiedMeshVersion(m);
        lastMeshKeyformVersionStamp[i] = computeKeyformVersionStamp(src, h);
        final Object form = acc.meshGetInterpolatedForm(m);
        lastMeshInterpForm[i] = form;
        lastMeshInterpEditVer[i] = form == null ? -1 : acc.formGetEditVersion(form);
        lastMeshDrawOrder[i] = acc.meshGetDrawOrder(m);
        lastMeshVisible[i] = acc.controllableSourceIsVisible(src);
        lastMeshVisHierarchy[i] = acc.controllableSourceIsVisibleHierarchy(src);
        if (captureSelectedDeep) {
            selectedMeshBaselines.put(src, SelectedMeshBaseline.capture(src, form, h, fullArrayHashes));
        }
        final Object container = acc.meshGetContainer(m);
        final Object gmesh = container == null ? null : acc.meshContainerGetMesh(container);
        lastMeshGMeshPosRefs[i] = gmesh == null ? null : acc.meshBaseGetPositions(gmesh);
    }

    private void snapshotAllGlueStates(final EditSubtreeHandles h) throws Throwable {
        final int aCount = affecters.length;
        lastGlueCachedVer = new int[aCount];
        lastGlueSourceVer = new int[aCount];
        lastGlueIntensityBits = new int[aCount];
        for (int i = 0; i < aCount; i++) {
            recordGlueSnapshot(i, h);
        }
    }

    void recordGlueSnapshot(final int i, final EditSubtreeHandles h) throws Throwable {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        final Object aff = affecters[i];
        if (!h.glueClass.isInstance(aff)) {
            return;
        }
        lastGlueCachedVer[i] = acc.glueGetCachedEditVersion(aff);
        final Object src = acc.glueGetSource(aff);
        lastGlueSourceVer[i] = acc.glueSourceGetEditVersion(src);
        final Object form = acc.glueGetInterpolatedForm(aff);
        lastGlueIntensityBits[i] = form == null ? 0 : Float.floatToRawIntBits(acc.glueFormGetIntensity(form));
    }

    void captureParameters(final Object paramSet, final EditSubtreeHandles h) throws Throwable {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        lastParamUpdateVersion = acc.paramSetGetUpdateVersion(paramSet);
        final List<?> params = acc.paramSetGetParameters(paramSet);
        if (params == null) {
            lastParamValues = null;
            return;
        }
        if (lastParamValues == null || lastParamValues.length != params.size()) {
            lastParamValues = new float[params.size()];
        }
        for (int i = 0; i < params.size(); i++) {
            lastParamValues[i] = acc.paramGetValue(params.get(i));
        }
    }

    boolean parametersUnchanged(final Object paramSet, final EditSubtreeHandles h) throws Throwable {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        if (acc.paramSetGetUpdateVersion(paramSet) != lastParamUpdateVersion) {
            return false;
        }
        final List<?> params = acc.paramSetGetParameters(paramSet);
        if (params == null || lastParamValues == null || params.size() != lastParamValues.length) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            final float cur = acc.paramGetValue(params.get(i));
            if (Float.floatToRawIntBits(cur) != Float.floatToRawIntBits(lastParamValues[i])) {
                return false;
            }
        }
        return true;
    }

    /** Computes an O(keyforms) integer version stamp without hashing position arrays or unpacking colors. */
    static int computeKeyformVersionStamp(final Object source, final EditSubtreeHandles h) {
        final EditSubtreeCompiledAccessors.HostOps acc = h.acc;
        final List<?> kfs = acc.sourceGetKeyforms(source);
        if (kfs == null) {
            return 0;
        }
        int stamp = kfs.size();
        for (int i = 0; i < kfs.size(); i++) {
            final Object kf = kfs.get(i);
            stamp *= HASH_PRIME;
            if (kf != null) {
                stamp += System.identityHashCode(kf) * HASH_PRIME + acc.formGetEditVersion(kf);
            }
        }
        return stamp;
    }

    static int computeDeformerKeyformSignature(
            final Object source,
            final boolean isWarp,
            final boolean isRotation,
            final EditSubtreeHandles h,
            final LongAdder fullArrayHashes)
            throws Throwable {
        final Object kfsObj = (Object) h.sourceGetKeyforms.invokeExact(source);
        if (!(kfsObj instanceof List<?> kfs)) {
            return 0;
        }
        int sum = kfs.size();
        for (final Object kf : kfs) {
            if (kf == null) {
                sum *= HASH_PRIME;
                continue;
            }
            sum = mixFormHeader(
                    sum,
                    kf,
                    (float) h.deformerFormGetOpacity.invokeExact(kf),
                    (Object) h.deformerFormGetMultiplyColor.invokeExact(kf),
                    (Object) h.deformerFormGetScreenColor.invokeExact(kf),
                    h);
            if (isWarp) {
                final float[] pos = (float[]) h.warpFormGetPositions.invokeExact(kf);
                if (fullArrayHashes != null) {
                    fullArrayHashes.increment();
                }
                sum = sum * HASH_PRIME + Arrays.hashCode(pos);
            } else if (isRotation) {
                sum = sum * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetOriginX.invokeExact(kf));
                sum = sum * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetOriginY.invokeExact(kf));
                sum = sum * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetAngle.invokeExact(kf));
                sum = sum * HASH_PRIME + Float.floatToRawIntBits((float) h.rotFormGetScale.invokeExact(kf));
                sum = sum * HASH_PRIME + ((boolean) h.rotFormIsReflectX.invokeExact(kf) ? 1 : 0);
                sum = sum * HASH_PRIME + ((boolean) h.rotFormIsReflectY.invokeExact(kf) ? 1 : 0);
            }
        }
        return sum;
    }

    static int computeMeshKeyformSignature(
            final Object source, final EditSubtreeHandles h, final LongAdder fullArrayHashes) throws Throwable {
        final Object kfsObj = (Object) h.sourceGetKeyforms.invokeExact(source);
        if (!(kfsObj instanceof List<?> kfs)) {
            return 0;
        }
        int sum = kfs.size();
        for (final Object kf : kfs) {
            if (kf == null) {
                sum *= HASH_PRIME;
                continue;
            }
            sum = mixFormHeader(
                    sum,
                    kf,
                    (float) h.meshFormGetOpacity.invokeExact(kf),
                    (Object) h.meshFormGetMultiplyColor.invokeExact(kf),
                    (Object) h.meshFormGetScreenColor.invokeExact(kf),
                    h);
            final float[] pos = (float[]) h.meshFormGetPositions.invokeExact(kf);
            if (fullArrayHashes != null) {
                fullArrayHashes.increment();
            }
            sum = sum * HASH_PRIME + Arrays.hashCode(pos);
        }
        return sum;
    }

    private static void captureRgba(final Object color, final float[] dst, final EditSubtreeHandles h)
            throws Throwable {
        dst[0] = (float) h.floatColorGetR.invokeExact(color);
        dst[1] = (float) h.floatColorGetG.invokeExact(color);
        dst[2] = (float) h.floatColorGetB.invokeExact(color);
        dst[3] = (float) h.floatColorGetA.invokeExact(color);
    }

    /** Deep baseline scoped to a single selected or hovered deformer to detect unversioned handle drags. */
    static final class SelectedDeformerBaseline {
        static final int DIFF_NONE = 0;
        static final int DIFF_LOCAL = 1;
        static final int DIFF_KEYFORM = 2;

        final int keyformSig;
        final Object interpForm;
        final int opacityBits;
        final float[] multiplyRgba = new float[ROTATION_SCALAR_STRIDE];
        final float[] screenRgba = new float[ROTATION_SCALAR_STRIDE];
        final float[] warpPositions;
        final float[] rotScalars;
        final boolean rotReflectX;
        final boolean rotReflectY;

        private SelectedDeformerBaseline(
                final int keyformSig,
                final Object interpForm,
                final int opacityBits,
                final float[] warpPositions,
                final float[] rotScalars,
                final boolean rotReflectX,
                final boolean rotReflectY) {
            this.keyformSig = keyformSig;
            this.interpForm = interpForm;
            this.opacityBits = opacityBits;
            this.warpPositions = warpPositions;
            this.rotScalars = rotScalars;
            this.rotReflectX = rotReflectX;
            this.rotReflectY = rotReflectY;
        }

        static SelectedDeformerBaseline capture(
                final Object src,
                final Object interpForm,
                final boolean isWarp,
                final boolean isRotation,
                final EditSubtreeHandles h,
                final LongAdder fullArrayHashes)
                throws Throwable {
            final int kfSig = computeDeformerKeyformSignature(src, isWarp, isRotation, h, fullArrayHashes);
            if (interpForm == null) {
                return new SelectedDeformerBaseline(kfSig, null, 0, null, null, false, false);
            }
            final int opacity = Float.floatToRawIntBits((float) h.deformerFormGetOpacity.invokeExact(interpForm));
            float[] wPos = null;
            float[] rSc = null;
            boolean rX = false;
            boolean rY = false;
            if (isWarp) {
                final float[] pos = (float[]) h.warpFormGetPositions.invokeExact(interpForm);
                wPos = pos == null ? null : pos.clone();
            } else if (isRotation) {
                rSc = new float[] {
                    (float) h.rotFormGetOriginX.invokeExact(interpForm),
                    (float) h.rotFormGetOriginY.invokeExact(interpForm),
                    (float) h.rotFormGetAngle.invokeExact(interpForm),
                    (float) h.rotFormGetScale.invokeExact(interpForm)
                };
                rX = (boolean) h.rotFormIsReflectX.invokeExact(interpForm);
                rY = (boolean) h.rotFormIsReflectY.invokeExact(interpForm);
            }
            final SelectedDeformerBaseline b =
                    new SelectedDeformerBaseline(kfSig, interpForm, opacity, wPos, rSc, rX, rY);
            captureRgba((Object) h.deformerFormGetMultiplyColor.invokeExact(interpForm), b.multiplyRgba, h);
            captureRgba((Object) h.deformerFormGetScreenColor.invokeExact(interpForm), b.screenRgba, h);
            return b;
        }

        int diff(
                final Object src,
                final Object curForm,
                final boolean isWarp,
                final boolean isRotation,
                final EditSubtreeHandles h,
                final LongAdder fullArrayHashes)
                throws Throwable {
            final int curKfSig = computeDeformerKeyformSignature(src, isWarp, isRotation, h, fullArrayHashes);
            if (curKfSig != keyformSig) {
                return DIFF_KEYFORM;
            }
            if (curForm != interpForm) {
                return DIFF_LOCAL;
            }
            if (curForm == null) {
                return DIFF_NONE;
            }
            if (Float.floatToRawIntBits((float) h.deformerFormGetOpacity.invokeExact(curForm)) != opacityBits) {
                return DIFF_LOCAL;
            }
            final Object mul = (Object) h.deformerFormGetMultiplyColor.invokeExact(curForm);
            final Object scr = (Object) h.deformerFormGetScreenColor.invokeExact(curForm);
            if (!EditSubtreeHandles.sameFloatColor(
                            multiplyRgba[0], multiplyRgba[1], multiplyRgba[2], multiplyRgba[3], mul, h)
                    || !EditSubtreeHandles.sameFloatColor(
                            screenRgba[0], screenRgba[1], screenRgba[2], screenRgba[3], scr, h)) {
                return DIFF_LOCAL;
            }
            if (isWarp) {
                final float[] pos = (float[]) h.warpFormGetPositions.invokeExact(curForm);
                if (fullArrayHashes != null) {
                    fullArrayHashes.increment();
                }
                return Arrays.equals(pos, warpPositions) ? DIFF_NONE : DIFF_LOCAL;
            }
            if (isRotation) {
                final boolean sameScalars = Float.floatToRawIntBits((float) h.rotFormGetOriginX.invokeExact(curForm))
                                == Float.floatToRawIntBits(rotScalars[0])
                        && Float.floatToRawIntBits((float) h.rotFormGetOriginY.invokeExact(curForm))
                                == Float.floatToRawIntBits(rotScalars[1])
                        && Float.floatToRawIntBits((float) h.rotFormGetAngle.invokeExact(curForm))
                                == Float.floatToRawIntBits(rotScalars[2])
                        && Float.floatToRawIntBits((float) h.rotFormGetScale.invokeExact(curForm))
                                == Float.floatToRawIntBits(rotScalars[3])
                        && (boolean) h.rotFormIsReflectX.invokeExact(curForm) == rotReflectX
                        && (boolean) h.rotFormIsReflectY.invokeExact(curForm) == rotReflectY;
                return sameScalars ? DIFF_NONE : DIFF_LOCAL;
            }
            return DIFF_NONE;
        }
    }

    /** Deep baseline scoped to a single selected or hovered ArtMesh. */
    static final class SelectedMeshBaseline {
        final int keyformSig;
        final Object interpForm;
        final int opacityBits;
        final long colorHash;

        private SelectedMeshBaseline(
                final int keyformSig, final Object interpForm, final int opacityBits, final long colorHash) {
            this.keyformSig = keyformSig;
            this.interpForm = interpForm;
            this.opacityBits = opacityBits;
            this.colorHash = colorHash;
        }

        static SelectedMeshBaseline capture(
                final Object src, final Object interpForm, final EditSubtreeHandles h, final LongAdder fullArrayHashes)
                throws Throwable {
            final int kfSig = computeMeshKeyformSignature(src, h, fullArrayHashes);
            if (interpForm == null) {
                return new SelectedMeshBaseline(kfSig, null, 0, 0L);
            }
            final int opacity = Float.floatToRawIntBits((float) h.meshFormGetOpacity.invokeExact(interpForm));
            final long mh =
                    EditSubtreeHandles.packColorRgba(h, (Object) h.meshFormGetMultiplyColor.invokeExact(interpForm));
            final long sh =
                    EditSubtreeHandles.packColorRgba(h, (Object) h.meshFormGetScreenColor.invokeExact(interpForm));
            return new SelectedMeshBaseline(kfSig, interpForm, opacity, mh * HASH_PRIME + sh);
        }

        boolean matches(
                final Object src, final Object curForm, final EditSubtreeHandles h, final LongAdder fullArrayHashes)
                throws Throwable {
            if (computeMeshKeyformSignature(src, h, fullArrayHashes) != keyformSig || curForm != interpForm) {
                return false;
            }
            if (curForm == null) {
                return true;
            }
            if (Float.floatToRawIntBits((float) h.meshFormGetOpacity.invokeExact(curForm)) != opacityBits) {
                return false;
            }
            final long mh =
                    EditSubtreeHandles.packColorRgba(h, (Object) h.meshFormGetMultiplyColor.invokeExact(curForm));
            final long sh = EditSubtreeHandles.packColorRgba(h, (Object) h.meshFormGetScreenColor.invokeExact(curForm));
            return (mh * HASH_PRIME + sh) == colorHash;
        }
    }

    private static int mixFormHeader(
            final int seed,
            final Object kf,
            final float opacity,
            final Object mul,
            final Object scr,
            final EditSubtreeHandles h)
            throws Throwable {
        int sum = seed * HASH_PRIME + System.identityHashCode(kf);
        sum = sum * HASH_PRIME + (int) h.formGetEditVersion.invokeExact(kf);
        sum = sum * HASH_PRIME + Float.floatToRawIntBits(opacity);
        final long mulHash = mul == null ? 0L : EditSubtreeHandles.packColorRgba(h, mul);
        final long scrHash = scr == null ? 0L : EditSubtreeHandles.packColorRgba(h, scr);
        sum = sum * HASH_PRIME + Long.hashCode(mulHash);
        return sum * HASH_PRIME + Long.hashCode(scrHash);
    }

    private static int partSignature(final Object part, final EditSubtreeCompiledAccessors.HostOps acc) {
        final Object src = acc.partGetSource(part);
        int sig = System.identityHashCode(part);
        sig = sig * HASH_PRIME + (acc.partSourceIsVisible(src) ? 1 : 0);
        sig = sig * HASH_PRIME + (acc.partSourceIsVisibleHierarchy(src) ? 1 : 0);
        sig = sig * HASH_PRIME + acc.partGetDrawOrder(part);
        final Object form = acc.partGetInterpolatedForm(part);
        sig = sig * HASH_PRIME + System.identityHashCode(form);
        if (form != null) {
            sig = sig * HASH_PRIME + acc.formGetEditVersion(form);
        }
        return sig;
    }
}
