package dev.turboism.kernel;

import dev.turboism.sdk.diagnostics.DiagnosticReport;
import java.time.Instant;
import java.util.List;

record KernelDiagnosticReport(Instant createdAt) implements DiagnosticReport {

    KernelDiagnosticReport() {
        this(Instant.now());
    }

    @Override
    public List<Problem> problems() {
        return List.of();
    }
}
