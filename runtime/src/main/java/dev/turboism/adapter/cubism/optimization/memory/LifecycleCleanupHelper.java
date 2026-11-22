package dev.turboism.adapter.cubism.optimization.memory;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.swing.JComponent;

/**
 * Enforces bounded undo history, clears undo stacks and static source holders on document close,
 * clears inspector object references on empty selection, and unregisters parameter-group rename
 * AWT event listeners when components detach.
 */
final class LifecycleCleanupHelper {

    private static final String INSPECTOR_INTERFACE = "com/live2d/inspector/ICInspector";
    private static final String EDIT_MODE_INTERFACE = "com/live2d/doc/IEditMode";
    private static final String AC_EDIT_MODE_CLASS = "com/live2d/cubism/doc/ACEditMode";
    private static final String CWIDGET_TEXTFIELD_CLASS = "com/live2d/ui/control/CTextField";
    private static final String UNDOABLE_CLASS = "com/live2d/undo/ACUndoable";
    private static final String RENAMING_LISTENER_CLIENT_KEY = "turboism.memoryLeak.renameHierarchyGuard";

    private final Listener statsListener;

    // CUndoManager handles
    private final HostHandles.HostField undoListField;
    private final HostHandles.HostField undoPosField;
    private final HostHandles.HostField undoLimitField;
    private final MethodHandle undoableDie;
    private final MethodHandle undoClear;

    // CModelingDocument / CSceneDocument close handles
    private final MethodHandle modelDocGetModes;
    private final MethodHandle modelDocGetSource;
    private final MethodHandle editModeGetUndoManager;
    private final MethodHandle sceneDocGetUndoManager;
    private final MethodHandle sceneDocGetSource;
    private final List<StaticHolderRef> staticHolders;

    // Inspector handles (palette/inspector/d)
    private final HostHandles.HostField inspectorClassField;
    private final HostHandles.HostField inspectorListField;
    private final HostHandles.HostField inspectorMapField;
    private final MethodHandle inspectorSetValues;
    private final Class<?> editModeInterface;
    private final ClassValue<Optional<MethodHandle>> ciSetEditModeCache = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(final Class<?> type) {
            return resolveSetEditMode(type);
        }
    };

    // ParameterGroupRowWidget handles
    private final HostHandles.HostField rowWidgetMainWidgetField;
    private final HostHandles.HostField rowWidgetListenerField;
    private final HostHandles.HostField rowWidgetRenameFlagField;
    private final MethodHandle widgetGetJComponent;
    private final List<WeakReference<AWTEventListener>> activeRenameListeners =
            Collections.synchronizedList(new ArrayList<>());

    interface Listener {
        void onUndoEvicted(int count);

        void onUndoManagersCleared(int count);

        void onInspectorCleared();

        void onStaticHolderCleared();

        void onRenameListenerCleaned();
    }

    private record StaticHolderRef(
            MemoryLeakMitigationTarget.StaticHolderSpec spec, Class<?> expectedType, HostHandles.HostField field) {}

    LifecycleCleanupHelper(
            final ClassLoader loader, final MemoryLeakMitigationTarget target, final Listener statsListener)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(target, "target");
        this.statsListener = Objects.requireNonNull(statsListener, "statsListener");

        final Class<?> undoMgrClass = HostHandles.load(loader, MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER);
        final Class<?> undoableClass = HostHandles.load(loader, UNDOABLE_CLASS);
        undoListField = HostHandles.field(undoMgrClass, "_undoList");
        undoPosField = HostHandles.field(undoMgrClass, "currentPos");
        undoLimitField = HostHandles.field(undoMgrClass, "limit");
        undoableDie = HostHandles.virtual(undoableClass, "die", "()V", MethodType.methodType(void.class, Object.class));
        undoClear = HostHandles.virtual(undoMgrClass, "clear", "()V", MethodType.methodType(void.class, Object.class));

        final Class<?> modelDocClass = HostHandles.load(loader, MemoryLeakMitigationTarget.MODELING_DOC_OWNER);
        final Class<?> modelSourceClass = HostHandles.load(loader, MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER);
        final Class<?> acEditModeClass = HostHandles.load(loader, AC_EDIT_MODE_CLASS);
        modelDocGetModes = HostHandles.virtual(
                modelDocClass, "getAllEditModes", "()Ljava/util/List;", MethodType.methodType(List.class, Object.class));
        modelDocGetSource = HostHandles.virtual(
                modelDocClass,
                "getModelSource",
                "()L" + MemoryLeakMitigationTarget.MODEL_SOURCE_OWNER + ";",
                MethodType.methodType(Object.class, Object.class));
        editModeGetUndoManager = HostHandles.virtual(
                acEditModeClass,
                "getUndoManager",
                "()L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";",
                MethodType.methodType(Object.class, Object.class));

        final Class<?> sceneDocClass = HostHandles.load(loader, MemoryLeakMitigationTarget.SCENE_DOC_OWNER);
        final Class<?> sceneSourceClass = HostHandles.load(loader, MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER);
        sceneDocGetUndoManager = HostHandles.virtual(
                sceneDocClass,
                "getUndoManager",
                "()L" + MemoryLeakMitigationTarget.UNDO_MANAGER_OWNER + ";",
                MethodType.methodType(Object.class, Object.class));
        sceneDocGetSource = HostHandles.virtual(
                sceneDocClass,
                "getSceneSource",
                "()L" + MemoryLeakMitigationTarget.SCENE_SOURCE_OWNER + ";",
                MethodType.methodType(Object.class, Object.class));

        final List<StaticHolderRef> resolvedHolders = new ArrayList<>();
        for (final MemoryLeakMitigationTarget.StaticHolderSpec spec : target.staticHolders()) {
            try {
                final Class<?> holderCls = HostHandles.load(loader, spec.owner());
                final Field f = holderCls.getDeclaredField(spec.field());
                final Class<?> expectedType = spec.sceneSource() ? sceneSourceClass : modelSourceClass;
                if (expectedType.isAssignableFrom(f.getType())) {
                    resolvedHolders.add(new StaticHolderRef(spec, expectedType, HostHandles.field(holderCls, spec.field())));
                }
            } catch (final ReflectiveOperationException ignored) {
                // Optional holder absent or different obfuscated type on this release.
            }
        }
        staticHolders = List.copyOf(resolvedHolders);

        final Class<?> inspectorCtrlClass = HostHandles.load(loader, MemoryLeakMitigationTarget.INSPECTOR_CTRL_OWNER);
        inspectorClassField = HostHandles.field(inspectorCtrlClass, "h");
        inspectorListField = HostHandles.field(inspectorCtrlClass, "i");
        inspectorMapField = HostHandles.field(inspectorCtrlClass, "j");
        final Class<?> icInspectorClass = HostHandles.load(loader, INSPECTOR_INTERFACE);
        inspectorSetValues = HostHandles.virtual(
                icInspectorClass,
                "setValueList",
                "(Ljava/util/List;Lcom/live2d/core/a;ZZ)V",
                MethodType.methodType(
                        void.class, Object.class, List.class, Object.class, boolean.class, boolean.class));
        editModeInterface = HostHandles.load(loader, EDIT_MODE_INTERFACE);

        final Class<?> rowWidgetClass = HostHandles.load(loader, MemoryLeakMitigationTarget.PARAM_GROUP_ROW_WIDGET_OWNER);
        rowWidgetMainWidgetField = HostHandles.field(rowWidgetClass, "mainWidget");
        rowWidgetListenerField = HostHandles.field(rowWidgetClass, "textFieldListener");
        rowWidgetRenameFlagField = HostHandles.field(rowWidgetClass, "rename");
        final Class<?> cWidgetClass = HostHandles.load(loader, MemoryLeakMitigationTarget.C_WIDGET_OWNER);
        widgetGetJComponent = HostHandles.virtual(
                cWidgetClass, "getJComponent", "()Ljavax/swing/JComponent;", MethodType.methodType(JComponent.class, Object.class));
    }

    /** Enforces {@code CUndoManager.limit} by calling {@code die()} on and evicting oldest entries. */
    void enforceUndoLimit(final Object undoManager) {
        if (undoManager == null) {
            return;
        }
        try {
            final int limit = ((Number) undoLimitField.get(undoManager)).intValue();
            if (limit <= 0) {
                return;
            }
            @SuppressWarnings("unchecked")
            final List<Object> undoList = (List<Object>) undoListField.get(undoManager);
            final int currentPos = ((Number) undoPosField.get(undoManager)).intValue();
            if (undoList == null || undoList.size() <= limit || currentPos <= 0) {
                return;
            }
            final int excess = undoList.size() - limit;
            final int evictCount = Math.min(excess, currentPos);
            if (evictCount <= 0) {
                return;
            }
            for (int i = 0; i < evictCount; i++) {
                final Object item = undoList.get(i);
                if (item != null) {
                    undoableDie.invokeExact(item);
                }
            }
            undoList.subList(0, evictCount).clear();
            undoPosField.set(undoManager, Integer.valueOf(currentPos - evictCount));
            statsListener.onUndoEvicted(evictCount);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Clears all edit mode undo managers and matching static source holders after model close. */
    void onModelingDocumentClosed(final Object modelingDoc) {
        if (modelingDoc == null) {
            return;
        }
        try {
            final List<?> editModes = (List<?>) modelDocGetModes.invokeExact(modelingDoc);
            int cleared = 0;
            if (editModes != null) {
                for (final Object mode : editModes) {
                    if (mode == null) {
                        continue;
                    }
                    final Object undoMgr = (Object) editModeGetUndoManager.invokeExact(mode);
                    if (undoMgr != null) {
                        undoClear.invokeExact(undoMgr);
                        cleared++;
                    }
                }
            }
            if (cleared > 0) {
                statsListener.onUndoManagersCleared(cleared);
            }
            final Object closingSource = (Object) modelDocGetSource.invokeExact(modelingDoc);
            clearMatchingStaticHolders(closingSource, false);
            pruneOrphanRenameListeners();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Clears scene undo manager and matching static scene holders after scene close. */
    void onSceneDocumentClosed(final Object sceneDoc) {
        if (sceneDoc == null) {
            return;
        }
        try {
            final Object undoMgr = (Object) sceneDocGetUndoManager.invokeExact(sceneDoc);
            if (undoMgr != null) {
                undoClear.invokeExact(undoMgr);
                statsListener.onUndoManagersCleared(1);
            }
            final Object closingSource = (Object) sceneDocGetSource.invokeExact(sceneDoc);
            clearMatchingStaticHolders(closingSource, true);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Clears retained inspector selection class, object list, and cached inspector targets. */
    void onInspectorCleared(final Object inspectorCtrl) {
        if (inspectorCtrl == null) {
            return;
        }
        try {
            inspectorClassField.set(inspectorCtrl, null);
            inspectorListField.set(inspectorCtrl, null);
            final Object rawMap = inspectorMapField.get(inspectorCtrl);
            if (rawMap instanceof Map<?, ?> map) {
                for (final Object inspector : map.values()) {
                    if (inspector == null) {
                        continue;
                    }
                    inspectorSetValues.invokeExact(
                            inspector, Collections.emptyList(), (Object) null, false, false);
                    final Optional<MethodHandle> setEditMode = ciSetEditModeCache.get(inspector.getClass());
                    if (setEditMode.isPresent()) {
                        setEditMode.get().invokeExact(inspector, (Object) null);
                    }
                }
            }
            statsListener.onInspectorCleared();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Attaches a hierarchy-based AWT listener guard when folder renaming starts. */
    void onParameterRenameStarted(final Object rowWidget) {
        if (rowWidget == null) {
            return;
        }
        try {
            final Object listenerObj = rowWidgetListenerField.get(rowWidget);
            if (!(listenerObj instanceof AWTEventListener awtListener)) {
                return;
            }
            activeRenameListeners.add(new WeakReference<>(awtListener));
            final Object mainWidget = rowWidgetMainWidgetField.get(rowWidget);
            if (mainWidget == null) {
                return;
            }
            final JComponent comp = (JComponent) widgetGetJComponent.invokeExact(mainWidget);
            if (comp == null || comp.getClientProperty(RENAMING_LISTENER_CLIENT_KEY) != null) {
                return;
            }
            comp.putClientProperty(RENAMING_LISTENER_CLIENT_KEY, Boolean.TRUE);
            final WeakReference<Object> widgetRef = new WeakReference<>(rowWidget);
            final WeakReference<AWTEventListener> listenerRef = new WeakReference<>(awtListener);
            final WeakReference<Component> compRef = new WeakReference<>(comp);
            comp.addHierarchyListener(new HierarchyListener() {
                @Override
                public void hierarchyChanged(final HierarchyEvent event) {
                    final Component c = compRef.get();
                    final AWTEventListener l = listenerRef.get();
                    if (c == null || l == null || ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 && !c.isDisplayable())
                            || ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0 && c.getParent() == null)) {
                        if (l != null) {
                            Toolkit.getDefaultToolkit().removeAWTEventListener(l);
                            statsListener.onRenameListenerCleaned();
                        }
                        final Object rw = widgetRef.get();
                        if (rw != null) {
                            try {
                                rowWidgetRenameFlagField.set(rw, Boolean.FALSE);
                            } catch (final Throwable failure) {
                                FatalErrors.rethrowIfFatal(failure);
                            }
                        }
                        if (c != null) {
                            c.removeHierarchyListener(this);
                            if (c instanceof JComponent jc) {
                                jc.putClientProperty(RENAMING_LISTENER_CLIENT_KEY, null);
                            }
                        }
                    }
                }
            });
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void clearMatchingStaticHolders(final Object closingSource, final boolean sceneSource) throws Throwable {
        for (final StaticHolderRef ref : staticHolders) {
            if (ref.spec().sceneSource() != sceneSource) {
                continue;
            }
            final Object held = ref.field().get(null);
            if (held != null && (closingSource == null || held == closingSource)) {
                ref.field().set(null, null);
                statsListener.onStaticHolderCleared();
            }
        }
    }

    private void pruneOrphanRenameListeners() {
        synchronized (activeRenameListeners) {
            final Iterator<WeakReference<AWTEventListener>> it = activeRenameListeners.iterator();
            while (it.hasNext()) {
                final AWTEventListener listener = it.next().get();
                if (listener == null) {
                    it.remove();
                    continue;
                }
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
                it.remove();
                statsListener.onRenameListenerCleaned();
            }
        }
    }

    private Optional<MethodHandle> resolveSetEditMode(final Class<?> type) {
        try {
            return Optional.of(HostHandles.virtual(
                    type,
                    "setEditMode",
                    "(L" + EDIT_MODE_INTERFACE + ";)V",
                    MethodType.methodType(void.class, Object.class, Object.class)));
        } catch (final ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }

    /** Clears tracking state on bridge shutdown. */
    void close() {
        activeRenameListeners.clear();
    }
}
