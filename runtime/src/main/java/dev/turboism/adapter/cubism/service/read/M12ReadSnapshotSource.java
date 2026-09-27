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
 * Supplemental runtime/adapter source for typed read families that are not yet
 * available from the minimum CubismFacade snapshot source.
 */
public interface M12ReadSnapshotSource {

    M12ReadSnapshotSource EMPTY = new M12ReadSnapshotSource() {
    };

    /**
     * @return the PSD document snapshots for the current project; the default reports none
     */
    default List<PsdDocumentSnapshot> psdDocuments() {
        return List.of();
    }

    /**
     * @return the clip-mask snapshots for the active model; the default reports none
     */
    default List<ClipMaskSnapshot> clipMasks() {
        return List.of();
    }

    /**
     * @return the texture atlas snapshots for the current project; the default reports none
     */
    default List<TextureAtlasSnapshot> textureAtlases() {
        return List.of();
    }

    /**
     * @return the observed render status; the default reports none
     */
    default Optional<RenderStatusSnapshot> renderStatus() {
        return Optional.empty();
    }

    /**
     * @return the observed workspace; the default reports none
     */
    default Optional<WorkspaceSnapshot> workspace() {
        return Optional.empty();
    }

    /**
     * @return the observed theme status; the default reports none
     */
    default Optional<ThemeStatusSnapshot> themeStatus() {
        return Optional.empty();
    }
}
