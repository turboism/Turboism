package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ProtectedExportCensus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the read-only {@link ProtectedExportCensus} the plugin's planner receives.
 *
 * <p>This class replaces the two deleted runtime planners
 * ({@code ProtectedExportObfuscationPlan}, {@code ProtectedExportDeformerPlan}): it only
 * <em>reads</em> the model source through {@link ProtectedExportHostOperations} and copies
 * the identities a plan needs — never a host object and never a write. The token allocation
 * itself belongs to the plugin; the runtime's {@link ProtectedExportPlanAdmission} verifies
 * what comes back.</p>
 *
 * <p>Every read that cannot be pinned rejects with a bounded {@code protected-export.*}
 * key instead of riding through: a missing or duplicate GUID, a blank authored identity, a
 * pass-through object without a stable GUID and an unsupported deformer family are all
 * census failures, never silently skipped members.</p>
 */
final class ProtectedExportCensusBuilder {

    private ProtectedExportCensusBuilder() {}

    /**
     * The flatten surface of one model source: the deformer GUIDs plus their resolved
     * child-to-parent edges. Binding compares the copy's sub-census against the source's,
     * because a copy that re-parented or dropped a deformer is not the censused model.
     *
     * <p>{@code parentGuids} carries only edges that resolve inside the deformer census; a
     * dangling or absent host parent edge classifies the deformer as a root, matching the
     * native downward-traversal semantics.</p>
     */
    record DeformerCensus(List<String> guids, Map<String, String> parentGuids) {
        DeformerCensus {
            guids = List.copyOf(Objects.requireNonNull(guids, "guids"));
            parentGuids = Map.copyOf(Objects.requireNonNull(parentGuids, "parentGuids"));
        }
    }

    /** The full planner input census of one model source. */
    static ProtectedExportCensus build(final ProtectedExportHostOperations host, final Object modelSource) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(modelSource, "modelSource");
        final DeformerCensus deformers = deformerCensus(host, modelSource);

        final Set<String> artMeshes = new TreeSet<>();
        for (Object mesh :
                requireList(host.allArtMeshes(modelSource), "protected-export.obfuscation-census-ambiguous")) {
            if (!host.isArtMeshSource(mesh)) {
                throw new ProtectedExportRejection("protected-export.obfuscation-census-ambiguous");
            }
            final String guid = requireText(host.objectGuid(mesh), "protected-export.obfuscation-guid-missing");
            if (!artMeshes.add(guid)) {
                throw new ProtectedExportRejection("protected-export.obfuscation-guid-duplicate");
            }
            if (blank(host.objectLocalName(mesh)) || blank(host.drawableIdString(mesh))) {
                throw new ProtectedExportRejection("protected-export.obfuscation-identity-missing");
            }
        }

        // Reserve every source identity in the model so a token can never collide with an
        // identity that must survive — including each ArtMesh's own, which additionally
        // guarantees every rewrite is a real change.
        final Set<String> reservedIds = new TreeSet<>();
        final Set<String> reservedNames = new TreeSet<>();
        final Set<String> passThroughGuids = new TreeSet<>();
        for (Object object :
                requireList(host.allObjects(modelSource), "protected-export.obfuscation-census-ambiguous")) {
            if (object == null || !host.isControllableSource(object)) {
                continue;
            }
            if (!host.isWarpDeformer(object)
                    && !host.isRotationDeformer(object)
                    && !host.isArtMeshSource(object)
                    && !host.isPartSource(object)) {
                // Pass-through channel: the object's own identity is reserved like every
                // other source's but never planned for a rewrite.
                final String guid = requireText(
                        host.objectGuid(object), "protected-export.obfuscation-pass-through-identity-missing");
                passThroughGuids.add(guid);
            }
            final String id = host.objectIdString(object);
            if (id != null) {
                reservedIds.add(id);
            }
            if (host.isArtMeshSource(object)) {
                final String drawableId = host.drawableIdString(object);
                if (drawableId != null) {
                    reservedIds.add(drawableId);
                }
            }
            final String name = host.objectLocalName(object);
            if (name != null) {
                reservedNames.add(name);
            }
        }
        // Parameter sources sit outside the controllable census; reserve their identities
        // through the parameter-source accessors.
        for (Object parameter :
                requireList(host.allParameters(modelSource), "protected-export.obfuscation-census-ambiguous")) {
            final String id = host.parameterSourceIdString(parameter);
            if (id != null) {
                reservedIds.add(id);
            }
            final String name = host.parameterSourceName(parameter);
            if (name != null) {
                reservedNames.add(name);
            }
        }
        // Physics and motion-sync settings live outside the object census as well: each is
        // identified, its authored identity is reserved, and its GUID joins the family the
        // planner allocates family-prefixed tokens for.
        final Set<String> physicsGuids =
                settings(host, host.allPhysicsSettings(modelSource), reservedIds, reservedNames, "physics");
        final Set<String> motionSyncGuids =
                settings(host, host.allMotionSyncSettings(modelSource), reservedIds, reservedNames, "motion-sync");

        return new ProtectedExportCensus(
                deformers.guids(),
                deformers.parentGuids(),
                List.copyOf(artMeshes),
                List.copyOf(physicsGuids),
                List.copyOf(motionSyncGuids),
                Set.copyOf(passThroughGuids),
                Set.copyOf(reservedNames),
                Set.copyOf(reservedIds));
    }

    /** The deformer sub-census of one model source; see {@link DeformerCensus}. */
    static DeformerCensus deformerCensus(final ProtectedExportHostOperations host, final Object modelSource) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(modelSource, "modelSource");
        final List<?> read =
                requireList(host.allDeformers(modelSource), "protected-export.deformer-census-unavailable");
        final Map<String, Object> byGuid = new TreeMap<>();
        for (Object deformer : read) {
            if (!host.isWarpDeformer(deformer) && !host.isRotationDeformer(deformer)) {
                throw new ProtectedExportRejection("protected-export.unsupported-deformer-family");
            }
            final String guid = requireText(host.deformerGuid(deformer), "protected-export.deformer-guid-missing");
            if (byGuid.putIfAbsent(guid, deformer) != null) {
                throw new ProtectedExportRejection("protected-export.deformer-guid-duplicate");
            }
        }
        final Map<String, String> parentGuids = new TreeMap<>();
        for (Map.Entry<String, Object> entry : byGuid.entrySet()) {
            final String target = host.deformerTargetGuid(entry.getValue());
            if (target == null || target.isBlank() || !byGuid.containsKey(target)) {
                continue;
            }
            if (target.equals(entry.getKey())) {
                throw new ProtectedExportRejection("protected-export.deformer-parent-self");
            }
            parentGuids.put(entry.getKey(), target);
        }
        return new DeformerCensus(List.copyOf(byGuid.keySet()), parentGuids);
    }

    /**
     * Validates and reserves every settings source of one family, returning the sorted
     * census GUIDs. A member of the wrong shape, a blank GUID, a duplicate GUID or a blank
     * authored name/ID is a hard rejection — the settings census never lets an unpinnable
     * member ride through.
     */
    private static Set<String> settings(
            final ProtectedExportHostOperations host,
            final List<?> settings,
            final Set<String> reservedIds,
            final Set<String> reservedNames,
            final String family) {
        final Set<String> guids = new TreeSet<>();
        for (Object setting : requireList(settings, "protected-export.unpinnable-settings:" + family)) {
            final String guid = requireText(host.settingsGuid(setting), "protected-export.obfuscation-guid-missing");
            if (!guids.add(guid)) {
                throw new ProtectedExportRejection("protected-export.obfuscation-guid-duplicate");
            }
            final String id = host.settingsIdString(setting);
            final String name = host.settingsName(setting);
            if (blank(id) || blank(name)) {
                throw new ProtectedExportRejection("protected-export.obfuscation-identity-missing");
            }
            reservedIds.add(id);
            reservedNames.add(name);
        }
        return guids;
    }

    private static List<?> requireList(final List<?> values, final String messageKey) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            throw new ProtectedExportRejection(messageKey);
        }
        return List.copyOf(values);
    }

    private static String requireText(final String value, final String messageKey) {
        if (blank(value)) {
            throw new ProtectedExportRejection(messageKey);
        }
        return value;
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }
}
