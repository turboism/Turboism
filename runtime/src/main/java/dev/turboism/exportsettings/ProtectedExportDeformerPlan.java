package dev.turboism.exportsettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

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
        admitStructure(host, modelSource);
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

    /**
     * Whole-structure admission. The parameter-controllable census ({@code allObjects}
     * covers drawable, deformer, affecter, part and alias sources on the exact host)
     * must contain only supported families — Warp/Rotation deformers, ArtMeshes and
     * parts. Glue sources are affecters, ArtPath/DeformPath sources are drawables and
     * aliases are controllable sources, so every one of them lands in this census and
     * is rejected here. Physics and motion-sync settings live outside the census and
     * get their own emptiness check.
     *
     * <p>Category whitelisting alone is not sufficient: unsupported content also
     * hides <em>inside</em> otherwise-allowed sources. The host's own {@code contain*}
     * gates answer the model-level families (blend colors, morph-target
     * parameters/enhancements, aliases, art paths, inverted clipping, quad
     * transforms, offscreen rendering, motion sync), and every admitted object is
     * scanned for embedded members the census cannot see — keyform morph-target
     * sets, extended morph-target sets and attached extension objects. Anything
     * detected or unknown rejects; the runtime never relies on a plugin-side
     * planner having run first.</p>
     */
    private static void admitStructure(
        final ProtectedExportHostOperations host,
        final Object modelSource
    ) {
        final List<?> objects = host.allObjects(modelSource);
        final Map<String, Integer> unsupported = new TreeMap<>();
        for (Object object : objects) {
            final boolean supported = object != null
                && (host.isWarpDeformer(object) || host.isRotationDeformer(object)
                    || host.isArtMeshSource(object) || host.isPartSource(object));
            if (!supported) {
                String family;
                try {
                    family = host.unsupportedObjectFamily(object);
                } catch (Throwable failure) {
                    family = "unknown";
                }
                if (family == null || family.isBlank()) {
                    family = "unknown";
                }
                // Bounded key space: overflow families fold into "other" so the
                // detail stays readable on pathological censuses.
                final String key =
                    unsupported.size() < 12 || unsupported.containsKey(family)
                        ? family : "other";
                unsupported.merge(key, 1, Integer::sum);
            }
        }
        if (!unsupported.isEmpty()) {
            final StringBuilder detail =
                new StringBuilder("protected-export.unsupported-structure:");
            for (Map.Entry<String, Integer> entry : unsupported.entrySet()) {
                detail.append(entry.getKey()).append('=').append(entry.getValue())
                    .append(',');
            }
            detail.setLength(detail.length() - 1);
            throw new ProtectedExportPlanRejection(detail.toString());
        }
        final int physicsSettings = host.allPhysicsSettings(modelSource).size();
        final int motionSyncSettings = host.allMotionSyncSettings(modelSource).size();
        if (physicsSettings != 0 || motionSyncSettings != 0) {
            throw new ProtectedExportPlanRejection(
                "protected-export.unsupported-settings:physics="
                    + physicsSettings + ",motion-sync=" + motionSyncSettings);
        }
        final List<String> features = host.unsupportedModelFeatures(modelSource);
        if (!features.isEmpty()) {
            throw new ProtectedExportPlanRejection(
                "protected-export.unsupported-feature:"
                    + String.join(",", features));
        }
        for (Object object : objects) {
            if (object == null) {
                continue;
            }
            final List<String> embedded = host.embeddedUnsupportedFamilies(object);
            if (!embedded.isEmpty()) {
                throw new ProtectedExportPlanRejection(
                    "protected-export.embedded-structure:"
                        + String.join(",", embedded));
            }
        }
    }

    /** Bounded preflight rejection identity for an unplannable deformer census. */
    public static final class ProtectedExportPlanRejection extends RuntimeException {
        public ProtectedExportPlanRejection(final String messageKey) {
            super(Objects.requireNonNull(messageKey, "messageKey"));
        }
    }
}
