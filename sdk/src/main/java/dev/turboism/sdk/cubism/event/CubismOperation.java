package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.PreviewApi;

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
@PreviewApi
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
    /** Changes Deformer opacity. */
    SET_DEFORMER_OPACITY("cubism.model.deformer.set-opacity"),
    /** Changes Deformer visibility. */
    SET_DEFORMER_VISIBLE("cubism.model.deformer.set-visible"),
    /** Changes the Deformer lock state. */
    SET_DEFORMER_LOCKED("cubism.model.deformer.set-locked"),
    /** Replaces a Warp Deformer grid. */
    REPLACE_WARP_DEFORMER_GRID("cubism.model.warp-deformer.replace-grid"),
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
    /** Executes a typed semantic Editor command identified by the event subject. */
    EXECUTE_EDITOR_COMMAND("cubism.editor.command.execute"),
    /** Opens a semantic object context menu. */
    OPEN_CONTEXT_MENU("cubism.editor.context-menu.open");

    private final String id;

    CubismOperation(final String id) {
        this.id = id;
    }

    /** Returns the stable Turboism diagnostic identity for this operation. */
    public String id() {
        return id;
    }
}
