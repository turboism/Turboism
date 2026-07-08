package dev.turboism.diagnostics;

import dev.turboism.sdk.diagnostics.DiagnosticReport;

import java.time.Instant;
import java.util.Objects;

public record CubismFacadeAuditEvent(
    String pluginId,
    String permissionId,
    String methodName,
    DiagnosticReport.Severity severity,
    Instant timestamp
) {
    public CubismFacadeAuditEvent {
        pluginId = Objects.requireNonNull(pluginId, "pluginId");
        permissionId = Objects.requireNonNull(permissionId, "permissionId");
        methodName = Objects.requireNonNull(methodName, "methodName");
        severity = Objects.requireNonNull(severity, "severity");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
