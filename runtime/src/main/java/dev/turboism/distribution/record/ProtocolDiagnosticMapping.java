package dev.turboism.distribution.record;

import java.util.Objects;

record ProtocolDiagnosticMapping(String code, String category, String severity) {
    private static final String CATEGORY = "RECORD_CORRUPTION";
    private static final String SEVERITY = "ERROR";

    ProtocolDiagnosticMapping {
        code = Objects.requireNonNull(code, "code");
        category = Objects.requireNonNull(category, "category");
        severity = Objects.requireNonNull(severity, "severity");
    }

    static ProtocolDiagnosticMapping forIssue(ProtocolValidationIssue issue) {
        return new ProtocolDiagnosticMapping(issue.code(), CATEGORY, SEVERITY);
    }
}
