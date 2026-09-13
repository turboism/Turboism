package dev.turboism.exportsettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure deformer-flatten planner over {@link ProtectedExportHostOperations} reads.
 *
 * <p>Every deformer in the census must be a supported family (Warp or Rotation). The
 * application order is deterministic leaf-to-root over the deformer-parent edges
 * ({@code targetDeformerGuid}): a deformer is applied only after every deformer
 * targeting it. Parents that are absent or resolve to non-deformer anchors classify
 * as roots, matching the native downward-traversal semantics. Cycles, duplicate GUIDs,
 * unresolved deformer-parent edges and unsupported families are hard rejections —
 * never skipped.</p>
 */
public final class ProtectedExportDeformerPlan {

    /** Deterministic leaf-to-root deformer GUID application order. */
    public record Order(List<String> leafToRootGuids) {
        public Order {
            leafToRootGuids = List.copyOf(Objects.requireNonNull(leafToRootGuids,
                "leafToRootGuids"));
        }
    }

    private ProtectedExportDeformerPlan() {
    }

    /**
     * Computes the deterministic leaf-to-root application order for a model source.
     *
     * @throws ProtectedExportPlanRejection on any unsupported or ambiguous census
     */
    public static Order plan(
        final ProtectedExportHostOperations host,
        final Object modelSource
    ) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(modelSource, "modelSource");
        final List<?> deformers = host.allDeformers(modelSource);
        final Map<String, Object> byGuid = new LinkedHashMap<>();
        for (Object deformer : deformers) {
            if (!host.isWarpDeformer(deformer) && !host.isRotationDeformer(deformer)) {
                throw new ProtectedExportPlanRejection(
                    "protected-export.unsupported-deformer-family");
            }
            final String guid = host.deformerGuid(deformer);
            if (guid == null || guid.isBlank()) {
                throw new ProtectedExportPlanRejection(
                    "protected-export.deformer-guid-missing");
            }
            if (byGuid.putIfAbsent(guid, deformer) != null) {
                throw new ProtectedExportPlanRejection(
                    "protected-export.deformer-guid-duplicate");
            }
        }

        // child -> parent edges restricted to deformer parents; anything else is a
        // dangling anchor and classifies the node as a root (native semantics).
        final Map<String, String> parent = new LinkedHashMap<>();
        final Map<String, Set<String>> children = new LinkedHashMap<>();
        for (String guid : byGuid.keySet()) {
            children.put(guid, new LinkedHashSet<>());
        }
        for (Map.Entry<String, Object> entry : byGuid.entrySet()) {
            final String target = host.deformerTargetGuid(entry.getValue());
            if (target == null || !byGuid.containsKey(target)) {
                parent.put(entry.getKey(), null);
                continue;
            }
            if (target.equals(entry.getKey())) {
                throw new ProtectedExportPlanRejection(
                    "protected-export.deformer-parent-self");
            }
            parent.put(entry.getKey(), target);
            children.get(target).add(entry.getKey());
        }

        // Kahn leaf-to-root: a node becomes ready once all its deformer children are
        // emitted; the ready set is GUID-sorted so ties are deterministic.
        final List<String> order = new ArrayList<>(byGuid.size());
        final Set<String> emitted = new LinkedHashSet<>();
        final List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : children.entrySet()) {
            if (entry.getValue().isEmpty()) {
                ready.add(entry.getKey());
            }
        }
        while (!ready.isEmpty()) {
            ready.sort(Comparator.naturalOrder());
            final String guid = ready.remove(0);
            if (!emitted.add(guid)) {
                continue;
            }
            order.add(guid);
            final String parentGuid = parent.get(guid);
            if (parentGuid != null) {
                final Set<String> siblings = children.get(parentGuid);
                siblings.remove(guid);
                if (siblings.isEmpty()) {
                    ready.add(parentGuid);
                }
            }
        }
        if (emitted.size() != byGuid.size()) {
            throw new ProtectedExportPlanRejection(
                "protected-export.deformer-cycle");
        }
        return new Order(order);
    }

    /** Bounded preflight rejection identity for an unplannable deformer census. */
    public static final class ProtectedExportPlanRejection extends RuntimeException {
        public ProtectedExportPlanRejection(final String messageKey) {
            super(Objects.requireNonNull(messageKey, "messageKey"));
        }
    }
}
