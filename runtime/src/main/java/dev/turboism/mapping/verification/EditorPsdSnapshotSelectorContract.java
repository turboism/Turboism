package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact selector contract for immutable PSD/layer snapshots and ArtMesh layer bindings. */
public final class EditorPsdSnapshotSelectorContract {

    public static final String CAPABILITY_ID = "cubism.editor-model.psd.snapshot.read";
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.texture-manager",
        "cubism.editor-model.texture-manager.raw-images",
        "cubism.editor-model.layered-image-wrapper.image",
        "cubism.editor-model.layered-image.class",
        "cubism.editor-model.layered-image.guid",
        "cubism.editor-model.layered-image.psd-file",
        "cubism.editor-model.layered-image.children",
        "cubism.editor-model.layer-entry.class",
        "cubism.editor-model.layer-entry.guid",
        "cubism.editor-model.layer-entry.name",
        "cubism.editor-model.layer-entry.visible",
        "cubism.editor-model.layer-entry.clipping",
        "cubism.editor-model.layer-group.class",
        "cubism.editor-model.layer-group.children",
        "cubism.editor-model.art-mesh-source.texture-input-extension",
        "cubism.editor-model.texture-input-extension.class",
        "cubism.editor-model.texture-input-extension.model-image-input",
        "cubism.editor-model.texture-input-model-image.class",
        "cubism.editor-model.texture-input-model-image.model-image",
        "cubism.editor-model.model-image.class",
        "cubism.editor-model.model-image.current-layer-input-data",
        "cubism.editor-model.layer-input-data.class",
        "cubism.editor-model.layer-input-data.layer",
        "cubism.editor-model.guid.value"
    );

    private EditorPsdSnapshotSelectorContract() {
    }
}
