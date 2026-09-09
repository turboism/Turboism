package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact Cubism 5.3.02 selector contract for the native PSD raw-image replace seam.
 *
 * <p>The native process owns the Import PSD transaction. The adapter only reads the current edit
 * state and invokes the explicit five-argument entry; the begin/end and GroupUndo selectors below
 * are retained as static evidence of that ownership and are deliberately not invoked by the
 * adapter.</p>
 */
public final class EditorRawImagePsdReplaceSelectorContract {
    public static final String CAPABILITY_ID = "cubism.editor-model.psd.raw-image.replace";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String SUPPORTED_CUBISM_VERSION = "5.3.02";

    /** Existing exact selectors reused for the current document/edit-state checks. */
    public static final String APP_CONTROLLER_CLASS_ALIAS =
        "cubism.editor-model.app-controller.class";
    public static final String MODELING_DOCUMENT_CLASS_ALIAS =
        "cubism.editor-model.modeling-document.class";
    public static final String CURRENT_EDIT_MODE_ALIAS =
        "cubism.editor-command.canvas.edit-mode";
    public static final String EDITING_STATE_ALIAS =
        "cubism.editor-command.canvas.is-editing";
    public static final String LAYERED_IMAGE_CLASS_ALIAS =
        EditorRawImagePsdSelectorContract.LAYERED_IMAGE_CLASS_ALIAS;

    /** Exact Kotlin-object receiver selectors for {@code com.live2d.cubism.process.psd.a}. */
    public static final String PSD_IMPORT_PROCESS_CLASS_ALIAS =
        "cubism.editor-model.psd-import-process.class";
    public static final String PSD_IMPORT_PROCESS_INSTANCE_ALIAS =
        "cubism.editor-model.psd-import-process.instance";
    public static final String PSD_IMPORT_REPLACE_ALIAS =
        "cubism.editor-model.psd-import-process.replace";

    /** Static evidence selectors for the transaction native owns; the adapter never calls them. */
    public static final String NATIVE_EDIT_MODE_CLASS_ALIAS =
        "cubism.editor-model.psd-import.native-edit-mode.class";
    public static final String NATIVE_BEGIN_EDIT_ALIAS =
        "cubism.editor-model.psd-import.native-edit-mode.begin";
    public static final String NATIVE_END_EDIT_ALIAS =
        "cubism.editor-model.psd-import.native-edit-mode.end";
    public static final String NATIVE_GROUP_UNDO_CLASS_ALIAS =
        "cubism.editor-model.psd-import.group-undo.class";

    /**
     * Aliases invoked by this internal slice and therefore required for production admission.
     * Transaction evidence is deliberately kept out of this set: the native five-argument entry
     * owns that boundary and the adapter never resolves or invokes begin/end/GroupUndo members.
     */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        APP_CONTROLLER_CLASS_ALIAS,
        MODELING_DOCUMENT_CLASS_ALIAS,
        CURRENT_EDIT_MODE_ALIAS,
        EDITING_STATE_ALIAS,
        LAYERED_IMAGE_CLASS_ALIAS,
        PSD_IMPORT_PROCESS_CLASS_ALIAS,
        PSD_IMPORT_PROCESS_INSTANCE_ALIAS,
        PSD_IMPORT_REPLACE_ALIAS
    );

    /**
     * Report-only exact evidence for the transaction boundary observed in the native method body.
     * These aliases are verified by static evidence tests, but are not production admission.
     */
    public static final Set<String> TRANSACTION_EVIDENCE_ALIASES = Set.of(
        NATIVE_EDIT_MODE_CLASS_ALIAS,
        NATIVE_BEGIN_EDIT_ALIAS,
        NATIVE_END_EDIT_ALIAS,
        NATIVE_GROUP_UNDO_CLASS_ALIAS
    );

    private EditorRawImagePsdReplaceSelectorContract() {
    }
}
