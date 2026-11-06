package dev.turboism.plugin.protectedexport;

import dev.turboism.sdk.cubism.export.ProtectedExportCensus;
import dev.turboism.sdk.cubism.export.ProtectedExportPlan;
import dev.turboism.sdk.cubism.export.ProtectedExportPlanResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic protected-export planner over the runtime-provided
 * {@link ProtectedExportCensus}.
 *
 * <p>This is the plugin's single planner: it computes the leaf-to-root deformer order and
 * the SHA-256 obfuscation targets for ArtMeshes, physics settings and motion-sync settings
 * in one place, from copied read evidence only. It never opens a document, mutates a model
 * or performs an export, and the runtime verifies every returned entry against its own
 * census before it rewrites a single identity.</p>
 *
 * <p>Token scheme: every censused identity receives a deterministic family-prefixed name
 * and private ID token derived from the SHA-256 of its stable GUID ({@code ArtMesh_<hex>} /
 * {@code @<hex>}, {@code Physics_<hex>} / {@code PhysicsId_<hex>}, {@code MotionSync_<hex>}
 * / {@code MotionSyncId_<hex>}). The hex suffix starts at 16 characters and lengthens until
 * the pair collides with nothing — not with a reserved source name/ID (including the
 * identity's own), not with an earlier allocation — so the rewrite is a real change and can
 * never alias an authored identity.</p>
 */
final class ProtectedExportPlanner implements dev.turboism.sdk.cubism.export.ProtectedExportPlanner {

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

    @Override
    public ProtectedExportPlanResult plan(final ProtectedExportCensus census) {
        Objects.requireNonNull(census, "census");
        try {
            final Pool pool = new Pool(census.reservedSourceNames(), census.reservedSourceIds());
            final Map<String, ProtectedExportPlan.Target> artMeshes = pool.allocate(
                    census.artMeshGuids(), ART_MESH_NAME_PREFIX, ART_MESH_ID_PREFIX, MAX_HASH_PREFIX_LENGTH);
            final Map<String, ProtectedExportPlan.Target> physics = pool.allocate(
                    census.physicsSettingGuids(),
                    PHYSICS_NAME_PREFIX,
                    PHYSICS_ID_PREFIX,
                    MAX_SETTINGS_HASH_PREFIX_LENGTH);
            final Map<String, ProtectedExportPlan.Target> motionSync = pool.allocate(
                    census.motionSyncSettingGuids(),
                    MOTION_SYNC_NAME_PREFIX,
                    MOTION_SYNC_ID_PREFIX,
                    MAX_SETTINGS_HASH_PREFIX_LENGTH);
            return new ProtectedExportPlanResult.Admitted(new ProtectedExportPlan(
                    leafToRoot(census), artMeshes, physics, motionSync, census.passThroughGuids()));
        } catch (PlanRejection rejection) {
            return new ProtectedExportPlanResult.Rejected(rejection.getMessage());
        }
    }

    /**
     * Deterministic leaf-to-root order over the census's child-to-parent edges: a deformer
     * is emitted only after every deformer hanging under it. Roots and siblings are visited
     * in GUID order, and the traversal is iterative so a deep hierarchy cannot overflow the
     * stack. A cycle is a rejection — never a silently truncated order.
     */
    private static List<String> leafToRoot(final ProtectedExportCensus census) {
        final Map<String, List<String>> children = new TreeMap<>();
        for (String guid : census.deformerGuids()) {
            children.put(guid, new ArrayList<>());
        }
        for (Map.Entry<String, String> edge : census.deformerParentGuids().entrySet()) {
            children.get(edge.getValue()).add(edge.getKey());
        }
        children.values().forEach(list -> list.sort(String::compareTo));

        final Map<String, Integer> state = new HashMap<>();
        final List<String> order = new ArrayList<>(children.size());
        final List<String> starts = new ArrayList<>(children.keySet());
        for (String start : starts) {
            if (state.getOrDefault(start, 0) != 0) {
                continue;
            }
            final Deque<Frame> pending = new ArrayDeque<>();
            pending.push(new Frame(start, false));
            while (!pending.isEmpty()) {
                final Frame frame = pending.pop();
                final int current = state.getOrDefault(frame.guid(), 0);
                if (frame.expanded()) {
                    if (current != 1) {
                        continue;
                    }
                    order.add(frame.guid());
                    state.put(frame.guid(), 2);
                    continue;
                }
                if (current == 1) {
                    throw new PlanRejection("protected-export.deformer-cycle");
                }
                if (current == 2) {
                    continue;
                }
                state.put(frame.guid(), 1);
                pending.push(new Frame(frame.guid(), true));
                final List<String> stableChildren = children.get(frame.guid());
                for (int index = stableChildren.size() - 1; index >= 0; index--) {
                    pending.push(new Frame(stableChildren.get(index), false));
                }
            }
        }
        if (order.size() != census.deformerGuids().size()) {
            throw new PlanRejection("protected-export.deformer-cycle");
        }
        return order;
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
     * Plan-wide allocation state: every rewritten family shares the hash set, the used-name
     * set and the used-ID set, so a settings token can never collide with an ArtMesh token,
     * with an earlier token of its own family, or with a reserved authored identity.
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
            for (String guid : new TreeSet<>(guids)) {
                final String hash = sha256Hex(guid);
                if (!hashes.add(hash)) {
                    throw new PlanRejection("protected-export.obfuscation-hash-collision");
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
                    throw new PlanRejection("protected-export.obfuscation-unallocatable");
                }
            }
            return planned;
        }
    }

    /** One traversal frame: a deformer GUID plus whether its children were already pushed. */
    private record Frame(String guid, boolean expanded) {}

    /** Bounded planner rejection converted into {@link ProtectedExportPlanResult.Rejected}. */
    private static final class PlanRejection extends RuntimeException {
        private PlanRejection(final String messageKey) {
            super(messageKey);
        }
    }
}
