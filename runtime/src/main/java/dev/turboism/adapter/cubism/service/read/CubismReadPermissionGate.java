package dev.turboism.adapter.cubism.service.read;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.permission.CubismPermissionException;

import java.util.Objects;

/**
 * Capability-aware read permission boundary.  The operation and capability IDs
 * remain intact when a service is constructed outside {@code CorePluginContext}.
 */
@FunctionalInterface
public interface CubismReadPermissionGate {

    /**
     * Enforces the read permission for one operation.
     *
     * @param permissionId the permission the caller must hold
     * @param operationId the operation identity recorded in diagnostics
     * @param capabilityId the capability the operation belongs to
     * @throws CubismPermissionException when the caller does not hold the permission
     */
    void require(String permissionId, String operationId, String capabilityId) throws CubismPermissionException;

    /**
     * Adapts the plugin-scoped permission gate to this capability-aware boundary, keeping
     * the operation and capability ids intact for diagnostics.
     *
     * @param permissionGate the plugin's permission gate, non-null
     * @return a gate delegating to it
     * @throws NullPointerException if {@code permissionGate} is null
     */
    static CubismReadPermissionGate from(final CubismPermissionGate permissionGate) {
        Objects.requireNonNull(permissionGate, "permissionGate");
        return permissionGate::require;
    }
}
