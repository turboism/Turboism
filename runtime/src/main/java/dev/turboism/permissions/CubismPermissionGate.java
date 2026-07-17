package dev.turboism.permissions;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class CubismPermissionGate {

    private final String pluginId;
    private final List<PluginPermission> grantedPermissions;
    private final Consumer<CubismFacadeAuditEvent> auditSink;
    private final Clock clock;

    public CubismPermissionGate(
        final String pluginId,
        final List<PluginPermission> grantedPermissions,
        final Consumer<CubismFacadeAuditEvent> auditSink,
        final Clock clock
    ) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.grantedPermissions = List.copyOf(Objects.requireNonNull(grantedPermissions, "grantedPermissions"));
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean hasPermission(final String permissionId) {
        Objects.requireNonNull(permissionId, "permissionId");
        return grantedPermissions.stream().anyMatch(permission -> permissionId.equals(permission.id()));
    }

    public void require(final String permissionId, final String operationId) {
        require(permissionId, operationId, null);
    }

    public void require(
        final String permissionId,
        final String operationId,
        final String capabilityId
    ) {
        requireText(permissionId, "permissionId");
        requireText(operationId, "operationId");
        if (capabilityId != null) {
            requireText(capabilityId, "capabilityId");
        }
        if (grantedPermissions.stream().anyMatch(permission -> permissionId.equals(permission.id()))) {
            return;
        }
        auditSink.accept(new CubismFacadeAuditEvent(
            pluginId,
            permissionId,
            operationId,
            capabilityId,
            DiagnosticReport.Severity.WARNING,
            clock.instant()
        ));
        throw new CubismPermissionException(
            "Plugin " + pluginId + " is missing required Cubism permission " + permissionId + " for " + operationId
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
