package dev.turboism.diagnostics;

import dev.turboism.sdk.diagnostics.DiagnosticReport;

import java.time.Instant;
import java.util.Objects;

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
