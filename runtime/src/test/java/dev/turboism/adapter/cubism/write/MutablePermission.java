package dev.turboism.adapter.cubism.write;

import dev.turboism.sdk.permission.CubismPermissionException;

final class MutablePermission implements dev.turboism.permissions.PermissionChecker {

    private boolean allowed;

    MutablePermission(final boolean allowed) {
        this.allowed = allowed;
    }

    void setAllowed(final boolean allowed) {
        this.allowed = allowed;
    }

    @Override
    public void check(final String permissionId, final String operation) throws CubismPermissionException {
        if (allowed) {
            return;
        }
        throw new CubismPermissionException("Missing required permission " + permissionId + " for " + operation);
    }
}
