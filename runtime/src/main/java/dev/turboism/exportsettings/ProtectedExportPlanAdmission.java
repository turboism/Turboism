package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ProtectedExportCensus;
import dev.turboism.sdk.cubism.export.ProtectedExportPlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Admission for a plugin-authored {@link ProtectedExportPlan} (design C5 §2.5).
 *
 * <p>This class replaces the two deleted runtime planners and deliberately inverts them:
 * the runtime no longer <em>produces</em> a plan, it <em>verifies</em> the one the plugin's
 * {@link dev.turboism.sdk.cubism.export.ProtectedExportPlanner} returned. Verification
 * recomputes the deterministic SHA-256 token allocation over the censused GUIDs and the
 * model-wide reserved pools, and walks every deformer parent edge to prove the order is
 * leaf-to-root. A plan that disagrees in a single identity, token or edge is rejected with
 * a bounded key, so a planner can never widen its own authority, skip an identity or
 * reorder a deformer before its child.</p>
 *
 * <p>Rejection keys (all {@code protected-export.*}): {@code plan-deformer-order}, the
 * order is not exactly the deformer census once each; {@code deformer-cycle}, the order is
 * not leaf-to-root; {@code plan-obfuscation-identities}, a rewritten family's GUID set
 * disagrees with the census; {@code obfuscation-token-mismatch}, a token is not the
 * canonical allocation; {@code plan-pass-through-guids}, the pass-through set disagrees.
 * Census-level failures keep the deleted planners' keys
 * ({@code obfuscation-guid-missing/-duplicate}, {@code obfuscation-identity-missing},
 * {@code obfuscation-hash-collision}, {@code obfuscation-unallocatable}).</p>
 */
final class ProtectedExportPlanAdmission {

    private static final String ART_MESH_NAME_PREFIX = "ArtMesh_";
    private static final String ART_MESH_ID_PREFIX = "@";
    private static final String PHYSICS_NAME_PREFIX = "Physics_";
    private static final String PHYSICS_ID_PREFIX = "PhysicsId_";
    private static final String MOTION_SYNC_NAME_PREFIX = "MotionSync_";
    private static final String MOTION_SYNC_ID_PREFIX = "MotionSyncId_";
    private static final int INITIAL_HASH_LENGTH = 16;
    private static final int MAX_TARGET_ID_LENGTH = 63;
    private static final int MAX_HASH_PREFIX_LENGTH = MAX_TARGET_ID_LENGTH - ART_MESH_ID_PREFIX.length();
    private static final int MAX_SETTINGS_HASH_PREFIX_LENGTH = MAX_TARGET_ID_LENGTH - MOTION_SYNC_ID_PREFIX.length();

    private ProtectedExportPlanAdmission() {}

    /**
     * Verifies one plan against the census it was planned from.
     *
     * @throws ProtectedExportRejection naming the first bounded disagreement
     */
    static void verify(final ProtectedExportPlan plan, final ProtectedExportCensus census) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(census, "census");
        verifyDeformerOrder(plan.deformerOrderLeafToRoot(), census);
        if (!census.passThroughGuids().equals(plan.passThroughGuids())) {
            throw new ProtectedExportRejection("protected-export.plan-pass-through-guids");
        }
        final Pool pool = new Pool(census.reservedSourceNames(), census.reservedSourceIds());
        requireFamily(
                plan.artMeshTargets(),
                pool.allocate(census.artMeshGuids(), ART_MESH_NAME_PREFIX, ART_MESH_ID_PREFIX, MAX_HASH_PREFIX_LENGTH));
        requireFamily(
                plan.physicsSettings(),
                pool.allocate(
                        census.physicsSettingGuids(),
                        PHYSICS_NAME_PREFIX,
                        PHYSICS_ID_PREFIX,
                        MAX_SETTINGS_HASH_PREFIX_LENGTH));
        requireFamily(
                plan.motionSyncSettings(),
                pool.allocate(
                        census.motionSyncSettingGuids(),
                        MOTION_SYNC_NAME_PREFIX,
                        MOTION_SYNC_ID_PREFIX,
                        MAX_SETTINGS_HASH_PREFIX_LENGTH));
    }

    /**
     * The order must list every censused deformer exactly once and never before a deformer
     * that hangs under it: a child deformer is applied first, its parent afterwards.
     */
    private static void verifyDeformerOrder(final List<String> order, final ProtectedExportCensus census) {
        final Set<String> censused = Set.copyOf(census.deformerGuids());
        final Set<String> seen = new LinkedHashSet<>();
        for (String guid : order) {
            if (!censused.contains(guid) || !seen.add(guid)) {
                throw new ProtectedExportRejection("protected-export.plan-deformer-order");
            }
        }
        if (seen.size() != censused.size()) {
            throw new ProtectedExportRejection("protected-export.plan-deformer-order");
        }
        final Map<String, Integer> position = new LinkedHashMap<>();
        for (int index = 0; index < order.size(); index++) {
            position.put(order.get(index), index);
        }
        for (Map.Entry<String, String> edge : census.deformerParentGuids().entrySet()) {
            if (position.get(edge.getKey()) >= position.get(edge.getValue())) {
                throw new ProtectedExportRejection("protected-export.deformer-cycle");
            }
        }
    }

    private static void requireFamily(
            final Map<String, ProtectedExportPlan.Target> planned,
            final Map<String, ProtectedExportPlan.Target> canonical) {
        if (!planned.keySet().equals(canonical.keySet())) {
            throw new ProtectedExportRejection("protected-export.plan-obfuscation-identities");
        }
        if (!planned.equals(canonical)) {
            throw new ProtectedExportRejection("protected-export.obfuscation-token-mismatch");
        }
    }

    private static String sha256Hex(final String guid) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return HexFormat.of().formatHex(digest.digest(guid.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The canonical allocation of one verification run: every rewritten family shares the
     * hash set, the used-name set and the used-ID set, so a token can never collide with
     * another family's token, with an earlier allocation, or with a reserved authored
     * identity. The suffix is the shortest SHA-256 prefix of at least
     * {@value #INITIAL_HASH_LENGTH} characters that collides with nothing, capped at the
     * longest prefix a {@value #MAX_TARGET_ID_LENGTH}-character ID token can carry.
     */
    private static final class Pool {
        private final Set<String> reservedNames;
        private final Set<String> reservedIds;
        private final Set<String> hashes = new LinkedHashSet<>();
        private final Set<String> usedNames = new LinkedHashSet<>();
        private final Set<String> usedIds = new LinkedHashSet<>();

        private Pool(final Set<String> reservedNames, final Set<String> reservedIds) {
            this.reservedNames = Objects.requireNonNull(reservedNames, "reservedNames");
            this.reservedIds = Objects.requireNonNull(reservedIds, "reservedIds");
        }

        private Map<String, ProtectedExportPlan.Target> allocate(
                final List<String> guids, final String namePrefix, final String idPrefix, final int maxPrefix) {
            final Map<String, ProtectedExportPlan.Target> planned = new LinkedHashMap<>();
            for (String guid : guids) {
                final String hash = sha256Hex(guid);
                if (!hashes.add(hash)) {
                    throw new ProtectedExportRejection("protected-export.obfuscation-hash-collision");
                }
                boolean allocated = false;
                final int ceiling = Math.min(hash.length(), maxPrefix);
                for (int length = INITIAL_HASH_LENGTH; length <= ceiling; length++) {
                    final String suffix = hash.substring(0, length);
                    final String name = namePrefix + suffix;
                    final String idToken = idPrefix + suffix;
                    if (reservedNames.contains(name)
                            || reservedNames.contains(idToken)
                            || reservedIds.contains(name)
                            || reservedIds.contains(idToken)
                            || usedNames.contains(name)
                            || usedIds.contains(idToken)) {
                        continue;
                    }
                    planned.put(guid, new ProtectedExportPlan.Target(name, idToken));
                    usedNames.add(name);
                    usedIds.add(idToken);
                    allocated = true;
                    break;
                }
                if (!allocated) {
                    throw new ProtectedExportRejection("protected-export.obfuscation-unallocatable");
                }
            }
            return planned;
        }
    }
}
