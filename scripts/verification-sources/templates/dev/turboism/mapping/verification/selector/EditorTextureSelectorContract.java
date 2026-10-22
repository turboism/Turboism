package dev.turboism.mapping.verification.selector;

import dev.turboism.internal.ReviewedVersions;
import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for Editor model texture-library reads and
 * undo-enveloped writes.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):
 * {@code CModelSource.getTextureManager()} exposes {@code CTextureManager} whose
 * {@code getRawImages()}, {@code getModelImageGroups()}, {@code getTextureAtlases()}
 * and {@code getAllModelImages()} enumerate the model's textures; names and pixel
 * dimensions come from {@code CLayeredImage} / {@code CModelImage} /
 * {@code CTextureAtlas}. Every write routes through {@code TextureManagerHandler}
 * undo factories ({@code UndoAddOrRemove_ModelImageGroup}, {@code UndoAddOrRemove_ModelImage},
 * {@code UndoAddOrRemove_TextureAtlas}, {@code UndoAddOrRemove_RawImage}) whose
 * construct-and-redo objects are registered into the edit-mode {@code GroupUndo}.
 * Cubism 5.0.00 through 5.2.03 lack the 5.3 non-dialog raw-image handler overload. Their adapter
 * instead composes the same native layer-input and raw-image Undo factories,
 * registering each prepared edit before forceRedo and never invoking a dialog
 * or deleting the owning model images, ArtMeshes or texture atlases.</p>
 */
public final class EditorTextureSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";
    public static final String READ_CAPABILITY_ID = "cubism.editor-model.texture.read";
    public static final String WRITE_CAPABILITY_ID = "cubism.editor-model.texture.write";
    public static final String REGISTER_BIND_CAPABILITY_ID = "cubism.editor-model.texture.register-bind";

    /** Exact installations reviewed for the legacy factory route; no version-range inference. */
    public static final Set<String> LEGACY_RAW_IMAGE_REMOVAL_VERSIONS = ReviewedVersions.between("5.0.00", "5.2.03");

    public static final Set<String> READ_REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.texture-manager",
        "cubism.editor-model.texture-manager.raw-images",
        "cubism.editor-model.texture-manager.model-image-groups",
        "cubism.editor-model.texture-manager.all-model-images",
        "cubism.editor-model.texture-manager.texture-atlases",
        "cubism.editor-model.texture-manager.handler",
        "cubism.editor-model.layered-image-wrapper.image",
        "cubism.editor-model.layered-image.class",
        "cubism.editor-model.layered-image.name",
        "cubism.editor-model.layered-image.width",
        "cubism.editor-model.layered-image.height",
        "cubism.editor-model.model-image-group.class",
        "cubism.editor-model.model-image-group.group-name",
        "cubism.editor-model.model-image-group.memo",
        "cubism.editor-model.model-image-group.model-images",
        "cubism.editor-model.model-image.class",
        "cubism.editor-model.model-image.name",
        "cubism.editor-model.model-image.width",
        "cubism.editor-model.model-image.height",
        "cubism.editor-model.texture-atlas.class",
        "cubism.editor-model.texture-atlas.name",
        "cubism.editor-model.texture-atlas.width",
        "cubism.editor-model.texture-atlas.height",
        "cubism.editor-model.texture-atlas.atlas-version",
        "cubism.editor-model.texture-atlas.model-images",
        "cubism.editor-model.layered-image.guid",
        "cubism.editor-model.model-image.guid",
        "cubism.editor-model.texture-atlas.guid",
        "cubism.editor-model.guid.value"
    );

    public static final Set<String> WRITE_REQUIRED_ALIASES = writeAliases();

    /** Aliases required for the exact 5.3.02 and 5.3.03 raw-image removal route. */
    public static final Set<String> REMOVE_RAW_IMAGE_ALIASES = Set.of(
        "cubism.editor-model.texture-handler.remove-raw-image"
    );

    /** Exact 5.2.03 native factories and reads for non-cascading raw-image removal. */
    public static final Set<String> REMOVE_RAW_IMAGE_5203_ALIASES = Set.of(
        "cubism.editor-model.model-image.input-filter-env",
        "cubism.editor-model.model-image-filter-env.layer-input-data",
        "cubism.editor-model.layer-selector-map.get",
        "cubism.editor-model.texture-undo.layer-input.create",
        "cubism.editor-model.texture-undo.raw-image.create",
        "cubism.editor-model.texture-undo.force-redo"
    );

    /** Aliases required for the 5.3.02 model-image registration/binding slice. */
    public static final Set<String> REGISTER_BIND_REQUIRED_ALIASES = registerBindAliases();

    /**
     * Aliases required for the read-only ArtMesh texture-input observation:
     * {@code texture-input-extension → current-input} classification plus model-image
     * or texture-atlas GUID resolution through the active model's texture manager.
     * {@code CTextureInput_TextureAtlasRegion.getTextureAtlasGuid()} and its class
     * selector are 5.3.02 evidence.
     */
    public static final Set<String> TEXTURE_INPUT_OBSERVE_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model-source.texture-manager",
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.art-mesh-source.class",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.art-mesh-source.texture-input-extension",
        "cubism.editor-model.texture-input-extension.current-input",
        "cubism.editor-model.texture-input-model-image.class",
        "cubism.editor-model.texture-input-model-image.model-image",
        "cubism.editor-model.texture-input-texture-atlas-region.class",
        "cubism.editor-model.texture-atlas-guid",
        "cubism.editor-model.texture-manager.all-model-images",
        "cubism.editor-model.texture-manager.texture-atlases",
        "cubism.editor-model.model-image.class",
        "cubism.editor-model.model-image.guid",
        "cubism.editor-model.texture-atlas.class",
        "cubism.editor-model.texture-atlas.guid",
        "cubism.editor-model.guid.value"
    );

    /**
     * Private static {@code CImageResource} decode counters. They are pinned in
     * the verification record for the offline decode oracle but are never
     * resolved through the runtime resolver: the offline fixture substitutes
     * same-signature public static counters.
     */
    public static final Set<String> DECODE_ORACLE_ALIASES = Set.of(
        "cubism.editor-model.image-resource.loaded-count",
        "cubism.editor-model.image-resource.loaded-bytes",
        "cubism.editor-model.image-resource.byte-data-bytes"
    );

    private static Set<String> registerBindAliases() {
        final HashSet<String> aliases = new HashSet<>(READ_REQUIRED_ALIASES);
        aliases.addAll(Set.of(
            "cubism.editor-model.undo.add",
            "cubism.editor-model.model-source.all-art-meshes",
            "cubism.editor-model.art-mesh.class",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.art-mesh-source.class",
            "cubism.editor-model.art-mesh-source.guid",
            "cubism.editor-model.art-mesh-source.texture-input-extension",
            "cubism.editor-model.art-mesh-source.uvs",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
            "cubism.editor-model.parameter-controllable-source.extensions",
            "cubism.editor-model.parameter-controllable-source.target-deformer-source",
            "cubism.editor-model.parameter-controllable-source.all-parent-deformers",
            "cubism.editor-model.id.value",
            "cubism.editor-model.image-resource.create",
            "cubism.editor-model.image-color-type.int-argb",
            "cubism.editor-model.image-resource.image",
            "cubism.editor-model.image-resource.width",
            "cubism.editor-model.image-resource.height",
            "cubism.editor-model.layered-image.create",
            "cubism.editor-model.layered-image-wrapper.create",
            "cubism.editor-model.model-image-guid.create",
            "cubism.editor-model.model-image.create",
            "cubism.editor-model.model-image.input-filter",
            "cubism.editor-model.model-image.input-filter-env",
            "cubism.editor-model.model-image.linked-raw-image-guids",
            "cubism.editor-model.model-image-group.add-linked-layered-image",
            "cubism.editor-model.model-image-filter-env.companion",
            "cubism.editor-model.model-image-filter-env.output-image",
            "cubism.editor-model.model-image-filter-env.output-transform",
            "cubism.editor-model.model-image-filter-env.has-current-image-guid",
            "cubism.editor-model.model-image-filter-env.current-image-guid",
            "cubism.editor-model.model-image-filter-env.set-current-image-guid",
            "cubism.editor-model.filter-value.create",
            "cubism.editor-model.filter-value.id",
            "cubism.editor-model.empty-filter.create",
            "cubism.editor-model.filter-set.add-filter",
            "cubism.editor-model.filter-instance.set-input",
            "cubism.editor-model.const-value-connector.create",
            "cubism.editor-model.caffine.create",
            "cubism.editor-model.filter-set.connect-env-output",
            "cubism.editor-model.filter-set.verify",
            "cubism.editor-model.verify-collector.create",
            "cubism.editor-model.undo-add-remove-raw-image-wrapper.create",
            "cubism.editor-model.undo-add-remove-model-image-group.create",
            "cubism.editor-model.undo-add-remove-model-image.create",
            "cubism.editor-model.undo-add-remove-extension.create",
            "cubism.editor-model.undo.execute-undo",
            "cubism.texture-atlas.undo.force-redo",
            "cubism.editor-history.semantic.add-remove.owner",
            "cubism.editor-model.texture-input-extension.class",
            "cubism.editor-model.texture-input-model-image.class",
            "cubism.editor-model.texture-input-extension.create",
            "cubism.editor-model.texture-input-extension.add-input",
            "cubism.editor-model.texture-input-extension.set-current-input",
            "cubism.editor-model.texture-input-extension.inputs",
            "cubism.editor-model.texture-input-extension.current-input",
            "cubism.editor-model.texture-input-extension.model-image-input",
            "cubism.editor-model.texture-input-model-image.create",
            "cubism.editor-model.texture-input-model-image.model-image",
            "cubism.editor-model.texture-input-model-image.cached-image",
            "cubism.editor-model.cached-image-param.companion",
            "cubism.editor-model.cached-image-param.default",
            "cubism.editor-model.cached-image.texture",
            "cubism.editor-model.art-mesh-source.texture",
            "cubism.editor-model.art-mesh-source.set-texture",
            "cubism.editor-model.art-mesh-source.texture-state",
            "cubism.editor-model.art-mesh-source.set-texture-state",
            "cubism.editor-model.texture-state.model-image",
            "cubism.editor-model.uu-undo.instance",
            "cubism.editor-model.uu-undo.local-simple-create"
        ));
        aliases.addAll(Set.of(
            "cubism.editor-model.editable-mesh-extension.class",
            "cubism.editor-model.editable-mesh-extension.owner",
            "cubism.editor-model.editable-mesh-extension.locked"
        ));
        return Set.copyOf(aliases);
    }

    private static Set<String> writeAliases() {
        final HashSet<String> aliases = new HashSet<>(READ_REQUIRED_ALIASES);
        aliases.addAll(Set.of(
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.current-document",
            "cubism.editor-model.app-controller.complete-pack",
            "cubism.editor-model.modeling-document.edit-mode",
            "cubism.editor-model.modeling-document.mark-dirty",
            "cubism.editor-model.edit-mode.begin",
            "cubism.editor-model.edit-mode.end",
            "cubism.editor-model.undo.add",
            "cubism.editor-model.texture-undo.undo",
            "cubism.editor-model.undo.add-listener",
            "cubism.editor-model.undo-listener.class",
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.repaint-canvas",
            "cubism.editor-model.model-image-group.create",
            "cubism.editor-model.texture-handler.add-model-image-group",
            "cubism.editor-model.texture-handler.remove-model-image",
            "cubism.editor-model.texture-atlas.create",
            "cubism.editor-model.texture-handler.add-texture-atlas",
            "cubism.editor-model.texture-handler.remove-texture-atlas"
        ));
        return Set.copyOf(aliases);
    }

    private EditorTextureSelectorContract() {
    }
}
