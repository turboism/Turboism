package dev.turboism.permissions;

import dev.turboism.sdk.permission.CubismPermissionException;

import java.util.Objects;

@FunctionalInterface
public interface PermissionChecker {

    void check(String permissionId, String operation) throws CubismPermissionException;

    static PermissionChecker allowAll() {
        return (permissionId, operation) -> {
        };
    }

    static PermissionChecker from(final CubismPermissionGate permissionGate) {
        Objects.requireNonNull(permissionGate, "permissionGate");
        return permissionGate::require;
    }
}
