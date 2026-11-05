package dev.turboism.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.permission.PermissionIds;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the admission contract of {@link CapabilityAdmission}: the same enabled → capability →
 * permission order for every row, no plugin identity, and a fail-closed single-owner arbitration.
 */
class CapabilityAdmissionTest {

    private static final List<String> MIRROR_PERMISSIONS =
            List.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE);

    @Test
    void admitsNothingWhenNothingIsLoaded() {
        assertEquals(List.of(), CapabilityAdmission.admitted(List.of(), CapabilityAdmission.MESH_MIRROR_HOOKS));
        assertEquals(
                Optional.empty(), CapabilityAdmission.admittedOwner(List.of(), CapabilityAdmission.MESH_MIRROR_HOOKS));
    }

    @Test
    void admitsEveryEntitledPluginInLoadOrder() {
        final List<PluginAdmissionView> plugins = List.of(
                view("dev.example.first", PluginLifecycleState.ENABLED, MIRROR_PERMISSIONS, mirrorCapability()),
                view("dev.example.disabled", PluginLifecycleState.DISABLED, MIRROR_PERMISSIONS, mirrorCapability()),
                view("dev.example.second", PluginLifecycleState.ENABLED, MIRROR_PERMISSIONS, mirrorCapability()));

        assertEquals(
                List.of("dev.example.first", "dev.example.second"),
                CapabilityAdmission.admitted(plugins, CapabilityAdmission.MESH_MIRROR_HOOKS));
    }

    @Test
    void admitsTheSingleEntitledPluginAsOwner() {
        final List<PluginAdmissionView> plugins = List.of(
                view("dev.example.owner", PluginLifecycleState.ENABLED, MIRROR_PERMISSIONS, mirrorCapability()));

        assertEquals(
                Optional.of("dev.example.owner"),
                CapabilityAdmission.admittedOwner(plugins, CapabilityAdmission.MESH_MIRROR_HOOKS));
    }

    @Test
    void failsClosedWhenASingleOwnerSurfaceHasTwoEntitledPlugins() {
        final List<PluginAdmissionView> plugins = List.of(
                view("dev.example.alpha", PluginLifecycleState.ENABLED, MIRROR_PERMISSIONS, mirrorCapability()),
                view("dev.example.beta", PluginLifecycleState.ENABLED, MIRROR_PERMISSIONS, mirrorCapability()));

        final IllegalStateException refusal = assertThrows(
                IllegalStateException.class,
                () -> CapabilityAdmission.admittedOwner(plugins, CapabilityAdmission.MESH_MIRROR_HOOKS));
        assertTrue(refusal.getMessage().contains(CapabilityAdmission.MESH_MIRROR_HOOKS), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("dev.example.alpha"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("dev.example.beta"), refusal.getMessage());
    }

    @Test
    void refusesADisabledPluginHoweverPermissioned() {
        final List<PluginAdmissionView> plugins = List.of(
                view("dev.example.disabled", PluginLifecycleState.DISABLED, MIRROR_PERMISSIONS, mirrorCapability()));

        assertEquals(List.of(), CapabilityAdmission.admitted(plugins, CapabilityAdmission.MESH_MIRROR_HOOKS));
    }

    @Test
    void refusesAPluginMissingOneRequiredPermission() {
        final List<PluginAdmissionView> plugins = List.of(view(
                "dev.example.partial",
                PluginLifecycleState.ENABLED,
                List.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE),
                mirrorCapability()));

        assertEquals(List.of(), CapabilityAdmission.admitted(plugins, CapabilityAdmission.MESH_MIRROR_HOOKS));
    }

    @Test
    void refusesAPluginWithoutTheDeclaredCapability() {
        final List<PluginAdmissionView> plugins = List.of(view(
                "dev.example.unrelated",
                PluginLifecycleState.ENABLED,
                MIRROR_PERMISSIONS,
                List.of("cubism.mesh.read")));

        assertEquals(List.of(), CapabilityAdmission.admitted(plugins, CapabilityAdmission.MESH_MIRROR_HOOKS));
    }

    @Test
    void admitsAnyDeclaredAlternativeCapabilityOfTheRow() {
        final List<PluginAdmissionView> plugins = List.of(
                view(
                        "dev.example.deformer",
                        PluginLifecycleState.ENABLED,
                        List.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE),
                        List.of("cubism.deformer.alt-axis-mirror")),
                view(
                        "dev.example.artmesh",
                        PluginLifecycleState.ENABLED,
                        List.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE),
                        List.of("cubism.artmesh.mirror")),
                view(
                        "dev.example.view-menu",
                        PluginLifecycleState.ENABLED,
                        List.of(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE),
                        List.of("ui.view-context-menu.contribute")));

        assertEquals(
                List.of("dev.example.deformer", "dev.example.artmesh"),
                CapabilityAdmission.admitted(plugins, CapabilityAdmission.WARP_ALT_MIRROR_HOOKS));
        assertEquals(
                List.of("dev.example.view-menu"),
                CapabilityAdmission.admitted(plugins, CapabilityAdmission.VIEW_CONTEXT_MENU_MOUNT));
    }

    @Test
    void refusesAnUndeclaredHookId() {
        assertThrows(IllegalArgumentException.class, () -> CapabilityAdmission.admitted(List.of(), "mesh.wing-hooks"));
        assertThrows(
                IllegalArgumentException.class, () -> CapabilityAdmission.admittedOwner(List.of(), "mesh.wing-hooks"));
    }

    @Test
    void declaresEachHookIdExactlyOnce() {
        final Set<String> hookIds = new java.util.HashSet<>();
        for (CapabilityRule rule : CapabilityAdmission.rules()) {
            assertTrue(hookIds.add(rule.hookId()), "duplicated hook id " + rule.hookId());
        }
    }

    @Test
    void refusesADuplicatedHookIdInADeclaredTable() {
        final CapabilityRule first = new CapabilityRule(
                "mesh.wing-hooks", Set.of("cubism.mesh.read"), Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ));
        final CapabilityRule second = new CapabilityRule(
                "mesh.wing-hooks", Set.of("cubism.mesh.write"), Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE));

        final IllegalStateException refusal =
                assertThrows(IllegalStateException.class, () -> CapabilityAdmission.index(List.of(first, second)));
        assertTrue(refusal.getMessage().contains("mesh.wing-hooks"), refusal.getMessage());
    }

    @Test
    void refusesABlankHookIdOrAnEmptyCapabilitySet() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapabilityRule(
                        " ", Set.of("cubism.mesh.read"), Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CapabilityRule(
                        "mesh.wing-hooks", Set.of(), Set.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ)));
    }

    private static List<String> mirrorCapability() {
        return List.of("cubism.mesh.mirror-axis-angle");
    }

    private static PluginAdmissionView view(
            final String id,
            final PluginLifecycleState state,
            final List<String> permissionIds,
            final List<String> capabilities) {
        return new PluginAdmissionView() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public PluginLifecycleState state() {
                return state;
            }

            @Override
            public List<String> permissionIds() {
                return permissionIds;
            }

            @Override
            public List<String> capabilities() {
                return capabilities;
            }

            @Override
            public String toString() {
                return id;
            }
        };
    }
}
