package dev.turboism.plugin.protectedexport;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.PartId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, plugin-private description of protected-export preflight.
 *
 * <p>The plan contains copied read evidence and proposed target strings only. It never authorizes,
 * performs, or implies an export or a model mutation. In particular, target IDs are private
 * strings and are not host-issued {@link ArtMeshId} values.</p>
 */
record ProtectedExportPlan(
    List<DeformerId> deformerOrder,
    List<ArtMeshId> artMeshOrder,
    List<PartId> partIds,
    List<ParameterId> parameterIds,
    Map<ArtMeshId, ArtMeshTarget> artMeshTargets,
    List<PartSnapshot> partSnapshots
) {
    private static final Pattern TARGET_ID_PATTERN = Pattern.compile("[0-9a-zA-Z_@]+");

    ProtectedExportPlan {
        deformerOrder = List.copyOf(Objects.requireNonNull(deformerOrder, "deformerOrder"));
        artMeshOrder = List.copyOf(Objects.requireNonNull(artMeshOrder, "artMeshOrder"));
        partIds = List.copyOf(Objects.requireNonNull(partIds, "partIds"));
        parameterIds = List.copyOf(Objects.requireNonNull(parameterIds, "parameterIds"));
        artMeshTargets = immutableOrderedMap(artMeshTargets, "artMeshTargets");
        partSnapshots = List.copyOf(Objects.requireNonNull(partSnapshots, "partSnapshots"));
    }

    /**
     * This planner cannot read Extended Interpolation from the current SDK. The condition is
     * always present, immutable, and cannot be supplied or removed by a caller.
     */
    Set<UnresolvedCondition> unresolvedConditions() {
        return Set.of(UnresolvedCondition.EXTENDED_INTERPOLATION_UNVERIFIED);
    }

    /** Compatibility view of the proposed names, keyed by opaque source lookup IDs. */
    Map<ArtMeshId, String> obfuscatedArtMeshes() {
        final LinkedHashMap<ArtMeshId, String> result = new LinkedHashMap<>();
        artMeshTargets.forEach((sourceId, target) -> result.put(sourceId, target.name()));
        return Collections.unmodifiableMap(result);
    }

    /** Compatibility view of private target ID tokens, never host-issued ArtMeshId values. */
    Map<ArtMeshId, String> obfuscatedArtMeshIds() {
        final LinkedHashMap<ArtMeshId, String> result = new LinkedHashMap<>();
        artMeshTargets.forEach((sourceId, target) -> result.put(sourceId, target.idToken()));
        return Collections.unmodifiableMap(result);
    }

    enum UnresolvedCondition {
        EXTENDED_INTERPOLATION_UNVERIFIED
    }

    /** Proposed ArtMesh name plus a private, not-yet-host-issued target ID token. */
    record ArtMeshTarget(String name, String idToken) {
        ArtMeshTarget {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ArtMesh target name must not be blank");
            }
            if (idToken == null
                || idToken.length() >= 64
                || idToken.isEmpty()
                || Character.isDigit(idToken.charAt(0))
                || !TARGET_ID_PATTERN.matcher(idToken).matches()) {
                throw new IllegalArgumentException("ArtMesh target ID token is invalid");
            }
        }
    }

    /** Read-only structural evidence for a Part that must remain unchanged later. */
    record PartSnapshot(
        PartId id,
        String name,
        Optional<PartId> parentId,
        List<PartId> childIds,
        float opacity
    ) {
        PartSnapshot {
            id = Objects.requireNonNull(id, "id");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Part name must not be blank");
            }
            parentId = Objects.requireNonNull(parentId, "parentId");
            childIds = List.copyOf(Objects.requireNonNull(childIds, "childIds"));
            if (!Float.isFinite(opacity)) {
                throw new IllegalArgumentException("Part opacity must be finite");
            }
        }
    }

    private static <K, V> Map<K, V> immutableOrderedMap(
        final Map<K, V> values,
        final String name
    ) {
        Objects.requireNonNull(values, name);
        final LinkedHashMap<K, V> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
            Objects.requireNonNull(key, name + " key"),
            Objects.requireNonNull(value, name + " value")
        ));
        return Collections.unmodifiableMap(copy);
    }
}
