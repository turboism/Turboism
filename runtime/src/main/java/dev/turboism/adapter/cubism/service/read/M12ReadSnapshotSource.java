package dev.turboism.adapter.cubism.service.read;

import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Runtime/adapter seam for M12 read capability families that are not yet part
 * of the minimum M6 CubismFacade snapshot source.
 */
public interface M12ReadSnapshotSource {

    M12ReadSnapshotSource EMPTY = new M12ReadSnapshotSource() {
    };

    default List<PsdDocumentSnapshot> psdDocuments() {
        return List.of();
    }

    default List<ClipMaskSnapshot> clipMasks() {
        return List.of();
    }

    default List<TextureAtlasSnapshot> textureAtlases() {
        return List.of();
    }

    default Optional<RenderStatusSnapshot> renderStatus() {
        return Optional.empty();
    }

    default Optional<WorkspaceSnapshot> workspace() {
        return Optional.empty();
    }

    default Optional<ThemeStatusSnapshot> themeStatus() {
        return Optional.empty();
    }
}
