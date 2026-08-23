package dev.turboism.permissions;

import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;

import java.util.List;
import java.util.Objects;

/**
 * The narrow permission test a Cubism adapter needs: given a permission id and the operation
 * demanding it, either return or refuse.
 *
 * <p>Deliberately smaller than {@link CubismPermissionGate} so adapters can be exercised
 * without an audit sink or clock; the factories here adapt a real gate or a bare grant list
 * into this shape.
 */
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

    static PermissionChecker from(final List<PluginPermission> grantedPermissions) {
        Objects.requireNonNull(grantedPermissions, "grantedPermissions");
        return (permissionId, operation) -> {
            if (grantedPermissions.stream().anyMatch(permission -> permissionId.equals(permission.id()))) {
                return;
            }
            throw new CubismPermissionException(
                "Missing required permission " + permissionId + " for " + operation
            );
        };
    }
}
