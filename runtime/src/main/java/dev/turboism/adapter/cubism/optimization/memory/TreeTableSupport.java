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
 * Provides shared {@code CTreeTable} listener/Swing detaching, FNV-1a hashing, and reflective host
 * member discovery for palette optimization helpers.
 */
final class TreeTableSupport {

    static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final String KOTLIN_FUNCTION_PREFIX = "kotlin.jvm.functions.";
    private static final String TREE_TABLE_JTREE_FIELD = "jTree";
    private static final String TREE_TABLE_GET_JTABLE_METHOD = "getJTable";
    private static final String WIDGET_EVENT_IMPL_FIELD = "eventImpl";
    private static final String WIDGET_GET_JCOMPONENT_METHOD = "getJComponent";
    private static final String KEY_REGISTRY_ERROR = "Cannot read KeyRegistry map";
    private static final long NULL_HASH = 0L;
    private static final long EMPTY_LIST_HASH = -1L;

    private TreeTableSupport() {}

    /** Detaches obsolete or closing {@code CTreeTable} instances from AWT and Swing roots. */
    static final class Detacher {
        private final Map<?, ?> keyRegistryMap;
        private final HostHandles.HostField treeTableJTreeField;
        private final MethodHandle treeTableGetJTable;
        private final HostHandles.HostField widgetEventImplField;
        private final MethodHandle widgetGetJComponent;
        private final HostHandles.HostField handlerKeyListenerField;
        private final HostHandles.HostField handlerFocusListenerField;
        private final List<HostHandles.HostField> handlerClearableFields;

        Detacher(final ClassLoader loader, final MemoryLeakMitigationTarget target)
                throws ReflectiveOperationException {
            Objects.requireNonNull(loader, "loader");
            Objects.requireNonNull(target, "target");

            final Class<?> keyRegistryClass = HostHandles.load(loader, target.keyRegistryOwner());
            final HostHandles.HostField mapField =
                    HostHandles.field(keyRegistryClass, MemoryLeakMitigationTarget.KEY_REGISTRY_MAP_FIELD);
            try {
                keyRegistryMap = (Map<?, ?>) mapField.get(null);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw new ReflectiveOperationException(KEY_REGISTRY_ERROR, failure);
            }

            final Class<?> treeTableClass = HostHandles.load(loader, MemoryLeakMitigationTarget.TREE_TABLE_OWNER);
            treeTableJTreeField = HostHandles.field(treeTableClass, TREE_TABLE_JTREE_FIELD);
            final Method getJTableMethod = findNoArgMethod(treeTableClass, TREE_TABLE_GET_JTABLE_METHOD);
            treeTableGetJTable =
                    HostHandles.unreflect(getJTableMethod).asType(MethodType.methodType(JTable.class, Object.class));

            final Class<?> widgetClass = HostHandles.load(loader, MemoryLeakMitigationTarget.C_WIDGET_OWNER);
            final Class<?> uiHandlerClass = HostHandles.load(loader, MemoryLeakMitigationTarget.UI_EVENT_HANDLER_OWNER);
            widgetEventImplField = HostHandles.field(widgetClass, WIDGET_EVENT_IMPL_FIELD);
            widgetGetJComponent = HostHandles.unreflect(findNoArgMethod(widgetClass, WIDGET_GET_JCOMPONENT_METHOD))
                    .asType(MethodType.methodType(JComponent.class, Object.class));

            handlerKeyListenerField = findUniqueFieldByAssignableType(uiHandlerClass, KeyListener.class);
            handlerFocusListenerField = findUniqueFieldByAssignableType(uiHandlerClass, FocusListener.class);
            final List<HostHandles.HostField> clearables = new ArrayList<>();
            for (final Field f : uiHandlerClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())
                        && f.getType().getName().startsWith(KOTLIN_FUNCTION_PREFIX)) {
                    clearables.add(HostHandles.field(uiHandlerClass, f.getName()));
                }
            }
            handlerClearableFields = List.copyOf(clearables);
        }

        /** Detaches listeners, renderers, selection models, and {@code KeyRegistry} entries for {@code treeTable}. */
        boolean detachTreeTable(final Object treeTable) {
            if (treeTable == null) {
                return false;
            }
            try {
                final Object handler = widgetEventImplField.get(treeTable);
                final JComponent jComp = (JComponent) widgetGetJComponent.invokeExact(treeTable);
                final JTable jTable = (JTable) treeTableGetJTable.invokeExact(treeTable);
                final Object rawTree = treeTableJTreeField.get(treeTable);
                final JTree jTree = rawTree instanceof JTree tree ? tree : null;

                detachHandler(handler, jComp, jTable, jTree);
                detachTable(jTable);
                detachTree(jTree);
                return true;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return false;
            }
        }

        /** Returns the cached handle reading {@code CTreeTable.getJTable()}. */
        MethodHandle treeTableGetJTable() {
            return treeTableGetJTable;
        }

        /** Returns the cached field handle reading {@code CTreeTable.jTree}. */
        HostHandles.HostField treeTableJTreeField() {
            return treeTableJTreeField;
        }

        private void detachHandler(final Object handler, final JComponent jComp, final JTable jTable, final JTree jTree)
                throws Throwable {
            if (handler == null) {
                return;
            }
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

        private static void detachTable(final JTable jTable) {
            if (jTable == null) {
                return;
            }
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

        private static void detachTree(final JTree jTree) {
            if (jTree == null) {
                return;
            }
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
    }

    /** Weak document reference paired with the last built structural FNV-1a fingerprint. */
    static final class CachedPanelState {
        final WeakReference<Object> docRef;
        final long fingerprint;

        CachedPanelState(final Object doc, final long fingerprint) {
            this.docRef = new WeakReference<>(doc);
            this.fingerprint = fingerprint;
        }
    }

    /** Mixes a 64-bit integer value into the running FNV-1a hash. */
    static long mix(final long hash, final long value) {
        return (hash ^ value) * FNV_PRIME;
    }

    /** Mixes a nullable string length and UTF-16 code units into the running FNV-1a hash. */
    static long mixString(final long hash, final String value) {
        if (value == null) {
            return mix(hash, NULL_HASH);
        }
        long h = mix(hash, value.length());
        for (int i = 0; i < value.length(); i++) {
            h = mix(h, value.charAt(i));
        }
        return h;
    }

    /** Mixes nullable object value hash without allocating intermediate strings. */
    static long mixNullableHash(final long hash, final Object value) {
        return mix(hash, value == null ? NULL_HASH : Objects.hashCode(value));
    }

    /** Mixes sentinel count when an optional list is absent. */
    static long mixMissingList(final long hash) {
        return mix(hash, EMPTY_LIST_HASH);
    }

    /** Finds a declared zero-arg method or throws {@link NoSuchMethodException}. */
    static Method findNoArgMethod(final Class<?> owner, final String name) throws NoSuchMethodException {
        final Method found = findOptionalNoArgMethod(owner, name);
        if (found != null) {
            return found;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + "()");
    }

    /** Searches the class hierarchy for a non-bridge zero-arg method by exact name. */
    static Method findOptionalNoArgMethod(final Class<?> owner, final String name) {
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

    /** Finds a non-static zero-arg method returning exact {@code returnType}. */
    static Method findSingleMethodByReturn(final Class<?> owner, final Class<?> returnType)
            throws NoSuchMethodException {
        for (final Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == returnType) {
                return m;
            }
        }
        throw new NoSuchMethodException(owner.getName() + " zero-arg returning " + returnType.getName());
    }

    /** Finds a non-static zero-arg method returning {@link List}. */
    static Method findNoArgListMethod(final Class<?> owner) throws NoSuchMethodException {
        for (final Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == List.class) {
                return m;
            }
        }
        throw new NoSuchMethodException(owner.getName() + " zero-arg List method");
    }

    /** Finds the unique non-static field assignable to {@code targetType}. */
    static HostHandles.HostField findUniqueFieldByAssignableType(final Class<?> owner, final Class<?> targetType)
            throws ReflectiveOperationException {
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

    /** Resolves the last mutable instance {@code boolean} field tracking Kotlin panel initialization. */
    static String resolveLastMutableBooleanFieldName(final Class<?> panelClass) {
        String candidate = null;
        for (final Field f : panelClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())
                    && !Modifier.isFinal(f.getModifiers())
                    && f.getType() == boolean.class) {
                candidate = f.getName();
            }
        }
        return candidate;
    }
}
