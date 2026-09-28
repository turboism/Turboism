package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import java.util.List;

/**
 * Editor-backed projection of the active model's texture library.
 *
 * <p>Reads reflect the Editor's {@code CTextureManager} document state. Writes are
 * Editor-authoring operations executed inside the native Undo envelope
 * (edit-mode begin/end with a registered {@code GroupUndo}); every write is
 * undoable through the Editor's Undo history. Common texture-library operations
 * require the corresponding read or write capability from an admitted texture
 * contract. On later versions the write contract must also match the native
 * edit, Undo, rollback and refresh dependencies. Raw-image removal requires an
 * admitted non-dialog native route selected from the matched mapping.</p>
 */
@CubismEditor(from = "5.2.03")
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
    @CubismEditor(from = "5.2.03")
    void addModelImageGroup(String name);

    /** Removes one model image by id. */
    @CubismEditor(from = "5.2.03")
    void removeModelImage(ModelImageId id);

    /** Creates a new texture atlas with the given canvas size and returns its id. */
    @CubismEditor(from = "5.2.03")
    TextureAtlasId addTextureAtlas(String name, int widthPixels, int heightPixels);

    /** Removes one texture atlas by id. */
    @CubismEditor(from = "5.2.03")
    void removeTextureAtlas(TextureAtlasId id);

    /**
     * Removes one raw layered image and its layer inputs by id, retaining the model images,
     * ArtMeshes and texture atlases. The 5.2.03 route composes exact native Undo factories;
     * the 5.3 routes use the native non-cascading handler. No confirmation dialog is opened.
     */
    @CubismEditor(from = "5.2.03")
    void removeRawImage(RawImageId id);
}
