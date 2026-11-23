package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.FocusListener;
import java.awt.event.KeyListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.DefaultListSelectionModel;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeSelectionListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeSelectionModel;

/**
 * Eliminates {@code CPartsTreeTable} / {@code util.KeyListener} retention leaks and skips
 * redundant Parts palette rebuilds when model structure, metadata, filters, and search text match.
 */
final class PartsPaletteHelper {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

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

    private final WeakIdentityMap<Object, CachedPartsState> cachedStates = new WeakIdentityMap<>();
    private final Listener statsListener;

    // KeyRegistry & CWidget / ui.m accessors
    private final Map<?, ?> keyRegistryMap;
    private final HostHandles.HostField treeTableJTreeField;
    private final MethodHandle treeTableGetJTable;
    private final HostHandles.HostField widgetEventImplField;
    private final MethodHandle widgetGetJComponent;
    private final HostHandles.HostField handlerKeyListenerField;
    private final HostHandles.HostField handlerFocusListenerField;
    private final List<HostHandles.HostField> handlerClearableFields;

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

    PartsPaletteHelper(final ClassLoader loader, final MemoryLeakMitigationTarget target, final Listener listener)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");
        this.statsListener = Objects.requireNonNull(listener, "listener");

        final Class<?> keyRegistryClass = HostHandles.load(loader, target.keyRegistryOwner());
        final HostHandles.HostField mapField =
                HostHandles.field(keyRegistryClass, MemoryLeakMitigationTarget.KEY_REGISTRY_MAP_FIELD);
        try {
            keyRegistryMap = (Map<?, ?>) mapField.get(null);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException("Cannot read KeyRegistry map", failure);
        }

        final Class<?> treeTableClass = HostHandles.load(loader, MemoryLeakMitigationTarget.TREE_TABLE_OWNER);
        treeTableJTreeField = HostHandles.field(treeTableClass, "jTree");
        final Method getJTableMethod = findNoArgMethod(treeTableClass, "getJTable");
        treeTableGetJTable =
                HostHandles.unreflect(getJTableMethod).asType(MethodType.methodType(JTable.class, Object.class));

        final Class<?> widgetClass = HostHandles.load(loader, MemoryLeakMitigationTarget.C_WIDGET_OWNER);
        final Class<?> uiHandlerClass = HostHandles.load(loader, MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER);
        widgetEventImplField = HostHandles.field(widgetClass, "eventImpl");
        widgetGetJComponent = HostHandles.unreflect(findNoArgMethod(widgetClass, "getJComponent"))
                .asType(MethodType.methodType(JComponent.class, Object.class));

        handlerKeyListenerField = findUniqueFieldByAssignableType(uiHandlerClass, KeyListener.class);
        handlerFocusListenerField = findUniqueFieldByAssignableType(uiHandlerClass, FocusListener.class);
        final List<HostHandles.HostField> clearables = new ArrayList<>();
        for (final Field f : uiHandlerClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (f.getType().getName().startsWith("kotlin.jvm.functions.")) {
                clearables.add(HostHandles.field(uiHandlerClass, f.getName()));
            }
        }
        handlerClearableFields = List.copyOf(clearables);

        final Class<?> partsPanelClass = HostHandles.load(loader, MemoryLeakMitigationTarget.PARTS_PANEL_OWNER);
        final Class<?> completePackClass = HostHandles.load(loader, COMPLETE_PACK_CLASS);
        final Class<?> modelingDocClass = HostHandles.load(loader, MODEL_DOC_CLASS);
        final Class<?> cModelClass = HostHandles.load(loader, CMODEL_CLASS);
        final Class<?> viewContextClass = HostHandles.load(loader, VIEW_CONTEXT_CLASS);
        final Class<?> textFieldClass = HostHandles.load(loader, TEXT_FIELD_CLASS);
        final Class<?> twoStateClass = HostHandles.load(loader, TWO_STATE_BTN_CLASS);
        final Class<?> checkBoxClass = HostHandles.load(loader, CHECK_BOX_CLASS);

        partsCompletePackField = findUniqueFieldByAssignableType(partsPanelClass, completePackClass);
        partsTreeTableField = HostHandles.field(partsPanelClass, MemoryLeakMitigationTarget.TREE_TABLE_FIELD);
        partsInteractionField =
                findOptionalFieldByPackagePrefix(partsPanelClass, PARTS_INTERACTION_CLASS, MouseListener.class);
        final String initFlagName = resolveInitFlagFieldName(partsPanelClass);
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

        partsGetDoc = HostHandles.unreflect(findSingleMethodByReturn(partsPanelClass, modelingDocClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        partsGetModel = HostHandles.unreflect(findSingleMethodByReturn(partsPanelClass, cModelClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        partsGetViewContext = HostHandles.unreflect(findSingleMethodByReturn(partsPanelClass, viewContextClass))
                .asType(MethodType.methodType(Object.class, Object.class));
        partsGetTopParts = HostHandles.unreflect(findNoArgListMethod(partsPanelClass))
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
        final Method getIdMethod = findNoArgMethod(controllableClass, "getId");
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

        final Method alphaMethod = findOptionalNoArgMethod(artMeshClass, "getAlphaComposition");
        artMeshGetAlphaComp = alphaMethod == null
                ? null
                : HostHandles.unreflect(alphaMethod).asType(MethodType.methodType(Object.class, Object.class));
        final Method colorMethod = findOptionalNoArgMethod(artMeshClass, "getColorComposition");
        artMeshGetColorComp = colorMethod == null
                ? null
                : HostHandles.unreflect(colorMethod).asType(MethodType.methodType(Object.class, Object.class));
        final Method clipListMethod = findNoArgMethod(artMeshClass, "getClipGuidList");
        artMeshGetClipGuids =
                HostHandles.unreflect(clipListMethod).asType(MethodType.methodType(Collection.class, Object.class));
        artMeshGetInvertMask = HostHandles.virtual(
                artMeshClass, "getInvertClippingMask", "()Z", MethodType.methodType(boolean.class, Object.class));
        final Method artGuidMethod = findNoArgMethod(artMeshClass, "getGuid");
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
        final Method stateMethod = findNoArgMethod(twoStateClass, "getState");
        twoStateGetState = HostHandles.unreflect(stateMethod).asType(MethodType.methodType(Object.class, Object.class));
        checkBoxIsSelected = HostHandles.virtual(
                checkBoxClass, "isSelected", "()Z", MethodType.methodType(boolean.class, Object.class));
    }

    /** Detaches an obsolete or closing {@code CPartsTreeTable} from global AWT and Swing roots. */
    void detachTreeTable(final Object treeTable) {
        if (treeTable == null) {
            return;
        }
        try {
            final Object handler = widgetEventImplField.get(treeTable);
            final JComponent jComp = (JComponent) widgetGetJComponent.invokeExact(treeTable);
            final JTable jTable = (JTable) treeTableGetJTable.invokeExact(treeTable);
            final Object rawTree = treeTableJTreeField.get(treeTable);
            final JTree jTree = rawTree instanceof JTree tree ? tree : null;

            if (handler != null) {
                final Object kl = handlerKeyListenerField.get(handler);
                if (kl != null) {
                    final Object wrapper = keyRegistryMap.remove(kl);
                    if (wrapper instanceof AWTEventListener awtListener) {
                        Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener);
                    }
                    handlerKeyListenerField.set(handler, null);
                }
                final Object fl = handlerFocusListenerField.get(handler);
                if (fl instanceof FocusListener focusListener) {
                    if (jComp != null) jComp.removeFocusListener(focusListener);
                    if (jTable != null) jTable.removeFocusListener(focusListener);
                    if (jTree != null) jTree.removeFocusListener(focusListener);
                    handlerFocusListenerField.set(handler, null);
                }
                for (final HostHandles.HostField f : handlerClearableFields) {
                    f.set(handler, null);
                }
            }

            if (jTable != null) {
                jTable.setTransferHandler(null);
                jTable.setDropTarget(null);
                for (final MouseListener ml : jTable.getMouseListeners()) {
                    jTable.removeMouseListener(ml);
                }
                for (final MouseMotionListener mml : jTable.getMouseMotionListeners()) {
                    jTable.removeMouseMotionListener(mml);
                }
                final TableColumnModel colModel = jTable.getColumnModel();
                if (colModel != null) {
                    for (int i = 0; i < colModel.getColumnCount(); i++) {
                        final TableColumn col = colModel.getColumn(i);
                        col.setHeaderRenderer(null);
                        col.setCellRenderer(null);
                        col.setCellEditor(null);
                    }
                }
                jTable.setDefaultRenderer(Object.class, null);
                jTable.setSelectionModel(new DefaultListSelectionModel());
                jTable.setModel(new DefaultTableModel());
            }

            if (jTree != null) {
                for (final TreeExpansionListener tel : jTree.getTreeExpansionListeners()) {
                    jTree.removeTreeExpansionListener(tel);
                }
                for (final TreeSelectionListener tsl : jTree.getTreeSelectionListeners()) {
                    jTree.removeTreeSelectionListener(tsl);
                }
                jTree.setCellRenderer(null);
                jTree.setSelectionModel(new DefaultTreeSelectionModel());
                jTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
            }
            statsListener.onTreeDetached();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
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

    /** Detaches active tree tables and interaction node lists on panel close ({@code y()} / {@code w()}). */
    void onPartsPanelClosed(final Object partsPanel) {
        if (partsPanel == null) {
            return;
        }
        cachedStates.remove(partsPanel);
        try {
            final Object current = partsTreeTableField.get(partsPanel);
            if (current != null) {
                detachTreeTable(current);
                partsTreeTableField.set(partsPanel, null);
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
                cachedStates.put(partsPanel, new CachedPartsState(doc, fingerprint));
                return false;
            }

            final CachedPartsState prev = cachedStates.get(partsPanel);
            if (prev == null || prev.docRef.get() != doc || prev.fingerprint != fingerprint) {
                cachedStates.put(partsPanel, new CachedPartsState(doc, fingerprint));
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
            final JTable jTable = (JTable) treeTableGetJTable.invokeExact(currentTree);
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
        long h = FNV_OFFSET_BASIS;
        h = mix(h, System.identityHashCode(doc));
        final Object model = (Object) partsGetModel.invokeExact(partsPanel);
        h = mix(h, System.identityHashCode(model));
        final Object viewCtx = (Object) partsGetViewContext.invokeExact(partsPanel);
        h = mix(h, System.identityHashCode(viewCtx));

        for (final HostHandles.HostField f : partsBoolFilterFields) {
            h = mix(h, Boolean.TRUE.equals(f.get(partsPanel)) ? 1L : 0L);
        }
        for (final HostHandles.HostField f : partsTextFields) {
            final Object tf = f.get(partsPanel);
            final String text = tf == null ? "" : (String) textFieldGetText.invokeExact(tf);
            h = mixString(h, text);
        }
        for (final HostHandles.HostField f : partsTwoStateButtons) {
            final Object btn = f.get(partsPanel);
            final Object state = btn == null ? null : (Object) twoStateGetState.invokeExact(btn);
            h = mix(h, System.identityHashCode(state));
        }
        for (final HostHandles.HostField f : partsCheckBoxes) {
            final Object cb = f.get(partsPanel);
            final boolean sel = cb != null && (boolean) checkBoxIsSelected.invokeExact(cb);
            h = mix(h, sel ? 1L : 0L);
        }

        final List<?> topParts = (List<?>) partsGetTopParts.invokeExact(partsPanel);
        if (topParts != null && !topParts.isEmpty()) {
            h = mixNodeList(h, topParts, model);
        }
        return h;
    }

    private long mixNodeList(long h, final List<?> nodes, final Object model) throws Throwable {
        if (nodes == null) {
            return mix(h, -1L);
        }
        h = mix(h, nodes.size());
        for (final Object node : nodes) {
            if (node == null) {
                h = mix(h, 0L);
                continue;
            }
            h = mix(h, System.identityHashCode(node));
            h = mix(h, System.identityHashCode(node.getClass()));
            final Object idObj = (Object) controllableGetId.invokeExact(node);
            final String idStr = idObj == null ? "" : (String) idGetString.invokeExact(idObj);
            h = mixString(h, idStr);
            final String localName = (String) controllableGetName.invokeExact(node);
            h = mixString(h, localName);

            final Class<?> cls = node.getClass();
            final String clsName = cls.getName();
            if (clsName.equals("com.live2d.cubism.doc.model.parts.CPartSource")) {
                final List<?> sub = (List<?>) partGetChildren.invokeExact(node);
                h = mixNodeList(h, sub, model);
            } else if (clsName.equals("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource")) {
                if (artMeshGetAlphaComp != null) {
                    final Object alpha = (Object) artMeshGetAlphaComp.invokeExact(node);
                    h = mix(h, System.identityHashCode(alpha));
                }
                if (artMeshGetColorComp != null) {
                    final Object colorComp = (Object) artMeshGetColorComp.invokeExact(node);
                    h = mix(h, System.identityHashCode(colorComp));
                }
                final Collection<?> clips = (Collection<?>) artMeshGetClipGuids.invokeExact(node);
                h = mix(h, clips == null ? 0L : clips.size());
                final boolean inv = (boolean) artMeshGetInvertMask.invokeExact(node);
                h = mix(h, inv ? 1L : 0L);
                boolean clipper = false;
                if (model != null) {
                    final Object guid = (Object) artMeshGetGuid.invokeExact(node);
                    final Object drawable = guid == null ? null : (Object) modelGetDrawable.invokeExact(model, guid);
                    clipper = drawable != null && (boolean) drawableIsClipper.invokeExact(drawable);
                }
                h = mix(h, clipper ? 1L : 0L);
            }
        }
        return h;
    }

    private static long mix(final long hash, final long value) {
        return (hash ^ value) * FNV_PRIME;
    }

    private static long mixString(final long hash, final String s) {
        if (s == null) {
            return mix(hash, 0L);
        }
        long h = mix(hash, s.length());
        for (int i = 0; i < s.length(); i++) {
            h = mix(h, s.charAt(i));
        }
        return h;
    }

    private static Method findNoArgMethod(final Class<?> owner, final String name) throws NoSuchMethodException {
        final Method found = findOptionalNoArgMethod(owner, name);
        if (found != null) {
            return found;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "()");
    }

    private static Method findOptionalNoArgMethod(final Class<?> owner, final String name) {
        Method bridgeFallback = null;
        for (Class<?> cur = owner; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (final Method m : cur.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getName().equals(name)) {
                    if (!m.isBridge()) {
                        return m;
                    }
                    if (bridgeFallback == null) {
                        bridgeFallback = m;
                    }
                }
            }
        }
        return bridgeFallback;
    }

    private static Method findSingleMethodByReturn(final Class<?> owner, final Class<?> returnType)
            throws NoSuchMethodException {
        for (final Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == returnType) {
                return m;
            }
        }
        throw new NoSuchMethodException(owner.getName() + " zero-arg returning " + returnType.getName());
    }

    private static Method findNoArgListMethod(final Class<?> owner) throws NoSuchMethodException {
        for (final Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == List.class) {
                return m;
            }
        }
        throw new NoSuchMethodException(owner.getName() + " zero-arg List method");
    }

    private static HostHandles.HostField findUniqueFieldByAssignableType(
            final Class<?> owner, final Class<?> targetType) throws ReflectiveOperationException {
        String match = null;
        for (final Field f : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && targetType.isAssignableFrom(f.getType())) {
                if (match != null) {
                    throw new NoSuchFieldException(
                            "Ambiguous field of type " + targetType.getName() + " on " + owner.getName());
                }
                match = f.getName();
            }
        }
        if (match == null) {
            throw new NoSuchFieldException("No field of type " + targetType.getName() + " on " + owner.getName());
        }
        return HostHandles.field(owner, match);
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

    private static String resolveInitFlagFieldName(final Class<?> partsPanelClass) {
        // In all reviewed versions, the last mutable instance boolean field (A or B) tracks initialized state.
        String candidate = null;
        for (final Field f : partsPanelClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    && !Modifier.isFinal(f.getModifiers())
                    && f.getType() == boolean.class) {
                candidate = f.getName();
            }
        }
        return candidate;
    }

    private static final class CachedPartsState {
        private final WeakReference<Object> docRef;
        private final long fingerprint;

        private CachedPartsState(final Object doc, final long fingerprint) {
            this.docRef = new WeakReference<>(doc);
            this.fingerprint = fingerprint;
        }
    }
}
