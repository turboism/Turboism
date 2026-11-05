package dev.turboism.core.lifecycle;

import dev.turboism.sdk.permission.PermissionIds;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Capability admission for the runtime surfaces that used to be gated on a hardcoded plugin id.
 *
 * <p>One table answers every question of the form "which plugin may drive this hook?" with the
 * same order: enabled plugin, then a declared capability, then the granted permissions. Nothing
 * here inspects a plugin id, so a plugin that declares the capability and holds the permissions is
 * admitted whether it is first-party or not; a plugin that does not is never admitted, however
 * permissioned.
 *
 * <p>The rules are fixed at class initialization and validated once: a blank or duplicated
 * {@code hookId} is a configuration error and fails the first touch of this class, never a race
 * that silently hands one surface to two owners.
 */
public final class CapabilityAdmission {

    /** Shared mesh-mirror hooks bound to whomever declares the mirror-axis capability. */
    public static final String MESH_MIRROR_HOOKS = "mesh.mirror-hooks";
    /** Shared Warp Deformer and ArtMesh mirror hooks. */
    public static final String WARP_ALT_MIRROR_HOOKS = "warp.alt-mirror-hooks";
    /** Canvas-top control strip the shared mirror hooks mount buttons into. */
    public static final String VIEW_CONTEXT_MENU_MOUNT = "ui.view-context-menu.mount";
    /**
     * Runtime point-and-edge layout brush for one registered mesh tool. The capability half of this
     * row is read from the manifest when the tool is registered — no plugin list exists at that
     * point — and its permission half is enforced by the brush on every operation, so the row is the
     * declaration of record for the surface rather than a list lookup.
     */
    public static final String MESH_POINT_AND_EDGE_LAYOUT = "mesh.point-and-edge-layout";
    /** Protected-export pipeline armed for the declared orchestration owner. */
    public static final String EXPORT_PROTECTED_ORCHESTRATION = "export.protected-orchestration";

    private static final Set<CapabilityRule> RULES = index(List.of(
            new CapabilityRule(
                    MESH_MIRROR_HOOKS,
                    Set.of("cubism.mesh.mirror-axis-angle"),
                    Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE)),
            new CapabilityRule(
                    WARP_ALT_MIRROR_HOOKS,
                    Set.of("cubism.deformer.alt-axis-mirror", "cubism.artmesh.mirror"),
                    Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)),
            new CapabilityRule(
                    VIEW_CONTEXT_MENU_MOUNT,
                    Set.of("ui.view-context-menu.contribute"),
                    Set.of(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE)),
            new CapabilityRule(
                    MESH_POINT_AND_EDGE_LAYOUT,
                    Set.of("cubism.mesh.point-and-edge-layout"),
                    Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ, PermissionIds.TURBOISM_CUBISM_MODEL_WRITE)),
            new CapabilityRule(
                    EXPORT_PROTECTED_ORCHESTRATION,
                    Set.of("cubism.export.protected"),
                    Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ))));

    private CapabilityAdmission() {}

    /**
     * Returns the admitted plugin ids for one hook in load order.
     *
     * @param plugins the currently loaded plugin summaries to search
     * @param hookId the row to apply, one of the constants of this class
     * @return the entitled plugin ids; empty when nothing is entitled
     * @throws IllegalArgumentException when no row declares {@code hookId}
     */
    public static List<String> admitted(final List<? extends PluginAdmissionView> plugins, final String hookId) {
        final CapabilityRule rule = rule(hookId);
        Objects.requireNonNull(plugins, "plugins");
        final List<String> admitted = new ArrayList<>();
        for (PluginAdmissionView plugin : plugins) {
            if (admits(rule, plugin)) {
                admitted.add(plugin.id());
            }
        }
        return List.copyOf(admitted);
    }

    /**
     * Returns the single admitted plugin id for a surface that may have exactly one owner.
     *
     * <p>Fail-closed by construction: the answer is empty when nothing is entitled, and a second
     * entitled owner raises {@link IllegalStateException} instead of picking one of them.
     *
     * @param plugins the currently loaded plugin summaries to search
     * @param hookId the row to apply, one of the constants of this class
     * @return the entitled plugin id; empty when nothing is entitled
     * @throws IllegalStateException when more than one plugin is entitled
     * @throws IllegalArgumentException when no row declares {@code hookId}
     */
    public static Optional<String> admittedOwner(
            final List<? extends PluginAdmissionView> plugins, final String hookId) {
        final List<String> admitted = admitted(plugins, hookId);
        if (admitted.size() > 1) {
            throw new IllegalStateException("capability admission " + hookId + " has " + admitted.size()
                    + " entitled owners " + admitted + "; a single-owner surface admits exactly one plugin");
        }
        return admitted.stream().findFirst();
    }

    /** Returns the complete immutable rule table. */
    public static Set<CapabilityRule> rules() {
        return RULES;
    }

    /**
     * Indexes a declared table, refusing a blank or duplicated hook id.
     *
     * @param declared the rows to validate
     * @return the immutable rule set
     * @throws IllegalStateException when two rows declare the same {@code hookId}
     */
    static Set<CapabilityRule> index(final List<CapabilityRule> declared) {
        Objects.requireNonNull(declared, "declared");
        final Set<String> hookIds = new HashSet<>();
        for (CapabilityRule rule : declared) {
            if (!hookIds.add(rule.hookId())) {
                throw new IllegalStateException(
                        "capability admission declares hook id " + rule.hookId() + " more than once");
            }
        }
        return Set.copyOf(declared);
    }

    private static boolean admits(final CapabilityRule rule, final PluginAdmissionView plugin) {
        return plugin.state() == PluginLifecycleState.ENABLED
                && plugin.permissionIds().containsAll(rule.allOfPermissions())
                && plugin.capabilities().stream().anyMatch(rule.anyOfCapabilities()::contains);
    }

    private static CapabilityRule rule(final String hookId) {
        Objects.requireNonNull(hookId, "hookId");
        for (CapabilityRule rule : RULES) {
            if (rule.hookId().equals(hookId)) {
                return rule;
            }
        }
        throw new IllegalArgumentException("no capability admission rule declares hook id " + hookId);
    }
}
