package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.swing.JTable;

/**
 * Eliminates Deformer palette {@code CDeformerTreeTable} retention leaks and skips redundant
 * {@code a(boolean)} tree rebuilds when hierarchy, names, visibility, locks, and context match.
 */
final class DeformerPaletteHelper {

    private static final String MODEL_DOC_CLASS = "com/live2d/cubism/doc/modeling/CModelingDocument";
    private static final String CMODEL_CLASS = "com/live2d/cubism/doc/model/CModel";
    private static final String MODEL_SOURCE_CLASS = "com/live2d/cubism/doc/model/CModelSource";
    private static final String CONTROLLABLE_CLASS = "com/live2d/cubism/doc/model/ACParameterControllable";
    private static final String CONTROLLABLE_SOURCE_CLASS = "com/live2d/cubism/doc/model/ACParameterControllableSource";
    private static final String PART_SOURCE_CLASS = "com/live2d/cubism/doc/model/parts/CPartSource";
    private static final String VIEW_CONTEXT_CLASS = "com/live2d/cubism/view/context/CEViewContext";
    private static final String MODELING_VIEW_CONTEXT_CLASS =
            "com/live2d/cubism/view/context/CEViewContext_ModelingView";
    private static final String COMPLETE_PACK_CLASS = "com/live2d/cubism/pack/CECompletePack";
    private static final String UPDATE_MANAGER_CLASS = "com/live2d/cubism/CEUpdateManager";
    private static final String REPAINT_TYPE_CLASS = "com/live2d/cubism/CEUpdateManager$b";
    private static final String DEFORMER_HANDLER_FIELD = "c";
    private static final String GET_SOURCE_METHOD = "get_source";
    private static final String GET_SOURCE_FALLBACK_METHOD = "getSource";
    private static final String EXPAND_DEFORMER_METHOD = "expandByObjectGuidDeformerPalette";
    private static final String HANDLER_SYNC_METHOD = "h";
    private static final String REPAINT_FLAG_DEFAULT_FIELD = "a";
    private static final String REPAINT_FLAG_ERROR = "Cannot read CEUpdateManager$b.a";

    private final WeakIdentityMap<Object, TreeTableSupport.CachedPanelState> cachedStates = new WeakIdentityMap<>();
    private final Listener statsListener;
    private final TreeTableSupport.Detacher detacher;

    private final Class<?> modelingViewContextClass;
    private final HostHandles.HostField panelCompletePackField;
    private final HostHandles.HostField panelTreeTableField;
    private final HostHandles.HostField panelHandlerField;
    private final HostHandles.HostField panelInitFlagField;
    private final List<HostHandles.HostField> panelBoolFilterFields;
    private final MethodHandle panelGetDoc;
    private final MethodHandle panelGetModel;
    private final MethodHandle panelGetModelSource;
    private final MethodHandle panelGetViewContext;
    private final MethodHandle handlerSyncSelection;
    private final List<HostHandles.HostField> handlerClearFields;

    private final MethodHandle packGetUpdateManager;
    private final MethodHandle packRepaintCanvas;
    private final Object repaintCanvasDefaultFlag;
    private final MethodHandle updateMgrSelectionGuids;
    private final MethodHandle updateMgrExpandDeformer;

    private final MethodHandle modelGetAllDeformers;
    private final MethodHandle modelGetAllArtMeshes;
    private final MethodHandle modelGetAllArtPaths;
    private final MethodHandle modelSourceGetAllParts;
    private final MethodHandle modelSourceGetRootPart;

    private final MethodHandle objGetSource;
    private final MethodHandle sourceGetGuid;
    private final MethodHandle sourceGetTargetDeformerGuid;
    private final MethodHandle sourceGetId;
    private final MethodHandle sourceGetLocalName;
    private final MethodHandle sourceIsVisible;
    private final MethodHandle sourceIsLocked;
    private final MethodHandle sourceIsLockedInHierarchy;
    private final MethodHandle partGetChildGuids;

    /** Callback receiving telemetry events for deformer tree detach and reuse. */
    interface Listener {
        void onDeformerTreeDetached();

        void onDeformerTreeReused();
    }

    DeformerPaletteHelper(
            final ClassLoader loader,
            final MemoryLeakMitigationTarget target,
            final TreeTableSupport.Detacher detacher,
            final Listener listener)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");
        this.detacher = Objects.requireNonNull(detacher, "detacher");
        this.statsListener = Objects.requireNonNull(listener, "listener");

        final Class<?> panelClass = HostHandles.load(loader, target.deformerPanelOwner());
        final Class<?> handlerClass = HostHandles.load(loader, MemoryLeakMitigationTarget.DEFORMER_HANDLER_OWNER);
        final Class<?> completePackClass = HostHandles.load(loader, COMPLETE_PACK_CLASS);
        final Class<?> modelingDocClass = HostHandles.load(loader, MODEL_DOC_CLASS);
        final Class<?> cModelClass = HostHandles.load(loader, CMODEL_CLASS);
        final Class<?> modelSourceClass = HostHandles.load(loader, MODEL_SOURCE_CLASS);
        final Class<?> viewContextClass = HostHandles.load(loader, VIEW_CONTEXT_CLASS);
        modelingViewContextClass = HostHandles.load(loader, MODELING_VIEW_CONTEXT_CLASS);

        panelCompletePackField = TreeTableSupport.findUniqueFieldByAssignableType(panelClass, completePackClass);
        panelTreeTableField = HostHandles.field(panelClass, MemoryLeakMitigationTarget.TREE_TABLE_FIELD);
        panelHandlerField = HostHandles.field(panelClass, DEFORMER_HANDLER_FIELD);
        final String initFlagName = resolveInitFlagFieldName(panelClass, target.deformerInitFlagField());
        panelInitFlagField = initFlagName == null ? null : HostHandles.field(panelClass, initFlagName);

        final List<HostHandles.HostField> bools = new java.util.ArrayList<>();
        for (final Field f : panelClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    && f.getType() == boolean.class
                    && !f.getName().equals(initFlagName)) {
                bools.add(HostHandles.field(panelClass, f.getName()));
            }
        }
        panelBoolFilterFields = List.copyOf(bools);

        panelGetDoc = HostHandles.unreflect(TreeTableSupport.findSingleMethodByReturn(panelClass, modelingDocClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        panelGetModel = HostHandles.unreflect(TreeTableSupport.findSingleMethodByReturn(panelClass, cModelClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        panelGetModelSource = HostHandles.unreflect(
                        TreeTableSupport.findSingleMethodByReturn(panelClass, modelSourceClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        panelGetViewContext = HostHandles.unreflect(
                        TreeTableSupport.findSingleMethodByReturn(panelClass, viewContextClass))
                .asType(MethodType.methodType(Object.class, Object.class));

        final Method syncMethod = TreeTableSupport.findOptionalNoArgMethod(handlerClass, HANDLER_SYNC_METHOD);
        handlerSyncSelection = syncMethod == null
                ? null
                : HostHandles.unreflect(syncMethod).asType(MethodType.methodType(void.class, Object.class));
        final List<HostHandles.HostField> clearables = new java.util.ArrayList<>();
        for (final Field f : handlerClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    && !Modifier.isFinal(f.getModifiers())
                    && !f.getType().isPrimitive()
                    && Collection.class.isAssignableFrom(f.getType())) {
                clearables.add(HostHandles.field(handlerClass, f.getName()));
            }
        }
        handlerClearFields = List.copyOf(clearables);

        final Class<?> updateMgrClass = HostHandles.load(loader, UPDATE_MANAGER_CLASS);
        final Class<?> repaintEnumClass = HostHandles.load(loader, REPAINT_TYPE_CLASS);
        packGetUpdateManager = HostHandles.virtual(
                completePackClass,
                "getUpdateManager",
                "()L" + UPDATE_MANAGER_CLASS + ";",
                MethodType.methodType(Object.class, Object.class));
        packRepaintCanvas = HostHandles.virtual(
                completePackClass,
                "repaintCanvas",
                "(L" + REPAINT_TYPE_CLASS + ";)V",
                MethodType.methodType(void.class, Object.class, Object.class));
        try {
            repaintCanvasDefaultFlag = HostHandles.field(repaintEnumClass, REPAINT_FLAG_DEFAULT_FIELD)
                    .get(null);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException(REPAINT_FLAG_ERROR, failure);
        }
        updateMgrSelectionGuids = HostHandles.virtual(
                updateMgrClass,
                "getSelectionGuidList",
                "()Ljava/util/List;",
                MethodType.methodType(List.class, Object.class));
        final Method expandMethod = resolveOptionalMethod(updateMgrClass, EXPAND_DEFORMER_METHOD, List.class);
        updateMgrExpandDeformer = expandMethod == null
                ? null
                : HostHandles.unreflect(expandMethod)
                        .asType(MethodType.methodType(void.class, Object.class, List.class));

        modelGetAllDeformers = HostHandles.virtual(
                cModelClass, "getAllDeformers", "()Ljava/util/List;", MethodType.methodType(List.class, Object.class));
        modelGetAllArtMeshes = HostHandles.virtual(
                cModelClass, "getAllArtMeshes", "()Ljava/util/List;", MethodType.methodType(List.class, Object.class));
        modelGetAllArtPaths = HostHandles.virtual(
                cModelClass, "getAllArtPaths", "()Ljava/util/List;", MethodType.methodType(List.class, Object.class));
        modelSourceGetAllParts = HostHandles.virtual(
                modelSourceClass, "getAllParts", "()Ljava/util/List;", MethodType.methodType(List.class, Object.class));
        final Method getRootPartMethod = TreeTableSupport.findOptionalNoArgMethod(modelSourceClass, "getRootPart");
        modelSourceGetRootPart = getRootPartMethod == null
                ? null
                : HostHandles.unreflect(getRootPartMethod).asType(MethodType.methodType(Object.class, Object.class));

        final Class<?> objClass = HostHandles.load(loader, CONTROLLABLE_CLASS);
        final Class<?> sourceClass = HostHandles.load(loader, CONTROLLABLE_SOURCE_CLASS);
        final Class<?> partSourceClass = HostHandles.load(loader, PART_SOURCE_CLASS);

        Method getSourceMethod = TreeTableSupport.findOptionalNoArgMethod(objClass, GET_SOURCE_METHOD);
        if (getSourceMethod == null) {
            getSourceMethod = TreeTableSupport.findNoArgMethod(objClass, GET_SOURCE_FALLBACK_METHOD);
        }
        objGetSource = HostHandles.unreflect(getSourceMethod).asType(MethodType.methodType(Object.class, Object.class));

        sourceGetGuid = HostHandles.unreflect(TreeTableSupport.findNoArgMethod(sourceClass, "getGuid"))
                .asType(MethodType.methodType(Object.class, Object.class));
        sourceGetTargetDeformerGuid = HostHandles.unreflect(
                        TreeTableSupport.findNoArgMethod(sourceClass, "getTargetDeformerGuid"))
                .asType(MethodType.methodType(Object.class, Object.class));
        sourceGetId = HostHandles.unreflect(TreeTableSupport.findNoArgMethod(sourceClass, "getId"))
                .asType(MethodType.methodType(Object.class, Object.class));
        sourceGetLocalName = HostHandles.virtual(
                sourceClass, "getLocalName", "()Ljava/lang/String;", MethodType.methodType(String.class, Object.class));
        sourceIsVisible = HostHandles.virtual(
                sourceClass, "isVisible", "()Z", MethodType.methodType(boolean.class, Object.class));
        sourceIsLocked =
                HostHandles.virtual(sourceClass, "isLocked", "()Z", MethodType.methodType(boolean.class, Object.class));
        sourceIsLockedInHierarchy = HostHandles.virtual(
                sourceClass, "isLockedInHierarchy", "()Z", MethodType.methodType(boolean.class, Object.class));
        partGetChildGuids = HostHandles.virtual(
                partSourceClass,
                "getChildGuids",
                "()Ljava/util/List;",
                MethodType.methodType(List.class, Object.class));
    }

    /** Detaches obsolete {@code CDeformerTreeTable} references before installing {@code nextTree}. */
    void onBeforeSetTreeTable(final Object panel, final Object nextTree) {
        if (panel == null) {
            return;
        }
        try {
            final Object previous = panelTreeTableField.get(panel);
            if (previous != null && previous != nextTree && detacher.detachTreeTable(previous)) {
                statsListener.onDeformerTreeDetached();
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Detaches active tree tables, clears cached snapshots, and resets init state on panel close. */
    void onPanelClosed(final Object panel) {
        if (panel == null) {
            return;
        }
        cachedStates.remove(panel);
        try {
            final Object current = panelTreeTableField.get(panel);
            if (current != null && detacher.detachTreeTable(current)) {
                statsListener.onDeformerTreeDetached();
            }
            if (panelInitFlagField != null) {
                panelInitFlagField.set(panel, Boolean.FALSE);
            }
            if (current != null) {
                panelTreeTableField.set(panel, null);
            }
            final Object handler = panelHandlerField.get(panel);
            if (handler != null) {
                for (final HostHandles.HostField f : handlerClearFields) {
                    final Object val = f.get(handler);
                    if (val instanceof Collection<?> col) {
                        col.clear();
                    }
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Checks whether the existing {@code CDeformerTreeTable} matches document, model, view context,
     * filter booleans, hierarchy, names, visibility, and locks; skips rebuild when unchanged.
     */
    boolean tryReuseDeformerPalette(final Object panel, final boolean skipExpand) {
        if (panel == null) {
            return false;
        }
        try {
            final Object viewCtx = (Object) panelGetViewContext.invokeExact(panel);
            if (!modelingViewContextClass.isInstance(viewCtx)) {
                return false;
            }
            final Object doc = (Object) panelGetDoc.invokeExact(panel);
            final Object model = (Object) panelGetModel.invokeExact(panel);
            final Object modelSource = (Object) panelGetModelSource.invokeExact(panel);
            if (doc == null || model == null || modelSource == null) {
                return false;
            }

            final Object currentTree = panelTreeTableField.get(panel);
            final Object handler = panelHandlerField.get(panel);
            final boolean initialized =
                    panelInitFlagField != null && Boolean.TRUE.equals(panelInitFlagField.get(panel));
            final long fingerprint = computeFingerprint(panel, viewCtx, doc, model, modelSource);
            if (fingerprint == 0L || currentTree == null || handler == null || !initialized) {
                cachedStates.put(panel, new TreeTableSupport.CachedPanelState(doc, fingerprint));
                return false;
            }

            final TreeTableSupport.CachedPanelState prev = cachedStates.get(panel);
            if (prev == null || prev.docRef.get() != doc || prev.fingerprint != fingerprint) {
                cachedStates.put(panel, new TreeTableSupport.CachedPanelState(doc, fingerprint));
                return false;
            }

            final Object pack = panelCompletePackField.get(panel);
            if (!skipExpand && pack != null && updateMgrExpandDeformer != null) {
                final Object updateMgr = (Object) packGetUpdateManager.invokeExact(pack);
                if (updateMgr != null) {
                    final List<?> selectedGuids = (List<?>) updateMgrSelectionGuids.invokeExact(updateMgr);
                    updateMgrExpandDeformer.invokeExact(updateMgr, selectedGuids);
                }
            }
            if (handlerSyncSelection != null) {
                handlerSyncSelection.invokeExact(handler);
            }
            if (pack != null && repaintCanvasDefaultFlag != null) {
                packRepaintCanvas.invokeExact(pack, repaintCanvasDefaultFlag);
            }
            final JTable jTable = (JTable) detacher.treeTableGetJTable().invokeExact(currentTree);
            if (jTable != null) {
                jTable.repaint();
            }
            statsListener.onDeformerTreeReused();
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private long computeFingerprint(
            final Object panel, final Object viewCtx, final Object doc, final Object model, final Object modelSource)
            throws Throwable {
        long h = TreeTableSupport.FNV_OFFSET_BASIS;
        h = TreeTableSupport.mix(h, System.identityHashCode(doc));
        h = TreeTableSupport.mix(h, System.identityHashCode(model));
        h = TreeTableSupport.mix(h, System.identityHashCode(modelSource));
        h = TreeTableSupport.mix(h, System.identityHashCode(viewCtx));
        h = TreeTableSupport.mix(h, System.identityHashCode(viewCtx.getClass()));

        for (final HostHandles.HostField f : panelBoolFilterFields) {
            h = TreeTableSupport.mix(h, Boolean.TRUE.equals(f.get(panel)) ? 1L : 0L);
        }

        h = mixControllables(h, (List<?>) modelGetAllDeformers.invokeExact(model));
        if (h == 0L) {
            return 0L;
        }
        h = mixControllables(h, (List<?>) modelGetAllArtMeshes.invokeExact(model));
        if (h == 0L) {
            return 0L;
        }
        h = mixControllables(h, (List<?>) modelGetAllArtPaths.invokeExact(model));
        if (h == 0L) {
            return 0L;
        }

        if (modelSourceGetRootPart != null) {
            final Object rootPart = (Object) modelSourceGetRootPart.invokeExact(modelSource);
            h = mixPartSource(h, rootPart);
        }
        final List<?> allParts = (List<?>) modelSourceGetAllParts.invokeExact(modelSource);
        if (allParts == null) {
            return TreeTableSupport.mixMissingList(h);
        }
        h = TreeTableSupport.mix(h, allParts.size());
        for (int i = 0, n = allParts.size(); i < n; i++) {
            h = mixPartSource(h, allParts.get(i));
        }
        return h == 0L ? 1L : h;
    }

    private long mixControllables(long h, final List<?> objects) throws Throwable {
        if (objects == null) {
            return TreeTableSupport.mixMissingList(h);
        }
        h = TreeTableSupport.mix(h, objects.size());
        for (int i = 0, n = objects.size(); i < n; i++) {
            final Object obj = objects.get(i);
            if (obj == null) {
                h = TreeTableSupport.mix(h, 0L);
                continue;
            }
            h = TreeTableSupport.mix(h, System.identityHashCode(obj));
            final Object src = (Object) objGetSource.invokeExact(obj);
            if (src == null) {
                return 0L;
            }
            h = mixSourceMetadata(h, src);
        }
        return h;
    }

    private long mixPartSource(long h, final Object partSource) throws Throwable {
        if (partSource == null) {
            return TreeTableSupport.mix(h, 0L);
        }
        h = mixSourceMetadata(h, partSource);
        final List<?> childGuids = (List<?>) partGetChildGuids.invokeExact(partSource);
        if (childGuids == null) {
            return TreeTableSupport.mixMissingList(h);
        }
        h = TreeTableSupport.mix(h, childGuids.size());
        for (int i = 0, n = childGuids.size(); i < n; i++) {
            h = TreeTableSupport.mixNullableHash(h, childGuids.get(i));
        }
        return h;
    }

    private long mixSourceMetadata(long h, final Object src) throws Throwable {
        h = TreeTableSupport.mix(h, System.identityHashCode(src));
        h = TreeTableSupport.mix(h, System.identityHashCode(src.getClass()));
        h = TreeTableSupport.mixNullableHash(h, (Object) sourceGetGuid.invokeExact(src));
        h = TreeTableSupport.mixNullableHash(h, (Object) sourceGetTargetDeformerGuid.invokeExact(src));
        h = TreeTableSupport.mixNullableHash(h, (Object) sourceGetId.invokeExact(src));
        h = TreeTableSupport.mixString(h, (String) sourceGetLocalName.invokeExact(src));
        h = TreeTableSupport.mix(h, (boolean) sourceIsVisible.invokeExact(src) ? 1L : 0L);
        h = TreeTableSupport.mix(h, (boolean) sourceIsLocked.invokeExact(src) ? 1L : 0L);
        h = TreeTableSupport.mix(h, (boolean) sourceIsLockedInHierarchy.invokeExact(src) ? 1L : 0L);
        return h;
    }

    private static String resolveInitFlagFieldName(final Class<?> panelClass, final String configuredName) {
        for (final Field f : panelClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    && f.getType() == boolean.class
                    && f.getName().equals(configuredName)) {
                return configuredName;
            }
        }
        return TreeTableSupport.resolveLastMutableBooleanFieldName(panelClass);
    }

    private static Method resolveOptionalMethod(final Class<?> owner, final String name, final Class<?> paramType) {
        try {
            return owner.getMethod(name, paramType);
        } catch (final NoSuchMethodException missing) {
            return null;
        }
    }
}
