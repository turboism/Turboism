package dev.turboism.adapter.cubism.textureatlas;

import java.util.Set;

/** Exact Cubism 5.2.0 selector contract for the texture-atlas authoring provider. */
public final class VerifiedCubism520TextureAtlasSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.texture-atlas.layout.write";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.value",
        "cubism.texture-atlas.data-model.class",
        "cubism.texture-atlas.data-model.document",
        "cubism.texture-atlas.data-model.model-source",
        "cubism.texture-atlas.data-model.sheets",
        "cubism.texture-atlas.sheet.atlas",
        "cubism.texture-atlas.model-source.texture-manager",
        "cubism.texture-atlas.texture-manager.images",
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
        "cubism.texture-atlas.undo.create",
        "cubism.texture-atlas.undo.force-redo",
        "cubism.texture-atlas.group-undo.add"
    );

    public static final Set<String> HOOK_ALIASES = Set.of(
        "cubism.texture-atlas.model-image-list.class",
        "cubism.texture-atlas.model-image-list.init",
        "cubism.texture-atlas.model-image-list.data-model"
    );

    private VerifiedCubism520TextureAtlasSelectorContract() {
    }
}
