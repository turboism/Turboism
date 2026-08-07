package dev.turboism.mapping.verification;

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
 * Cubism 5.2.03 lacks the non-dialog raw-image removal path
 * ({@code TextureManagerHandler.a(CLayeredImageGuid, boolean)} is 5.3.02-only),
 * so {@code removeRawImage} fails closed on 5.2.</p>
 */
public final class EditorTextureSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String READ_CAPABILITY_ID = "cubism.editor-model.texture.read";
    public static final String WRITE_CAPABILITY_ID = "cubism.editor-model.texture.write";

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
        "cubism.editor-model.guid.value"
    );

    public static final Set<String> WRITE_REQUIRED_ALIASES = writeAliases();

    /** Aliases required only for the 5.3.02 raw-image removal path. */
    public static final Set<String> REMOVE_RAW_IMAGE_ALIASES = Set.of(
        "cubism.editor-model.texture-handler.remove-raw-image"
    );

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
