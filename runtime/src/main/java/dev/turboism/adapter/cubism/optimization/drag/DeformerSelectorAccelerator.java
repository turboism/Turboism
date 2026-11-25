package dev.turboism.adapter.cubism.optimization.drag;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.ComboBoxModel;
import javax.swing.JComboBox;

/**
 * Accelerates {@code DeformerSelectorUiFactory.updateWidget()} with {@code O(D)} parent grouping
 * and structural hierarchy fingerprinting to avoid repeated {@code FlatComboBoxUI} layout churn.
 */
final class DeformerSelectorAccelerator implements AutoCloseable {

    enum UpdateOutcome {
        /** Reused the installed combo model and avoided redundant model swaps. */
        SKIPPED,
        /** Rebuilt combo model items in {@code O(D)} because the hierarchy or labels changed. */
        REBUILT,
        /** Deferred execution to the unmodified host bytecode due to assertions or errors. */
        FALLBACK
    }

    private static final String FACTORY_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/ParameterControllable_wrapperForInspector$DeformerSelectorUiFactory";
    private static final String COMBO_ITEM_OWNER = FACTORY_OWNER + "$DeformerComboBoxItem";
    private static final String SELECTABLE_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/ParameterControllable_wrapperForInspector$DeformerSelectable";
    private static final String WRAP_OWNER =
            "com/live2d/cubism/view/palette/inspector/editPalette/ParameterControllable_wrapperForInspector";
    private static final String DOC_OWNER = "com/live2d/cubism/doc/modeling/CModelingDocument";
    private static final String MODEL_OWNER = "com/live2d/cubism/doc/model/CModel";
    private static final String MODEL_SOURCE_OWNER = "com/live2d/cubism/doc/model/CModelSource";
    private static final String DEFORMER_OWNER = "com/live2d/cubism/doc/model/deformer/ACDeformer";
    private static final String DEFORMER_SOURCE_OWNER = "com/live2d/cubism/doc/model/deformer/ACDeformerSource";
    private static final String CONTROLLABLE_OWNER = "com/live2d/cubism/doc/model/ACParameterControllable";
    private static final String CONTROLLABLE_SOURCE_OWNER = "com/live2d/cubism/doc/model/ACParameterControllableSource";
    private static final String PART_SOURCE_OWNER = "com/live2d/cubism/doc/model/parts/CPartSource";
    private static final String ID_OWNER = "com/live2d/core/id/Id";
    private static final String DEFORMER_GUID_OWNER = "com/live2d/type/CDeformerGuid";
    private static final String DEFORMER_GUID_COMPANION_OWNER = "com/live2d/type/CDeformerGuid$a";
    private static final String COMBO_OWNER = "com/live2d/ui/control/CComboBox";
    private static final String COMBO_MODEL_OWNER = "com/live2d/ui/control/CComboBox$a";
    private static final String SWING_COMBO_OWNER = "com/live2d/ui/swingImpl/l";
    private static final String INSPECTOR_OWNER = "com/live2d/inspector/simpleInspectors/CInspector_CInspectableObject";
    private static final String INSPECTOR_LOCK_OWNER = "com/live2d/inspector/i";
    private static final String APP_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting";
    private static final String DEFORMER_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$Deformer";

    private static final String ROOT_DISPLAY_INDENT = "";
    private static final String INITIAL_TREE_INDENT = " ";
    private static final String TREE_INDENT_STEP = " ";
    private static final int PRECOMPUTED_INDENT_DEPTHS = 32;
    private static final String[] INDENT_BY_DEPTH = createIndentTable();

    private static final int INITIAL_CHILDREN_BUCKET_CAP = 2;
    private static final int HASH_INITIAL_BUCKET_CAP = 16;
    private static final float HASH_LOAD_FACTOR = 0.75f;
    private static final int INITIAL_FRAME_STACK_CAP = 16;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long BOOL_TRUE_MIX = 1231L;
    private static final long BOOL_FALSE_MIX = 1237L;

    private static final MethodType STATIC_TO_OBJ = MethodType.methodType(Object.class);
    private static final MethodType OBJ_TO_OBJ = MethodType.methodType(Object.class, Object.class);
    private static final MethodType OBJ_TO_BOOL = MethodType.methodType(boolean.class, Object.class);
    private static final MethodType OBJ_OBJ_TO_VOID = MethodType.methodType(void.class, Object.class, Object.class);
    private static final MethodType OBJ_BOOL_TO_VOID = MethodType.methodType(void.class, Object.class, boolean.class);
    private static final MethodType OBJ_STR_TO_OBJ = MethodType.methodType(Object.class, Object.class, String.class);

    private final ClassLoader loader;
    private final Object bindLock = new Object();
    private final Object cacheLock = new Object();
    private final List<FactoryCacheEntry> factoryCaches = new ArrayList<>();
    private volatile Handles bound;
    private volatile boolean bindAttempted;
    private volatile boolean closed;

    private DeformerSelectorAccelerator(final ClassLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /** Creates a lazily bound accelerator for {@code loader}, or {@code null} when null. */
    static DeformerSelectorAccelerator tryCreate(final ClassLoader loader) {
        return loader == null ? null : new DeformerSelectorAccelerator(loader);
    }

    /** Releases cached host references when the parent drag bridge closes. */
    @Override
    public void close() {
        closed = true;
        bound = null;
        synchronized (cacheLock) {
            factoryCaches.clear();
        }
    }

    boolean isBound() {
        return ensureBound() != null;
    }

    /**
     * Executes an accelerated {@code DeformerSelectorUiFactory.updateWidget()} pass on EDT-like callers
     * when {@code selectorFactory} matches Cubism inspector invariants.
     */
    UpdateOutcome tryUpdateWidget(final Object selectorFactory) {
        if (closed || selectorFactory == null) {
            return UpdateOutcome.FALLBACK;
        }
        final Handles handles = ensureBound();
        if (handles == null || !handles.factoryClass.isInstance(selectorFactory)) {
            return UpdateOutcome.FALLBACK;
        }
        try {
            final List<?> values = (List<?>) (Object) handles.factoryGetValues.invokeExact(selectorFactory);
            if (values == null) {
                return UpdateOutcome.FALLBACK;
            }
            Object firstWrap = null;
            Object firstModelSource = null;
            boolean sameModel = true;
            for (int i = 0, n = values.size(); i < n; i++) {
                final Object selectable = values.get(i);
                if (selectable == null) {
                    return UpdateOutcome.FALLBACK;
                }
                final Object wrap = (Object) handles.selectableGetWrap.invokeExact(selectable);
                if (wrap == null) {
                    continue;
                }
                final Object doc = (Object) handles.wrapGetDoc.invokeExact(wrap);
                if (doc == null) {
                    return UpdateOutcome.FALLBACK;
                }
                final Object source = (Object) handles.docGetModelSource.invokeExact(doc);
                if (firstWrap == null) {
                    firstWrap = wrap;
                    firstModelSource = source;
                } else if (!Objects.equals(firstModelSource, source)) {
                    sameModel = false;
                }
            }
            if (!sameModel) {
                // Defer assertion handling and mismatched selection behavior to unmodified host code.
                invalidateCache(selectorFactory);
                return UpdateOutcome.FALLBACK;
            }
            if (firstWrap == null) {
                return UpdateOutcome.SKIPPED;
            }

            final Object model = (Object) handles.wrapGetModel.invokeExact(firstWrap);
            final Object combo = (Object) handles.factoryGetCombo.invokeExact(selectorFactory);
            if (model == null || combo == null) {
                return UpdateOutcome.FALLBACK;
            }
            final List<?> allDeformers = (List<?>) (Object) handles.modelGetAllDeformers.invokeExact(model);
            final Object swingComboObj = (Object) handles.comboGetJComboBox.invokeExact(combo);
            if (allDeformers == null || !(swingComboObj instanceof JComboBox<?> jCombo)) {
                return UpdateOutcome.FALLBACK;
            }
            final boolean showPartName = readShowPartName(handles);
            final ComboBoxModel<?> installedModel = jCombo.getModel();

            final FactoryCacheEntry entry = getOrCreateEntry(selectorFactory);
            final UpdateOutcome outcome;
            final Map<Object, Object> itemByGuid;
            if (entry.matches(handles, firstModelSource, showPartName, installedModel, allDeformers)) {
                itemByGuid = entry.itemByGuid;
                outcome = UpdateOutcome.SKIPPED;
            } else {
                final Object rootGuid = resolveRootGuid(handles);
                itemByGuid = rebuildComboModel(
                        handles, entry, selectorFactory, firstModelSource, showPartName, combo, allDeformers, rootGuid);
                outcome = UpdateOutcome.REBUILT;
            }

            final Object selectedGuid = resolveSelectedGuid(handles, values, firstWrap);
            final Object targetItem = selectedGuid == null ? null : itemByGuid.get(selectedGuid);
            if (outcome == UpdateOutcome.REBUILT || jCombo.getSelectedItem() != targetItem) {
                handles.comboSetSelectedItem.invokeExact(combo, targetItem);
            }
            syncEnabledState(handles, selectorFactory, combo, jCombo);
            return outcome;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            invalidateCache(selectorFactory);
            return UpdateOutcome.FALLBACK;
        }
    }

    private Handles ensureBound() {
        if (closed) {
            return null;
        }
        final Handles existing = bound;
        if (bindAttempted) {
            return existing;
        }
        synchronized (bindLock) {
            if (closed) {
                return null;
            }
            if (bindAttempted) {
                return bound;
            }
            bindAttempted = true;
            try {
                // Lazy resolution avoids premature linkage probes in minimal verification loaders.
                bound = Handles.resolve(loader);
            } catch (final ReflectiveOperationException | LinkageError failure) {
                FatalErrors.rethrowIfFatal(failure);
                bound = null;
            }
            return bound;
        }
    }

    private Object resolveSelectedGuid(final Handles handles, final List<?> values, final Object firstWrap)
            throws Throwable {
        final Object firstGuid = readTargetDeformerGuid(handles, firstWrap);
        for (int i = 0, n = values.size(); i < n; i++) {
            final Object wrap = (Object) handles.selectableGetWrap.invokeExact(values.get(i));
            if (wrap == null) {
                continue;
            }
            final Object guid = readTargetDeformerGuid(handles, wrap);
            if (!Objects.equals(firstGuid, guid)) {
                return null;
            }
        }
        return firstGuid;
    }

    private Object readTargetDeformerGuid(final Handles handles, final Object wrap) throws Throwable {
        final Object obj = (Object) handles.wrapGetObj.invokeExact(wrap);
        if (obj == null) {
            return null;
        }
        final Object src = (Object) handles.objGetSource.invokeExact(obj);
        return src == null ? null : (Object) handles.sourceGetTargetDeformerGuid.invokeExact(src);
    }

    private Map<Object, Object> rebuildComboModel(
            final Handles handles,
            final FactoryCacheEntry entry,
            final Object selectorFactory,
            final Object modelSource,
            final boolean showPartName,
            final Object combo,
            final List<?> allDeformers,
            final Object rootGuid)
            throws Throwable {
        final int count = allDeformers.size();
        final Object[] guids = new Object[count];
        final Object[] targetGuids = new Object[count];
        final Object[] sources = new Object[count];
        final String[] localNames = new String[count];
        final String[] idStrings = new String[count];
        final Object[] parts = new Object[count];
        final String[] partLocalNames = new String[count];
        final String[] partIdStrings = new String[count];
        final Map<Object, List<Integer>> childrenByParent = new HashMap<>(hashCapacity(count));

        long fingerprint = FNV_OFFSET_BASIS;
        fingerprint = mixLong(fingerprint, showPartName ? BOOL_TRUE_MIX : BOOL_FALSE_MIX);
        fingerprint = mixLong(fingerprint, count);

        // Group deformers by parent GUID in one linear pass to avoid O(D^2) tree scanning.
        for (int i = 0; i < count; i++) {
            final Object deformer = allDeformers.get(i);
            final Object guid = (Object) handles.deformerGetGuid.invokeExact(deformer);
            final Object targetGuid = (Object) handles.deformerGetTargetDeformerGuid.invokeExact(deformer);
            final Object src = (Object) handles.deformerGetSource.invokeExact(deformer);
            final String localName = src == null ? null : (String) (Object) handles.sourceGetLocalName.invokeExact(src);
            final Object idObj = src == null ? null : (Object) handles.sourceGetId.invokeExact(src);
            final String idStr = idObj == null ? null : (String) (Object) handles.idGetIdString.invokeExact(idObj);
            final Object part = src == null ? null : (Object) handles.sourceGetParent.invokeExact(src);
            final String partName =
                    part == null ? null : (String) (Object) handles.sourceGetLocalName.invokeExact(part);
            final Object partIdObj = part == null ? null : (Object) handles.sourceGetId.invokeExact(part);
            final String partIdStr =
                    partIdObj == null ? null : (String) (Object) handles.idGetIdString.invokeExact(partIdObj);

            guids[i] = guid;
            targetGuids[i] = targetGuid;
            sources[i] = src;
            localNames[i] = localName;
            idStrings[i] = idStr;
            parts[i] = part;
            partLocalNames[i] = partName;
            partIdStrings[i] = partIdStr;

            fingerprint = mixObject(fingerprint, guid);
            fingerprint = mixObject(fingerprint, targetGuid);
            fingerprint = mixObject(fingerprint, localName);
            fingerprint = mixObject(fingerprint, idStr);
            fingerprint = mixObject(fingerprint, partName);
            fingerprint = mixObject(fingerprint, partIdStr);

            List<Integer> siblings = childrenByParent.get(targetGuid);
            if (siblings == null) {
                siblings = new ArrayList<>(INITIAL_CHILDREN_BUCKET_CAP);
                childrenByParent.put(targetGuid, siblings);
            }
            siblings.add(i);
        }

        final ArrayList<Object> items = new ArrayList<>(count + 1);
        final Map<Object, Object> itemByGuid = new HashMap<>(hashCapacity(count + 1));
        final Object rootItem = (Object) handles.comboItemCtor.invokeExact((Object) null, ROOT_DISPLAY_INDENT);
        items.add(rootItem);
        if (rootGuid != null) {
            itemByGuid.put(rootGuid, rootItem);
        }

        final List<Integer> rootChildren = childrenByParent.get(rootGuid);
        if (rootChildren != null && !rootChildren.isEmpty()) {
            // Iterative pre-order walk preserves Cubism's exact DFS ordering without stack overflow risk.
            final List<TraversalFrame> stack = new ArrayList<>(INITIAL_FRAME_STACK_CAP);
            final Set<Object> activePath = new HashSet<>(INITIAL_FRAME_STACK_CAP);
            if (rootGuid != null) {
                activePath.add(rootGuid);
            }
            stack.add(new TraversalFrame(rootChildren, 1, INITIAL_TREE_INDENT, rootGuid));
            while (!stack.isEmpty()) {
                final TraversalFrame top = stack.get(stack.size() - 1);
                if (top.cursor >= top.indices.size()) {
                    stack.remove(stack.size() - 1);
                    if (top.ownedGuid != null) {
                        activePath.remove(top.ownedGuid);
                    }
                    continue;
                }
                final int idx = top.indices.get(top.cursor++);
                final Object src = sources[idx];
                final Object guid = guids[idx];
                final Object item = (Object) handles.comboItemCtor.invokeExact(src, top.indent);
                items.add(item);
                itemByGuid.putIfAbsent(guid, item);

                final List<Integer> children = childrenByParent.get(guid);
                if (children != null && !children.isEmpty() && (guid == null || activePath.add(guid))) {
                    final int nextDepth = top.depth + 1;
                    stack.add(new TraversalFrame(children, nextDepth, indentForDepth(nextDepth), guid));
                }
            }
        }

        final Object comboModel = (Object) handles.comboModelCtor.invokeExact((Object) items);
        handles.comboSetModel.invokeExact(combo, comboModel);

        entry.update(
                selectorFactory,
                modelSource,
                showPartName,
                comboModel,
                fingerprint,
                count,
                guids,
                targetGuids,
                sources,
                localNames,
                idStrings,
                parts,
                partLocalNames,
                partIdStrings,
                itemByGuid);
        return itemByGuid;
    }

    private void syncEnabledState(
            final Handles handles, final Object selectorFactory, final Object combo, final JComboBox<?> jCombo)
            throws Throwable {
        if (handles.inspectorGetFormAnimationLock == null
                || handles.inspectorLockInstanceGetter == null
                || handles.inspectorLockActiveAccessor == null) {
            return;
        }
        final Object inspector = (Object) handles.factoryGetInspector.invokeExact(selectorFactory);
        if (inspector == null) {
            return;
        }
        final boolean baseEditable = (boolean) handles.inspectorIsEnabled.invokeExact(inspector)
                && (boolean) handles.inspectorIsEditable.invokeExact(inspector);
        final boolean formAnimLocked;
        if ((boolean) handles.inspectorGetFormAnimationLock.invokeExact(inspector)) {
            final Object lockSingleton = (Object) handles.inspectorLockInstanceGetter.invokeExact();
            formAnimLocked =
                    lockSingleton != null && (boolean) handles.inspectorLockActiveAccessor.invokeExact(lockSingleton);
        } else {
            formAnimLocked = false;
        }
        final boolean nextEnabled = baseEditable && !formAnimLocked;
        if (jCombo.isEnabled() != nextEnabled) {
            handles.comboSetEnabled.invokeExact(combo, nextEnabled);
        }
    }

    private boolean readShowPartName(final Handles handles) throws Throwable {
        if (handles.appSettingInstanceGetter == null
                || handles.appSettingGetDeformer == null
                || handles.deformerSettingShowPartName == null) {
            return false;
        }
        final Object settings = (Object) handles.appSettingInstanceGetter.invokeExact();
        if (settings == null) {
            return false;
        }
        final Object deformerSetting = (Object) handles.appSettingGetDeformer.invokeExact(settings);
        return deformerSetting != null && (boolean) handles.deformerSettingShowPartName.invokeExact(deformerSetting);
    }

    private Object resolveRootGuid(final Handles handles) throws Throwable {
        final Object companion = (Object) handles.guidCompanionField.invokeExact();
        return companion == null ? null : (Object) handles.guidRootAccessor.invokeExact(companion);
    }

    private FactoryCacheEntry getOrCreateEntry(final Object selectorFactory) {
        synchronized (cacheLock) {
            FactoryCacheEntry found = null;
            final Iterator<FactoryCacheEntry> it = factoryCaches.iterator();
            while (it.hasNext()) {
                final FactoryCacheEntry candidate = it.next();
                final Object referent = candidate.factoryRef.get();
                if (referent == null) {
                    it.remove();
                } else if (referent == selectorFactory) {
                    found = candidate;
                }
            }
            if (found == null) {
                found = new FactoryCacheEntry(selectorFactory);
                factoryCaches.add(found);
            }
            return found;
        }
    }

    private void invalidateCache(final Object selectorFactory) {
        synchronized (cacheLock) {
            final Iterator<FactoryCacheEntry> it = factoryCaches.iterator();
            while (it.hasNext()) {
                final FactoryCacheEntry candidate = it.next();
                final Object referent = candidate.factoryRef.get();
                if (referent == null || referent == selectorFactory) {
                    it.remove();
                }
            }
        }
    }

    private static int hashCapacity(final int expectedSize) {
        return Math.max(HASH_INITIAL_BUCKET_CAP, (int) (expectedSize / HASH_LOAD_FACTOR) + 1);
    }

    private static String indentForDepth(final int depth) {
        if (depth < INDENT_BY_DEPTH.length) {
            return INDENT_BY_DEPTH[depth];
        }
        return TREE_INDENT_STEP.repeat(depth);
    }

    private static String[] createIndentTable() {
        final String[] table = new String[PRECOMPUTED_INDENT_DEPTHS];
        table[0] = ROOT_DISPLAY_INDENT;
        for (int i = 1; i < table.length; i++) {
            table[i] = table[i - 1] + TREE_INDENT_STEP;
        }
        return table;
    }

    private static long mixLong(final long hash, final long value) {
        return (hash ^ value) * FNV_PRIME;
    }

    private static long mixObject(final long hash, final Object value) {
        return mixLong(hash, value == null ? 0L : Integer.toUnsignedLong(value.hashCode()));
    }

    private static final class TraversalFrame {
        private final List<Integer> indices;
        private final int depth;
        private final String indent;
        private final Object ownedGuid;
        private int cursor;

        private TraversalFrame(
                final List<Integer> indices, final int depth, final String indent, final Object ownedGuid) {
            this.indices = indices;
            this.depth = depth;
            this.indent = indent;
            this.ownedGuid = ownedGuid;
        }
    }

    private record Handles(
            Class<?> factoryClass,
            Class<?> comboModelClass,
            MethodHandle factoryGetValues,
            MethodHandle factoryGetInspector,
            MethodHandle factoryGetCombo,
            MethodHandle selectableGetWrap,
            MethodHandle wrapGetDoc,
            MethodHandle wrapGetModel,
            MethodHandle wrapGetObj,
            MethodHandle docGetModelSource,
            MethodHandle modelGetAllDeformers,
            MethodHandle objGetSource,
            MethodHandle sourceGetTargetDeformerGuid,
            MethodHandle sourceGetLocalName,
            MethodHandle sourceGetId,
            MethodHandle sourceGetParent,
            MethodHandle idGetIdString,
            MethodHandle deformerGetGuid,
            MethodHandle deformerGetTargetDeformerGuid,
            MethodHandle deformerGetSource,
            MethodHandle comboGetJComboBox,
            MethodHandle comboSetModel,
            MethodHandle comboSetSelectedItem,
            MethodHandle comboSetEnabled,
            MethodHandle comboModelCtor,
            MethodHandle comboItemCtor,
            MethodHandle guidCompanionField,
            MethodHandle guidRootAccessor,
            MethodHandle inspectorIsEnabled,
            MethodHandle inspectorIsEditable,
            MethodHandle appSettingInstanceGetter,
            MethodHandle appSettingGetDeformer,
            MethodHandle deformerSettingShowPartName,
            MethodHandle inspectorGetFormAnimationLock,
            MethodHandle inspectorLockInstanceGetter,
            MethodHandle inspectorLockActiveAccessor) {

        private static Handles resolve(final ClassLoader loader) throws ReflectiveOperationException {
            final Class<?> factoryCls = HostHandles.load(loader, FACTORY_OWNER);
            final Class<?> comboItemCls = HostHandles.load(loader, COMBO_ITEM_OWNER);
            final Class<?> selectableCls = HostHandles.load(loader, SELECTABLE_OWNER);
            final Class<?> wrapCls = HostHandles.load(loader, WRAP_OWNER);
            final Class<?> docCls = HostHandles.load(loader, DOC_OWNER);
            final Class<?> modelCls = HostHandles.load(loader, MODEL_OWNER);
            final Class<?> deformerCls = HostHandles.load(loader, DEFORMER_OWNER);
            final Class<?> controllableCls = HostHandles.load(loader, CONTROLLABLE_OWNER);
            final Class<?> controllableSrcCls = HostHandles.load(loader, CONTROLLABLE_SOURCE_OWNER);
            final Class<?> idCls = HostHandles.load(loader, ID_OWNER);
            final Class<?> guidCls = HostHandles.load(loader, DEFORMER_GUID_OWNER);
            final Class<?> guidCompCls = HostHandles.load(loader, DEFORMER_GUID_COMPANION_OWNER);
            final Class<?> comboCls = HostHandles.load(loader, COMBO_OWNER);
            final Class<?> comboModelCls = HostHandles.load(loader, COMBO_MODEL_OWNER);
            final Class<?> inspectorCls = HostHandles.load(loader, INSPECTOR_OWNER);

            final MethodHandle factoryGetValues =
                    HostHandles.virtual(factoryCls, "getValues", "()Ljava/util/List;", OBJ_TO_OBJ);
            final MethodHandle factoryGetInspector =
                    HostHandles.virtual(factoryCls, "getInspector", "()L" + INSPECTOR_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle factoryGetCombo =
                    HostHandles.virtual(factoryCls, "getCombo", "()L" + COMBO_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle selectableGetWrap =
                    HostHandles.virtual(selectableCls, "getWrap", "()L" + WRAP_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle wrapGetDoc = HostHandles.virtual(wrapCls, "getDoc", "()L" + DOC_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle wrapGetModel =
                    HostHandles.virtual(wrapCls, "getModel", "()L" + MODEL_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle wrapGetObj =
                    HostHandles.virtual(wrapCls, "getObj", "()L" + CONTROLLABLE_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle docGetModelSource =
                    HostHandles.virtual(docCls, "getModelSource", "()L" + MODEL_SOURCE_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle modelGetAllDeformers =
                    HostHandles.virtual(modelCls, "getAllDeformers", "()Ljava/util/List;", OBJ_TO_OBJ);
            final MethodHandle objGetSource = HostHandles.virtual(
                    controllableCls, "getSource", "()L" + CONTROLLABLE_SOURCE_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle sourceGetTargetDeformerGuid = HostHandles.virtual(
                    controllableSrcCls, "getTargetDeformerGuid", "()L" + DEFORMER_GUID_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle sourceGetLocalName =
                    HostHandles.virtual(controllableSrcCls, "getLocalName", "()Ljava/lang/String;", OBJ_TO_OBJ);
            final MethodHandle sourceGetId = HostHandles.virtual(
                    controllableSrcCls,
                    "getId",
                    "()Lcom/live2d/cubism/doc/model/id/CParameterControllableId;",
                    OBJ_TO_OBJ);
            final MethodHandle sourceGetParent =
                    HostHandles.virtual(controllableSrcCls, "getParent", "()L" + PART_SOURCE_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle idGetIdString =
                    HostHandles.virtual(idCls, "getIdString", "()Ljava/lang/String;", OBJ_TO_OBJ);
            final MethodHandle deformerGetGuid =
                    HostHandles.virtual(deformerCls, "getGuid", "()L" + DEFORMER_GUID_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle deformerGetTargetDeformerGuid = HostHandles.virtual(
                    deformerCls, "getTargetDeformerGuid", "()L" + DEFORMER_GUID_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle deformerGetSource =
                    HostHandles.virtual(deformerCls, "getSource", "()L" + DEFORMER_SOURCE_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle comboGetJComboBox =
                    HostHandles.virtual(comboCls, "getJComboBox", "()L" + SWING_COMBO_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle comboSetModel =
                    HostHandles.virtual(comboCls, "setModel", "(L" + COMBO_MODEL_OWNER + ";)V", OBJ_OBJ_TO_VOID);
            final MethodHandle comboSetSelectedItem =
                    HostHandles.virtual(comboCls, "setSelectedItem", "(Ljava/lang/Object;)V", OBJ_OBJ_TO_VOID);
            final MethodHandle comboSetEnabled = HostHandles.virtual(comboCls, "setEnabled", "(Z)V", OBJ_BOOL_TO_VOID);
            final MethodHandle comboModelCtor = HostHandles.construct(comboModelCls, "(Ljava/util/List;)V", OBJ_TO_OBJ);
            final MethodHandle comboItemCtor = HostHandles.construct(
                    comboItemCls, "(L" + DEFORMER_SOURCE_OWNER + ";Ljava/lang/String;)V", OBJ_STR_TO_OBJ);
            final MethodHandle guidCompanionField =
                    HostHandles.getter(guidCls, "Companion", "L" + DEFORMER_GUID_COMPANION_OWNER + ";", STATIC_TO_OBJ);
            final MethodHandle guidRootAccessor =
                    HostHandles.virtual(guidCompCls, "a", "()L" + DEFORMER_GUID_OWNER + ";", OBJ_TO_OBJ);
            final MethodHandle inspectorIsEnabled = HostHandles.virtual(inspectorCls, "isEnabled", "()Z", OBJ_TO_BOOL);
            final MethodHandle inspectorIsEditable =
                    HostHandles.virtual(inspectorCls, "isEditable", "()Z", OBJ_TO_BOOL);

            MethodHandle appSettingInstanceGetter = null;
            MethodHandle appSettingGetDeformer = null;
            MethodHandle deformerSettingShowPartName = null;
            try {
                final Class<?> appSettingCls = HostHandles.load(loader, APP_SETTING_OWNER);
                final Class<?> deformerSettingCls = HostHandles.load(loader, DEFORMER_SETTING_OWNER);
                appSettingInstanceGetter =
                        HostHandles.getter(appSettingCls, "INSTANCE", "L" + APP_SETTING_OWNER + ";", STATIC_TO_OBJ);
                appSettingGetDeformer = HostHandles.virtual(
                        appSettingCls, "getDeformer", "()L" + DEFORMER_SETTING_OWNER + ";", OBJ_TO_OBJ);
                deformerSettingShowPartName =
                        HostHandles.virtual(deformerSettingCls, "getShowPartNameOfDeformer", "()Z", OBJ_TO_BOOL);
            } catch (final ReflectiveOperationException | LinkageError failure) {
                FatalErrors.rethrowIfFatal(failure);
                appSettingInstanceGetter = null;
                appSettingGetDeformer = null;
                deformerSettingShowPartName = null;
            }

            MethodHandle inspectorGetFormAnimationLock = null;
            MethodHandle inspectorLockInstanceGetter = null;
            MethodHandle inspectorLockActiveAccessor = null;
            try {
                final Class<?> lockCls = HostHandles.load(loader, INSPECTOR_LOCK_OWNER);
                inspectorGetFormAnimationLock =
                        HostHandles.virtual(inspectorCls, "getFormAnimationLock", "()Z", OBJ_TO_BOOL);
                inspectorLockInstanceGetter =
                        HostHandles.getter(lockCls, "a", "L" + INSPECTOR_LOCK_OWNER + ";", STATIC_TO_OBJ);
                inspectorLockActiveAccessor = HostHandles.virtual(lockCls, "b", "()Z", OBJ_TO_BOOL);
            } catch (final ReflectiveOperationException | LinkageError failure) {
                FatalErrors.rethrowIfFatal(failure);
                inspectorGetFormAnimationLock = null;
                inspectorLockInstanceGetter = null;
                inspectorLockActiveAccessor = null;
            }

            return new Handles(
                    factoryCls,
                    comboModelCls,
                    factoryGetValues,
                    factoryGetInspector,
                    factoryGetCombo,
                    selectableGetWrap,
                    wrapGetDoc,
                    wrapGetModel,
                    wrapGetObj,
                    docGetModelSource,
                    modelGetAllDeformers,
                    objGetSource,
                    sourceGetTargetDeformerGuid,
                    sourceGetLocalName,
                    sourceGetId,
                    sourceGetParent,
                    idGetIdString,
                    deformerGetGuid,
                    deformerGetTargetDeformerGuid,
                    deformerGetSource,
                    comboGetJComboBox,
                    comboSetModel,
                    comboSetSelectedItem,
                    comboSetEnabled,
                    comboModelCtor,
                    comboItemCtor,
                    guidCompanionField,
                    guidRootAccessor,
                    inspectorIsEnabled,
                    inspectorIsEditable,
                    appSettingInstanceGetter,
                    appSettingGetDeformer,
                    deformerSettingShowPartName,
                    inspectorGetFormAnimationLock,
                    inspectorLockInstanceGetter,
                    inspectorLockActiveAccessor);
        }
    }

    private static final class FactoryCacheEntry {
        private final WeakReference<Object> factoryRef;
        private WeakReference<Object> modelSourceRef;
        private WeakReference<Object> comboModelRef;
        private boolean showPartName;
        private long fingerprint;
        private int deformerCount;
        private Object[] guids;
        private Object[] targetGuids;
        private WeakReference<Object>[] sourceRefs;
        private String[] localNames;
        private String[] idStrings;
        private WeakReference<Object>[] partRefs;
        private String[] partLocalNames;
        private String[] partIdStrings;
        private Map<Object, Object> itemByGuid;

        private FactoryCacheEntry(final Object selectorFactory) {
            this.factoryRef = new WeakReference<>(selectorFactory);
        }

        private boolean matches(
                final Handles handles,
                final Object modelSource,
                final boolean currentShowPartName,
                final ComboBoxModel<?> installedModel,
                final List<?> allDeformers)
                throws Throwable {
            if (comboModelRef == null
                    || installedModel == null
                    || !handles.comboModelClass.isInstance(installedModel)
                    || comboModelRef.get() != installedModel
                    || modelSourceRef == null
                    || modelSourceRef.get() != modelSource
                    || showPartName != currentShowPartName
                    || allDeformers.size() != deformerCount) {
                return false;
            }
            long curFingerprint = FNV_OFFSET_BASIS;
            curFingerprint = mixLong(curFingerprint, currentShowPartName ? BOOL_TRUE_MIX : BOOL_FALSE_MIX);
            curFingerprint = mixLong(curFingerprint, deformerCount);
            for (int i = 0; i < deformerCount; i++) {
                final Object deformer = allDeformers.get(i);
                final Object guid = (Object) handles.deformerGetGuid.invokeExact(deformer);
                final Object targetGuid = (Object) handles.deformerGetTargetDeformerGuid.invokeExact(deformer);
                final Object src = (Object) handles.deformerGetSource.invokeExact(deformer);
                if (!Objects.equals(guid, guids[i])
                        || !Objects.equals(targetGuid, targetGuids[i])
                        || sourceRefs[i].get() != src) {
                    return false;
                }
                final String localName =
                        src == null ? null : (String) (Object) handles.sourceGetLocalName.invokeExact(src);
                final Object idObj = src == null ? null : (Object) handles.sourceGetId.invokeExact(src);
                final String idStr = idObj == null ? null : (String) (Object) handles.idGetIdString.invokeExact(idObj);
                if (!Objects.equals(localName, localNames[i]) || !Objects.equals(idStr, idStrings[i])) {
                    return false;
                }
                final Object part = src == null ? null : (Object) handles.sourceGetParent.invokeExact(src);
                final WeakReference<Object> prevPartRef = partRefs[i];
                if (prevPartRef == null ? part != null : prevPartRef.get() != part) {
                    return false;
                }
                final String partName =
                        part == null ? null : (String) (Object) handles.sourceGetLocalName.invokeExact(part);
                final Object partIdObj = part == null ? null : (Object) handles.sourceGetId.invokeExact(part);
                final String partIdStr =
                        partIdObj == null ? null : (String) (Object) handles.idGetIdString.invokeExact(partIdObj);
                if (!Objects.equals(partName, partLocalNames[i]) || !Objects.equals(partIdStr, partIdStrings[i])) {
                    return false;
                }
                curFingerprint = mixObject(curFingerprint, guid);
                curFingerprint = mixObject(curFingerprint, targetGuid);
                curFingerprint = mixObject(curFingerprint, localName);
                curFingerprint = mixObject(curFingerprint, idStr);
                curFingerprint = mixObject(curFingerprint, partName);
                curFingerprint = mixObject(curFingerprint, partIdStr);
            }
            return curFingerprint == fingerprint;
        }

        @SuppressWarnings("unchecked")
        private void update(
                final Object selectorFactory,
                final Object modelSource,
                final boolean currentShowPartName,
                final Object comboModel,
                final long nextFingerprint,
                final int count,
                final Object[] nextGuids,
                final Object[] nextTargetGuids,
                final Object[] nextSources,
                final String[] nextLocalNames,
                final String[] nextIdStrings,
                final Object[] nextParts,
                final String[] nextPartLocalNames,
                final String[] nextPartIdStrings,
                final Map<Object, Object> nextItemByGuid) {
            if (factoryRef.get() != selectorFactory) {
                return;
            }
            this.modelSourceRef = new WeakReference<>(modelSource);
            this.comboModelRef = new WeakReference<>(comboModel);
            this.showPartName = currentShowPartName;
            this.fingerprint = nextFingerprint;
            this.deformerCount = count;
            this.guids = nextGuids;
            this.targetGuids = nextTargetGuids;
            this.sourceRefs = (WeakReference<Object>[]) new WeakReference<?>[count];
            this.partRefs = (WeakReference<Object>[]) new WeakReference<?>[count];
            for (int i = 0; i < count; i++) {
                this.sourceRefs[i] = new WeakReference<>(nextSources[i]);
                this.partRefs[i] = nextParts[i] == null ? null : new WeakReference<>(nextParts[i]);
            }
            this.localNames = nextLocalNames;
            this.idStrings = nextIdStrings;
            this.partLocalNames = nextPartLocalNames;
            this.partIdStrings = nextPartIdStrings;
            this.itemByGuid = nextItemByGuid;
        }
    }
}
