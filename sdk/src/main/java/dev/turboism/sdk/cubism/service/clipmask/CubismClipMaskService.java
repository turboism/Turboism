package dev.turboism.sdk.cubism.service.clipmask;


import java.util.List;
import java.util.Objects;

/**
 * Read-only clip-mask inspection service for SDK-only plugins.
 *
 * <p>Implementations adapt verified {@code CubismReadCapabilityService} clip-mask and
 * mesh snapshots into stable {@link ClipMaskRecord} values. Plugins must not depend on
 * Cubism internal ArtMesh classes or reflection to inspect clip-mask relationships.</p>
 */
public interface CubismClipMaskService {

    /**
     * Returns a snapshot of the current model's ArtMesh clip-mask data.
     *
     * @return immutable snapshot records deduplicated by {@code guid} (first occurrence
     *         wins); empty when no active model or no clip-mask data is available
     */
    List<ClipMaskRecord> collectClipMaskRecords();

    /**
     * Read-only ArtMesh clip-mask snapshot exposed to plugins.
     *
     * @param guid stable ArtMesh GUID (the clip-mask target mesh id)
     * @param id user-visible ArtMesh ID string, or an empty string
     * @param displayName short display name: the mesh name when known, otherwise the
     *        first 8 characters of the GUID
     * @param inverted whether this ArtMesh uses inverted clipping masks
     * @param orderedMaskGuids ordered GUID list of ArtMeshes used as this ArtMesh's
     *        clip masks; immutable, elements non-blank
     */
    record ClipMaskRecord(
        String guid,
        String id,
        String displayName,
        boolean inverted,
        List<String> orderedMaskGuids
    ) {
        public ClipMaskRecord {
            Objects.requireNonNull(guid, "guid");
            if (guid.isBlank()) {
                throw new IllegalArgumentException("guid must not be blank");
            }
            id = id == null ? "" : id;
            displayName = displayName == null ? "" : displayName;
            orderedMaskGuids = List.copyOf(Objects.requireNonNull(orderedMaskGuids, "orderedMaskGuids"));
            for (String maskGuid : orderedMaskGuids) {
                Objects.requireNonNull(maskGuid, "orderedMaskGuids element");
                if (maskGuid.isBlank()) {
                    throw new IllegalArgumentException("orderedMaskGuids must not contain blank values");
                }
            }
        }

        /** Returns whether this ArtMesh has any clip-mask references. */
        public boolean hasMasks() {
            return !orderedMaskGuids.isEmpty();
        }
    }
}
