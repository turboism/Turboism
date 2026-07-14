package dev.turboism.preview;

import dev.turboism.sdk.diagnostics.DiagnosticReport;

import java.time.Instant;
import java.util.List;

record PreviewDiagnosticReport(Instant createdAt) implements DiagnosticReport {

    PreviewDiagnosticReport() {
        this(Instant.now());
    }

    @Override
    public List<Problem> problems() {
        return List.of();
    }
}
