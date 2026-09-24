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
import java.util.TreeSet;

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
 *
 * <p>Everything else in the census is pass-through content, never filtered: any
 * controllable source outside the flatten/obfuscate surface (Glue, ArtPath, alias,
 * and future families alike) is admitted and pinned by the model census instead.
 * The only remaining structural rejections are objects whose identity cannot be
 * pinned — a census member that is not a controllable source, or carries no stable
 * GUID or ID — and duplicate identities, which would make the census ambiguous.</p>
 */
public final class ProtectedExportDeformerPlan {

    /**
     * Deterministic leaf-to-root deformer GUID application order, plus the stable
     * GUIDs of every census object admitted as an untouched pass-through channel —
     * they are never applied, renamed or re-identified; the list exists so every
     * consumer of the plan can see the channel was censused deliberately.
     */
    public record Order(List<String> leafToRootGuids, List<String> passThroughGuids) {
        public Order {
            leafToRootGuids = List.copyOf(Objects.requireNonNull(leafToRootGuids,
                "leafToRootGuids"));
            passThroughGuids = List.copyOf(Objects.requireNonNull(
                passThroughGuids, "passThroughGuids"));
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
        final List<String> passThroughGuids = admitStructure(host, modelSource);
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
        return new Order(order, passThroughGuids);
    }

    /**
     * Whole-structure admission. The parameter-controllable census ({@code allObjects}
     * covers drawable, deformer, affecter, part and alias sources on the exact host)
     * no longer filters families: Warp/Rotation deformers are flattened, ArtMeshes
     * are obfuscated, parts are preserved, and every other controllable source is an
     * untouched pass-through member whose identity and references the model census
     * pins. Physics and motion-sync settings live outside the census and pass
     * through under their own pinning.
     *
     * <p>What still rejects is only what cannot be safely pinned: a census member
     * that is not a controllable source or carries no stable GUID/ID, an unknown
     * family whose references cannot be enumerated, or a duplicate identity that
     * would make the census ambiguous. Embedded content (morph-target sets,
     * extensions such as deform-path skinning) and the host {@code contain*}
     * feature flags are pass-through as well — they are pinned by the census, not
     * rejected.</p>
     *
     * @return the sorted stable GUIDs of the admitted pass-through sources, never
     *     {@code null}
     */
    private static List<String> admitStructure(
        final ProtectedExportHostOperations host,
        final Object modelSource
    ) {
        final List<?> objects = host.allObjects(modelSource);
        final Map<String, Integer> unpinnable = new TreeMap<>();
        final Set<String> seenGuids = new LinkedHashSet<>();
        final Set<String> duplicateGuids = new LinkedHashSet<>();
        final List<String> passThrough = new ArrayList<>();
        for (Object object : objects) {
            final String family = familyOf(host, object);
            final boolean protectedFamily = object != null
                && (host.isWarpDeformer(object) || host.isRotationDeformer(object)
                    || host.isArtMeshSource(object) || host.isPartSource(object));
            final boolean knownPassThrough = !protectedFamily
                && ("glue".equals(family) || "art-path".equals(family)
                    || "alias".equals(family));
            final String guid = protectedFamily || knownPassThrough
                ? pinnableGuid(host, object) : null;
            if (guid == null) {
                // Bounded key space: overflow families fold into "other" so the
                // detail stays readable on pathological censuses.
                final String key = unpinnable.size() < 12
                        || unpinnable.containsKey(family)
                    ? family : "other";
                unpinnable.merge(key, 1, Integer::sum);
                continue;
            }
            if (!seenGuids.add(guid)) {
                duplicateGuids.add(guid);
            }
            if (knownPassThrough) {
                passThrough.add(guid);
            }
        }
        if (!unpinnable.isEmpty()) {
            final StringBuilder detail =
                new StringBuilder("protected-export.unpinnable-structure:");
            for (Map.Entry<String, Integer> entry : unpinnable.entrySet()) {
                detail.append(entry.getKey()).append('=').append(entry.getValue())
                    .append(',');
            }
            detail.setLength(detail.length() - 1);
            throw new ProtectedExportPlanRejection(detail.toString());
        }
        if (!duplicateGuids.isEmpty()) {
            throw new ProtectedExportPlanRejection(
                "protected-export.duplicate-guid:"
                    + String.join(",", new TreeSet<>(duplicateGuids)));
        }
        passThrough.sort(Comparator.naturalOrder());
        return List.copyOf(passThrough);
    }

    /**
     * The stable GUID of a census member, or {@code null} when the member has no
     * pinnable identity — not a controllable source, a blank/missing GUID, or a
     * blank/missing ID. Such members reject the session rather than pass through
     * half-pinned.
     */
    private static String pinnableGuid(
        final ProtectedExportHostOperations host,
        final Object object
    ) {
        if (object == null || !host.isControllableSource(object)) {
            return null;
        }
        try {
            final String guid = host.objectGuid(object);
            final String id = host.objectIdString(object);
            if (guid == null || guid.isBlank() || id == null || id.isBlank()) {
                return null;
            }
            return guid;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Bounded family token for diagnostics; degrades to {@code unknown} when the
     * host cannot classify the object at all.
     */
    private static String familyOf(
        final ProtectedExportHostOperations host,
        final Object object
    ) {
        if (object == null) {
            return "null";
        }
        String family;
        try {
            family = host.censusFamily(object);
        } catch (Throwable failure) {
            family = "unknown";
        }
        return family == null || family.isBlank() ? "unknown" : family;
    }

    /** Bounded preflight rejection identity for an unplannable deformer census. */
    public static final class ProtectedExportPlanRejection extends RuntimeException {
        public ProtectedExportPlanRejection(final String messageKey) {
            super(Objects.requireNonNull(messageKey, "messageKey"));
        }
    }
}
