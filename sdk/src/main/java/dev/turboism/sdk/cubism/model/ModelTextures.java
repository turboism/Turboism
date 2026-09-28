package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.cubism.psd.PsdReplaceResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
     * Reads the immutable raw-image/model-image/ArtMesh texture relation graph when the exact
     * Editor relationship selectors are available. Unsupported implementations return a typed
     * unavailable value rather than an available empty graph.
     */
    @CubismEditor({"5.3.02"})
    default TextureRelationsSnapshot relations() {
        return TextureRelationsSnapshot.unavailable();
    }

    /**
     * Resolves only requested source identities, without loading layer trees or the complete
     * relation graph. Empty queries read binding metadata only. Providers may enumerate shallow
     * identity indexes; this is not a constant-time guarantee. Unsupported providers never fall
     * back to {@link #relations()}.
     */
    @CubismEditor({"5.3.02"})
    default TextureSourcesSnapshot sources(final TextureSourceQuery query) {
        Objects.requireNonNull(query, "query");
        return TextureSourcesSnapshot.unavailable();
    }

    /**
     * Exports one explicitly identified raw image to a runtime-owned PSD handle.
     *
     * <p>The export is Cubism's native layered-image rebuild of its current resources, not a
     * byte copy of the originally imported file. Only saves of the issued handle are
     * observable to {@link PsdEditFile#observeSaves}; saving under a different name or path
     * in the external application is not tracked.</p>
     *
     * <p>Implementations that do not own the native PSD service return a completed typed
     * {@link PsdExportResult.Status#UNAVAILABLE} result. The default never invokes a file
     * handle or accepts a caller-supplied path.</p>
     */
    @CubismEditor({"5.3.02"})
    default CompletionStage<PsdExportResult> exportRawImagePsd(final RawImageId source) {
        Objects.requireNonNull(source, "source");
        return CompletableFuture.completedFuture(new PsdExportResult(
                PsdExportResult.Status.UNAVAILABLE,
                "PSD raw-image export is unavailable.",
                source,
                Optional.empty(),
                Optional.empty()));
    }

    /**
     * Replaces one explicitly identified raw image from a runtime-issued PSD revision.
     *
     * <p>A raw image shared by several model images or ArtMeshes is replaced once and every
     * dependent object observes the new content; the native matcher performs the merge, not
     * the caller.</p>
     *
     * <p>Implementations that do not own the native PSD service return a completed typed
     * {@link PsdReplaceResult.Status#UNAVAILABLE} result. The default does not inspect or
     * invoke either opaque argument.</p>
     */
    @CubismEditor({"5.3.02"})
    default CompletionStage<PsdReplaceResult> replaceRawImagePsd(
            final RawImageId target, final PsdEditFile file, final PsdFileRevision revision) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(revision, "revision");
        return CompletableFuture.completedFuture(new PsdReplaceResult(
                PsdReplaceResult.Status.UNAVAILABLE,
                "PSD raw-image replacement is unavailable.",
                target,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    }

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
