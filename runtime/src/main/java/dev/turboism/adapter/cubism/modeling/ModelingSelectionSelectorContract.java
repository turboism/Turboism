package dev.turboism.adapter.cubism.modeling;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Set;

/** Independently admitted exact ordinary-modeling point selection and tool lifecycle surface. */
public final class ModelingSelectionSelectorContract {
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.modeling-tool.selection-brush";
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("5.2.03", "5.3.02", "5.3.03");
    public static final String APP = "cubism.editor-model.app-controller.instance";
    public static final String DOCUMENT = "cubism.editor-model.app-controller.current-document";
    public static final String DOCUMENT_CLASS = "cubism.editor-model.modeling-document.class";
    public static final String MODE_SET = "cubism.editor-model.modeling-document.set-edit-mode";
    public static final String VIEW = "cubism.editor-model.modeling-document.last-active-view";
    public static final String VIEW_CLASS = "cubism.editor-model.modeling-view.class";
    public static final String PACK = "cubism.editor-model.view.complete-pack";
    public static final String MAIN_VIEW = "cubism.editor-model.complete-pack.main-view";
    public static final String COMPONENT = "cubism.editor-model.widget.jcomponent";
    public static final String CAMERA = "cubism.editor-model.modeling-view.camera";
    public static final String MODEL = "cubism.editor-model.modeling-view.model";
    public static final String MODEL_OBJECT = "cubism.editor-model.model.get-object";
    public static final String SOURCE_ID = "cubism.editor-model.parameter-controllable-source.id";
    public static final String POINTS = "cubism.editor-model.point-selector.selected-points";
    public static final String VECTOR_CREATE = "cubism.editor-model.vector.create";
    public static final String VECTOR_X = "cubism.editor-model.vector.x";
    public static final String VECTOR_Y = "cubism.editor-model.vector.y";
    public static final String PROJECT = "cubism.editor-model.camera.document-to-component";
    public static final String BEGIN_EDIT = "cubism.editor-model.edit-mode.begin";
    public static final String END_EDIT = "cubism.editor-model.edit-mode.end";
    public static final String UPDATE_MANAGER = "cubism.editor-model.complete-pack.update-manager";
    public static final String MAIN_FRAME = "cubism.editor-model.app-controller.main-frame";
    public static final String VIEW_SCENE_GRAPH = "cubism.editor-model.view.scene-graph";
    public static final String SCENE_COMPONENT_OBJECTS = "cubism.editor-model.scene-graph.component-objects";
    public static final String ENTITY_TRAVERSE_ALL = "cubism.editor-model.entity.traverse-all";
    public static final String ENTITY_ENABLED_IN_HIERARCHY = "cubism.editor-model.entity.enabled-in-hierarchy";
    public static final String GUI_BUTTON_CLASS = "cubism.editor-model.gui-button.class";
    public static final String GUI_ICON_BUTTON_CLASS = "cubism.editor-model.gui-icon-button.class";
    public static final String GUI_BUTTON_ON_COMPONENT = "cubism.editor-model.gui-button.on-component";
    public static final String GUI_BUTTON_COMPONENT_BOUNDS = "cubism.editor-model.gui-button.component-bounds";
    public static final String GUI_BOUNDS_CONTAINS = "cubism.editor-model.gui-bounds.contains";
    public static final String CURRENT_MODE = "cubism.editor-model.modeling-document.current-edit-mode";
    public static final String MAIN_MODE_CLASS = "cubism.editor-model.modeling-mode.class";
    public static final String MAIN_SELECTOR = "cubism.editor-model.modeling-mode.selector";
    public static final String SELECTED_OBJECTS = "cubism.editor-model.modeling-selector.selected-objects";
    public static final String SELECTOR_BOUNDING_BOX = "cubism.editor-model.modeling-selector.bounding-box";
    public static final String BOUNDING_BOX_DIRTY = "cubism.editor-model.bounding-box.mark-dirty";
    public static final String SOURCE_EDITABLE =
            "cubism.editor-model.parameter-controllable-source.editable-in-hierarchy";
    public static final String SOURCE_SELECTION = "cubism.editor-model.parameter-controllable-source.selection";
    public static final String SELECTION_POINTS = "cubism.editor-model.parameter-selection.point-selector";
    public static final String OBJECT_SOURCE = "cubism.editor-model.parameter-controllable.source";
    public static final String OBJECT_POINTS = "cubism.editor-model.parameter-controllable.all-points";
    public static final String VIEW_EDITABLE = "cubism.editor-model.modeling-view.editable-object";
    public static final String POINT_EX_CLASS = "cubism.editor-model.modeling-point-ex.class";
    public static final String POINT_CANVAS = "cubism.editor-model.modeling-point-ex.canvas-position";
    public static final String POINT_SOURCE = "cubism.editor-model.modeling-point.source";
    public static final String MESH_POINT_CLASS = "cubism.editor-model.modeling-mesh-point.class";
    public static final String MESH_POINT_INDEX = "cubism.editor-model.modeling-mesh-point.index";
    public static final String MESH_POINT_UID = "cubism.editor-model.modeling-mesh-point.uid";
    public static final String WARP_POINT_CLASS = "cubism.editor-model.modeling-warp-point.class";
    public static final String WARP_POINT_INDEX = "cubism.editor-model.modeling-warp-point.index";
    public static final String POINT_REMOVE = "cubism.editor-model.point-selector.remove";
    public static final String POINT_WEIGHT = "cubism.editor-model.point-selector.weight";
    public static final String POINT_ADD_WEIGHTED = "cubism.editor-model.point-selector.add-weighted";
    public static final String SELECTION_REFRESH = "cubism.editor-model.update-manager.refresh-selection";
    public static final String SELECTION_UNDO = "cubism.editor-model.undo.capture-selection";
    public static final String MODE_EDITING = "cubism.editor-model.modeling-mode.is-editing";
    public static final String TOOL_MODE = "cubism.editor-model.app-controller.tool-mode";
    public static final String TOOL_GROUP = "cubism.editor-model.app-controller.tool-group";
    public static final String SETUP_TOOL = "cubism.editor-model.app-controller.setup-tool";
    public static final String ARROW_TOOL = "cubism.editor-model.arrow-tool.instance";
    public static final String NATIVE_TOOL_BUTTONS = "cubism.editor-model.main-frame.tool-buttons";
    public static final String BRUSH_ANCHOR = "cubism.editor-model.main-frame-view.brush-selection-button";
    public static final String ARROW_BUTTON = "cubism.editor-model.main-frame-view.arrow-button";
    public static final String TOGGLE_CREATE = "cubism.editor-model.modeling-tool.toggle-create";
    public static final String CURRENT_VIEW = "cubism.editor-model.app-controller.current-view";
    public static final String VIEW_LISTENERS = "cubism.editor-model.app-controller.view-change-listeners";
    public static final String TOGGLE_ROLLOVER = "cubism.editor-model.modeling-tool.set-rollover-icon";
    public static final String UPDATE_TOOL_BUTTONS = "cubism.editor-model.main-frame.update-tool-buttons";
    public static final String MESH_CLASS = "cubism.editor-model.art-mesh.class";
    public static final String WARP_CLASS = "cubism.editor-model.warp.class";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
            APP,
            DOCUMENT,
            DOCUMENT_CLASS,
            MODE_SET,
            VIEW,
            VIEW_CLASS,
            PACK,
            MAIN_VIEW,
            COMPONENT,
            CAMERA,
            MODEL,
            MODEL_OBJECT,
            SOURCE_ID,
            POINTS,
            VECTOR_CREATE,
            VECTOR_X,
            VECTOR_Y,
            PROJECT,
            BEGIN_EDIT,
            END_EDIT,
            UPDATE_MANAGER,
            MAIN_FRAME,
            VIEW_SCENE_GRAPH,
            SCENE_COMPONENT_OBJECTS,
            ENTITY_TRAVERSE_ALL,
            ENTITY_ENABLED_IN_HIERARCHY,
            GUI_BUTTON_CLASS,
            GUI_ICON_BUTTON_CLASS,
            GUI_BUTTON_ON_COMPONENT,
            GUI_BUTTON_COMPONENT_BOUNDS,
            GUI_BOUNDS_CONTAINS,
            CURRENT_MODE,
            MAIN_MODE_CLASS,
            MAIN_SELECTOR,
            SELECTED_OBJECTS,
            SELECTOR_BOUNDING_BOX,
            BOUNDING_BOX_DIRTY,
            SOURCE_EDITABLE,
            SOURCE_SELECTION,
            SELECTION_POINTS,
            OBJECT_SOURCE,
            OBJECT_POINTS,
            VIEW_EDITABLE,
            POINT_EX_CLASS,
            POINT_CANVAS,
            POINT_SOURCE,
            MESH_POINT_CLASS,
            MESH_POINT_INDEX,
            MESH_POINT_UID,
            WARP_POINT_CLASS,
            WARP_POINT_INDEX,
            POINT_REMOVE,
            POINT_WEIGHT,
            POINT_ADD_WEIGHTED,
            SELECTION_REFRESH,
            SELECTION_UNDO,
            MODE_EDITING,
            TOOL_MODE,
            TOOL_GROUP,
            SETUP_TOOL,
            ARROW_TOOL,
            NATIVE_TOOL_BUTTONS,
            BRUSH_ANCHOR,
            ARROW_BUTTON,
            TOGGLE_CREATE,
            CURRENT_VIEW,
            VIEW_LISTENERS,
            TOGGLE_ROLLOVER,
            UPDATE_TOOL_BUTTONS,
            MESH_CLASS,
            WARP_CLASS);

    private ModelingSelectionSelectorContract() {}

    /** Requires the complete independent point-selection capability for a supported exact version. */
    public static boolean authorizes(VerifiedMemberResolver resolver) {
        return resolver != null
                && SUPPORTED_VERSIONS.contains(resolver.cubismVersion())
                && resolver.authorizesFeature(ADAPTER_SLICE_ID, CAPABILITY_ID, REQUIRED_ALIASES);
    }
}
