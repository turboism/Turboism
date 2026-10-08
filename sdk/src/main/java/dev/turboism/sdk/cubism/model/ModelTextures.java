package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;

import java.util.List;
import java.util.Optional;

/**
 * Editor-backed projection of the active model's texture library.
 *
 * <p>Reads reflect the Editor's {@code CTextureManager} document state. Writes are
 * Editor-authoring operations executed inside the native Undo envelope
 * (edit-mode begin/end with a registered {@code GroupUndo}); every write is
 * undoable through the Editor's Undo history.</p>
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface ModelTextures {

    /** Raw layered images registered on the model. */
    List<RawTexture> rawImages();

    /** Model image groups (texture slots used by art meshes). */
    List<ModelImageGroup> modelImageGroups();

    /** Texture atlas documents. */
    List<AtlasTexture> textureAtlases();

    /**
     * Creates a new empty model image group.
     *
     * <p>Editor {@code CModelImageGroup} carries no stable guid, so the group is
     * located afterwards through {@link #modelImageGroups()} by group name.</p>
     */
    void addModelImageGroup(String name);

    /** Removes one model image by id. */
    void removeModelImage(ModelImageId id);

    /** Creates a new texture atlas with the given canvas size and returns its id. */
    TextureAtlasId addTextureAtlas(String name, int widthPixels, int heightPixels);

    /** Removes one texture atlas by id. */
    void removeTextureAtlas(TextureAtlasId id);

    /** Removes one raw layered image and its layer inputs by id. */
    @CubismEditor({"5.3.02", "5.3.03"})
    void removeRawImage(RawImageId id);

    /**
     * Registers one authorized image as a raw layered image plus model image and
     * binds it to an unbound ArtMesh inside a single Editor authoring
     * transaction (registration contribution before the binding contribution).
     *
     * <p>The image grant is consumed by the call. The target must be an
     * existing, unlocked, unbound ArtMesh with no extension and no parent
     * deformer. On any rejection or partial failure the model is left
     * untouched.</p>
     *
     * @return the detached registration/binding handle
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default ModelImageBinding registerImageAndBind(
        final AuthorizedImageContent image,
        final ArtMeshId target
    ) {
        throw unavailable("Model image registration and binding");
    }

    /**
     * Binds an already-registered model image to an unbound ArtMesh.
     *
     * <p>Binding the same image to the same ArtMesh again is a no-change
     * operation that touches no native state. Binding a different image to an
     * ArtMesh that already carries a texture-input extension, or any extension
     * not created by this slice, is rejected.</p>
     *
     * @return the detached binding handle
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default ModelImageBinding bindModelImage(
        final ModelImageId image,
        final ArtMeshId target
    ) {
        throw unavailable("Model image binding");
    }

    /**
     * Observes the native texture input currently bound to one ArtMesh.
     *
     * <p>Reads the mesh's texture-input extension and reports the authored resource
     * it resolves to — a model image or a texture atlas region — under the active
     * model generation. The observation is detached: it never exposes host objects
     * and is not a write token. An empty result means the mesh genuinely carries no
     * extension or no current input; a missing target, an unresolvable or foreign
     * resource, or an unknown input kind fails instead of reporting unbound. This
     * is authoring binding identity, not a render texture index and not proof of
     * sampling.</p>
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default Optional<ArtMeshTextureInput> textureInput(final ArtMeshId target) {
        throw unavailable("ArtMesh texture input observation");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(feature + " is unavailable.");
    }
}
