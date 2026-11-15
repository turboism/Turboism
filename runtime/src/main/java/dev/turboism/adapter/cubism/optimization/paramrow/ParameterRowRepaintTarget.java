package dev.turboism.adapter.cubism.optimization.paramrow;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Defines reviewed host classes, fields, and method descriptors for incremental Parameter Palette
 * row synchronization and repaint elision.
 */
public record ParameterRowRepaintTarget(
        HostArtifactDigest digest,
        String version,
        String controllerOwner,
        String highlightField,
        String showKeyformsMethod) {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.parameterRowRepaint";

    /** All reviewed Cubism 5.0.00 through 5.3.04 versions carrying the palette controller. */
    public static final Set<String> SUPPORTED_VERSIONS = ReviewedVersions.since("5.0.00");

    private static final Set<String> LINE_5_0_EARLY = ReviewedVersions.between("5.0.00", "5.0.02");
    private static final Set<String> LINE_5_0_LATE = ReviewedVersions.between("5.0.03", "5.0.06");
    private static final Set<String> LINE_5_1 = ReviewedVersions.line("5.1");

    private static final String CONTROLLER_OWNER_5_0_EARLY = "com/live2d/cubism/view/palette/parameter/k";
    private static final String CONTROLLER_OWNER_5_0_TO_5_1 = "com/live2d/cubism/view/palette/parameter/m";
    private static final String CONTROLLER_OWNER_5_2_PLUS = "com/live2d/cubism/view/palette/parameter/s";

    private static final String HIGHLIGHT_FIELD_5_0 = "z";
    private static final String HIGHLIGHT_FIELD_5_1_PLUS = "y";

    private static final String SHOW_KEYFORMS_METHOD_5_0 = "q";
    private static final String SHOW_KEYFORMS_METHOD_5_1_PLUS = "o";

    /** Controller palette refresh entry point. */
    public static final String UPDATE_METHOD = "a";

    /** Descriptor of the controller palette refresh entry point. */
    public static final String UPDATE_DESCRIPTOR = "(Z)V";

    /** Controller selection profiling marker string. */
    public static final String SELECTION_LOG_MARKER = "Update Parameter Selection";

    /** Controller structure profiling marker string. */
    public static final String STRUCTURE_LOG_MARKER = "Update Parameter Structure";

    /** Controller per-parameter value update helper method name. */
    public static final String VALUE_SYNC_METHOD = "a";

    /** Descriptor of the controller per-parameter value update helper. */
    public static final String VALUE_SYNC_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/modeling/CModelingDocument;Lcom/live2d/cubism/doc/model/CModel;)V";

    /** Controller field storing the root {@code RowLayoutVBox}. */
    public static final String CHILDREN_BOX_FIELD = "d";

    /** Field descriptor of {@link #CHILDREN_BOX_FIELD}. */
    public static final String CHILDREN_BOX_DESCRIPTOR = "Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;";

    /** Controller field storing the flat {@code CArrayList} of active parameter rows. */
    public static final String ROW_LIST_FIELD = "h";

    /** Field descriptor of {@link #ROW_LIST_FIELD}. */
    public static final String ROW_LIST_DESCRIPTOR = "Lcom/live2d/type/CArrayList;";

    /** Controller field storing the currently selected model controllable sources. */
    public static final String SELECTED_OBJECTS_FIELD = "n";

    /** Field descriptor of {@link #SELECTED_OBJECTS_FIELD}. */
    public static final String SELECTED_OBJECTS_DESCRIPTOR = "Ljava/util/List;";

    /** Highlight record suffix on the controller owner. */
    public static final String HIGHLIGHT_INNER_SUFFIX = "$b";

    /** Parameter GUID accessor on the highlight record. */
    public static final String HIGHLIGHT_GUID_METHOD = "a";

    /** Selected palette rows accessor on the controller. */
    public static final String GET_SELECTED_ROWS_METHOD = "g";

    /** List getter descriptor. */
    public static final String LIST_GETTER_DESCRIPTOR = "()Ljava/util/List;";

    /** Boolean getter descriptor. */
    public static final String BOOLEAN_GETTER_DESCRIPTOR = "()Z";

    /** Float getter descriptor. */
    public static final String FLOAT_GETTER_DESCRIPTOR = "()F";

    /** Float setter descriptor. */
    public static final String FLOAT_SETTER_DESCRIPTOR = "(F)V";

    /** Void no-arg method descriptor. */
    public static final String VOID_METHOD_DESCRIPTOR = "()V";

    /** String getter descriptor. */
    public static final String STRING_GETTER_DESCRIPTOR = "()Ljava/lang/String;";

    /** Vertical row layout owner. */
    public static final String ROW_LAYOUT_OWNER = "com/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox";

    /** Children list accessor on {@code RowLayoutVBox}. */
    public static final String GET_CHILDREN_METHOD = "getChildren";

    /** Parameter row value interface owner. */
    public static final String PARAM_ROW_INTERFACE = "com/live2d/cubism/view/palette/parameter/ui/B";

    /** Primary parameter ID accessor on {@code B}. */
    public static final String GET_PARAM1_ID_METHOD = "getParameter1_id";

    /** Secondary parameter ID accessor on {@code B}. */
    public static final String GET_PARAM2_ID_METHOD = "getParameter2_id";

    /** Primary row current value getter on {@code B}. */
    public static final String GET_PARAM1_VALUE_METHOD = "getParam_currentValue";

    /** Primary row current value setter on {@code B}. */
    public static final String SET_PARAM1_VALUE_METHOD = "setParam_currentValue";

    /** Secondary row current value getter on {@code B}. */
    public static final String GET_PARAM2_VALUE_METHOD = "getParamSec_currentValue";

    /** Secondary row current value setter on {@code B}. */
    public static final String SET_PARAM2_VALUE_METHOD = "setParamSec_currentValue";

    /** Single parameter row widget owner. */
    public static final String SINGLE_ROW_OWNER =
            "com/live2d/cubism/view/palette/parameter/ui/SingleParameterRowWidget";

    /** Double parameter row widget owner. */
    public static final String DOUBLE_ROW_OWNER =
            "com/live2d/cubism/view/palette/parameter/ui/DoubleParameterRowWidget";

    /** Parameter group folder widget owner. */
    public static final String GROUP_ROW_OWNER = "com/live2d/cubism/view/palette/parameter/ui/ParameterGroupRowWidget";

    /** Base widget owner. */
    public static final String WIDGET_OWNER = "com/live2d/ui/CWidget";

    /** Main container getter on row widgets. */
    public static final String GET_MAIN_WIDGET_METHOD = "getMainWidget";

    /** Descriptor returning {@code CWidget}. */
    public static final String WIDGET_GETTER_DESCRIPTOR = "()Lcom/live2d/ui/CWidget;";

    /** Selection object setter on {@code SingleParameterRowWidget}. */
    public static final String SET_SELECTED_OBJECTS_METHOD = "setSelectedObjects$cubism";

    /** Descriptor of {@code setSelectedObjects$cubism(List)}. */
    public static final String SET_SELECTED_OBJECTS_DESCRIPTOR = "(Ljava/util/List;)V";

    /** Layout resize method on {@code DoubleParameterRowWidget}. */
    public static final String RESIZE_METHOD = "resize";

    /** Primary parameter source accessor on single and double rows. */
    public static final String GET_PARAMETER_METHOD = "getParameter";

    /** Secondary parameter source accessor on {@code DoubleParameterRowWidget}. */
    public static final String GET_PARAMETER_SEC_METHOD = "getParameterSec";

    /** Parameter source descriptor. */
    public static final String PARAM_SOURCE_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/param/CParameterSource;";

    /** Blocker control accessor on single and double rows. */
    public static final String GET_BLOCKER_METHOD = "getBlocker";

    /** Blocker getter descriptor. */
    public static final String BLOCKER_GETTER_DESCRIPTOR = "()Lcom/live2d/ui/control/CBlocker;";

    /** Combined mode toggle accessor on {@code DoubleParameterRowWidget}. */
    public static final String GET_COMBINED_ENABLE_METHOD = "getCombinedEnable";

    /** Parameter group data accessor on {@code ParameterGroupRowWidget}. */
    public static final String GET_PARAMETER_GROUP_METHOD = "getParameterGroup";

    /** Parameter group getter descriptor. */
    public static final String PARAMETER_GROUP_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/param/group/CParameterGroup;";

    /** Folding pane accessor on {@code ParameterGroupRowWidget}. */
    public static final String GET_FOLDING_PANE_METHOD = "getFoldingPane";

    /** Folding pane getter descriptor. */
    public static final String FOLDING_PANE_GETTER_DESCRIPTOR = "()Lcom/live2d/ui/container/CFoldingPane;";

    /** Child row layout accessor on {@code ParameterGroupRowWidget}. */
    public static final String GET_ROW_LAYOUT_METHOD = "getRowLayout";

    /** Row layout getter descriptor. */
    public static final String ROW_LAYOUT_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/view/palette/parameter/ui/RowLayoutVBox;";

    /** Folding pane owner. */
    public static final String FOLDING_PANE_OWNER = "com/live2d/ui/container/CFoldingPane";

    /** Expand state accessor on {@code CFoldingPane}. */
    public static final String IS_EXPAND_METHOD = "isExpand";

    /** Lock blocker owner. */
    public static final String BLOCKER_OWNER = "com/live2d/ui/control/CBlocker";

    /** Block state accessor on {@code CBlocker}. */
    public static final String IS_BLOCK_METHOD = "isBlock";

    /** Widget repaint trigger on {@code CWidget}. */
    public static final String REPAINT_METHOD = "repaint";

    /** Widget visibility getter on {@code CWidget}. */
    public static final String IS_VISIBLE_METHOD = "isVisible";

    /** Swing component getter on {@code CWidget}. */
    public static final String GET_JCOMPONENT_METHOD = "getJComponent";

    /** Descriptor of {@code getJComponent()}. */
    public static final String JCOMPONENT_GETTER_DESCRIPTOR = "()Ljavax/swing/JComponent;";

    /** Color type owner. */
    public static final String COLOR_OWNER = "com/live2d/type/CColor";

    /** Color getter descriptor. */
    public static final String COLOR_GETTER_DESCRIPTOR = "()Lcom/live2d/type/CColor;";

    /** AWT color conversion accessor on {@code CColor}. */
    public static final String GET_JCOLOR_METHOD = "getJColor";

    /** Descriptor of {@code getJColor()}. */
    public static final String JCOLOR_GETTER_DESCRIPTOR = "()Ljava/awt/Color;";

    /** Parameter group model owner. */
    public static final String PARAMETER_GROUP_OWNER = "com/live2d/cubism/doc/model/param/group/CParameterGroup";

    /** Name getter on parameters and parameter groups. */
    public static final String GET_NAME_METHOD = "getName";

    /** Group visibility color getter on {@code CParameterGroup}. */
    public static final String GET_VISIBILITY_COLOR_METHOD = "getVisibilityColor";

    /** Folder expansion getter on {@code CParameterGroup}. */
    public static final String GET_FOLDER_OPENED_METHOD = "getFolderIsOpened";

    /** Model owner. */
    public static final String MODEL_OWNER = "com/live2d/cubism/doc/model/CModel";

    /** Parameter set accessor on {@code CModel}. */
    public static final String GET_PARAMETER_SET_METHOD = "getParameterSet";

    /** Parameter set getter descriptor. */
    public static final String PARAMETER_SET_GETTER_DESCRIPTOR = "()Lcom/live2d/cubism/doc/model/param/CParameterSet;";

    /** All controllable model objects accessor on {@code CModel}. */
    public static final String GET_ALL_OBJECTS_METHOD = "getAllObjects";

    /** Parameter set owner. */
    public static final String PARAMETER_SET_OWNER = "com/live2d/cubism/doc/model/param/CParameterSet";

    /** Runtime parameter lookup method on {@code CParameterSet}. */
    public static final String PARAM_SET_GET_METHOD = "get";

    /** Descriptor of {@code CParameterSet.get(CParameterId)}. */
    public static final String PARAM_SET_GET_DESCRIPTOR =
            "(Lcom/live2d/cubism/doc/model/id/CParameterId;)Lcom/live2d/cubism/doc/model/param/CParameter;";

    /** Runtime parameter owner. */
    public static final String PARAMETER_OWNER = "com/live2d/cubism/doc/model/param/CParameter";

    /** Parameter ID owner. */
    public static final String PARAMETER_ID_OWNER = "com/live2d/cubism/doc/model/id/CParameterId";

    /** Parameter value accessor on {@code CParameter}. */
    public static final String GET_VALUE_METHOD = "getValue";

    /** Parameter source owner. */
    public static final String PARAMETER_SOURCE_OWNER = "com/live2d/cubism/doc/model/param/CParameterSource";

    /** Minimum value getter on {@code CParameterSource}. */
    public static final String GET_MIN_VALUE_METHOD = "getMinValue";

    /** Maximum value getter on {@code CParameterSource}. */
    public static final String GET_MAX_VALUE_METHOD = "getMaxValue";

    /** Default value getter on {@code CParameterSource}. */
    public static final String GET_DEFAULT_VALUE_METHOD = "getDefaultValue";

    /** Repeat toggle getter on {@code CParameterSource}. */
    public static final String IS_REPEAT_METHOD = "isRepeat";

    /** Combined state getter on {@code CParameterSource}. */
    public static final String GET_COMBINED_METHOD = "getCombined";

    /** Morph target flag getter on {@code CParameterSource}. */
    public static final String IS_MORPH_TARGET_METHOD = "isMorphTarget";

    /** Parameter type getter on {@code CParameterSource}. */
    public static final String GET_PARAM_TYPE_METHOD = "getParamType";

    /** Descriptor of {@code CParameterSource.getParamType()}. */
    public static final String PARAM_TYPE_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/param/CParameterSource$Type;";

    /** Parameter GUID accessor on {@code CParameterSource}. */
    public static final String GET_GUID_METHOD = "getGuid";

    /** Parameter GUID getter descriptor. */
    public static final String PARAM_GUID_GETTER_DESCRIPTOR = "()Lcom/live2d/type/CParameterGuid;";

    /** Parameter ID accessor on {@code CParameterSource}. */
    public static final String GET_ID_METHOD = "getId";

    /** Parameter ID getter descriptor. */
    public static final String PARAM_ID_GETTER_DESCRIPTOR = "()Lcom/live2d/cubism/doc/model/id/CParameterId;";

    /** Translucent visibility color getter on {@code CParameterSource}. */
    public static final String GET_VISIBILITY_COLOR_ALPHA50_METHOD = "getVisibilityColorAlpha50";

    /** Model controllable runtime object owner. */
    public static final String CONTROLLABLE_INTERFACE = "com/live2d/cubism/doc/model/ACParameterControllable";

    /** Source accessor on {@code ACParameterControllable}. */
    public static final String GET_SOURCE_METHOD = "getSource";

    /** Controllable source getter descriptor. */
    public static final String CONTROLLABLE_SOURCE_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/ACParameterControllableSource;";

    /** Model controllable source owner. */
    public static final String CONTROLLABLE_SOURCE_OWNER = "com/live2d/cubism/doc/model/ACParameterControllableSource";

    /** Primary keyform grid accessor on {@code ACParameterControllableSource}. */
    public static final String GET_KEYFORM_GRID_METHOD = "getKeyformGridSource";

    /** Extended keyform grid accessor on {@code ACParameterControllableSource}. */
    public static final String GET_EXT_KEYFORM_GRID_METHOD = "getExtendedKeyformGridSourceIfAvailable";

    /** Keyform grid getter descriptor. */
    public static final String KEYFORM_GRID_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/interpolator/KeyformGridSource;";

    /** Morph target set accessor on {@code ACParameterControllableSource}. */
    public static final String GET_MORPH_TARGET_SET_METHOD = "getKeyformMorphTargetSet";

    /** Morph target set getter descriptor. */
    public static final String MORPH_TARGET_SET_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/morphTarget/KeyFormMorphTargetSet;";

    /** Extended morph target set accessor on {@code ACParameterControllableSource}. */
    public static final String GET_EXT_MORPH_TARGET_SET_METHOD = "getExtendedKeyformMorphTargetSetIfAvailable";

    /** Extended morph target set getter descriptor. */
    public static final String EXT_MORPH_TARGET_SET_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/model/morphTarget/extendedInterpolation/ExtendedKeyFormMorphTargetSet;";

    /** Keyform grid owner. */
    public static final String KEYFORM_GRID_SOURCE_OWNER = "com/live2d/cubism/doc/model/interpolator/KeyformGridSource";

    /** Keyform bindings list accessor on {@code KeyformGridSource}. */
    public static final String GET_KEYFORM_BINDINGS_METHOD = "getKeyformBindings";

    /** Keyform binding owner. */
    public static final String KEYFORM_BINDING_SOURCE_OWNER =
            "com/live2d/cubism/doc/model/interpolator/KeyformBindingSource";

    /** Parameter GUID accessor on {@code KeyformBindingSource} and {@code IKeyFormMorphTarget}. */
    public static final String GET_PARAMETER_GUID_METHOD = "getParameterGuid";

    /** Key values list accessor on {@code KeyformBindingSource}. */
    public static final String GET_KEYS_METHOD = "getKeys";

    /** Morph target set interface owner. */
    public static final String MORPH_TARGET_SET_OWNER =
            "com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTargetSet";

    /** Morph targets list accessor on {@code IKeyFormMorphTargetSet}. */
    public static final String GET_MORPH_TARGETS_METHOD = "getMorphTargets";

    /** Morph target item interface owner. */
    public static final String MORPH_TARGET_OWNER = "com/live2d/cubism/doc/model/morphTarget/IKeyFormMorphTarget";

    /** Morph key value accessor on {@code IKeyFormMorphTarget}. */
    public static final String GET_KEY_VALUE_METHOD = "getKeyValue";

    /** Boxed float getter descriptor. */
    public static final String BOXED_FLOAT_GETTER_DESCRIPTOR = "()Ljava/lang/Float;";

    /** Modeling document owner. */
    public static final String MODELING_DOCUMENT_OWNER = "com/live2d/cubism/doc/modeling/CModelingDocument";

    /** Modeling edit mode base class owner. */
    public static final String EDIT_MODE_BASE_OWNER = "com/live2d/cubism/doc/modeling/CModelingEditMode_Base";

    /** Abstract edit mode superclass owner. */
    public static final String EDIT_MODE_OWNER = "com/live2d/cubism/doc/ACEditMode";

    /** Undo manager owner. */
    public static final String UNDO_MANAGER_OWNER = "com/live2d/undo/CUndoManager";

    /** Group undo owner. */
    public static final String GROUP_UNDO_OWNER = "com/live2d/undo/GroupUndo";

    /** Current edit mode getter on {@code CModelingDocument}. */
    public static final String GET_CURRENT_EDIT_MODE_METHOD = "getCurrentEditMode";

    /** Descriptor of {@code CModelingDocument.getCurrentEditMode()}. */
    public static final String MODELING_EDIT_MODE_GETTER_DESCRIPTOR =
            "()Lcom/live2d/cubism/doc/modeling/CModelingEditMode_Base;";

    /** Active transaction flag getter on {@code ACEditMode}. */
    public static final String IS_EDITING_METHOD = "isEditing";

    /** In-flight group undo getter on {@code ACEditMode}. */
    public static final String GET_CURRENT_UNDO_METHOD = "getCurrentUndo";

    /** Descriptor of {@code ACEditMode.getCurrentUndo()}. */
    public static final String GROUP_UNDO_GETTER_DESCRIPTOR = "()Lcom/live2d/undo/GroupUndo;";

    /** Last modification timestamp getter on {@code ACEditMode}. */
    public static final String GET_LAST_MODIFIED_TIME_MS_METHOD = "getLastModifiedTimeMs";

    /** Long no-arg getter descriptor. */
    public static final String LONG_GETTER_DESCRIPTOR = "()J";

    /** Primary undo manager getter on {@code ACEditMode}. */
    public static final String GET_UNDO_MANAGER_METHOD = "getUndoManager";

    /** Linked undo manager getter on {@code ACEditMode}. */
    public static final String GET_LINKED_UNDO_MANAGER_METHOD = "getLinkedUndoManager";

    /** Undo manager getter descriptor. */
    public static final String UNDO_MANAGER_GETTER_DESCRIPTOR = "()Lcom/live2d/undo/CUndoManager;";

    /** Undo cursor position getter on {@code CUndoManager}. */
    public static final String GET_CURRENT_POS_METHOD = "getCurrentPos";

    /** Integer no-arg getter descriptor. */
    public static final String INT_GETTER_DESCRIPTOR = "()I";

    /** Undo stack list getter on {@code CUndoManager}. */
    public static final String GET_UNDO_LIST_METHOD = "getUndoList";

    public ParameterRowRepaintTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(controllerOwner, "controllerOwner");
        Objects.requireNonNull(highlightField, "highlightField");
        Objects.requireNonNull(showKeyformsMethod, "showKeyformsMethod");
    }

    /** Resolves the reviewed target for an admitted host artifact digest. */
    public static Optional<ParameterRowRepaintTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        return ReviewedHostArtifacts.cubismVersionOf(digest)
                .filter(SUPPORTED_VERSIONS::contains)
                .map(version -> targetForVersion(digest, version));
    }

    /** Returns every supported reviewed target in version order. */
    public static List<ParameterRowRepaintTarget> all() {
        return ReviewedHostArtifacts.all().stream()
                .flatMap(artifact -> of(artifact).stream())
                .toList();
    }

    private static ParameterRowRepaintTarget targetForVersion(final HostArtifactDigest digest, final String version) {
        if (LINE_5_0_EARLY.contains(version)) {
            return new ParameterRowRepaintTarget(
                    digest, version, CONTROLLER_OWNER_5_0_EARLY, HIGHLIGHT_FIELD_5_0, SHOW_KEYFORMS_METHOD_5_0);
        }
        if (LINE_5_0_LATE.contains(version)) {
            return new ParameterRowRepaintTarget(
                    digest, version, CONTROLLER_OWNER_5_0_TO_5_1, HIGHLIGHT_FIELD_5_0, SHOW_KEYFORMS_METHOD_5_0);
        }
        if (LINE_5_1.contains(version)) {
            return new ParameterRowRepaintTarget(
                    digest,
                    version,
                    CONTROLLER_OWNER_5_0_TO_5_1,
                    HIGHLIGHT_FIELD_5_1_PLUS,
                    SHOW_KEYFORMS_METHOD_5_1_PLUS);
        }
        return new ParameterRowRepaintTarget(
                digest, version, CONTROLLER_OWNER_5_2_PLUS, HIGHLIGHT_FIELD_5_1_PLUS, SHOW_KEYFORMS_METHOD_5_1_PLUS);
    }

    /** Returns the descriptor of the highlight field on {@link #controllerOwner}. */
    public String highlightFieldDescriptor() {
        return "L" + controllerOwner + HIGHLIGHT_INNER_SUFFIX + ";";
    }

    /** Returns the internal class name of the highlight record inner class. */
    public String highlightInnerOwner() {
        return controllerOwner + HIGHLIGHT_INNER_SUFFIX;
    }

    /** Returns all host methods verified before arming the weave. */
    public List<MethodDep> methodDependencies() {
        return List.of(
                new MethodDep(controllerOwner, UPDATE_METHOD, UPDATE_DESCRIPTOR),
                new MethodDep(controllerOwner, VALUE_SYNC_METHOD, VALUE_SYNC_DESCRIPTOR),
                new MethodDep(controllerOwner, GET_SELECTED_ROWS_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(controllerOwner, showKeyformsMethod, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(highlightInnerOwner(), HIGHLIGHT_GUID_METHOD, PARAM_GUID_GETTER_DESCRIPTOR),
                new MethodDep(ROW_LAYOUT_OWNER, GET_CHILDREN_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(PARAM_ROW_INTERFACE, GET_PARAM1_ID_METHOD, PARAM_ID_GETTER_DESCRIPTOR),
                new MethodDep(PARAM_ROW_INTERFACE, GET_PARAM1_VALUE_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(PARAM_ROW_INTERFACE, SET_PARAM1_VALUE_METHOD, FLOAT_SETTER_DESCRIPTOR),
                new MethodDep(PARAM_ROW_INTERFACE, GET_PARAM2_ID_METHOD, PARAM_ID_GETTER_DESCRIPTOR),
                new MethodDep(PARAM_ROW_INTERFACE, GET_PARAM2_VALUE_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(PARAM_ROW_INTERFACE, SET_PARAM2_VALUE_METHOD, FLOAT_SETTER_DESCRIPTOR),
                new MethodDep(SINGLE_ROW_OWNER, GET_PARAMETER_METHOD, PARAM_SOURCE_GETTER_DESCRIPTOR),
                new MethodDep(SINGLE_ROW_OWNER, GET_BLOCKER_METHOD, BLOCKER_GETTER_DESCRIPTOR),
                new MethodDep(SINGLE_ROW_OWNER, SET_SELECTED_OBJECTS_METHOD, SET_SELECTED_OBJECTS_DESCRIPTOR),
                new MethodDep(SINGLE_ROW_OWNER, GET_MAIN_WIDGET_METHOD, WIDGET_GETTER_DESCRIPTOR),
                new MethodDep(DOUBLE_ROW_OWNER, GET_PARAMETER_METHOD, PARAM_SOURCE_GETTER_DESCRIPTOR),
                new MethodDep(DOUBLE_ROW_OWNER, GET_PARAMETER_SEC_METHOD, PARAM_SOURCE_GETTER_DESCRIPTOR),
                new MethodDep(DOUBLE_ROW_OWNER, GET_BLOCKER_METHOD, BLOCKER_GETTER_DESCRIPTOR),
                new MethodDep(DOUBLE_ROW_OWNER, GET_COMBINED_ENABLE_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(DOUBLE_ROW_OWNER, RESIZE_METHOD, VOID_METHOD_DESCRIPTOR),
                new MethodDep(DOUBLE_ROW_OWNER, GET_MAIN_WIDGET_METHOD, WIDGET_GETTER_DESCRIPTOR),
                new MethodDep(GROUP_ROW_OWNER, GET_PARAMETER_GROUP_METHOD, PARAMETER_GROUP_GETTER_DESCRIPTOR),
                new MethodDep(GROUP_ROW_OWNER, GET_FOLDING_PANE_METHOD, FOLDING_PANE_GETTER_DESCRIPTOR),
                new MethodDep(GROUP_ROW_OWNER, GET_ROW_LAYOUT_METHOD, ROW_LAYOUT_GETTER_DESCRIPTOR),
                new MethodDep(GROUP_ROW_OWNER, GET_MAIN_WIDGET_METHOD, WIDGET_GETTER_DESCRIPTOR),
                new MethodDep(FOLDING_PANE_OWNER, IS_EXPAND_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(BLOCKER_OWNER, IS_BLOCK_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(WIDGET_OWNER, REPAINT_METHOD, VOID_METHOD_DESCRIPTOR),
                new MethodDep(WIDGET_OWNER, IS_VISIBLE_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(WIDGET_OWNER, GET_JCOMPONENT_METHOD, JCOMPONENT_GETTER_DESCRIPTOR),
                new MethodDep(COLOR_OWNER, GET_JCOLOR_METHOD, JCOLOR_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_GROUP_OWNER, GET_NAME_METHOD, STRING_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_GROUP_OWNER, GET_VISIBILITY_COLOR_METHOD, COLOR_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_GROUP_OWNER, GET_FOLDER_OPENED_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(MODEL_OWNER, GET_PARAMETER_SET_METHOD, PARAMETER_SET_GETTER_DESCRIPTOR),
                new MethodDep(MODEL_OWNER, GET_ALL_OBJECTS_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SET_OWNER, PARAM_SET_GET_METHOD, PARAM_SET_GET_DESCRIPTOR),
                new MethodDep(PARAMETER_OWNER, GET_VALUE_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_GUID_METHOD, PARAM_GUID_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_ID_METHOD, PARAM_ID_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_NAME_METHOD, STRING_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_MIN_VALUE_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_MAX_VALUE_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_DEFAULT_VALUE_METHOD, FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, IS_REPEAT_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_COMBINED_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, IS_MORPH_TARGET_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_PARAM_TYPE_METHOD, PARAM_TYPE_GETTER_DESCRIPTOR),
                new MethodDep(PARAMETER_SOURCE_OWNER, GET_VISIBILITY_COLOR_ALPHA50_METHOD, COLOR_GETTER_DESCRIPTOR),
                new MethodDep(CONTROLLABLE_INTERFACE, GET_SOURCE_METHOD, CONTROLLABLE_SOURCE_GETTER_DESCRIPTOR),
                new MethodDep(CONTROLLABLE_SOURCE_OWNER, GET_KEYFORM_GRID_METHOD, KEYFORM_GRID_GETTER_DESCRIPTOR),
                new MethodDep(CONTROLLABLE_SOURCE_OWNER, GET_EXT_KEYFORM_GRID_METHOD, KEYFORM_GRID_GETTER_DESCRIPTOR),
                new MethodDep(
                        CONTROLLABLE_SOURCE_OWNER, GET_MORPH_TARGET_SET_METHOD, MORPH_TARGET_SET_GETTER_DESCRIPTOR),
                new MethodDep(
                        CONTROLLABLE_SOURCE_OWNER,
                        GET_EXT_MORPH_TARGET_SET_METHOD,
                        EXT_MORPH_TARGET_SET_GETTER_DESCRIPTOR),
                new MethodDep(KEYFORM_GRID_SOURCE_OWNER, GET_KEYFORM_BINDINGS_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(KEYFORM_BINDING_SOURCE_OWNER, GET_PARAMETER_GUID_METHOD, PARAM_GUID_GETTER_DESCRIPTOR),
                new MethodDep(KEYFORM_BINDING_SOURCE_OWNER, GET_KEYS_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(MORPH_TARGET_SET_OWNER, GET_MORPH_TARGETS_METHOD, LIST_GETTER_DESCRIPTOR),
                new MethodDep(MORPH_TARGET_OWNER, GET_PARAMETER_GUID_METHOD, PARAM_GUID_GETTER_DESCRIPTOR),
                new MethodDep(MORPH_TARGET_OWNER, GET_KEY_VALUE_METHOD, BOXED_FLOAT_GETTER_DESCRIPTOR),
                new MethodDep(
                        MODELING_DOCUMENT_OWNER, GET_CURRENT_EDIT_MODE_METHOD, MODELING_EDIT_MODE_GETTER_DESCRIPTOR),
                new MethodDep(EDIT_MODE_OWNER, IS_EDITING_METHOD, BOOLEAN_GETTER_DESCRIPTOR),
                new MethodDep(EDIT_MODE_OWNER, GET_CURRENT_UNDO_METHOD, GROUP_UNDO_GETTER_DESCRIPTOR),
                new MethodDep(EDIT_MODE_OWNER, GET_LAST_MODIFIED_TIME_MS_METHOD, LONG_GETTER_DESCRIPTOR),
                new MethodDep(EDIT_MODE_OWNER, GET_UNDO_MANAGER_METHOD, UNDO_MANAGER_GETTER_DESCRIPTOR),
                new MethodDep(EDIT_MODE_OWNER, GET_LINKED_UNDO_MANAGER_METHOD, UNDO_MANAGER_GETTER_DESCRIPTOR),
                new MethodDep(UNDO_MANAGER_OWNER, GET_CURRENT_POS_METHOD, INT_GETTER_DESCRIPTOR),
                new MethodDep(UNDO_MANAGER_OWNER, GET_UNDO_LIST_METHOD, LIST_GETTER_DESCRIPTOR));
    }

    /** Returns all host fields verified before arming the weave. */
    public List<FieldDep> fieldDependencies() {
        return List.of(
                new FieldDep(controllerOwner, CHILDREN_BOX_FIELD, CHILDREN_BOX_DESCRIPTOR),
                new FieldDep(controllerOwner, ROW_LIST_FIELD, ROW_LIST_DESCRIPTOR),
                new FieldDep(controllerOwner, SELECTED_OBJECTS_FIELD, SELECTED_OBJECTS_DESCRIPTOR),
                new FieldDep(controllerOwner, highlightField, highlightFieldDescriptor()));
    }

    /** Identifies one reviewed host method verified before arming the weave. */
    public record MethodDep(String owner, String name, String descriptor) {
        public MethodDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Identifies one reviewed host field verified before arming the weave. */
    public record FieldDep(String owner, String name, String descriptor) {
        public FieldDep {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
