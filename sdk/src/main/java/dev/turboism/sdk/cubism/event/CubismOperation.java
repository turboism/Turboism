package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.CubismEditor;

/**
 * Typed catalog of Cubism model and Editor operations that may emit a
 * {@code before -> invoke -> on -> after} lifecycle.
 *
 * <p>The identifiers are Turboism semantics. They never expose host class or
 * method names.</p>
 *
 * <p>A catalog entry does not enable a host hook or claim host-version support;
 * only a verified runtime producer may emit it.</p>
 */
@CubismEditor
public enum CubismOperation {

    /** Changes the Editor default-keyform lock. */
    SET_MODEL_DEFAULT_KEYFORM_LOCKED("cubism.model.default-keyform.set-locked"),
    /** Updates model evaluation. */
    UPDATE_MODEL("cubism.model.update"),
    /** Changes a parameter-group label color. */
    SET_PARAMETER_GROUP_LABEL_COLOR("cubism.model.parameter-group.set-label-color"),
    /** Combines two parameters. */
    COMBINE_PARAMETER("cubism.model.parameter.combine"),
    /** Removes a parameter combination. */
    UNCOMBINE_PARAMETER("cubism.model.parameter.uncombine"),
    /** Restores a parameter's default value. */
    RESET_PARAMETER_TO_DEFAULT("cubism.model.parameter.reset-to-default"),
    /** Changes a parameter value. */
    SET_PARAMETER_VALUE("cubism.model.parameter.set-value"),
    /** Replaces a parameter definition. */
    UPDATE_PARAMETER_DEFINITION("cubism.model.parameter.update-definition"),
    /** Replaces one target's parameter binding. */
    BIND_PARAMETER("cubism.model.parameter-binding.bind"),
    /** Creates a parameter-binding point. */
    CREATE_PARAMETER_BINDING_POINT("cubism.model.parameter-binding.create-point"),
    /** Moves a parameter-binding point. */
    MOVE_PARAMETER_BINDING_POINT("cubism.model.parameter-binding.move-point"),
    /** Deletes a parameter-binding point. */
    DELETE_PARAMETER_BINDING_POINT("cubism.model.parameter-binding.delete-point"),
    /** Removes one target's parameter binding. */
    UNBIND_PARAMETER("cubism.model.parameter-binding.unbind"),
    /** Inverts parameter bindings for selected targets. */
    INVERT_PARAMETER_BINDINGS("cubism.model.parameter-binding.invert"),
    /** Transfers parameter bindings between parameter identities. */
    TRANSFER_PARAMETER_BINDINGS("cubism.model.parameter-binding.transfer"),
    /** Changes a Part display name. */
    SET_PART_NAME("cubism.model.part.set-name"),
    /** Changes Part opacity. */
    SET_PART_OPACITY("cubism.model.part.set-opacity"),
    /** Changes ArtMesh opacity. */
    SET_DRAWABLE_OPACITY("cubism.model.art-mesh.set-opacity"),
    /** Changes ArtMesh visibility. */
    SET_DRAWABLE_VISIBLE("cubism.model.art-mesh.set-visible"),
    /** Changes the ArtMesh lock state. */
    SET_DRAWABLE_LOCKED("cubism.model.art-mesh.set-locked"),
    /** Replaces ArtMesh authoring geometry. */
    REPLACE_DRAWABLE_GEOMETRY("cubism.model.art-mesh.replace-geometry"),
    /** Changes only an explicitly selected stored ArtMesh keyform's positions. */
    SET_DRAWABLE_KEYFORM_POSITIONS("cubism.model.art-mesh.keyform.set-positions"),
    /** Replaces multiple ordinary keyform positions in one protected authoring contribution. */
    SET_DRAWABLE_KEYFORM_POSITIONS_BATCH("cubism.model.art-mesh.keyforms.set-positions"),
    /** Changes only an explicitly selected stored ArtMesh keyform's draw order. */
    SET_DRAWABLE_KEYFORM_DRAW_ORDER("cubism.model.art-mesh.keyform.set-draw-order"),
    SET_DRAWABLE_KEYFORM_DRAW_ORDERS_BATCH("cubism.model.art-mesh.keyform.set-draw-orders-batch"),
    /** Writes ordinary ArtMesh positions and draw orders together. */
    SET_DRAWABLE_KEYFORMS_BATCH("cubism.model.art-mesh.keyforms.set-values"),
    /** Creates an absolute control-point blend key on a neutral Warp parent. */
    CREATE_WARP_MORPH_KEYFORM("cubism.model.warp-deformer.morph-keyform.create"),
    /** Creates an absolute opacity blend key on a neutral Warp parent. */
    CREATE_WARP_OPACITY_MORPH_KEYFORM("cubism.model.warp-deformer.opacity-morph-keyform.create"),
    /** Creates one non-basic ArtMesh morph member with an absolute stored form. */
    CREATE_DRAWABLE_MORPH_KEYFORM("cubism.model.art-mesh.morph-keyform.create"),
    /** Changes only the absolute positions of an existing ArtMesh morph member. */
    SET_DRAWABLE_MORPH_KEYFORM_POSITIONS("cubism.model.art-mesh.morph-keyform.set-positions"),
    /** Changes only the absolute stored draw order of an existing ArtMesh morph member. */
    SET_DRAWABLE_MORPH_KEYFORM_DRAW_ORDER("cubism.model.art-mesh.morph-keyform.set-draw-order"),
    /** Changes Deformer opacity. */
    SET_DEFORMER_OPACITY("cubism.model.deformer.set-opacity"),
    /** Changes Deformer visibility. */
    SET_DEFORMER_VISIBLE("cubism.model.deformer.set-visible"),
    /** Changes the Deformer lock state. */
    SET_DEFORMER_LOCKED("cubism.model.deformer.set-locked"),
    /** Replaces a Warp Deformer grid. */
    REPLACE_WARP_DEFORMER_GRID("cubism.model.warp-deformer.replace-grid"),
    /** Replaces independently stored Rotation forms in one compare-and-set batch. */
    SET_ROTATION_KEYFORM_FORMS_BATCH("cubism.model.rotation-deformer.keyforms.set-forms"),
    /** Writes absolute controls in existing non-default ordinary Warp forms. */
    SET_WARP_KEYFORM_POSITIONS_BATCH("cubism.model.warp-deformer.keyforms.set-positions"),
    /** Changes a Rotation Deformer base angle. */
    SET_ROTATION_DEFORMER_BASE_ANGLE("cubism.model.rotation-deformer.set-base-angle"),
    /** Replaces a Rotation Deformer form. */
    REPLACE_ROTATION_DEFORMER_FORM("cubism.model.rotation-deformer.replace-form"),

    /** Opens a project. */
    OPEN_PROJECT("cubism.editor.project.open"),
    /** Closes a project. */
    CLOSE_PROJECT("cubism.editor.project.close"),
    /** Imports project content. */
    IMPORT_PROJECT("cubism.editor.project.import"),
    /** Exports project content. */
    EXPORT_PROJECT("cubism.editor.project.export"),
    /** Opens a document. */
    OPEN_DOCUMENT("cubism.editor.document.open"),
    /** Saves the active document. */
    SAVE_DOCUMENT("cubism.editor.document.save"),
    /** Saves the active document under another user-approved destination. */
    SAVE_DOCUMENT_AS("cubism.editor.document.save-as"),
    /** Closes a document. */
    CLOSE_DOCUMENT("cubism.editor.document.close"),
    /** Activates another open document. */
    SWITCH_DOCUMENT("cubism.editor.document.switch"),
    /** Reloads a document. */
    RELOAD_DOCUMENT("cubism.editor.document.reload"),
    /** Changes the Editor selection. */
    CHANGE_SELECTION("cubism.editor.selection.change"),
    /** Performs one native Undo operation. */
    UNDO("cubism.editor.history.undo"),
    /** Performs one native Redo operation. */
    REDO("cubism.editor.history.redo"),
    /** Imports parameter values. */
    IMPORT_PARAMETER_VALUES("cubism.editor.parameter-values.import"),
    /** Exports parameter values. */
    EXPORT_PARAMETER_VALUES("cubism.editor.parameter-values.export"),
    /** Reinitializes the active model. */
    REINITIALIZE_MODEL("cubism.editor.model.reinitialize"),
    /** Reinitializes a texture atlas. */
    REINITIALIZE_TEXTURE_ATLAS("cubism.editor.texture-atlas.reinitialize"),
    /** Registers one authorized image as a raw layered image and model image. */
    REGISTER_MODEL_IMAGE("cubism.editor.texture.register-model-image"),
    /** Binds a registered model image to an ArtMesh through the texture-input extension. */
    BIND_MODEL_IMAGE("cubism.editor.texture.bind-model-image"),
    /** Executes a typed semantic Editor command identified by the event subject. */
    EXECUTE_EDITOR_COMMAND("cubism.editor.command.execute"),
    /** Opens a semantic object context menu. */
    OPEN_CONTEXT_MENU("cubism.editor.context-menu.open"),

    /** Changes an object's part membership or deformer parent. */
    SET_HIERARCHY_PARENT("cubism.model.hierarchy.set-parent"),
    /** Removes an object's part membership or deformer parent. */
    DETACH_HIERARCHY_PARENT("cubism.model.hierarchy.detach"),
    /** Moves an ArtMesh in the canvas. */
    MOVE_DRAWABLE("cubism.model.art-mesh.move"),

    /** Moves a deformer's whole form to a new position. */
    MOVE_DEFORMER("cubism.model.deformer.move"),
    /** Changes an ArtMesh color. */
    SET_DRAWABLE_COLOR("cubism.model.art-mesh.set-color");

    private final String id;

    CubismOperation(final String id) {
        this.id = id;
    }

    /** Returns the stable Turboism diagnostic identity for this operation. */
    public String id() {
        return id;
    }
}
