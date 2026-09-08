package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact read-only selector contract for the Cubism 5.3.02 texture relation graph.
 *
 * <p>This is separate from the legacy texture-library contract because the
 * relation graph consumes additional selectors and is not verified for 5.2.03
 * or 5.3.03. The capability remains the existing model texture read
 * capability; no write selector is admitted here.</p>
 */
public final class EditorTextureRelationsSelectorContract {
    public static final String ADAPTER_SLICE_ID = EditorTextureSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = EditorTextureSelectorContract.READ_CAPABILITY_ID;
    public static final String SUPPORTED_CUBISM_VERSION = "5.3.02";

    /** Exact aliases consumed by {@code EditorTextureRelationsAccess}. */
    public static final Set<String> REQUIRED_ALIASES = relationAliases();

    private static Set<String> relationAliases() {
        final HashSet<String> aliases = new HashSet<>(EditorTextureSelectorContract.READ_REQUIRED_ALIASES);
        aliases.addAll(Set.of(
            "cubism.editor-model.texture-manager.art-mesh-usable-model-image-groups",
            "cubism.editor-model.layered-image-wrapper.import-time",
            "cubism.editor-model.layered-image-wrapper.modified-time",
            "cubism.editor-model.layered-image-wrapper.replaced",
            "cubism.editor-model.layered-image.psd-file",
            "cubism.editor-model.layered-image.children",
            "cubism.editor-model.layer-entry.class",
            "cubism.editor-model.layer-entry.guid",
            "cubism.editor-model.layer-entry.name",
            "cubism.editor-model.layer-group.class",
            "cubism.editor-model.layer-group.children",
            "cubism.editor-model.model-image.linked-raw-image-guids",
            "cubism.editor-model.model-image.input-filter-env",
            "cubism.editor-model.model-image-filter-env.class",
            "cubism.editor-model.model-image-filter-env.has-layer-input-data",
            "cubism.editor-model.model-image-filter-env.layer-input-data",
            "cubism.editor-model.model-image-filter-env.has-current-image-guid",
            "cubism.editor-model.model-image-filter-env.current-image-guid",
            "cubism.editor-model.layer-selector-map.class",
            "cubism.editor-model.layer-selector-map.image-to-layer-input",
            "cubism.editor-model.layer-input-data.class",
            "cubism.editor-model.layer-input-data.layer",
            "cubism.editor-model.layer-input-data.affine",
            "cubism.editor-model.layer-input-data.clipping-on-texture-px",
            "cubism.editor-model.model-image-group.linked-raw-image-guids",
            "cubism.editor-model.texture-input-extension.class",
            "cubism.editor-model.texture-input-extension.texture-inputs",
            "cubism.editor-model.texture-input-extension.current-texture-input-data",
            "cubism.editor-model.texture-input-model-image.class",
            "cubism.editor-model.texture-input-model-image.model-image-guid",
            "cubism.editor-model.texture-input-texture-atlas-region.class",
            "cubism.editor-model.texture-input-texture-atlas-region.texture-atlas-guid",
            "cubism.editor-model.model-source.all-art-meshes",
            "cubism.editor-model.art-mesh-source.texture-input-extension",
            "cubism.editor-model.model.all-art-meshes",
            "cubism.editor-model.art-mesh-source.class",
            "cubism.editor-model.art-mesh.class",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.parameter-controllable-source.id"
        ));
        return Set.copyOf(aliases);
    }

    private EditorTextureRelationsSelectorContract() {
    }
}
