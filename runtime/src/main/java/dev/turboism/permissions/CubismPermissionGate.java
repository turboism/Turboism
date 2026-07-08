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

    public void require(final String permissionId, final String methodName) {
        Objects.requireNonNull(permissionId, "permissionId");
        Objects.requireNonNull(methodName, "methodName");
        if (grantedPermissions.stream().anyMatch(permission -> permissionId.equals(permission.id()))) {
            return;
        }
        auditSink.accept(new CubismFacadeAuditEvent(
            pluginId,
            permissionId,
            methodName,
            DiagnosticReport.Severity.WARNING,
            clock.instant()
        ));
        throw new CubismPermissionException(
            "Plugin " + pluginId + " is missing required Cubism permission " + permissionId + " for " + methodName
        );
    }
}
