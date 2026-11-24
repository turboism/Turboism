package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.event.MouseListener;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.swing.JTable;

/**
 * Eliminates {@code CPartsTreeTable} / {@code util.KeyListener} retention leaks and skips
 * redundant Parts palette rebuilds when model structure, metadata, filters, and search text match.
 */
final class PartsPaletteHelper {

    private static final String MODEL_DOC_CLASS = "com/live2d/cubism/doc/modeling/CModelingDocument";
    private static final String CMODEL_CLASS = "com/live2d/cubism/doc/model/CModel";
    private static final String MODEL_SOURCE_CLASS = "com/live2d/cubism/doc/model/CModelSource";
    private static final String CONTROLLABLE_CLASS = "com/live2d/cubism/doc/model/ACParameterControllableSource";
    private static final String PART_SOURCE_CLASS = "com/live2d/cubism/doc/model/parts/CPartSource";
    private static final String ART_MESH_CLASS = "com/live2d/cubism/doc/model/drawable/artMesh/CArtMeshSource";
    private static final String DRAWABLE_CLASS = "com/live2d/cubism/doc/model/drawable/ACDrawable";
    private static final String VIEW_CONTEXT_CLASS = "com/live2d/cubism/view/context/CEViewContext";
    private static final String COMPLETE_PACK_CLASS = "com/live2d/cubism/pack/CECompletePack";
    private static final String UPDATE_MANAGER_CLASS = "com/live2d/cubism/CEUpdateManager";
    private static final String REPAINT_TYPE_CLASS = "com/live2d/cubism/CEUpdateManager$b";
    private static final String TEXT_FIELD_CLASS = "com/live2d/ui/control/CTextField";
    private static final String TWO_STATE_BTN_CLASS = "com/live2d/ui/control/CTwoStateIconButton";
    private static final String CHECK_BOX_CLASS = "com/live2d/ui/control/CCheckBox";
    private static final String PARTS_INTERACTION_CLASS = "com/live2d/cubism/view/palette/parts/T";

    private final WeakIdentityMap<Object, TreeTableSupport.CachedPanelState> cachedStates = new WeakIdentityMap<>();
    private final Listener statsListener;
    private final TreeTableSupport.Detacher detacher;

    // parts.b accessors
    private final HostHandles.HostField partsCompletePackField;
    private final HostHandles.HostField partsTreeTableField;
    private final HostHandles.HostField partsInteractionField;
    private final HostHandles.HostField partsInitFlagField;
    private final List<HostHandles.HostField> partsBoolFilterFields;
    private final List<HostHandles.HostField> partsTextFields;
    private final List<HostHandles.HostField> partsTwoStateButtons;
    private final List<HostHandles.HostField> partsCheckBoxes;
    private final MethodHandle partsGetDoc;
    private final MethodHandle partsGetModel;
    private final MethodHandle partsGetViewContext;
    private final MethodHandle partsGetTopParts;
    private final MethodHandle interactionSync;
    private final List<HostHandles.HostField> interactionClearFields;

    // Document / Model / Node / Update accessors
    private final MethodHandle packGetUpdateManager;
    private final MethodHandle packRepaintCanvas;
    private final Object repaintCanvasPartsFlag;
    private final MethodHandle updateMgrSelectionGuids;
    private final MethodHandle updateMgrExpandParts;
    private final MethodHandle modelGetDrawable;
    private final MethodHandle controllableGetId;
    private final MethodHandle idGetString;
    private final MethodHandle controllableGetName;
    private final MethodHandle partGetChildren;
    private final MethodHandle artMeshGetAlphaComp;
    private final MethodHandle artMeshGetColorComp;
    private final MethodHandle artMeshGetClipGuids;
    private final MethodHandle artMeshGetInvertMask;
    private final MethodHandle artMeshGetGuid;
    private final MethodHandle drawableIsClipper;
    private final MethodHandle textFieldGetText;
    private final MethodHandle twoStateGetState;
    private final MethodHandle checkBoxIsSelected;

    interface Listener {
        void onTreeDetached();

        void onTreeReused();
    }

    PartsPaletteHelper(
            final ClassLoader loader,
            final MemoryLeakMitigationTarget target,
            final TreeTableSupport.Detacher detacher,
            final Listener listener)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");
        this.detacher = Objects.requireNonNull(detacher, "detacher");
        this.statsListener = Objects.requireNonNull(listener, "listener");

        final Class<?> partsPanelClass = HostHandles.load(loader, MemoryLeakMitigationTarget.PARTS_PANEL_OWNER);
        final Class<?> completePackClass = HostHandles.load(loader, COMPLETE_PACK_CLASS);
        final Class<?> modelingDocClass = HostHandles.load(loader, MODEL_DOC_CLASS);
        final Class<?> cModelClass = HostHandles.load(loader, CMODEL_CLASS);
        final Class<?> viewContextClass = HostHandles.load(loader, VIEW_CONTEXT_CLASS);
        final Class<?> textFieldClass = HostHandles.load(loader, TEXT_FIELD_CLASS);
        final Class<?> twoStateClass = HostHandles.load(loader, TWO_STATE_BTN_CLASS);
        final Class<?> checkBoxClass = HostHandles.load(loader, CHECK_BOX_CLASS);

        partsCompletePackField = TreeTableSupport.findUniqueFieldByAssignableType(partsPanelClass, completePackClass);
        partsTreeTableField = HostHandles.field(partsPanelClass, MemoryLeakMitigationTarget.TREE_TABLE_FIELD);
        partsInteractionField =
                findOptionalFieldByPackagePrefix(partsPanelClass, PARTS_INTERACTION_CLASS, MouseListener.class);
        final String initFlagName = TreeTableSupport.resolveLastMutableBooleanFieldName(partsPanelClass);
        partsInitFlagField = initFlagName == null ? null : HostHandles.field(partsPanelClass, initFlagName);

        final List<HostHandles.HostField> bools = new ArrayList<>();
        final List<HostHandles.HostField> texts = new ArrayList<>();
        final List<HostHandles.HostField> btns = new ArrayList<>();
        final List<HostHandles.HostField> cbs = new ArrayList<>();
        for (final Field f : partsPanelClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType() == boolean.class) {
                if (!f.getName().equals(initFlagName)) {
                    bools.add(HostHandles.field(partsPanelClass, f.getName()));
                }
            } else if (textFieldClass.isAssignableFrom(f.getType())) {
                texts.add(HostHandles.field(partsPanelClass, f.getName()));
            } else if (twoStateClass.isAssignableFrom(f.getType())) {
                btns.add(HostHandles.field(partsPanelClass, f.getName()));
            } else if (checkBoxClass.isAssignableFrom(f.getType())) {
                cbs.add(HostHandles.field(partsPanelClass, f.getName()));
            }
        }
        partsBoolFilterFields = List.copyOf(bools);
        partsTextFields = List.copyOf(texts);
        partsTwoStateButtons = List.copyOf(btns);
        partsCheckBoxes = List.copyOf(cbs);

        partsGetDoc = HostHandles.unreflect(
                        TreeTableSupport.findSingleMethodByReturn(partsPanelClass, modelingDocClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        partsGetModel = HostHandles.unreflect(TreeTableSupport.findSingleMethodByReturn(partsPanelClass, cModelClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        partsGetViewContext = HostHandles.unreflect(
                        TreeTableSupport.findSingleMethodByReturn(partsPanelClass, viewContextClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        partsGetTopParts = HostHandles.unreflect(TreeTableSupport.findNoArgListMethod(partsPanelClass))
                .asType(MethodType.methodType(List.class, Object.class));

        if (partsInteractionField != null) {
            final Class<?> interactionClass = partsInteractionField.type();
            interactionSync =
                    HostHandles.virtual(interactionClass, "f", "()V", MethodType.methodType(void.class, Object.class));
            final List<HostHandles.HostField> icFields = new ArrayList<>();
            for (final Field f : interactionClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (Modifier.isFinal(f.getModifiers())) {
                    continue;
                }
                if (!f.getType().isPrimitive()) {
                    icFields.add(HostHandles.field(interactionClass, f.getName()));
                }
            }
            interactionClearFields = List.copyOf(icFields);
        } else {
            interactionSync = null;
            interactionClearFields = List.of();
        }

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
            repaintCanvasPartsFlag = HostHandles.field(repaintEnumClass, "b").get(null);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException("Cannot read CEUpdateManager$b.b", failure);
        }
        updateMgrSelectionGuids = HostHandles.virtual(
                updateMgrClass,
                "getSelectionGuidList",
                "()Ljava/util/List;",
                MethodType.methodType(List.class, Object.class));
        updateMgrExpandParts = HostHandles.virtual(
                updateMgrClass,
                "expandByObjectGuidPartsPalette",
                "(Ljava/util/List;)V",
                MethodType.methodType(void.class, Object.class, List.class));

        HostHandles.load(loader, MODEL_SOURCE_CLASS);
        final Class<?> controllableClass = HostHandles.load(loader, CONTROLLABLE_CLASS);
        final Class<?> partSourceClass = HostHandles.load(loader, PART_SOURCE_CLASS);
        final Class<?> artMeshClass = HostHandles.load(loader, ART_MESH_CLASS);
        final Class<?> drawableClass = HostHandles.load(loader, DRAWABLE_CLASS);
        final Method getIdMethod = TreeTableSupport.findNoArgMethod(controllableClass, "getId");
        controllableGetId =
                HostHandles.unreflect(getIdMethod).asType(MethodType.methodType(Object.class, Object.class));
        idGetString = HostHandles.virtual(
                getIdMethod.getReturnType(),
                "getIdString",
                "()Ljava/lang/String;",
                MethodType.methodType(String.class, Object.class));
        controllableGetName = HostHandles.virtual(
                controllableClass,
                "getLocalName",
                "()Ljava/lang/String;",
                MethodType.methodType(String.class, Object.class));
        partGetChildren = HostHandles.virtual(
                partSourceClass, "getChildren", "()Ljava/util/List;", MethodType.methodType(List.class, Object.class));

        final Method alphaMethod = TreeTableSupport.findOptionalNoArgMethod(artMeshClass, "getAlphaComposition");
        artMeshGetAlphaComp = alphaMethod == null
                ? null
                : HostHandles.unreflect(alphaMethod).asType(MethodType.methodType(Object.class, Object.class));
        final Method colorMethod = TreeTableSupport.findOptionalNoArgMethod(artMeshClass, "getColorComposition");
        artMeshGetColorComp = colorMethod == null
                ? null
                : HostHandles.unreflect(colorMethod).asType(MethodType.methodType(Object.class, Object.class));
        final Method clipListMethod = TreeTableSupport.findNoArgMethod(artMeshClass, "getClipGuidList");
        artMeshGetClipGuids =
                HostHandles.unreflect(clipListMethod).asType(MethodType.methodType(Collection.class, Object.class));
        artMeshGetInvertMask = HostHandles.virtual(
                artMeshClass, "getInvertClippingMask", "()Z", MethodType.methodType(boolean.class, Object.class));
        final Method artGuidMethod = TreeTableSupport.findNoArgMethod(artMeshClass, "getGuid");
        artMeshGetGuid = HostHandles.unreflect(artGuidMethod).asType(MethodType.methodType(Object.class, Object.class));
        modelGetDrawable = HostHandles.virtual(
                cModelClass,
                "getDrawable",
                "(L" + artGuidMethod.getReturnType().getName().replace('.', '/') + ";)L" + DRAWABLE_CLASS + ";",
                MethodType.methodType(Object.class, Object.class, Object.class));
        drawableIsClipper = HostHandles.virtual(
                drawableClass, "isClipper", "()Z", MethodType.methodType(boolean.class, Object.class));

        textFieldGetText = HostHandles.virtual(
                textFieldClass, "getText", "()Ljava/lang/String;", MethodType.methodType(String.class, Object.class));
        final Method stateMethod = TreeTableSupport.findNoArgMethod(twoStateClass, "getState");
        twoStateGetState = HostHandles.unreflect(stateMethod).asType(MethodType.methodType(Object.class, Object.class));
        checkBoxIsSelected = HostHandles.virtual(
                checkBoxClass, "isSelected", "()Z", MethodType.methodType(boolean.class, Object.class));
    }

    /** Detaches an obsolete or closing {@code CPartsTreeTable} from global AWT and Swing roots. */
    void detachTreeTable(final Object treeTable) {
        if (detacher.detachTreeTable(treeTable)) {
            statsListener.onTreeDetached();
        }
    }

    /** Detaches replaced tree tables inside {@code parts.b.a(CTreeTable)} before storing {@code nextTree}. */
    void onBeforeSetTreeTable(final Object partsPanel, final Object nextTree) {
        if (partsPanel == null) {
            return;
        }
        try {
            final Object previous = partsTreeTableField.get(partsPanel);
            if (previous != null && previous != nextTree) {
                detachTreeTable(previous);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Detaches active tree tables, resets init state, and clears interaction node lists on panel close ({@code y()} / {@code w()}). */
    void onPartsPanelClosed(final Object partsPanel) {
        if (partsPanel == null) {
            return;
        }
        cachedStates.remove(partsPanel);
        try {
            final Object current = partsTreeTableField.get(partsPanel);
            if (current != null) {
                detachTreeTable(current);
                if (partsInitFlagField != null) {
                    // Reset the Kotlin init flag before clearing the lateinit tree field so the next rebuild skips
                    // x()/k().
                    partsInitFlagField.set(partsPanel, Boolean.FALSE);
                    partsTreeTableField.set(partsPanel, null);
                }
            } else if (partsInitFlagField != null) {
                partsInitFlagField.set(partsPanel, Boolean.FALSE);
            }
            if (partsInteractionField != null) {
                final Object interaction = partsInteractionField.get(partsPanel);
                if (interaction != null) {
                    for (final HostHandles.HostField f : interactionClearFields) {
                        final Object val = f.get(interaction);
                        if (val instanceof Collection<?> col) {
                            col.clear();
                        } else {
                            f.set(interaction, null);
                        }
                    }
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Checks whether the existing {@code CPartsTreeTable} matches current document, model, filter,
     * and search state; if so, refreshes selection/highlight and returns {@code true} to skip rebuild.
     */
    boolean tryReusePartsPalette(
            final Object partsPanel, final boolean skipSelectionSync, final boolean expandToSelection) {
        if (partsPanel == null) {
            return false;
        }
        try {
            final Object doc = (Object) partsGetDoc.invokeExact(partsPanel);
            if (doc == null) {
                return false;
            }
            final Object currentTree = partsTreeTableField.get(partsPanel);
            final boolean initialized =
                    partsInitFlagField != null && Boolean.TRUE.equals(partsInitFlagField.get(partsPanel));
            final long fingerprint = computeFingerprint(partsPanel, doc);

            if (currentTree == null || !initialized) {
                cachedStates.put(partsPanel, new TreeTableSupport.CachedPanelState(doc, fingerprint));
                return false;
            }

            final TreeTableSupport.CachedPanelState prev = cachedStates.get(partsPanel);
            if (prev == null || prev.docRef.get() != doc || prev.fingerprint != fingerprint) {
                cachedStates.put(partsPanel, new TreeTableSupport.CachedPanelState(doc, fingerprint));
                return false;
            }

            final Object pack = partsCompletePackField.get(partsPanel);
            if (!skipSelectionSync && expandToSelection && pack != null) {
                final Object updateMgr = (Object) packGetUpdateManager.invokeExact(pack);
                if (updateMgr != null) {
                    final List<?> selectedGuids = (List<?>) updateMgrSelectionGuids.invokeExact(updateMgr);
                    updateMgrExpandParts.invokeExact(updateMgr, selectedGuids);
                }
            }
            if (pack != null && repaintCanvasPartsFlag != null) {
                packRepaintCanvas.invokeExact(pack, repaintCanvasPartsFlag);
            }
            if (partsInteractionField != null && interactionSync != null) {
                final Object interaction = partsInteractionField.get(partsPanel);
                if (interaction != null) {
                    interactionSync.invokeExact(interaction);
                }
            }
            final JTable jTable = (JTable) detacher.treeTableGetJTable().invokeExact(currentTree);
            if (jTable != null) {
                jTable.repaint();
            }
            statsListener.onTreeReused();
            return true;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private long computeFingerprint(final Object partsPanel, final Object doc) throws Throwable {
        long h = TreeTableSupport.FNV_OFFSET_BASIS;
        h = TreeTableSupport.mix(h, System.identityHashCode(doc));
        final Object model = (Object) partsGetModel.invokeExact(partsPanel);
        h = TreeTableSupport.mix(h, System.identityHashCode(model));
        final Object viewCtx = (Object) partsGetViewContext.invokeExact(partsPanel);
        h = TreeTableSupport.mix(h, System.identityHashCode(viewCtx));

        for (final HostHandles.HostField f : partsBoolFilterFields) {
            h = TreeTableSupport.mix(h, Boolean.TRUE.equals(f.get(partsPanel)) ? 1L : 0L);
        }
        for (final HostHandles.HostField f : partsTextFields) {
            final Object tf = f.get(partsPanel);
            final String text = tf == null ? "" : (String) textFieldGetText.invokeExact(tf);
            h = TreeTableSupport.mixString(h, text);
        }
        for (final HostHandles.HostField f : partsTwoStateButtons) {
            final Object btn = f.get(partsPanel);
            final Object state = btn == null ? null : (Object) twoStateGetState.invokeExact(btn);
            h = TreeTableSupport.mix(h, System.identityHashCode(state));
        }
        for (final HostHandles.HostField f : partsCheckBoxes) {
            final Object cb = f.get(partsPanel);
            final boolean sel = cb != null && (boolean) checkBoxIsSelected.invokeExact(cb);
            h = TreeTableSupport.mix(h, sel ? 1L : 0L);
        }

        final List<?> topParts = (List<?>) partsGetTopParts.invokeExact(partsPanel);
        if (topParts != null && !topParts.isEmpty()) {
            h = mixNodeList(h, topParts, model);
        }
        return h;
    }

    private long mixNodeList(long h, final List<?> nodes, final Object model) throws Throwable {
        if (nodes == null) {
            return TreeTableSupport.mixMissingList(h);
        }
        h = TreeTableSupport.mix(h, nodes.size());
        for (final Object node : nodes) {
            if (node == null) {
                h = TreeTableSupport.mix(h, 0L);
                continue;
            }
            h = TreeTableSupport.mix(h, System.identityHashCode(node));
            h = TreeTableSupport.mix(h, System.identityHashCode(node.getClass()));
            final Object idObj = (Object) controllableGetId.invokeExact(node);
            final String idStr = idObj == null ? "" : (String) idGetString.invokeExact(idObj);
            h = TreeTableSupport.mixString(h, idStr);
            final String localName = (String) controllableGetName.invokeExact(node);
            h = TreeTableSupport.mixString(h, localName);

            final Class<?> cls = node.getClass();
            final String clsName = cls.getName();
            if (clsName.equals("com.live2d.cubism.doc.model.parts.CPartSource")) {
                final List<?> sub = (List<?>) partGetChildren.invokeExact(node);
                h = mixNodeList(h, sub, model);
            } else if (clsName.equals("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource")) {
                if (artMeshGetAlphaComp != null) {
                    final Object alpha = (Object) artMeshGetAlphaComp.invokeExact(node);
                    h = TreeTableSupport.mix(h, System.identityHashCode(alpha));
                }
                if (artMeshGetColorComp != null) {
                    final Object colorComp = (Object) artMeshGetColorComp.invokeExact(node);
                    h = TreeTableSupport.mix(h, System.identityHashCode(colorComp));
                }
                final Collection<?> clips = (Collection<?>) artMeshGetClipGuids.invokeExact(node);
                h = TreeTableSupport.mix(h, clips == null ? 0L : clips.size());
                final boolean inv = (boolean) artMeshGetInvertMask.invokeExact(node);
                h = TreeTableSupport.mix(h, inv ? 1L : 0L);
                boolean clipper = false;
                if (model != null) {
                    final Object guid = (Object) artMeshGetGuid.invokeExact(node);
                    final Object drawable = guid == null ? null : (Object) modelGetDrawable.invokeExact(model, guid);
                    clipper = drawable != null && (boolean) drawableIsClipper.invokeExact(drawable);
                }
                h = TreeTableSupport.mix(h, clipper ? 1L : 0L);
            }
        }
        return h;
    }

    private static HostHandles.HostField findOptionalFieldByPackagePrefix(
            final Class<?> owner, final String className, final Class<?> assignable)
            throws ReflectiveOperationException {
        for (final Field f : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    && assignable.isAssignableFrom(f.getType())
                    && f.getType().getPackageName().equals("com.live2d.cubism.view.palette.parts")) {
                return HostHandles.field(owner, f.getName());
            }
        }
        return null;
    }
}
