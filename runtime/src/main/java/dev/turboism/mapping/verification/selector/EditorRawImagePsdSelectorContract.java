package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact Cubism 5.3.02 selector contract for raw-image PSD export and parse.
 *
 * <p>The contract deliberately includes the identity and layer-tree reads needed to prove that
 * the selected raw image and the parsed export are the intended resources. It does not authorize
 * a generic image export or an implementation-owned layer matcher.</p>
 */
public final class EditorRawImagePsdSelectorContract {
    public static final String CAPABILITY_ID = "cubism.editor-model.psd.raw-image.export";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String SUPPORTED_CUBISM_VERSION = "5.3.02";

    public static final String MODEL_SOURCE_TEXTURE_MANAGER_ALIAS =
        "cubism.editor-model.model-source.texture-manager";
    public static final String TEXTURE_MANAGER_RAW_IMAGES_ALIAS =
        "cubism.editor-model.texture-manager.raw-images";
    public static final String LAYERED_IMAGE_WRAPPER_IMAGE_ALIAS =
        "cubism.editor-model.layered-image-wrapper.image";
    public static final String LAYERED_IMAGE_CLASS_ALIAS =
        "cubism.editor-model.layered-image.class";
    public static final String LAYERED_IMAGE_GUID_ALIAS =
        "cubism.editor-model.layered-image.guid";
    public static final String LAYERED_IMAGE_NAME_ALIAS =
        "cubism.editor-model.layered-image.name";
    public static final String LAYERED_IMAGE_WIDTH_ALIAS =
        "cubism.editor-model.layered-image.width";
    public static final String LAYERED_IMAGE_HEIGHT_ALIAS =
        "cubism.editor-model.layered-image.height";
    public static final String LAYERED_IMAGE_PSD_DOC_ALIAS =
        "cubism.editor-model.layered-image.psd-doc";
    public static final String LAYERED_IMAGE_CHILDREN_ALIAS =
        "cubism.editor-model.layered-image.children";
    public static final String LAYER_ENTRY_CLASS_ALIAS =
        "cubism.editor-model.layer-entry.class";
    public static final String LAYER_ENTRY_GUID_ALIAS =
        "cubism.editor-model.layer-entry.guid";
    public static final String LAYER_ENTRY_NAME_ALIAS =
        "cubism.editor-model.layer-entry.name";
    public static final String LAYER_ENTRY_VISIBLE_ALIAS =
        "cubism.editor-model.layer-entry.visible";
    public static final String LAYER_ENTRY_CLIPPING_ALIAS =
        "cubism.editor-model.layer-entry.clipping";
    public static final String LAYER_ENTRY_OPACITY_ALIAS =
        "cubism.editor-model.layer-entry.opacity";
    public static final String LAYER_ENTRY_BLEND_ALIAS =
        "cubism.editor-model.layer-entry.blend";
    public static final String LAYER_ENTRY_TRANSPARENCY_SHAPES_ALIAS =
        "cubism.editor-model.layer-entry.transparency-shapes";
    public static final String BLEND_CLASS_ALIAS =
        "cubism.editor-model.blend.class";
    public static final String BLEND_PSD_VALUE_ALIAS =
        "cubism.editor-model.blend.psd-value";
    public static final String PSD_BLEND_CLASS_ALIAS =
        "cubism.editor-model.psd-blend.class";
    public static final String PSD_BLEND_KEY_ALIAS =
        "cubism.editor-model.psd-blend.key";
    public static final String LAYER_IDENTIFIER_CLASS_ALIAS =
        "cubism.editor-model.layer-identifier.class";
    public static final String LAYER_IDENTIFIER_ID_ALIAS =
        "cubism.editor-model.layer-identifier.id";
    public static final String LAYER_GROUP_LAYER_IDENTIFIER_ALIAS =
        "cubism.editor-model.layer-group.layer-identifier";
    public static final String LAYER_CLASS_ALIAS =
        "cubism.editor-model.layer.class";
    public static final String LAYER_LAYER_IDENTIFIER_ALIAS =
        "cubism.editor-model.layer.layer-identifier";
    public static final String LAYER_BOUNDS_ALIAS =
        "cubism.editor-model.layer.bounds";
    public static final String RECT_CLASS_ALIAS =
        "cubism.editor-model.rect.class";
    public static final String RECT_X_ALIAS =
        "cubism.editor-model.rect.x";
    public static final String RECT_Y_ALIAS =
        "cubism.editor-model.rect.y";
    public static final String RECT_WIDTH_ALIAS =
        "cubism.editor-model.rect.width";
    public static final String RECT_HEIGHT_ALIAS =
        "cubism.editor-model.rect.height";
    public static final String LAYER_GROUP_CLASS_ALIAS =
        "cubism.editor-model.layer-group.class";
    public static final String LAYER_GROUP_CHILDREN_ALIAS =
        "cubism.editor-model.layer-group.children";
    public static final String GUID_VALUE_ALIAS = "cubism.editor-model.guid.value";

    public static final String PSD_DOCUMENT_CLASS_ALIAS =
        "cubism.editor-model.psd-document.class";
    public static final String PSD_DOCUMENT_COMPANION_ALIAS =
        "cubism.editor-model.psd-document.companion";
    public static final String PSD_DOCUMENT_COMPANION_CLASS_ALIAS =
        "cubism.editor-model.psd-document-companion.class";
    public static final String PSD_DOCUMENT_PARSE_FILE_ALIAS =
        "cubism.editor-model.psd-document.parse-file";
    public static final String LAYERED_IMAGE_FROM_PSD_ALIAS =
        "cubism.editor-model.layered-image.from-psd";
    public static final String LAYERED_IMAGE_SAVE_PSD_ALIAS =
        "cubism.editor-model.layered-image.save-psd";
    /** Releases only detached layered images created for export verification. */
    public static final String LAYERED_IMAGE_DISPOSE_OWNED_ALIAS =
        "cubism.editor-model.layered-image.dispose-owned";
    public static final String PSD_PROGRESS_CLASS_ALIAS =
        "cubism.editor-model.psd-progress.class";
    public static final String PSD_PROGRESS_DEFAULT_ALIAS =
        "cubism.editor-model.psd-progress.default";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        MODEL_SOURCE_TEXTURE_MANAGER_ALIAS,
        TEXTURE_MANAGER_RAW_IMAGES_ALIAS,
        LAYERED_IMAGE_WRAPPER_IMAGE_ALIAS,
        LAYERED_IMAGE_CLASS_ALIAS,
        LAYERED_IMAGE_GUID_ALIAS,
        LAYERED_IMAGE_PSD_DOC_ALIAS,
        LAYERED_IMAGE_CHILDREN_ALIAS,
        LAYER_ENTRY_CLASS_ALIAS,
        LAYER_ENTRY_GUID_ALIAS,
        LAYER_ENTRY_NAME_ALIAS,
        LAYER_ENTRY_VISIBLE_ALIAS,
        LAYER_ENTRY_CLIPPING_ALIAS,
        LAYER_ENTRY_OPACITY_ALIAS,
        LAYER_ENTRY_BLEND_ALIAS,
        LAYER_ENTRY_TRANSPARENCY_SHAPES_ALIAS,
        LAYER_GROUP_CLASS_ALIAS,
        LAYER_GROUP_CHILDREN_ALIAS,
        LAYER_GROUP_LAYER_IDENTIFIER_ALIAS,
        LAYER_CLASS_ALIAS,
        LAYER_LAYER_IDENTIFIER_ALIAS,
        LAYER_BOUNDS_ALIAS,
        LAYER_IDENTIFIER_CLASS_ALIAS,
        LAYER_IDENTIFIER_ID_ALIAS,
        RECT_CLASS_ALIAS,
        RECT_X_ALIAS,
        RECT_Y_ALIAS,
        RECT_WIDTH_ALIAS,
        RECT_HEIGHT_ALIAS,
        BLEND_CLASS_ALIAS,
        BLEND_PSD_VALUE_ALIAS,
        PSD_BLEND_CLASS_ALIAS,
        PSD_BLEND_KEY_ALIAS,
        GUID_VALUE_ALIAS,
        LAYERED_IMAGE_NAME_ALIAS,
        LAYERED_IMAGE_WIDTH_ALIAS,
        LAYERED_IMAGE_HEIGHT_ALIAS,
        PSD_DOCUMENT_CLASS_ALIAS,
        PSD_DOCUMENT_COMPANION_ALIAS,
        PSD_DOCUMENT_COMPANION_CLASS_ALIAS,
        PSD_DOCUMENT_PARSE_FILE_ALIAS,
        LAYERED_IMAGE_FROM_PSD_ALIAS,
        LAYERED_IMAGE_SAVE_PSD_ALIAS,
        LAYERED_IMAGE_DISPOSE_OWNED_ALIAS,
        PSD_PROGRESS_CLASS_ALIAS,
        PSD_PROGRESS_DEFAULT_ALIAS
    );

    private EditorRawImagePsdSelectorContract() {
    }
}
