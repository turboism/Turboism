package dev.turboism.adapter.cubism.optimization.paramrow;

import dev.turboism.core.reflect.HostHandles;
import java.awt.Color;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;

/**
 * Resolves every reviewed Parameter Palette host member once and exposes it as a typed read.
 *
 * <p>The palette controller, its rows and the model graph live in the host loader, so this class
 * holds {@link MethodHandle}s instead of host types. Each accessor names the exact reviewed
 * descriptor it reads and returns a plain value or a record, so no caller handles a descriptor.
 * Every handle is adapted to an erased {@code (Object...)} shape, which makes the handle itself
 * perform the reviewed {@code CHECKCAST} on the receiver and arguments.</p>
 *
 * <p>Resolution is the whole admission test: the constructor either resolves every member the
 * bridge calls or throws {@link HostHandles.HostMemberRefused}, so a constructed access object
 * cannot fail later on a missing member. The reviewed {@code CModelingEditMode_Base} superclass
 * relation is the one structural contract a member lookup cannot see, so it is checked here.</p>
 */
final class ParameterRowHostAccess {

    /** Row shape of a palette child widget that carries one parameter. */
    static final int ROW_KIND_SINGLE = 1;

    /** Row shape of a palette child widget that carries two parameters. */
    static final int ROW_KIND_DOUBLE = 2;

    /** Row shape of a palette folder that owns a nested row layout. */
    static final int ROW_KIND_GROUP = 3;

    /** Row shape of a palette child widget that only paints itself. */
    static final int ROW_KIND_WIDGET = 4;

    /** Row shape of a widget outside the reviewed palette families. */
    static final int ROW_KIND_UNKNOWN = 0;

    private static final MethodType OBJECT_GETTER = MethodType.methodType(Object.class, Object.class);
    private static final MethodType BOOLEAN_GETTER = MethodType.methodType(boolean.class, Object.class);
    private static final MethodType INT_GETTER = MethodType.methodType(int.class, Object.class);
    private static final MethodType LONG_GETTER = MethodType.methodType(long.class, Object.class);
    private static final MethodType FLOAT_GETTER = MethodType.methodType(float.class, Object.class);
    private static final MethodType OBJECT_SETTER = MethodType.methodType(void.class, Object.class, Object.class);
    private static final MethodType LIST_SETTER = MethodType.methodType(void.class, Object.class, List.class);
    private static final MethodType FLOAT_SETTER = MethodType.methodType(void.class, Object.class, float.class);
    private static final MethodType VOID_CALL = MethodType.methodType(void.class, Object.class);
    private static final MethodType KEYED_GETTER = MethodType.methodType(Object.class, Object.class, Object.class);

    private static final int NULL_COLOR_ARGB = 0;
    private static final String EXTENDS_ERROR_INFIX = " does not extend ";

    private final Class<?> singleRowClass;
    private final Class<?> doubleRowClass;
    private final Class<?> groupRowClass;
    private final Class<?> widgetClass;
    private final Class<?> parameterRowClass;
    private final Class<?> controllableClass;
    private final Class<?> modelingDocumentClass;
    private final Class<?> modelClass;
    private final Class<?> editModeClass;

    private final MethodHandle childrenBoxGetter;
    private final MethodHandle rowListGetter;
    private final MethodHandle selectedObjectsGetter;
    private final MethodHandle highlightGetter;
    private final MethodHandle highlightSetter;
    private final MethodHandle highlightGuidGetter;
    private final MethodHandle selectedRowsGetter;
    private final MethodHandle showKeyformsGetter;
    private final MethodHandle rowLayoutChildrenGetter;

    private final MethodHandle singleRowParameterGetter;
    private final MethodHandle singleRowBlockerGetter;
    private final MethodHandle singleRowSelectedObjectsSetter;
    private final MethodHandle singleRowMainWidgetGetter;
    private final MethodHandle doubleRowParameterGetter;
    private final MethodHandle doubleRowParameterSecGetter;
    private final MethodHandle doubleRowBlockerGetter;
    private final MethodHandle doubleRowCombinedEnableGetter;
    private final MethodHandle doubleRowResize;
    private final MethodHandle doubleRowMainWidgetGetter;
    private final MethodHandle groupRowParameterGroupGetter;
    private final MethodHandle groupRowFoldingPaneGetter;
    private final MethodHandle groupRowRowLayoutGetter;
    private final MethodHandle groupRowMainWidgetGetter;
    private final MethodHandle foldingPaneIsExpandGetter;
    private final MethodHandle blockerIsBlockGetter;
    private final MethodHandle widgetRepaint;
    private final MethodHandle widgetVisibleGetter;
    private final MethodHandle widgetComponentGetter;
    private final MethodHandle colorJColorGetter;
    private final MethodHandle parameterGroupNameGetter;
    private final MethodHandle parameterGroupVisibilityColorGetter;
    private final MethodHandle parameterGroupFolderOpenedGetter;

    private final MethodHandle parameterRowIdGetter;
    private final MethodHandle parameterRowValueGetter;
    private final MethodHandle parameterRowValueSetter;
    private final MethodHandle parameterRowSecIdGetter;
    private final MethodHandle parameterRowSecValueGetter;
    private final MethodHandle parameterRowSecValueSetter;

    private final MethodHandle modelParameterSetGetter;
    private final MethodHandle modelObjectsGetter;
    private final MethodHandle parameterSetGet;
    private final MethodHandle parameterValueGetter;

    private final MethodHandle sourceGuidGetter;
    private final MethodHandle sourceIdGetter;
    private final MethodHandle sourceNameGetter;
    private final MethodHandle sourceMinValueGetter;
    private final MethodHandle sourceMaxValueGetter;
    private final MethodHandle sourceDefaultValueGetter;
    private final MethodHandle sourceRepeatGetter;
    private final MethodHandle sourceCombinedGetter;
    private final MethodHandle sourceMorphTargetGetter;
    private final MethodHandle sourceParamTypeGetter;
    private final MethodHandle sourceVisibilityColorGetter;

    private final MethodHandle controllableSourceGetter;
    private final MethodHandle sourceKeyformGridGetter;
    private final MethodHandle sourceExtendedKeyformGridGetter;
    private final MethodHandle sourceMorphTargetSetGetter;
    private final MethodHandle sourceExtendedMorphTargetSetGetter;
    private final MethodHandle gridBindingsGetter;
    private final MethodHandle bindingGuidGetter;
    private final MethodHandle bindingKeysGetter;
    private final MethodHandle morphTargetsGetter;
    private final MethodHandle morphTargetGuidGetter;
    private final MethodHandle morphTargetKeyValueGetter;

    private final MethodHandle documentEditModeGetter;
    private final MethodHandle editModeEditingGetter;
    private final MethodHandle editModeCurrentUndoGetter;
    private final MethodHandle editModeLastModifiedGetter;
    private final MethodHandle editModeUndoManagerGetter;
    private final MethodHandle editModeLinkedUndoManagerGetter;
    private final MethodHandle undoManagerPositionGetter;
    private final MethodHandle undoManagerListGetter;

    /**
     * Resolves every reviewed host member for one admitted artifact.
     *
     * @param loader admitted host class loader
     * @param target reviewed version target descriptors
     * @throws ReflectiveOperationException when a reviewed class, member or superclass relation is
     *     unavailable
     */
    ParameterRowHostAccess(final ClassLoader loader, final ParameterRowRepaintTarget target)
            throws ReflectiveOperationException {
        Objects.requireNonNull(target, "target");

        final Class<?> controllerClass = HostHandles.load(loader, target.controllerOwner());
        final Class<?> highlightClass = HostHandles.load(loader, target.highlightInnerOwner());
        final Class<?> rowLayoutClass = HostHandles.load(loader, ParameterRowRepaintTarget.ROW_LAYOUT_OWNER);
        parameterRowClass = HostHandles.load(loader, ParameterRowRepaintTarget.PARAM_ROW_INTERFACE);
        singleRowClass = HostHandles.load(loader, ParameterRowRepaintTarget.SINGLE_ROW_OWNER);
        doubleRowClass = HostHandles.load(loader, ParameterRowRepaintTarget.DOUBLE_ROW_OWNER);
        groupRowClass = HostHandles.load(loader, ParameterRowRepaintTarget.GROUP_ROW_OWNER);
        widgetClass = HostHandles.load(loader, ParameterRowRepaintTarget.WIDGET_OWNER);
        final Class<?> foldingPaneClass = HostHandles.load(loader, ParameterRowRepaintTarget.FOLDING_PANE_OWNER);
        final Class<?> blockerClass = HostHandles.load(loader, ParameterRowRepaintTarget.BLOCKER_OWNER);
        final Class<?> colorClass = HostHandles.load(loader, ParameterRowRepaintTarget.COLOR_OWNER);
        final Class<?> parameterGroupClass = HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_GROUP_OWNER);
        modelClass = HostHandles.load(loader, ParameterRowRepaintTarget.MODEL_OWNER);
        final Class<?> parameterSetClass = HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_SET_OWNER);
        final Class<?> parameterClass = HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_OWNER);
        final Class<?> sourceClass = HostHandles.load(loader, ParameterRowRepaintTarget.PARAMETER_SOURCE_OWNER);
        controllableClass = HostHandles.load(loader, ParameterRowRepaintTarget.CONTROLLABLE_INTERFACE);
        final Class<?> controllableSourceClass =
                HostHandles.load(loader, ParameterRowRepaintTarget.CONTROLLABLE_SOURCE_OWNER);
        final Class<?> gridClass = HostHandles.load(loader, ParameterRowRepaintTarget.KEYFORM_GRID_SOURCE_OWNER);
        final Class<?> bindingClass = HostHandles.load(loader, ParameterRowRepaintTarget.KEYFORM_BINDING_SOURCE_OWNER);
        final Class<?> morphSetClass = HostHandles.load(loader, ParameterRowRepaintTarget.MORPH_TARGET_SET_OWNER);
        final Class<?> morphTargetClass = HostHandles.load(loader, ParameterRowRepaintTarget.MORPH_TARGET_OWNER);
        modelingDocumentClass = HostHandles.load(loader, ParameterRowRepaintTarget.MODELING_DOCUMENT_OWNER);
        final Class<?> editModeBaseClass = HostHandles.load(loader, ParameterRowRepaintTarget.EDIT_MODE_BASE_OWNER);
        editModeClass = HostHandles.load(loader, ParameterRowRepaintTarget.EDIT_MODE_OWNER);
        final Class<?> undoManagerClass = HostHandles.load(loader, ParameterRowRepaintTarget.UNDO_MANAGER_OWNER);
        // The undo cursor's top entry is typed by the reviewed GroupUndo owner, so it must load too.
        HostHandles.load(loader, ParameterRowRepaintTarget.GROUP_UNDO_OWNER);
        if (!editModeClass.isAssignableFrom(editModeBaseClass)) {
            throw new ReflectiveOperationException(
                    editModeBaseClass.getName() + EXTENDS_ERROR_INFIX + editModeClass.getName());
        }

        childrenBoxGetter = HostHandles.getter(
                controllerClass,
                ParameterRowRepaintTarget.CHILDREN_BOX_FIELD,
                ParameterRowRepaintTarget.CHILDREN_BOX_DESCRIPTOR,
                OBJECT_GETTER);
        rowListGetter = HostHandles.getter(
                controllerClass,
                ParameterRowRepaintTarget.ROW_LIST_FIELD,
                ParameterRowRepaintTarget.ROW_LIST_DESCRIPTOR,
                OBJECT_GETTER);
        selectedObjectsGetter = HostHandles.getter(
                controllerClass,
                ParameterRowRepaintTarget.SELECTED_OBJECTS_FIELD,
                ParameterRowRepaintTarget.SELECTED_OBJECTS_DESCRIPTOR,
                OBJECT_GETTER);
        highlightGetter = HostHandles.getter(
                controllerClass, target.highlightField(), target.highlightFieldDescriptor(), OBJECT_GETTER);
        highlightSetter = HostHandles.setter(
                controllerClass, target.highlightField(), target.highlightFieldDescriptor(), OBJECT_SETTER);
        highlightGuidGetter = HostHandles.virtual(
                highlightClass,
                ParameterRowRepaintTarget.HIGHLIGHT_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        selectedRowsGetter = HostHandles.virtual(
                controllerClass,
                ParameterRowRepaintTarget.GET_SELECTED_ROWS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        showKeyformsGetter = HostHandles.virtual(
                controllerClass,
                target.showKeyformsMethod(),
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        rowLayoutChildrenGetter = HostHandles.virtual(
                rowLayoutClass,
                ParameterRowRepaintTarget.GET_CHILDREN_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                OBJECT_GETTER);

        parameterRowIdGetter = HostHandles.virtual(
                parameterRowClass,
                ParameterRowRepaintTarget.GET_PARAM1_ID_METHOD,
                ParameterRowRepaintTarget.PARAM_ID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        parameterRowValueGetter = HostHandles.virtual(
                parameterRowClass,
                ParameterRowRepaintTarget.GET_PARAM1_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                FLOAT_GETTER);
        parameterRowValueSetter = HostHandles.virtual(
                parameterRowClass,
                ParameterRowRepaintTarget.SET_PARAM1_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_SETTER_DESCRIPTOR,
                FLOAT_SETTER);
        parameterRowSecIdGetter = HostHandles.virtual(
                parameterRowClass,
                ParameterRowRepaintTarget.GET_PARAM2_ID_METHOD,
                ParameterRowRepaintTarget.PARAM_ID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        parameterRowSecValueGetter = HostHandles.virtual(
                parameterRowClass,
                ParameterRowRepaintTarget.GET_PARAM2_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                FLOAT_GETTER);
        parameterRowSecValueSetter = HostHandles.virtual(
                parameterRowClass,
                ParameterRowRepaintTarget.SET_PARAM2_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_SETTER_DESCRIPTOR,
                FLOAT_SETTER);

        singleRowParameterGetter = HostHandles.virtual(
                singleRowClass,
                ParameterRowRepaintTarget.GET_PARAMETER_METHOD,
                ParameterRowRepaintTarget.PARAM_SOURCE_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        singleRowBlockerGetter = HostHandles.virtual(
                singleRowClass,
                ParameterRowRepaintTarget.GET_BLOCKER_METHOD,
                ParameterRowRepaintTarget.BLOCKER_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        singleRowSelectedObjectsSetter = HostHandles.virtual(
                singleRowClass,
                ParameterRowRepaintTarget.SET_SELECTED_OBJECTS_METHOD,
                ParameterRowRepaintTarget.SET_SELECTED_OBJECTS_DESCRIPTOR,
                LIST_SETTER);
        singleRowMainWidgetGetter = HostHandles.virtual(
                singleRowClass,
                ParameterRowRepaintTarget.GET_MAIN_WIDGET_METHOD,
                ParameterRowRepaintTarget.WIDGET_GETTER_DESCRIPTOR,
                OBJECT_GETTER);

        doubleRowParameterGetter = HostHandles.virtual(
                doubleRowClass,
                ParameterRowRepaintTarget.GET_PARAMETER_METHOD,
                ParameterRowRepaintTarget.PARAM_SOURCE_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        doubleRowParameterSecGetter = HostHandles.virtual(
                doubleRowClass,
                ParameterRowRepaintTarget.GET_PARAMETER_SEC_METHOD,
                ParameterRowRepaintTarget.PARAM_SOURCE_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        doubleRowBlockerGetter = HostHandles.virtual(
                doubleRowClass,
                ParameterRowRepaintTarget.GET_BLOCKER_METHOD,
                ParameterRowRepaintTarget.BLOCKER_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        doubleRowCombinedEnableGetter = HostHandles.virtual(
                doubleRowClass,
                ParameterRowRepaintTarget.GET_COMBINED_ENABLE_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        doubleRowResize = HostHandles.virtual(
                doubleRowClass,
                ParameterRowRepaintTarget.RESIZE_METHOD,
                ParameterRowRepaintTarget.VOID_METHOD_DESCRIPTOR,
                VOID_CALL);
        doubleRowMainWidgetGetter = HostHandles.virtual(
                doubleRowClass,
                ParameterRowRepaintTarget.GET_MAIN_WIDGET_METHOD,
                ParameterRowRepaintTarget.WIDGET_GETTER_DESCRIPTOR,
                OBJECT_GETTER);

        groupRowParameterGroupGetter = HostHandles.virtual(
                groupRowClass,
                ParameterRowRepaintTarget.GET_PARAMETER_GROUP_METHOD,
                ParameterRowRepaintTarget.PARAMETER_GROUP_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        groupRowFoldingPaneGetter = HostHandles.virtual(
                groupRowClass,
                ParameterRowRepaintTarget.GET_FOLDING_PANE_METHOD,
                ParameterRowRepaintTarget.FOLDING_PANE_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        groupRowRowLayoutGetter = HostHandles.virtual(
                groupRowClass,
                ParameterRowRepaintTarget.GET_ROW_LAYOUT_METHOD,
                ParameterRowRepaintTarget.ROW_LAYOUT_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        groupRowMainWidgetGetter = HostHandles.virtual(
                groupRowClass,
                ParameterRowRepaintTarget.GET_MAIN_WIDGET_METHOD,
                ParameterRowRepaintTarget.WIDGET_GETTER_DESCRIPTOR,
                OBJECT_GETTER);

        foldingPaneIsExpandGetter = HostHandles.virtual(
                foldingPaneClass,
                ParameterRowRepaintTarget.IS_EXPAND_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        blockerIsBlockGetter = HostHandles.virtual(
                blockerClass,
                ParameterRowRepaintTarget.IS_BLOCK_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        widgetRepaint = HostHandles.virtual(
                widgetClass,
                ParameterRowRepaintTarget.REPAINT_METHOD,
                ParameterRowRepaintTarget.VOID_METHOD_DESCRIPTOR,
                VOID_CALL);
        widgetVisibleGetter = HostHandles.virtual(
                widgetClass,
                ParameterRowRepaintTarget.IS_VISIBLE_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        widgetComponentGetter = HostHandles.virtual(
                widgetClass,
                ParameterRowRepaintTarget.GET_JCOMPONENT_METHOD,
                ParameterRowRepaintTarget.JCOMPONENT_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        colorJColorGetter = HostHandles.virtual(
                colorClass,
                ParameterRowRepaintTarget.GET_JCOLOR_METHOD,
                ParameterRowRepaintTarget.JCOLOR_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        parameterGroupNameGetter = HostHandles.virtual(
                parameterGroupClass,
                ParameterRowRepaintTarget.GET_NAME_METHOD,
                ParameterRowRepaintTarget.STRING_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        parameterGroupVisibilityColorGetter = HostHandles.virtual(
                parameterGroupClass,
                ParameterRowRepaintTarget.GET_VISIBILITY_COLOR_METHOD,
                ParameterRowRepaintTarget.COLOR_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        parameterGroupFolderOpenedGetter = HostHandles.virtual(
                parameterGroupClass,
                ParameterRowRepaintTarget.GET_FOLDER_OPENED_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);

        modelParameterSetGetter = HostHandles.virtual(
                modelClass,
                ParameterRowRepaintTarget.GET_PARAMETER_SET_METHOD,
                ParameterRowRepaintTarget.PARAMETER_SET_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        modelObjectsGetter = HostHandles.virtual(
                modelClass,
                ParameterRowRepaintTarget.GET_ALL_OBJECTS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        parameterSetGet = HostHandles.virtual(
                parameterSetClass,
                ParameterRowRepaintTarget.PARAM_SET_GET_METHOD,
                ParameterRowRepaintTarget.PARAM_SET_GET_DESCRIPTOR,
                KEYED_GETTER);
        parameterValueGetter = HostHandles.virtual(
                parameterClass,
                ParameterRowRepaintTarget.GET_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                FLOAT_GETTER);

        sourceGuidGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceIdGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_ID_METHOD,
                ParameterRowRepaintTarget.PARAM_ID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceNameGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_NAME_METHOD,
                ParameterRowRepaintTarget.STRING_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceMinValueGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_MIN_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                FLOAT_GETTER);
        sourceMaxValueGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_MAX_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                FLOAT_GETTER);
        sourceDefaultValueGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_DEFAULT_VALUE_METHOD,
                ParameterRowRepaintTarget.FLOAT_GETTER_DESCRIPTOR,
                FLOAT_GETTER);
        sourceRepeatGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.IS_REPEAT_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        sourceCombinedGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_COMBINED_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        sourceMorphTargetGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.IS_MORPH_TARGET_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        sourceParamTypeGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_PARAM_TYPE_METHOD,
                ParameterRowRepaintTarget.PARAM_TYPE_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceVisibilityColorGetter = HostHandles.virtual(
                sourceClass,
                ParameterRowRepaintTarget.GET_VISIBILITY_COLOR_ALPHA50_METHOD,
                ParameterRowRepaintTarget.COLOR_GETTER_DESCRIPTOR,
                OBJECT_GETTER);

        controllableSourceGetter = HostHandles.virtual(
                controllableClass,
                ParameterRowRepaintTarget.GET_SOURCE_METHOD,
                ParameterRowRepaintTarget.CONTROLLABLE_SOURCE_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceKeyformGridGetter = HostHandles.virtual(
                controllableSourceClass,
                ParameterRowRepaintTarget.GET_KEYFORM_GRID_METHOD,
                ParameterRowRepaintTarget.KEYFORM_GRID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceExtendedKeyformGridGetter = HostHandles.virtual(
                controllableSourceClass,
                ParameterRowRepaintTarget.GET_EXT_KEYFORM_GRID_METHOD,
                ParameterRowRepaintTarget.KEYFORM_GRID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceMorphTargetSetGetter = HostHandles.virtual(
                controllableSourceClass,
                ParameterRowRepaintTarget.GET_MORPH_TARGET_SET_METHOD,
                ParameterRowRepaintTarget.MORPH_TARGET_SET_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        sourceExtendedMorphTargetSetGetter = HostHandles.virtual(
                controllableSourceClass,
                ParameterRowRepaintTarget.GET_EXT_MORPH_TARGET_SET_METHOD,
                ParameterRowRepaintTarget.EXT_MORPH_TARGET_SET_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        gridBindingsGetter = HostHandles.virtual(
                gridClass,
                ParameterRowRepaintTarget.GET_KEYFORM_BINDINGS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        bindingGuidGetter = HostHandles.virtual(
                bindingClass,
                ParameterRowRepaintTarget.GET_PARAMETER_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        bindingKeysGetter = HostHandles.virtual(
                bindingClass,
                ParameterRowRepaintTarget.GET_KEYS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        morphTargetsGetter = HostHandles.virtual(
                morphSetClass,
                ParameterRowRepaintTarget.GET_MORPH_TARGETS_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        morphTargetGuidGetter = HostHandles.virtual(
                morphTargetClass,
                ParameterRowRepaintTarget.GET_PARAMETER_GUID_METHOD,
                ParameterRowRepaintTarget.PARAM_GUID_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        morphTargetKeyValueGetter = HostHandles.virtual(
                morphTargetClass,
                ParameterRowRepaintTarget.GET_KEY_VALUE_METHOD,
                ParameterRowRepaintTarget.BOXED_FLOAT_GETTER_DESCRIPTOR,
                OBJECT_GETTER);

        documentEditModeGetter = HostHandles.virtual(
                modelingDocumentClass,
                ParameterRowRepaintTarget.GET_CURRENT_EDIT_MODE_METHOD,
                ParameterRowRepaintTarget.MODELING_EDIT_MODE_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        editModeEditingGetter = HostHandles.virtual(
                editModeClass,
                ParameterRowRepaintTarget.IS_EDITING_METHOD,
                ParameterRowRepaintTarget.BOOLEAN_GETTER_DESCRIPTOR,
                BOOLEAN_GETTER);
        editModeCurrentUndoGetter = HostHandles.virtual(
                editModeClass,
                ParameterRowRepaintTarget.GET_CURRENT_UNDO_METHOD,
                ParameterRowRepaintTarget.GROUP_UNDO_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        editModeLastModifiedGetter = HostHandles.virtual(
                editModeClass,
                ParameterRowRepaintTarget.GET_LAST_MODIFIED_TIME_MS_METHOD,
                ParameterRowRepaintTarget.LONG_GETTER_DESCRIPTOR,
                LONG_GETTER);
        editModeUndoManagerGetter = HostHandles.virtual(
                editModeClass,
                ParameterRowRepaintTarget.GET_UNDO_MANAGER_METHOD,
                ParameterRowRepaintTarget.UNDO_MANAGER_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        editModeLinkedUndoManagerGetter = HostHandles.virtual(
                editModeClass,
                ParameterRowRepaintTarget.GET_LINKED_UNDO_MANAGER_METHOD,
                ParameterRowRepaintTarget.UNDO_MANAGER_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
        undoManagerPositionGetter = HostHandles.virtual(
                undoManagerClass,
                ParameterRowRepaintTarget.GET_CURRENT_POS_METHOD,
                ParameterRowRepaintTarget.INT_GETTER_DESCRIPTOR,
                INT_GETTER);
        undoManagerListGetter = HostHandles.virtual(
                undoManagerClass,
                ParameterRowRepaintTarget.GET_UNDO_LIST_METHOD,
                ParameterRowRepaintTarget.LIST_GETTER_DESCRIPTOR,
                OBJECT_GETTER);
    }

    /** Returns the controller root row layout, or {@code null} when the palette is not built. */
    Object childrenBox(final Object controller) throws Throwable {
        return (Object) childrenBoxGetter.invokeExact(controller);
    }

    /** Returns the child widgets of one row layout. */
    List<?> rowChildren(final Object rowLayout) throws Throwable {
        return (List<?>) read(rowLayoutChildrenGetter, rowLayout);
    }

    /** Returns the flat list of active palette rows. */
    List<?> flatRows(final Object controller) throws Throwable {
        return (List<?>) read(rowListGetter, controller);
    }

    /** Returns the currently selected model controllable sources. */
    List<?> selectedObjects(final Object controller) throws Throwable {
        return (List<?>) read(selectedObjectsGetter, controller);
    }

    /** Returns the palette rows the controller reports as selected. */
    List<?> selectedPaletteRows(final Object controller) throws Throwable {
        return (List<?>) read(selectedRowsGetter, controller);
    }

    /** Returns whether the palette currently draws keyform markers. */
    boolean showKeyforms(final Object controller) throws Throwable {
        return (boolean) showKeyformsGetter.invokeExact(controller);
    }

    /**
     * Clears the transient highlight record and returns the parameter GUID it named.
     *
     * <p>The host clears the field as selection updates begin, so the read and the clear are one
     * operation: a caller that only read would see the same highlight on the next pass.</p>
     */
    Object consumeHighlightGuid(final Object controller) throws Throwable {
        final Object highlight = (Object) highlightGetter.invokeExact(controller);
        if (highlight == null) {
            return null;
        }
        highlightSetter.invokeExact(controller, (Object) null);
        return (Object) highlightGuidGetter.invokeExact(highlight);
    }

    /** Returns the model parameter set that holds the live parameter values. */
    Object parameterSet(final Object model) throws Throwable {
        return (Object) modelParameterSetGetter.invokeExact(model);
    }

    /** Returns every controllable object of the model, or {@code null} when the model has none. */
    List<?> modelObjects(final Object model) throws Throwable {
        return (List<?>) read(modelObjectsGetter, model);
    }

    /** Reads the reviewed facts of one parameter source. */
    ParameterSourceFacts readParameterSource(final Object source) throws Throwable {
        return new ParameterSourceFacts(
                (Object) sourceGuidGetter.invokeExact(source),
                (Object) sourceIdGetter.invokeExact(source),
                (String) read(sourceNameGetter, source),
                Float.floatToRawIntBits((float) sourceMinValueGetter.invokeExact(source)),
                Float.floatToRawIntBits((float) sourceMaxValueGetter.invokeExact(source)),
                Float.floatToRawIntBits((float) sourceDefaultValueGetter.invokeExact(source)),
                (boolean) sourceRepeatGetter.invokeExact(source),
                (boolean) sourceCombinedGetter.invokeExact(source),
                (boolean) sourceMorphTargetGetter.invokeExact(source),
                (Object) sourceParamTypeGetter.invokeExact(source),
                colorArgb((Object) sourceVisibilityColorGetter.invokeExact(source)));
    }

    /** Returns whether {@code candidate} is a model controllable object. */
    boolean isControllable(final Object candidate) {
        return controllableClass.isInstance(candidate);
    }

    /** Returns the controllable source of one model object. */
    Object controllableSource(final Object controllable) throws Throwable {
        return (Object) controllableSourceGetter.invokeExact(controllable);
    }

    /** Returns the primary keyform grid of one controllable source. */
    Object keyformGrid(final Object source) throws Throwable {
        return (Object) sourceKeyformGridGetter.invokeExact(source);
    }

    /** Returns the extended keyform grid of one controllable source. */
    Object extendedKeyformGrid(final Object source) throws Throwable {
        return (Object) sourceExtendedKeyformGridGetter.invokeExact(source);
    }

    /** Returns the primary morph target set of one controllable source. */
    Object morphTargetSet(final Object source) throws Throwable {
        return (Object) sourceMorphTargetSetGetter.invokeExact(source);
    }

    /** Returns the extended morph target set of one controllable source. */
    Object extendedMorphTargetSet(final Object source) throws Throwable {
        return (Object) sourceExtendedMorphTargetSetGetter.invokeExact(source);
    }

    /** Returns the keyform bindings of one grid, or {@code null} when the grid has none. */
    List<?> keyformBindings(final Object grid) throws Throwable {
        return (List<?>) read(gridBindingsGetter, grid);
    }

    /** Returns the parameter GUID of one keyform binding. */
    Object bindingGuid(final Object binding) throws Throwable {
        return (Object) bindingGuidGetter.invokeExact(binding);
    }

    /** Returns the key values of one keyform binding, or {@code null} when it has none. */
    List<?> bindingKeys(final Object binding) throws Throwable {
        return (List<?>) read(bindingKeysGetter, binding);
    }

    /** Returns the morph targets of one morph target set, or {@code null} when it has none. */
    List<?> morphTargets(final Object morphSet) throws Throwable {
        return (List<?>) read(morphTargetsGetter, morphSet);
    }

    /** Returns the parameter GUID of one morph target. */
    Object morphTargetGuid(final Object morphTarget) throws Throwable {
        return (Object) morphTargetGuidGetter.invokeExact(morphTarget);
    }

    /** Returns the boxed key value of one morph target. */
    Float morphTargetKeyValue(final Object morphTarget) throws Throwable {
        return (Float) read(morphTargetKeyValueGetter, morphTarget);
    }

    /** Returns the reviewed row shape of one palette child widget. */
    int rowKind(final Object widget) {
        if (singleRowClass.isInstance(widget)) {
            return ROW_KIND_SINGLE;
        }
        if (doubleRowClass.isInstance(widget)) {
            return ROW_KIND_DOUBLE;
        }
        if (groupRowClass.isInstance(widget)) {
            return ROW_KIND_GROUP;
        }
        if (widgetClass.isInstance(widget)) {
            return ROW_KIND_WIDGET;
        }
        return ROW_KIND_UNKNOWN;
    }

    /** Pushes the selected model objects into one single row; a {@code null} list keeps the row. */
    void applySelectedObjects(final Object row, final List<?> selectedObjects) throws Throwable {
        if (selectedObjects == null) {
            return;
        }
        singleRowSelectedObjectsSetter.invokeExact(row, selectedObjects);
    }

    /** Reads the parameter source, lock state and main widget of one single row. */
    SingleRowProbe readSingleRow(final Object row) throws Throwable {
        final Object blocker = (Object) singleRowBlockerGetter.invokeExact(row);
        final boolean locked = blocker != null && (boolean) blockerIsBlockGetter.invokeExact(blocker);
        final Object mainWidget = (Object) singleRowMainWidgetGetter.invokeExact(row);
        final Object paramSource = (Object) singleRowParameterGetter.invokeExact(row);
        return new SingleRowProbe(paramSource, mainWidget, locked);
    }

    /** Reads both parameter sources, lock state, combined state and main widget of one double row. */
    DoubleRowProbe readDoubleRow(final Object row) throws Throwable {
        final Object param2 = (Object) doubleRowParameterSecGetter.invokeExact(row);
        final Object mainWidget = (Object) doubleRowMainWidgetGetter.invokeExact(row);
        final Object blocker = (Object) doubleRowBlockerGetter.invokeExact(row);
        final boolean locked = blocker != null && (boolean) blockerIsBlockGetter.invokeExact(blocker);
        final boolean combinedEnable = (boolean) doubleRowCombinedEnableGetter.invokeExact(row);
        final Object param1 = (Object) doubleRowParameterGetter.invokeExact(row);
        return new DoubleRowProbe(param1, param2, mainWidget, locked, combinedEnable);
    }

    /** Reads the folder state, group metadata and nested children of one group row. */
    GroupRowProbe readGroupRow(final Object row) throws Throwable {
        final Object mainWidget = (Object) groupRowMainWidgetGetter.invokeExact(row);
        final Object foldingPane = (Object) groupRowFoldingPaneGetter.invokeExact(row);
        final boolean expanded = foldingPane != null && (boolean) foldingPaneIsExpandGetter.invokeExact(foldingPane);
        final Object group = (Object) groupRowParameterGroupGetter.invokeExact(row);
        final boolean hasGroupData = group != null;
        boolean folderOpened = false;
        String groupName = null;
        int groupColorArgb = NULL_COLOR_ARGB;
        if (hasGroupData) {
            folderOpened = (boolean) parameterGroupFolderOpenedGetter.invokeExact(group);
            groupName = (String) read(parameterGroupNameGetter, group);
            groupColorArgb = colorArgb((Object) parameterGroupVisibilityColorGetter.invokeExact(group));
        }
        final Object rowLayout = (Object) groupRowRowLayoutGetter.invokeExact(row);
        final List<?> children = rowLayout == null ? null : (List<?>) read(rowLayoutChildrenGetter, rowLayout);
        return new GroupRowProbe(children, mainWidget, expanded, hasGroupData, groupName, folderOpened, groupColorArgb);
    }

    /** Reads the visibility, background colour and bounds that decide one widget's paint state. */
    WidgetVisualFacts readWidgetVisuals(final Object widget) throws Throwable {
        final boolean visible = (boolean) widgetVisibleGetter.invokeExact(widget);
        final JComponent component = (JComponent) read(widgetComponentGetter, widget);
        if (component == null) {
            return new WidgetVisualFacts(visible, false, NULL_COLOR_ARGB, 0, 0, 0);
        }
        final Color background = component.getBackground();
        final int backgroundArgb = background == null ? NULL_COLOR_ARGB : background.getRGB();
        return new WidgetVisualFacts(
                visible, true, backgroundArgb, component.getY(), component.getWidth(), component.getHeight());
    }

    /** Returns whether one row answers the reviewed parameter row interface. */
    boolean isParameterRow(final Object row) {
        return parameterRowClass.isInstance(row);
    }

    /** Returns the primary or secondary parameter ID of one parameter row. */
    Object parameterRowId(final Object row, final boolean secondary) throws Throwable {
        return secondary
                ? (Object) parameterRowSecIdGetter.invokeExact(row)
                : (Object) parameterRowIdGetter.invokeExact(row);
    }

    /** Returns the primary or secondary displayed value of one parameter row. */
    float parameterRowValue(final Object row, final boolean secondary) throws Throwable {
        return secondary
                ? (float) parameterRowSecValueGetter.invokeExact(row)
                : (float) parameterRowValueGetter.invokeExact(row);
    }

    /** Writes the primary or secondary displayed value of one parameter row. */
    void setParameterRowValue(final Object row, final boolean secondary, final float value) throws Throwable {
        if (secondary) {
            parameterRowSecValueSetter.invokeExact(row, value);
            return;
        }
        parameterRowValueSetter.invokeExact(row, value);
    }

    /** Returns the live runtime parameter for one parameter ID, or {@code null} when absent. */
    Object parameter(final Object paramSet, final Object paramId) throws Throwable {
        return (Object) parameterSetGet.invokeExact(paramSet, paramId);
    }

    /** Returns the live value of one runtime parameter. */
    float parameterValue(final Object parameter) throws Throwable {
        return (float) parameterValueGetter.invokeExact(parameter);
    }

    /** Resizes one double row to its current keyform layout. */
    void resizeDoubleRow(final Object row) throws Throwable {
        doubleRowResize.invokeExact(row);
    }

    /** Repaints one palette widget. */
    void repaintWidget(final Object widget) throws Throwable {
        widgetRepaint.invokeExact(widget);
    }

    /**
     * Reads the facts that decide whether the keyform cache still describes the document.
     *
     * <p>Returns {@code null} when the document, the model, the edit mode, an undo manager or an
     * undo cursor is not the reviewed shape. A {@code null} answer forbids elision: the caller must
     * rehash the keyforms, because it cannot prove the cache is current.</p>
     */
    EditStateProbe readEditState(final Object document, final Object model) throws Throwable {
        if (!modelingDocumentClass.isInstance(document) || !modelClass.isInstance(model)) {
            return null;
        }
        final List<?> allObjects = (List<?>) read(modelObjectsGetter, model);
        if (allObjects == null) {
            return null;
        }
        final long allObjectsCount = allObjects.size();
        final Object editMode = (Object) documentEditModeGetter.invokeExact(document);
        if (!editModeClass.isInstance(editMode)) {
            return null;
        }
        final boolean editing = (boolean) editModeEditingGetter.invokeExact(editMode);
        final Object currentUndo = (Object) editModeCurrentUndoGetter.invokeExact(editMode);
        final long lastModifiedTimeMs = (long) editModeLastModifiedGetter.invokeExact(editMode);
        final Object undoManager = (Object) editModeUndoManagerGetter.invokeExact(editMode);
        if (undoManager == null) {
            return null;
        }
        final UndoState undo = readUndoState(undoManager);
        if (undo == null) {
            return null;
        }
        final Object linkedUndoManager = (Object) editModeLinkedUndoManagerGetter.invokeExact(editMode);
        if (linkedUndoManager != null && linkedUndoManager != undoManager) {
            final UndoState linked = readUndoState(linkedUndoManager);
            if (linked == null) {
                return null;
            }
            return new EditStateProbe(
                    editMode,
                    undoManager,
                    undo.topUndo(),
                    linkedUndoManager,
                    linked.topUndo(),
                    editing,
                    currentUndo,
                    lastModifiedTimeMs,
                    allObjectsCount,
                    undo.position(),
                    undo.size(),
                    linked.position(),
                    linked.size());
        }
        return new EditStateProbe(
                editMode,
                undoManager,
                undo.topUndo(),
                null,
                null,
                editing,
                currentUndo,
                lastModifiedTimeMs,
                allObjectsCount,
                undo.position(),
                undo.size(),
                0L,
                0L);
    }

    /** Reads the cursor and top entry of one undo manager, or {@code null} when the cursor is out of range. */
    private UndoState readUndoState(final Object undoManager) throws Throwable {
        final int position = (int) undoManagerPositionGetter.invokeExact(undoManager);
        final List<?> undoList = (List<?>) read(undoManagerListGetter, undoManager);
        if (undoList == null) {
            return null;
        }
        final int size = undoList.size();
        if (position < 0 || position > size) {
            return null;
        }
        final Object topUndo = position > 0 ? undoList.get(position - 1) : null;
        return new UndoState(topUndo, position, size);
    }

    /** Returns the ARGB of one host colour, or {@code 0} when the colour or its AWT form is absent. */
    private int colorArgb(final Object color) throws Throwable {
        if (color == null) {
            return NULL_COLOR_ARGB;
        }
        final Color jColor = (Color) read(colorJColorGetter, color);
        return jColor == null ? NULL_COLOR_ARGB : jColor.getRGB();
    }

    /**
     * Reads one {@code (Object)Object} accessor.
     *
     * <p>{@link MethodHandle#invokeExact} types its result from the call site, so the value is read
     * as {@code Object} here and cast by the caller that knows the reviewed return type.</p>
     */
    private static Object read(final MethodHandle handle, final Object receiver) throws Throwable {
        return (Object) handle.invokeExact(receiver);
    }

    /** One single row's parameter source, main widget and lock state. */
    record SingleRowProbe(Object paramSource, Object mainWidget, boolean locked) {}

    /** One double row's two parameter sources, main widget, lock state and combined state. */
    record DoubleRowProbe(
            Object primary, Object secondary, Object mainWidget, boolean locked, boolean combinedEnable) {}

    /** One group row's nested children, main widget, folder state and group metadata. */
    record GroupRowProbe(
            List<?> children,
            Object mainWidget,
            boolean expanded,
            boolean hasGroupData,
            String groupName,
            boolean folderOpened,
            int groupColorArgb) {}

    /** One widget's paint-relevant state: visibility, background colour and bounds. */
    record WidgetVisualFacts(boolean visible, boolean hasComponent, int backgroundArgb, int y, int width, int height) {}

    /** The hashed facts of one parameter source; {@code guid} also keys the keyform hash map. */
    record ParameterSourceFacts(
            Object guid,
            Object id,
            String name,
            int minBits,
            int maxBits,
            int defaultBits,
            boolean repeat,
            boolean combined,
            boolean morphTarget,
            Object paramType,
            int visibilityColorArgb) {}

    /** One undo manager's cursor, stack size and top entry. */
    private record UndoState(Object topUndo, int position, int size) {}

    /** One document's edit-state identity: the objects and counters that gate keyform elision. */
    record EditStateProbe(
            Object editMode,
            Object undoManager,
            Object topUndo,
            Object linkedUndoManager,
            Object linkedTopUndo,
            boolean editing,
            Object currentUndo,
            long lastModifiedTimeMs,
            long allObjectsCount,
            long undoPos,
            long undoSize,
            long linkedUndoPos,
            long linkedUndoSize) {}
}
