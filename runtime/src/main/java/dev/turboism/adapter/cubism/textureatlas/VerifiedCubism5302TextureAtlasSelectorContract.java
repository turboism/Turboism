package dev.turboism.adapter.cubism.textureatlas;

import java.util.Set;

/** Exact Cubism 5.3.02 selector contract for the texture-atlas authoring provider. */
public final class VerifiedCubism5302TextureAtlasSelectorContract {

    public static final String ADAPTER_SLICE_ID = "cubism-5.3.02-editor-model";
    public static final String CAPABILITY_ID = "cubism.editor-model.texture-atlas-layout.write";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.value",
        "cubism.texture-atlas.document.id",
        "cubism.texture-atlas.document.data-model",
        "cubism.texture-atlas.data-model.class",
        "cubism.texture-atlas.data-model.atlases",
        "cubism.texture-atlas.data-model.images",
        "cubism.texture-atlas.data-model.apply",
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
        "cubism.texture-atlas.document.transaction",
        "cubism.texture-atlas.document.mark-dirty",
        "cubism.texture-atlas.document.refresh"
    );

    private VerifiedCubism5302TextureAtlasSelectorContract() {
    }
}
