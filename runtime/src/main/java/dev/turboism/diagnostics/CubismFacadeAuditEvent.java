package dev.turboism.diagnostics;

import dev.turboism.sdk.diagnostics.DiagnosticReport;

import java.time.Instant;
import java.util.Objects;

/**
 * One audit record of a permission-guarded Cubism facade call, produced at the moment access
 * was decided.
 *
 * <p>Text components are validated in the compact constructor, so a recorded event always
 * names a plugin, a permission and an operation. The event carries no result field: it
 * records that the check happened and at what severity, not what the call went on to do.
 *
 * @param pluginId the plugin whose call was checked; must not be null or blank
 * @param permissionId the permission the call demanded; must not be null or blank
 * @param operationId the guarded facade operation; must not be null or blank
 * @param capabilityId narrower capability the operation targeted, or null when the operation
 *                     is not capability-qualified; must not be blank when present
 * @param severity how the emitter classified this event; must not be null
 * @param timestamp when the check was decided, taken from the emitter's clock; must not be null
 */
public record CubismFacadeAuditEvent(
    String pluginId,
    String permissionId,
    String operationId,
    String capabilityId,
    DiagnosticReport.Severity severity,
    Instant timestamp
) {
    public CubismFacadeAuditEvent {
        pluginId = requireText(pluginId, "pluginId");
        permissionId = requireText(permissionId, "permissionId");
        operationId = requireText(operationId, "operationId");
        if (capabilityId != null) {
            capabilityId = requireText(capabilityId, "capabilityId");
        }
        severity = Objects.requireNonNull(severity, "severity");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    public CubismFacadeAuditEvent(
        final String pluginId,
        final String permissionId,
        final String operationId,
        final DiagnosticReport.Severity severity,
        final Instant timestamp
    ) {
        this(pluginId, permissionId, operationId, null, severity, timestamp);
    }

    /**
     * @deprecated use {@link #operationId()}; retained for source compatibility.
     */
    @Deprecated
    public String methodName() {
        return operationId;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
