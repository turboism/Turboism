package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable protected-export plan: the deterministic rewrite and flatten targets one
 * {@link ProtectedExportPlanner} computed from a {@link ProtectedExportCensus}.
 *
 * <p>The plan is pure data. It authorizes nothing: it carries no host object, no path and
 * no verb, so holding one cannot open, write, flatten or export anything. The runtime
 * verifies every entry against its own census before it uses a single token, and it may
 * only ever refuse a plan — never act on one it did not verify.</p>
 *
 * <p>Target tokens are private strings, not host-issued identifiers: a drawable-ID token
 * is never an identifier the host handed out.</p>
 */
@Incubating
@CubismEditor({"5.2.03", "5.3.02", "5.3.03", "5.3.04"})
public record ProtectedExportPlan(
        List<String> deformerOrderLeafToRoot,
        Map<String, Target> artMeshTargets,
        Map<String, Target> physicsSettings,
        Map<String, Target> motionSyncSettings,
        Set<String> passThroughGuids) {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-zA-Z_@]+");
    private static final int MAX_TOKEN_LENGTH = 63;

    public ProtectedExportPlan {
        deformerOrderLeafToRoot =
                List.copyOf(Objects.requireNonNull(deformerOrderLeafToRoot, "deformerOrderLeafToRoot"));
        for (String guid : deformerOrderLeafToRoot) {
            text(guid, "deformerOrderLeafToRoot");
        }
        if (Set.copyOf(deformerOrderLeafToRoot).size() != deformerOrderLeafToRoot.size()) {
            throw new IllegalArgumentException("deformerOrderLeafToRoot must list every deformer once");
        }
        artMeshTargets = immutableTargets(artMeshTargets, "artMeshTargets");
        physicsSettings = immutableTargets(physicsSettings, "physicsSettings");
        motionSyncSettings = immutableTargets(motionSyncSettings, "motionSyncSettings");
        passThroughGuids = Set.copyOf(Objects.requireNonNull(passThroughGuids, "passThroughGuids"));
        for (String guid : passThroughGuids) {
            text(guid, "passThroughGuids");
        }
        requireDisjoint(artMeshTargets.keySet(), physicsSettings.keySet(), "an ArtMesh");
        requireDisjoint(artMeshTargets.keySet(), motionSyncSettings.keySet(), "a motion-sync setting");
        requireDisjoint(physicsSettings.keySet(), motionSyncSettings.keySet(), "a physics setting");
        requireDisjoint(artMeshTargets.keySet(), passThroughGuids, "a pass-through object");
        requireDisjoint(physicsSettings.keySet(), passThroughGuids, "a pass-through object");
        requireDisjoint(motionSyncSettings.keySet(), passThroughGuids, "a pass-through object");
        // One token pool spans every rewritten family: two identities may never share a
        // name or an ID, because the staged output would carry an ambiguous identity.
        final Set<String> names = new HashSet<>();
        final Set<String> ids = new HashSet<>();
        for (Map<String, Target> family : List.of(artMeshTargets, physicsSettings, motionSyncSettings)) {
            for (Target target : family.values()) {
                if (!names.add(target.name()) || !ids.add(target.idToken())) {
                    throw new IllegalArgumentException("protected-export plan tokens must be unique");
                }
            }
        }
    }

    /** One censused identity's planned protected name and private ID token. */
    @Incubating
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03", "5.3.04"})
    public record Target(String name, String idToken) {
        public Target {
            name = token(name, "name");
            idToken = token(idToken, "idToken");
        }
    }

    private static void requireDisjoint(final Set<String> left, final Set<String> right, final String rightLabel) {
        for (String guid : right) {
            if (left.contains(guid)) {
                throw new IllegalArgumentException("a GUID must not be both rewritten and " + rightLabel);
            }
        }
    }

    private static Map<String, Target> immutableTargets(final Map<String, Target> targets, final String name) {
        Objects.requireNonNull(targets, name);
        targets.forEach((guid, target) -> {
            text(guid, name + " key");
            Objects.requireNonNull(target, name + " value");
        });
        return Map.copyOf(targets);
    }

    private static String token(final String value, final String name) {
        text(value, name);
        if (value.length() > MAX_TOKEN_LENGTH
                || Character.isDigit(value.charAt(0))
                || !TOKEN_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a 1-" + MAX_TOKEN_LENGTH + " character token");
        }
        return value;
    }

    private static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
