package dev.turboism.adapter.cubism.mesh;

import java.util.Set;

/** Exact-version selector admission contract for cubism.mesh-tool.session. */
public final class MeshToolSessionSelectorContract {
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.mesh-tool.session";
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("5.2.03", "5.3.02", "5.3.03");
    public static final String APPLICATION_INSTANCE = "cubism.editor-model.app-controller.instance";
    public static final String APPLICATION_CURRENT_DOCUMENT = "cubism.editor-model.app-controller.current-document";
    public static final String MODELING_DOCUMENT_LAST_ACTIVE_VIEW =
            "cubism.editor-model.modeling-document.last-active-view";
    public static final String MODELING_VIEW_CLASS = "cubism.editor-model.modeling-view.class";
    public static final String VIEW_COMPLETE_PACK = "cubism.editor-model.view.complete-pack";
    public static final String COMPLETE_PACK_MAIN_VIEW = "cubism.editor-model.complete-pack.main-view";
    public static final String WIDGET_JCOMPONENT = "cubism.editor-model.widget.jcomponent";
    public static final String VIEW_SCENE_GRAPH = "cubism.editor-model.view.scene-graph";
    public static final String SCENE_COMPONENT_OBJECTS = "cubism.editor-model.scene-graph.component-objects";
    public static final String ENTITY_TRAVERSE_ALL = "cubism.editor-model.entity.traverse-all";
    public static final String ENTITY_ENABLED_IN_HIERARCHY = "cubism.editor-model.entity.enabled-in-hierarchy";
    public static final String GUI_BUTTON_CLASS = "cubism.editor-model.gui-button.class";
    public static final String GUI_ICON_BUTTON_CLASS = "cubism.editor-model.gui-icon-button.class";
    public static final String GUI_BUTTON_ON_COMPONENT = "cubism.editor-model.gui-button.on-component";
    public static final String GUI_BUTTON_COMPONENT_BOUNDS = "cubism.editor-model.gui-button.component-bounds";
    public static final String GUI_BOUNDS_CONTAINS = "cubism.editor-model.gui-bounds.contains";
    public static final String MODELING_VIEW_CAMERA = "cubism.editor-model.modeling-view.camera";
    public static final String MODELING_VIEW_MODE = "cubism.editor-model.modeling-view.current-view-mode";
    public static final String VIEW_MODE_CURRENT_FORM = "cubism.editor-model.modeling-view.mode-current-form";
    public static final String VIEW_MODE_SOURCE = "cubism.editor-model.modeling-view.mode-source";
    public static final String VIEW_MODE_IMAGE = "cubism.editor-model.modeling-view.mode-image";
    public static final String MODELING_VIEW_MODEL = "cubism.editor-model.modeling-view.model";
    public static final String MODEL_GET_OBJECT = "cubism.editor-model.model.get-object";
    public static final String ARTMESH_CLASS = "cubism.editor-model.art-mesh.class";
    public static final String ARTMESH_SOURCE = "cubism.editor-model.art-mesh.source";
    public static final String ARTMESH_SOURCE_POSITIONS = "cubism.editor-model.art-mesh-source.positions";
    public static final String ARTMESH_SOURCE_INDICES = "cubism.editor-model.art-mesh-source.indices";
    public static final String ARTMESH_CALCULATED_FORM = "cubism.editor-model.parameter-controllable.calculated-form";
    public static final String ARTMESH_FORM_POSITIONS = "cubism.editor-model.art-mesh-form.positions";
    public static final String MESH_COORDINATE_CONVERTER_CREATE =
            "cubism.editor-model.mesh-coordinate-converter.create";
    public static final String MESH_COORDINATE_CONVERTER_TRANSFORM =
            "cubism.editor-model.mesh-coordinate-converter.transform";
    public static final String CAMERA_DOCUMENT_TO_COMPONENT = "cubism.editor-model.camera.document-to-component";
    public static final String VECTOR_CREATE = "cubism.editor-model.vector.create";
    public static final String VECTOR_X = "cubism.editor-model.vector.x";
    public static final String VECTOR_Y = "cubism.editor-model.vector.y";
    public static final String MESH_EDITOR_CLASS = "cubism.editor-model.mesh-editor.class";
    public static final String MESH_EDITOR_START_MODE = "cubism.editor-model.mesh-editor.start-mode";
    public static final String MESH_EDITOR_END_MODE = "cubism.editor-model.mesh-editor.end-mode";
    public static final String MESH_EDITOR_DOCUMENT = "cubism.editor-model.mesh-editor.document";
    public static final String MESH_EDITOR_EDIT_DATA_FOR = "cubism.editor-model.mesh-editor.edit-data-for";
    public static final String ARTMESH_EDIT_DATA_CLASS = "cubism.editor-model.mesh-edit-data.class";
    public static final String ARTMESH_EDIT_DATA_SOURCE = "cubism.editor-model.mesh-edit-data.art-mesh-source";
    public static final String ARTMESH_EDIT_DATA_STAGING = "cubism.editor-model.mesh-edit-data.editable-mesh";
    public static final String EDITABLE_MESH_CLASS = "cubism.editor-model.editable-mesh.class";
    public static final String EDITABLE_MESH_SELECTION = "cubism.editor-model.editable-mesh.selection";
    public static final String EDITABLE_SELECTION_CLASS = "cubism.editor-model.mesh-selection.class";
    public static final String EDITABLE_SELECTION_POINT_SELECTOR = "cubism.editor-model.mesh-selection.point-selector";
    public static final String POINT_SELECTOR_CLASS = "cubism.editor-model.point-selector.class";
    public static final String POINT_SELECTOR_SELECTED_POINTS = "cubism.editor-model.point-selector.selected-points";
    public static final String POINT_SELECTOR_CLEAR = "cubism.editor-model.point-selector.clear";
    public static final String POINT_SELECTOR_ADD = "cubism.editor-model.point-selector.add";
    public static final String EDITABLE_MESH_POINT_REF = "cubism.editor-model.editable-mesh.point-ref";
    public static final String EDITABLE_MESH_POINT_COUNT = "cubism.editor-model.editable-mesh.point-count";
    public static final String EDITABLE_MESH_GL_POSITIONS = "cubism.editor-model.editable-mesh.gl-positions";
    public static final String POINT_REF_CLASS = "cubism.editor-model.point-ref.class";
    public static final String POINT_REF_MESH = "cubism.editor-model.point-ref.mesh";
    public static final String POINT_REF_INDEX = "cubism.editor-model.point-ref.index";
    public static final String MODELING_DOCUMENT_CLASS = "cubism.editor-model.modeling-document.class";
    public static final String MODELING_DOCUMENT_SET_EDIT_MODE = "cubism.editor-model.modeling-document.set-edit-mode";
    public static final String MODELING_DOCUMENT_MODEL_SOURCE = "cubism.editor-model.modeling-document.model-source";
    public static final String MODEL_SOURCE_ALL_MESHES = "cubism.editor-model.model-source.all-art-meshes";
    public static final String ARTMESH_SOURCE_CLASS = "cubism.editor-model.art-mesh-source.class";
    public static final String ARTMESH_SOURCE_ID = "cubism.editor-model.parameter-controllable-source.id";
    public static final String ID_VALUE = "cubism.editor-model.id.value";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.current-document",
            "cubism.editor-model.modeling-document.last-active-view",
            "cubism.editor-model.modeling-view.class",
            "cubism.editor-model.view.complete-pack",
            "cubism.editor-model.complete-pack.main-view",
            "cubism.editor-model.widget.jcomponent",
            "cubism.editor-model.view.scene-graph",
            "cubism.editor-model.scene-graph.component-objects",
            "cubism.editor-model.entity.traverse-all",
            "cubism.editor-model.entity.enabled-in-hierarchy",
            "cubism.editor-model.gui-button.class",
            "cubism.editor-model.gui-icon-button.class",
            "cubism.editor-model.gui-button.on-component",
            "cubism.editor-model.gui-button.component-bounds",
            "cubism.editor-model.gui-bounds.contains",
            "cubism.editor-model.modeling-view.camera",
            "cubism.editor-model.modeling-view.current-view-mode",
            "cubism.editor-model.modeling-view.mode-current-form",
            "cubism.editor-model.modeling-view.mode-source",
            "cubism.editor-model.modeling-view.mode-image",
            "cubism.editor-model.modeling-view.model",
            "cubism.editor-model.model.get-object",
            "cubism.editor-model.art-mesh.class",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.art-mesh-source.positions",
            "cubism.editor-model.art-mesh-source.indices",
            "cubism.editor-model.parameter-controllable.calculated-form",
            "cubism.editor-model.art-mesh-form.positions",
            "cubism.editor-model.mesh-coordinate-converter.create",
            "cubism.editor-model.mesh-coordinate-converter.transform",
            "cubism.editor-model.camera.document-to-component",
            "cubism.editor-model.vector.create",
            "cubism.editor-model.vector.x",
            "cubism.editor-model.vector.y",
            "cubism.editor-model.mesh-editor.class",
            "cubism.editor-model.mesh-editor.start-mode",
            "cubism.editor-model.mesh-editor.end-mode",
            "cubism.editor-model.mesh-editor.document",
            "cubism.editor-model.mesh-editor.edit-data-for",
            "cubism.editor-model.mesh-edit-data.class",
            "cubism.editor-model.mesh-edit-data.art-mesh-source",
            "cubism.editor-model.mesh-edit-data.editable-mesh",
            "cubism.editor-model.editable-mesh.class",
            "cubism.editor-model.editable-mesh.selection",
            "cubism.editor-model.mesh-selection.class",
            "cubism.editor-model.mesh-selection.point-selector",
            "cubism.editor-model.point-selector.class",
            "cubism.editor-model.point-selector.selected-points",
            "cubism.editor-model.point-selector.clear",
            "cubism.editor-model.point-selector.add",
            "cubism.editor-model.editable-mesh.point-ref",
            "cubism.editor-model.editable-mesh.point-count",
            "cubism.editor-model.editable-mesh.gl-positions",
            "cubism.editor-model.point-ref.class",
            "cubism.editor-model.point-ref.mesh",
            "cubism.editor-model.point-ref.index",
            "cubism.editor-model.modeling-document.class",
            "cubism.editor-model.modeling-document.set-edit-mode",
            "cubism.editor-model.modeling-document.model-source",
            "cubism.editor-model.model-source.all-art-meshes",
            "cubism.editor-model.art-mesh-source.class",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.id.value",
            "cubism.editor-model.editable-mesh.coord-type");

    private MeshToolSessionSelectorContract() {}

    /** Returns true only when the resolver admits the complete exact feature contract. */
    public static boolean authorizes(final dev.turboism.mapping.verification.VerifiedMemberResolver resolver) {
        return resolver != null
                && SUPPORTED_VERSIONS.contains(resolver.cubismVersion())
                && resolver.authorizesFeature(ADAPTER_SLICE_ID, CAPABILITY_ID, REQUIRED_ALIASES);
    }

    /** Returns true only for a supported exact version and a complete admitted record. */
    public static boolean isAdmitted(
            final String version, final Set<String> capabilities, final Set<String> availableAliases) {
        return version != null
                && capabilities != null
                && availableAliases != null
                && SUPPORTED_VERSIONS.contains(version)
                && capabilities.contains(CAPABILITY_ID)
                && availableAliases.containsAll(REQUIRED_ALIASES);
    }
}
