package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, read-only census of one protected-export staging copy: the identities the
 * {@link ProtectedExportPlanner} may plan against, plus the reserved pools a token must
 * never collide with.
 *
 * <p>The census carries copied read evidence only. It contains no host objects, no paths
 * and no authority: nothing in it can be written back, opened or exported. Every collection
 * is sorted by stable GUID so two censuses of the same model compare equal regardless of
 * host iteration order.</p>
 *
 * <p>{@code deformerParentGuids} holds only child-to-parent edges that resolve inside
 * {@link #deformerGuids()}; a deformer whose host parent edge is absent or points outside
 * the deformer census is a root (the host's own downward traversal semantics).</p>
 */
@Incubating
@CubismEditor({
    "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02",
    "5.3.03", "5.3.04"
})
public record ProtectedExportCensus(
        List<String> deformerGuids,
        Map<String, String> deformerParentGuids,
        List<String> artMeshGuids,
        List<String> physicsSettingGuids,
        List<String> motionSyncSettingGuids,
        Set<String> passThroughGuids,
        Set<String> reservedSourceNames,
        Set<String> reservedSourceIds) {

    public ProtectedExportCensus {
        deformerGuids = sortedGuids(deformerGuids, "deformerGuids");
        artMeshGuids = sortedGuids(artMeshGuids, "artMeshGuids");
        physicsSettingGuids = sortedGuids(physicsSettingGuids, "physicsSettingGuids");
        motionSyncSettingGuids = sortedGuids(motionSyncSettingGuids, "motionSyncSettingGuids");
        final Set<String> deformers = Set.copyOf(deformerGuids);
        deformerParentGuids = Map.copyOf(Objects.requireNonNull(deformerParentGuids, "deformerParentGuids"));
        for (Map.Entry<String, String> edge : deformerParentGuids.entrySet()) {
            text(edge.getKey(), "deformerParentGuids key");
            text(edge.getValue(), "deformerParentGuids value");
            if (!deformers.contains(edge.getKey()) || !deformers.contains(edge.getValue())) {
                throw new IllegalArgumentException("deformer parent edge must resolve inside the deformer census");
            }
            if (edge.getKey().equals(edge.getValue())) {
                throw new IllegalArgumentException("deformer parent edge must not be reflexive");
            }
        }
        passThroughGuids = Set.copyOf(Objects.requireNonNull(passThroughGuids, "passThroughGuids"));
        for (String guid : passThroughGuids) {
            text(guid, "passThroughGuids");
        }
        reservedSourceNames = Set.copyOf(Objects.requireNonNull(reservedSourceNames, "reservedSourceNames"));
        for (String name : reservedSourceNames) {
            text(name, "reservedSourceNames");
        }
        reservedSourceIds = Set.copyOf(Objects.requireNonNull(reservedSourceIds, "reservedSourceIds"));
        for (String id : reservedSourceIds) {
            text(id, "reservedSourceIds");
        }
        // One GUID belongs to one rewritten family: a shared identity would receive two
        // different token pairs, and a pass-through GUID must never be rewritten at all.
        final Set<String> rewritten = new HashSet<>(artMeshGuids);
        for (String guid : physicsSettingGuids) {
            if (!rewritten.add(guid)) {
                throw new IllegalArgumentException("one GUID must not belong to two rewritten families");
            }
        }
        for (String guid : motionSyncSettingGuids) {
            if (!rewritten.add(guid)) {
                throw new IllegalArgumentException("one GUID must not belong to two rewritten families");
            }
        }
        for (String guid : passThroughGuids) {
            if (rewritten.contains(guid)) {
                throw new IllegalArgumentException("a pass-through GUID must not be rewritten");
            }
        }
    }

    private static List<String> sortedGuids(final List<String> guids, final String name) {
        Objects.requireNonNull(guids, name);
        for (String guid : guids) {
            text(guid, name);
        }
        final List<String> sorted = guids.stream().distinct().sorted().toList();
        if (sorted.size() != guids.size()) {
            throw new IllegalArgumentException(name + " must contain distinct GUIDs");
        }
        return sorted;
    }

    private static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
