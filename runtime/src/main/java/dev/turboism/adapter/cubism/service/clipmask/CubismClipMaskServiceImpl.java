package dev.turboism.adapter.cubism.service.clipmask;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Drawable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime implementation of {@link CubismClipMaskService} over the verified
 * {@link CubismReadCapabilityService} clip-mask and mesh reads.
 */
public final class CubismClipMaskServiceImpl implements CubismClipMaskService {

    private final CubismReadCapabilityService readService;

    private final CubismModelAccess modelAccess;

    public CubismClipMaskServiceImpl(
        final CubismReadCapabilityService readService,
        final CubismModelAccess modelAccess
    ) {
        this.readService = Objects.requireNonNull(readService, "readService");
        this.modelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
    }

    @Override
    public List<ClipMaskRecord> collectClipMaskRecords() {
        final List<ClipMaskSnapshot> snapshots = readService.clipMasks();
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        final MeshIndex meshIndex = nameIndex();
        final Map<String, InternalClipMaskRecord> byGuid = new LinkedHashMap<>();
        for (ClipMaskSnapshot snapshot : snapshots) {
            if (snapshot == null || byGuid.containsKey(snapshot.targetMeshId())) {
                continue;
            }
            final String guid = snapshot.targetMeshId();
            final String id = meshIndex.resolveId(guid);
            final String displayName = meshIndex.resolveDisplayName(guid);
            final ClipMaskRecord record = new ClipMaskRecord(
                guid,
                id,
                displayName,
                snapshot.inverted(),
                snapshot.orderedMaskSourceIds()
            );
            byGuid.put(guid, new InternalClipMaskRecord(guid, record));
        }
        final List<ClipMaskRecord> records = new ArrayList<>(byGuid.size());
        for (InternalClipMaskRecord internal : byGuid.values()) {
            records.add(internal.record());
        }
        return records;
    }

    /**
     * Best-effort display-name index, three levels: drawable (guid→name, guid→id) index from the
     * active editor model first, then the mesh (id→name) join; each read fails closed to the
     * next level, and a missing name falls back to the short GUID (first 8 characters).
     */
    private MeshIndex nameIndex() {
        final Map<String, String> namesByGuid = new LinkedHashMap<>();
        final Map<String, String> idsByGuid = new LinkedHashMap<>();
        final Set<String> meshIds = new HashSet<>();
        indexDrawables(namesByGuid, idsByGuid);
        indexMeshes(namesByGuid, meshIds);
        return new MeshIndex(namesByGuid, idsByGuid, meshIds);
    }

    private void indexDrawables(final Map<String, String> namesByGuid, final Map<String, String> idsByGuid) {
        try {
            final List<Drawable> drawables = modelAccess.active().drawables().all();
            if (drawables == null) {
                return;
            }
            for (Drawable drawable : drawables) {
                if (drawable == null) {
                    continue;
                }
                final String guid;
                try {
                    guid = drawable.guid();
                } catch (RuntimeException unavailable) {
                    continue; // per-drawable: guid unavailable -> skip this drawable
                }
                if (guid == null || guid.isBlank()) {
                    continue;
                }
                String name;
                try {
                    name = drawable.name();
                } catch (RuntimeException unavailable) {
                    name = ""; // name unavailable -> do not block the mesh join fallback
                }
                if (!name.isBlank()) {
                    namesByGuid.putIfAbsent(guid, name);
                }
                String id;
                try {
                    id = drawable.id() == null ? "" : drawable.id().value();
                } catch (RuntimeException unavailable) {
                    id = ""; // id unavailable -> do not block name collection
                }
                if (id != null && !id.isBlank()) {
                    idsByGuid.putIfAbsent(guid, id);
                }
            }
        } catch (RuntimeException unavailable) {
            // ponytail: drawables read unavailable -> mesh join fallback, matching legacy behavior
        }
    }

    private void indexMeshes(final Map<String, String> namesByGuid, final Set<String> meshIds) {
        try {
            final List<ArtMeshSnapshot> meshes = readService.meshes();
            if (meshes != null) {
                for (ArtMeshSnapshot mesh : meshes) {
                    if (mesh != null && mesh.id() != null && !mesh.id().isBlank()) {
                        namesByGuid.putIfAbsent(mesh.id(), mesh.name() == null ? "" : mesh.name());
                        meshIds.add(mesh.id());
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // ponytail: mesh read unavailable -> short-GUID fallback, matching legacy behavior
        }
    }

    /** Package-private dedup carrier: first-seen {@link ClipMaskRecord} per stable GUID. */
    record InternalClipMaskRecord(String guid, ClipMaskRecord record) {
    }

    private static final class MeshIndex {
        private final Map<String, String> namesByGuid;
        private final Map<String, String> idsByGuid;
        private final Set<String> meshIds;

        MeshIndex(
            final Map<String, String> namesByGuid,
            final Map<String, String> idsByGuid,
            final Set<String> meshIds
        ) {
            this.namesByGuid = namesByGuid;
            this.idsByGuid = idsByGuid;
            this.meshIds = meshIds;
        }

        /**
         * Real mesh id from the drawables index, then the legacy unit path (id==guid when the
         * guid is a joined mesh id), then empty.
         */
        String resolveId(final String guid) {
            final String drawableId = idsByGuid.get(guid);
            if (drawableId != null && !drawableId.isBlank()) {
                return drawableId;
            }
            return meshIds.contains(guid) ? guid : "";
        }

        String resolveDisplayName(final String guid) {
            final String name = namesByGuid.get(guid);
            if (name != null && !name.isBlank()) {
                return name;
            }
            return guid.length() <= 8 ? guid : guid.substring(0, 8);
        }
    }
}
