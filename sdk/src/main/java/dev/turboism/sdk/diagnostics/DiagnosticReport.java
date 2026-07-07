package dev.turboism.sdk.diagnostics;

import java.time.Instant;
import java.util.List;

/**
 * Public diagnostic report view.
 */
public interface DiagnosticReport {

    Instant createdAt();

    List<Problem> problems();

    interface Problem {
        String code();

        String message();

        String path();

        Severity severity();
    }

    enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}
