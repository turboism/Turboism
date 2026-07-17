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

    void require(String permissionId, String operationId, String capabilityId) throws CubismPermissionException;

    static CubismReadPermissionGate from(final CubismPermissionGate permissionGate) {
        Objects.requireNonNull(permissionGate, "permissionGate");
        return permissionGate::require;
    }
}
