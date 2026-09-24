package dev.turboism.exportsettings;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure ArtMesh-obfuscation planner over {@link ProtectedExportHostOperations} reads.
 *
 * <p>Mirrors the plugin-side {@code ProtectedExportPlanner} token scheme: every ArtMesh
 * receives a deterministic name {@code ArtMesh_<hex>} and drawable-ID token
 * {@code @<hex>} derived from the SHA-256 of its stable GUID. The hex suffix starts at
 * 16 characters and lengthens until the pair collides with nothing — not with any
 * reserved source name/ID (including the ArtMesh's own), not with any token already
 * allocated in this plan. Reserved coverage spans every object of the model source, so
 * a generated token can never alias an identity the export must preserve.</p>
 *
 * <p>Missing/blank GUIDs, names or IDs, duplicate GUIDs and duplicate GUID hashes are
 * hard rejections — never skipped.</p>
 */
public final class ProtectedExportObfuscationPlan {

    private static final String NAME_PREFIX = "ArtMesh_";
    private static final String ID_PREFIX = "@";
    private static final int INITIAL_HASH_LENGTH = 16;
    private static final int MAX_TARGET_ID_LENGTH = 63;
    private static final int MAX_HASH_PREFIX_LENGTH =
        MAX_TARGET_ID_LENGTH - ID_PREFIX.length();

    /** One ArtMesh's planned protected identity. */
    public record Target(String name, String idToken) {
        public Target {
            name = requireText(name, "name");
            idToken = requireText(idToken, "idToken");
        }
    }

    /**
     * Deterministic GUID → protected-identity mapping for every censused ArtMesh,
     * plus the stable GUIDs of Glue sources admitted as an untouched pass-through
     * channel. Glue GUIDs never appear in {@code byGuid}: a Glue keeps its
     * authored name, ID and mesh references.
     */
    public record Plan(Map<String, Target> byGuid, List<String> passThroughGlueGuids) {
        public Plan {
            byGuid = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(byGuid, "byGuid")));
            passThroughGlueGuids = List.copyOf(Objects.requireNonNull(
                passThroughGlueGuids, "passThroughGlueGuids"));
        }

        /** Every planned drawable-ID token. */
        public Set<String> idTokens() {
            final Set<String> tokens = new LinkedHashSet<>();
            byGuid.values().forEach(target -> tokens.add(target.idToken()));
            return Set.copyOf(tokens);
        }
    }

    private ProtectedExportObfuscationPlan() {
    }

    /**
     * Computes the obfuscation plan for a bound copy's model source.
     *
     * @throws ProtectedExportDeformerPlan.ProtectedExportPlanRejection on any
     *     unsupported or ambiguous census
     */
    public static Plan plan(
        final ProtectedExportHostOperations host,
        final Object modelSource
    ) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(modelSource, "modelSource");

        final List<?> artMeshes = host.allArtMeshes(modelSource);
        final Map<String, Object> byGuid = new LinkedHashMap<>();
        for (Object mesh : artMeshes) {
            if (!host.isArtMeshSource(mesh)) {
                throw reject("protected-export.obfuscation-census-ambiguous");
            }
            final String guid = host.objectGuid(mesh);
            if (guid == null || guid.isBlank()) {
                throw reject("protected-export.obfuscation-guid-missing");
            }
            if (byGuid.putIfAbsent(guid, mesh) != null) {
                throw reject("protected-export.obfuscation-guid-duplicate");
            }
            if (blank(host.objectLocalName(mesh)) || blank(host.drawableIdString(mesh))) {
                throw reject("protected-export.obfuscation-identity-missing");
            }
        }

        // Reserve every source identity in the model so a token can never collide
        // with an identity that must survive — including each ArtMesh's own, which
        // additionally guarantees every rewrite is a real change.
        final Set<String> reservedIds = new LinkedHashSet<>();
        final Set<String> reservedNames = new LinkedHashSet<>();
        final Set<String> glueGuids = new LinkedHashSet<>();
        for (Object object : host.allObjects(modelSource)) {
            if (host.isGlueSource(object)) {
                // Pass-through channel: the Glue's own identity is reserved like
                // every other source's but never planned for a rewrite.
                final String guid = host.objectGuid(object);
                if (guid == null || guid.isBlank()) {
                    throw reject("protected-export.obfuscation-glue-identity-missing");
                }
                glueGuids.add(guid);
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
        // Parameter sources sit outside the controllable census; reserve their
        // identities through the parameter-source accessors.
        for (Object parameter : host.allParameters(modelSource)) {
            final String id = host.parameterSourceIdString(parameter);
            if (id != null) {
                reservedIds.add(id);
            }
            final String name = host.parameterSourceName(parameter);
            if (name != null) {
                reservedNames.add(name);
            }
        }

        final Map<String, Target> result = new LinkedHashMap<>();
        final Set<String> hashes = new LinkedHashSet<>();
        final Set<String> usedNames = new LinkedHashSet<>();
        final Set<String> usedIds = new LinkedHashSet<>();
        final List<String> guids = byGuid.keySet().stream()
            .sorted(Comparator.naturalOrder())
            .toList();
        for (String guid : guids) {
            final String hash = sha256Hex(guid);
            if (!hashes.add(hash)) {
                throw reject("protected-export.obfuscation-hash-collision");
            }
            boolean allocated = false;
            final int ceiling = Math.min(hash.length(), MAX_HASH_PREFIX_LENGTH);
            for (int length = INITIAL_HASH_LENGTH; length <= ceiling; length++) {
                final String suffix = hash.substring(0, length);
                final String name = NAME_PREFIX + suffix;
                final String idToken = ID_PREFIX + suffix;
                if (reservedNames.contains(name) || reservedNames.contains(idToken)
                    || reservedIds.contains(name) || reservedIds.contains(idToken)
                    || usedNames.contains(name) || usedIds.contains(idToken)) {
                    continue;
                }
                result.put(guid, new Target(name, idToken));
                usedNames.add(name);
                usedIds.add(idToken);
                allocated = true;
                break;
            }
            if (!allocated) {
                throw reject("protected-export.obfuscation-unallocatable");
            }
        }
        final List<String> passThrough = new ArrayList<>(glueGuids);
        passThrough.sort(Comparator.naturalOrder());
        return new Plan(result, passThrough);
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    private static String sha256Hex(final String guid) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return HexFormat.of().formatHex(
            digest.digest(guid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static ProtectedExportDeformerPlan.ProtectedExportPlanRejection reject(
        final String key
    ) {
        return new ProtectedExportDeformerPlan.ProtectedExportPlanRejection(key);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
