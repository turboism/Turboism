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

    /**
     * Enforces the permission for one operation.
     *
     * @param permissionId the permission the caller must hold
     * @param operation the operation identity used in diagnostics
     * @throws CubismPermissionException when the permission is not granted
     */
    void check(String permissionId, String operation) throws CubismPermissionException;

    /**
     * A checker for call sites with no permission boundary.
     *
     * @return a checker that admits every permission
     */
    static PermissionChecker allowAll() {
        return (permissionId, operation) -> {
        };
    }

    /**
     * Adapts a real permission gate into this narrower shape.
     *
     * @param permissionGate the gate to delegate to, non-null
     * @return a checker enforcing through it
     * @throws NullPointerException if {@code permissionGate} is null
     */
    static PermissionChecker from(final CubismPermissionGate permissionGate) {
        Objects.requireNonNull(permissionGate, "permissionGate");
        return permissionGate::require;
    }

    /**
     * Adapts a bare grant list into this shape.
     *
     * @param grantedPermissions the permissions granted to the caller, non-null
     * @return a checker that refuses any permission id absent from the list
     * @throws NullPointerException if {@code grantedPermissions} is null
     */
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
