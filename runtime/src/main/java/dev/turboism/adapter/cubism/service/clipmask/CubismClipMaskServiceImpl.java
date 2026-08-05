package dev.turboism.adapter.cubism.service.clipmask;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime implementation of {@link CubismClipMaskService} over the verified
 * {@link CubismReadCapabilityService} clip-mask and mesh reads.
 */
public final class CubismClipMaskServiceImpl implements CubismClipMaskService {

    private final CubismReadCapabilityService readService;

    public CubismClipMaskServiceImpl(final CubismReadCapabilityService readService) {
        this.readService = Objects.requireNonNull(readService, "readService");
    }

    @Override
    public List<ClipMaskRecord> collectClipMaskRecords() {
        final List<ClipMaskSnapshot> snapshots = readService.clipMasks();
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        final MeshIndex meshIndex = meshIndex();
        final Map<String, InternalClipMaskRecord> byGuid = new LinkedHashMap<>();
        for (ClipMaskSnapshot snapshot : snapshots) {
            if (snapshot == null || byGuid.containsKey(snapshot.targetMeshId())) {
                continue;
            }
            final String guid = snapshot.targetMeshId();
            final String id = meshIndex.contains(guid) ? guid : "";
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
     * Best-effort mesh index: an ArtMesh whose {@code id} equals the target GUID
     * supplies its {@code name} as the display name; otherwise the short GUID
     * (first 8 characters) is used. A failing mesh read degrades to the
     * short-GUID fallback instead of failing the whole clip-mask snapshot.
     */
    private MeshIndex meshIndex() {
        final Map<String, String> namesByGuid = new LinkedHashMap<>();
        try {
            final List<ArtMeshSnapshot> meshes = readService.meshes();
            if (meshes != null) {
                for (ArtMeshSnapshot mesh : meshes) {
                    if (mesh != null && mesh.id() != null && !mesh.id().isBlank()) {
                        namesByGuid.putIfAbsent(mesh.id(), mesh.name() == null ? "" : mesh.name());
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // ponytail: mesh read unavailable -> short-GUID fallback, matching legacy behavior
            return new MeshIndex(namesByGuid);
        }
        return new MeshIndex(namesByGuid);
    }

    /** Package-private dedup carrier: first-seen {@link ClipMaskRecord} per stable GUID. */
    record InternalClipMaskRecord(String guid, ClipMaskRecord record) {
    }

    private static final class MeshIndex {
        private final Map<String, String> namesByGuid;

        MeshIndex(final Map<String, String> namesByGuid) {
            this.namesByGuid = namesByGuid;
        }

        boolean contains(final String guid) {
            return namesByGuid.containsKey(guid);
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
