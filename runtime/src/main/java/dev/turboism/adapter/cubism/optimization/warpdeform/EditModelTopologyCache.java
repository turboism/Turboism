package dev.turboism.adapter.cubism.optimization.warpdeform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-model cached DAG topology, parameter/form snapshots, and deformer overlay epochs.
 *
 * <p>Rebuilt whenever model membership, parent linkage, visible ArtMesh set, or glue wiring
 * changes, so steady edit drag frames detect dirty roots without reconstructing maps.</p>
 */
final class EditModelTopologyCache {

    private static final int INITIAL_LIST_CAPACITY = 4;
    private static final int ROTATION_SCALAR_STRIDE = 4;
    private static final int COLOR_CHANNEL_STRIDE = 8;
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
    int[] meshParentDeformerIdx;
    boolean hasArtPaths;
    int[] artPathDeformerIdx;

    Object[] affecters;
    boolean allAffectersGlue;
    int[] glueMeshAVisibleIdx;
    int[] glueMeshBVisibleIdx;
    int[][] meshGlueAdjacency;

    // Per-deformer baseline fingerprints.
    int[] lastDefSourceUpdateVer;
    int[] lastDefCopiedUpdateVer;
    int[] lastDefKeyformSum;
    Object[] lastDefInterpForm;
    int[] lastDefInterpEditVer;
    int[] lastDefOpacityBits;
    float[] lastDefMultiplyRgba;
    float[] lastDefScreenRgba;
    float[] lastRotScalars;
    boolean[] lastRotReflectFlags;
    float[][] lastWarpPositions;

    // Per-visible-ArtMesh baseline fingerprints.
    int[] lastMeshVer;
    int[] lastMeshCopiedVer;
    int[] lastMeshKeyformSum;
    Object[] lastMeshInterpForm;
    int[] lastMeshInterpEditVer;
    int[] lastMeshDrawOrder;
    int[] lastMeshOpacityBits;
    long[] lastMeshColorHash;
    boolean[] lastMeshVisible;
    boolean[] lastMeshVisHierarchy;
    float[][] lastMeshGMeshPosRefs;

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
    int skipCursor;
    boolean[] activeDirtyMeshes;

    void armAppearanceSkip(final Thread thread, final boolean[] dirtyMeshes) {
        this.skipThread = thread;
        this.skipCursor = 0;
        this.activeDirtyMeshes = dirtyMeshes;
    }

    void disarmAppearanceSkip() {
        this.skipThread = null;
        this.skipCursor = 0;
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

        final Object partsObj = (Object) h.modelGetAllParts.invokeExact(model);
        if (!(partsObj instanceof List<?> parts) || parts.size() != lastPartsCount) {
            return false;
        }
        for (int i = 0; i < parts.size(); i++) {
            if (partSignature(parts.get(i), h) != lastPartSignatures[i]) {
                return false;
            }
        }

        final Object defObj = (Object) h.modelGetAllDeformers.invokeExact(model);
        if (!(defObj instanceof List<?> defList) || defList.size() != deformers.length) {
            return false;
        }
        for (int i = 0; i < deformers.length; i++) {
            final Object d = defList.get(i);
            if (d != deformers[i]) {
                return false;
            }
            final Object pGuid = (Object) h.deformerGetTargetGuid.invokeExact(d);
            final Object resolvedParent = pGuid == null || pGuid.equals(h.emptyDeformerGuid)
                    ? null
                    : (Object) h.modelGetDeformer.invokeExact(model, pGuid);
            final int expectedParent = deformerParentIdx[i];
            if (expectedParent < 0) {
                if (resolvedParent != null) {
                    return false;
                }
            } else if (resolvedParent != deformers[expectedParent]) {
                return false;
            }
        }

        final Object allMeshObj = (Object) h.modelGetAllArtMeshes.invokeExact(model);
        if (!(allMeshObj instanceof List<?> meshList) || meshList.size() != allArtMeshes.length) {
            return false;
        }
        int visCursor = 0;
        for (int i = 0; i < meshList.size(); i++) {
            final Object m = meshList.get(i);
            if (m != allArtMeshes[i]) {
                return false;
            }
            final Object src = (Object) h.meshGetSource.invokeExact(m);
            final boolean vis = !optimizeMesh || (boolean) h.meshSourceIsVisibleHierarchy.invokeExact(src);
            if (vis) {
                if (visCursor >= visibleMeshes.length || visibleMeshes[visCursor] != m) {
                    return false;
                }
                final Object pGuid = (Object) h.meshGetTargetGuid.invokeExact(m);
                final Object resolvedParent = pGuid == null || pGuid.equals(h.emptyDeformerGuid)
                        ? null
                        : (Object) h.modelGetDeformer.invokeExact(model, pGuid);
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

        final Object affObj = (Object) h.modelGetAllAffecters.invokeExact(model);
        if (!(affObj instanceof List<?> affList) || affList.size() != affecters.length) {
            return false;
        }
        for (int i = 0; i < affecters.length; i++) {
            if (affList.get(i) != affecters[i]) {
                return false;
            }
        }
        final Object pathObj = (Object) h.modelGetAllArtPaths.invokeExact(model);
        if (!(pathObj instanceof List<?> pathList)) {
            return false;
        }
        if (hasArtPaths != !pathList.isEmpty() || pathList.size() != artPathDeformerIdx.length) {
            return false;
        }
        for (int i = 0; i < pathList.size(); i++) {
            final Object pGuid = (Object) h.artPathGetTargetGuid.invokeExact(pathList.get(i));
            final Object parent = pGuid == null || pGuid.equals(h.emptyDeformerGuid)
                    ? null
                    : (Object) h.modelGetDeformer.invokeExact(model, pGuid);
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
            final EditSubtreeHandles h)
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
            lastPartSignatures[i] = partSignature(parts.get(i), h);
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

        snapshotAllDeformerStates(h);
        snapshotAllMeshStates(h);
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
        isWarp = new boolean[n];
        isRotation = new boolean[n];
        deformerParentIdx = new int[n];
        deformerIndexByRef.clear();

        for (int i = 0; i < n; i++) {
            final Object d = defList.get(i);
            deformers[i] = d;
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
        final List<Integer> parentIndices = new ArrayList<>(allList.size());
        final IdentityHashMap<Object, Integer> visibleIndexByGuidSourceOrMesh = new IdentityHashMap<>(allList.size());
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
            parentIndices.add(pIdx);
            visibleIndexByGuidSourceOrMesh.put(m, visIdx);
            visibleIndexByGuid.put((Object) h.meshGetGuid.invokeExact(m), visIdx);
        }
        visibleMeshes = visible.toArray();
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

    private void snapshotAllDeformerStates(final EditSubtreeHandles h) throws Throwable {
        final int n = deformers.length;
        lastDefSourceUpdateVer = new int[n];
        lastDefCopiedUpdateVer = new int[n];
        lastDefKeyformSum = new int[n];
        lastDefInterpForm = new Object[n];
        lastDefInterpEditVer = new int[n];
        lastDefOpacityBits = new int[n];
        lastDefMultiplyRgba = new float[n * ROTATION_SCALAR_STRIDE];
        lastDefScreenRgba = new float[n * ROTATION_SCALAR_STRIDE];
        lastRotScalars = new float[n * ROTATION_SCALAR_STRIDE];
        lastRotReflectFlags = new boolean[n * 2];
        lastWarpPositions = new float[n][];
        for (int i = 0; i < n; i++) {
            recordDeformerSnapshot(i, h);
        }
    }

    void recordDeformerSnapshot(final int i, final EditSubtreeHandles h) throws Throwable {
        final Object d = deformers[i];
        final Object src = (Object) h.deformerGetSource.invokeExact(d);
        lastDefSourceUpdateVer[i] = (int) h.sourceGetUpdateVersion.invokeExact(src);
        lastDefCopiedUpdateVer[i] = (int) h.deformerGetCopiedUpdateVersion.invokeExact(d);
        lastDefKeyformSum[i] = computeDeformerKeyformSignature(src, isWarp[i], isRotation[i], h);
        final Object form = (Object) h.deformerGetInterpolatedForm.invokeExact(d);
        lastDefInterpForm[i] = form;
        lastDefInterpEditVer[i] = form == null ? -1 : (int) h.formGetEditVersion.invokeExact(form);
        if (form != null) {
            lastDefOpacityBits[i] = Float.floatToRawIntBits((float) h.deformerFormGetOpacity.invokeExact(form));
            final Object mul = (Object) h.deformerFormGetMultiplyColor.invokeExact(form);
            final Object scr = (Object) h.deformerFormGetScreenColor.invokeExact(form);
            final int base = i * ROTATION_SCALAR_STRIDE;
            lastDefMultiplyRgba[base] = (float) h.floatColorGetR.invokeExact(mul);
            lastDefMultiplyRgba[base + 1] = (float) h.floatColorGetG.invokeExact(mul);
            lastDefMultiplyRgba[base + 2] = (float) h.floatColorGetB.invokeExact(mul);
            lastDefMultiplyRgba[base + 3] = (float) h.floatColorGetA.invokeExact(mul);
            lastDefScreenRgba[base] = (float) h.floatColorGetR.invokeExact(scr);
            lastDefScreenRgba[base + 1] = (float) h.floatColorGetG.invokeExact(scr);
            lastDefScreenRgba[base + 2] = (float) h.floatColorGetB.invokeExact(scr);
            lastDefScreenRgba[base + 3] = (float) h.floatColorGetA.invokeExact(scr);
            if (isWarp[i]) {
                final float[] pos = (float[]) h.warpFormGetPositions.invokeExact(form);
                float[] copy = lastWarpPositions[i];
                if (pos == null) {
                    lastWarpPositions[i] = null;
                } else {
                    if (copy == null || copy.length != pos.length) {
                        copy = new float[pos.length];
                        lastWarpPositions[i] = copy;
                    }
                    System.arraycopy(pos, 0, copy, 0, pos.length);
                }
            } else if (isRotation[i]) {
                lastRotScalars[base] = (float) h.rotFormGetOriginX.invokeExact(form);
                lastRotScalars[base + 1] = (float) h.rotFormGetOriginY.invokeExact(form);
                lastRotScalars[base + 2] = (float) h.rotFormGetAngle.invokeExact(form);
                lastRotScalars[base + 3] = (float) h.rotFormGetScale.invokeExact(form);
                lastRotReflectFlags[i * 2] = (boolean) h.rotFormIsReflectX.invokeExact(form);
                lastRotReflectFlags[i * 2 + 1] = (boolean) h.rotFormIsReflectY.invokeExact(form);
            }
        }
    }

    private void snapshotAllMeshStates(final EditSubtreeHandles h) throws Throwable {
        final int mCount = visibleMeshes.length;
        lastMeshVer = new int[mCount];
        lastMeshCopiedVer = new int[mCount];
        lastMeshKeyformSum = new int[mCount];
        lastMeshInterpForm = new Object[mCount];
        lastMeshInterpEditVer = new int[mCount];
        lastMeshDrawOrder = new int[mCount];
        lastMeshOpacityBits = new int[mCount];
        lastMeshColorHash = new long[mCount];
        lastMeshVisible = new boolean[mCount];
        lastMeshVisHierarchy = new boolean[mCount];
        lastMeshGMeshPosRefs = new float[mCount][];
        for (int i = 0; i < mCount; i++) {
            recordMeshSnapshot(i, h);
        }
    }

    void recordMeshSnapshot(final int i, final EditSubtreeHandles h) throws Throwable {
        final Object m = visibleMeshes[i];
        final Object src = (Object) h.meshGetSource.invokeExact(m);
        lastMeshVer[i] = (int) h.meshSourceGetMeshVersion.invokeExact(src);
        lastMeshCopiedVer[i] = (int) h.meshGetCopiedMeshVersion.invokeExact(m);
        lastMeshKeyformSum[i] = computeMeshKeyformSignature(src, h);
        final Object form = (Object) h.meshGetInterpolatedForm.invokeExact(m);
        lastMeshInterpForm[i] = form;
        lastMeshInterpEditVer[i] = form == null ? -1 : (int) h.formGetEditVersion.invokeExact(form);
        lastMeshDrawOrder[i] = (int) h.meshGetDrawOrder.invokeExact(m);
        lastMeshVisible[i] = (boolean) h.meshSourceIsVisible.invokeExact(src);
        lastMeshVisHierarchy[i] = (boolean) h.meshSourceIsVisibleHierarchy.invokeExact(src);
        if (form != null) {
            lastMeshOpacityBits[i] = Float.floatToRawIntBits((float) h.meshFormGetOpacity.invokeExact(form));
            final long mh = EditSubtreeHandles.packColorRgba(h, (Object) h.meshFormGetMultiplyColor.invokeExact(form));
            final long sh = EditSubtreeHandles.packColorRgba(h, (Object) h.meshFormGetScreenColor.invokeExact(form));
            lastMeshColorHash[i] = mh * HASH_PRIME + sh;
        }
        final Object container = (Object) h.meshGetContainer.invokeExact(m);
        final Object gmesh = container == null ? null : (Object) h.meshContainerGetMesh.invokeExact(container);
        lastMeshGMeshPosRefs[i] = gmesh == null ? null : (float[]) h.meshBaseGetPositions.invokeExact(gmesh);
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
        final Object aff = affecters[i];
        if (!h.glueClass.isInstance(aff)) {
            return;
        }
        lastGlueCachedVer[i] = (int) h.glueGetCachedEditVersion.invokeExact(aff);
        final Object src = (Object) h.glueGetSource.invokeExact(aff);
        lastGlueSourceVer[i] = (int) h.glueSourceGetEditVersion.invokeExact(src);
        final Object form = (Object) h.glueGetInterpolatedForm.invokeExact(aff);
        lastGlueIntensityBits[i] =
                form == null ? 0 : Float.floatToRawIntBits((float) h.glueFormGetIntensity.invokeExact(form));
    }

    void captureParameters(final Object paramSet, final EditSubtreeHandles h) throws Throwable {
        lastParamUpdateVersion = (int) h.paramSetGetUpdateVersion.invokeExact(paramSet);
        final Object paramsObj = (Object) h.paramSetGetParameters.invokeExact(paramSet);
        if (!(paramsObj instanceof List<?> params)) {
            lastParamValues = null;
            return;
        }
        if (lastParamValues == null || lastParamValues.length != params.size()) {
            lastParamValues = new float[params.size()];
        }
        for (int i = 0; i < params.size(); i++) {
            lastParamValues[i] = (float) h.paramGetValue.invokeExact(params.get(i));
        }
    }

    boolean parametersUnchanged(final Object paramSet, final EditSubtreeHandles h) throws Throwable {
        if ((int) h.paramSetGetUpdateVersion.invokeExact(paramSet) != lastParamUpdateVersion) {
            return false;
        }
        final Object paramsObj = (Object) h.paramSetGetParameters.invokeExact(paramSet);
        if (!(paramsObj instanceof List<?> params)
                || lastParamValues == null
                || params.size() != lastParamValues.length) {
            return false;
        }
        for (int i = 0; i < params.size(); i++) {
            final float cur = (float) h.paramGetValue.invokeExact(params.get(i));
            if (Float.floatToRawIntBits(cur) != Float.floatToRawIntBits(lastParamValues[i])) {
                return false;
            }
        }
        return true;
    }

    static int computeDeformerKeyformSignature(
            final Object source, final boolean isWarp, final boolean isRotation, final EditSubtreeHandles h)
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

    static int computeMeshKeyformSignature(final Object source, final EditSubtreeHandles h) throws Throwable {
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
            sum = sum * HASH_PRIME + Arrays.hashCode(pos);
        }
        return sum;
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

    private static int partSignature(final Object part, final EditSubtreeHandles h) throws Throwable {
        final Object src = (Object) h.partGetSource.invokeExact(part);
        int sig = System.identityHashCode(part);
        sig = sig * HASH_PRIME + ((boolean) h.partSourceIsVisible.invokeExact(src) ? 1 : 0);
        sig = sig * HASH_PRIME + ((boolean) h.partSourceIsVisibleHierarchy.invokeExact(src) ? 1 : 0);
        sig = sig * HASH_PRIME + (int) h.partGetDrawOrder.invokeExact(part);
        final Object form = (Object) h.partGetInterpolatedForm.invokeExact(part);
        sig = sig * HASH_PRIME + System.identityHashCode(form);
        if (form != null) {
            sig = sig * HASH_PRIME + (int) h.formGetEditVersion.invokeExact(form);
        }
        return sig;
    }
}
