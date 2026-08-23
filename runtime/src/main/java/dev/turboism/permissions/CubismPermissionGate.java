package dev.turboism.permissions;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Enforces a single plugin's Cubism permission grants at the facade boundary.
 *
 * <p>The grant list is defensively copied at construction, so the gate's verdict cannot be
 * changed by mutating the list afterwards; re-granting requires a new gate. Every denial is
 * reported to the audit sink before the exception is thrown, so a refused call always leaves
 * a trace even if the caller swallows the exception. Grants are matched by exact permission
 * id.
 */
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

    /**
     * Tests a grant without auditing or throwing, for callers that need to degrade gracefully
     * rather than fail.
     *
     * @param permissionId the permission id to look for; must not be null
     * @return whether this plugin holds a grant with exactly that id
     * @throws NullPointerException if {@code permissionId} is null
     */
    public boolean hasPermission(final String permissionId) {
        Objects.requireNonNull(permissionId, "permissionId");
        return grantedPermissions.stream().anyMatch(permission -> permissionId.equals(permission.id()));
    }

    /**
     * Requires a permission for an operation that carries no capability qualifier.
     *
     * @param permissionId the permission the operation demands; must not be null or blank
     * @param operationId the operation being guarded, recorded in the audit event and the
     *                    exception message; must not be null or blank
     * @throws CubismPermissionException if the permission is not granted
     * @throws IllegalArgumentException if either argument is blank
     */
    public void require(final String permissionId, final String operationId) {
        require(permissionId, operationId, null);
    }

    /**
     * Requires a permission before an operation proceeds, auditing the denial if it does not.
     *
     * <p>Returns silently when the grant is present. Otherwise a {@link CubismFacadeAuditEvent}
     * at {@code WARNING} severity, timestamped from this gate's clock, is pushed to the audit
     * sink and the call is refused.
     *
     * @param permissionId the permission the operation demands; must not be null or blank
     * @param operationId the operation being guarded; must not be null or blank
     * @param capabilityId optional narrower capability recorded with the denial; may be null,
     *                     but must not be blank when present
     * @throws CubismPermissionException if the permission is not granted
     * @throws IllegalArgumentException if any supplied argument is blank
     */
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
