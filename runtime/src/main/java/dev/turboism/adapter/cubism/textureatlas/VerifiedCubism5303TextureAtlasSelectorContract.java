package dev.turboism.adapter.cubism.textureatlas;

import java.util.Set;

/** Exact Cubism 5.3.03 selector contract for the texture-atlas authoring provider. */
public final class VerifiedCubism5303TextureAtlasSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.texture-atlas.layout.write";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.value",
        "cubism.texture-atlas.data-model.class",
        "cubism.texture-atlas.data-model.document",
        "cubism.texture-atlas.data-model.model-source",
        "cubism.texture-atlas.texture-manager.atlases",
        "cubism.texture-atlas.model-source.texture-manager",
        "cubism.texture-atlas.texture-manager.images",
        "cubism.editor-model.texture-manager.handler",
        "cubism.texture-atlas.texture-manager-handler.drawable-uses",
        "cubism.texture-atlas.texture-input-relink.helper-instance",
        "cubism.texture-atlas.texture-input-relink.rebuild",
        "cubism.editor-model.model-source.verify",
        "cubism.texture-atlas.texture-manager.change-input-to-atlas",
        "cubism.texture-atlas.atlas.class",
        "cubism.texture-atlas.atlas.create",
        "cubism.texture-atlas.atlas.name",
        "cubism.texture-atlas.atlas.width",
        "cubism.texture-atlas.atlas.height",
        "cubism.texture-atlas.atlas.entries",
        "cubism.texture-atlas.entry.class",
        "cubism.texture-atlas.entry.create",
        "cubism.texture-atlas.entry.image",
        "cubism.texture-atlas.entry.transform",
        "cubism.texture-atlas.image.class",
        "cubism.texture-atlas.image.guid",
        "cubism.texture-atlas.image.width",
        "cubism.texture-atlas.image.height",
        "cubism.texture-atlas.affine.class",
        "cubism.texture-atlas.affine.create",
        "cubism.texture-atlas.affine.translate",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.texture-atlas.undo.create",
        "cubism.texture-atlas.undo.force-redo",
        "cubism.texture-atlas.group-undo.add",
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.modeling-document.mark-dirty"
    );

    public static final Set<String> HOOK_ALIASES = Set.of(
        "cubism.texture-atlas.model-image-list.class",
        "cubism.texture-atlas.model-image-list.init",
        "cubism.texture-atlas.model-image-list.data-model"
    );

    public static final Set<String> AUTO_LAYOUT_HOOK_ALIASES = Set.of(
        "cubism.texture-atlas.auto-layout.invoke"
    );

    public static final Set<String> NATIVE_INVOCATION_ALIASES = Set.of(
        VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_CLASS,
        VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_SETTINGS,
        VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_DATA,
        VerifiedTextureAtlasNativeInvocationAdapter.RECEIVER_OVERFLOW,
        VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_MARGIN,
        VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_ROTATE,
        VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_MODEL_IMAGE,
        VerifiedTextureAtlasNativeInvocationAdapter.SETTINGS_SCALE,
        VerifiedTextureAtlasNativeInvocationAdapter.DATA_ITEMS,
        VerifiedTextureAtlasNativeInvocationAdapter.DATA_WIDTH,
        VerifiedTextureAtlasNativeInvocationAdapter.DATA_HEIGHT,
        VerifiedTextureAtlasNativeInvocationAdapter.DATA_SCALE,
        VerifiedTextureAtlasNativeInvocationAdapter.DATA_CURRENT_SCALE,
        VerifiedTextureAtlasNativeInvocationAdapter.DATA_IMPL,
        VerifiedTextureAtlasNativeInvocationAdapter.IMPL_CONTAINER,
        VerifiedTextureAtlasNativeInvocationAdapter.CONTAINER_CHILDREN,
        VerifiedTextureAtlasNativeInvocationAdapter.ITEM_RECT,
        VerifiedTextureAtlasNativeInvocationAdapter.ITEM_MODEL_RECT,
        VerifiedTextureAtlasNativeInvocationAdapter.ITEM_WIDTH,
        VerifiedTextureAtlasNativeInvocationAdapter.ITEM_HEIGHT,
        VerifiedTextureAtlasNativeInvocationAdapter.ITEM_TRANSFORM,
        VerifiedTextureAtlasNativeInvocationAdapter.ITEM_EDIT_LAYER,
        VerifiedTextureAtlasNativeInvocationAdapter.ITEM_CURRENT_TRANSFORM,
        VerifiedTextureAtlasNativeInvocationAdapter.RECT_X,
        VerifiedTextureAtlasNativeInvocationAdapter.RECT_Y,
        VerifiedTextureAtlasNativeInvocationAdapter.RECT_WIDTH,
        VerifiedTextureAtlasNativeInvocationAdapter.RECT_HEIGHT,
        VerifiedTextureAtlasNativeInvocationAdapter.AFFINE_CREATE,
        VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_LAYER,
        VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_TRANSFORM,
        VerifiedTextureAtlasNativeInvocationAdapter.LAYER_REF_SET_TRANSFORM,
        VerifiedTextureAtlasNativeInvocationAdapter.EDITOR_AFFINE_CREATE
    );

    public static final Set<String> DIALOG_INJECTION_ALIASES = Set.of(
        VerifiedTextureAtlasNativeInvocationAdapter.DIALOG_CLASS,
        VerifiedTextureAtlasNativeInvocationAdapter.DIALOG_INIT
    );

    public static final Set<String> STATISTICS_ALIASES = Set.of(
        VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_INIT,
        VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_VIEW_DATA_MODEL,
        VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_DATA_MODEL_CURRENT_PAGE,
        VerifiedTextureAtlasNativeInvocationAdapter.STATISTICS_PAGE_STATE_ATLAS
    );

    private VerifiedCubism5303TextureAtlasSelectorContract() {
    }
}
