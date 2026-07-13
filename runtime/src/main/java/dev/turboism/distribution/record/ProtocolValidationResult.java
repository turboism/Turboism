package dev.turboism.distribution.record;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

record ProtocolValidationResult(ProtocolRecord parsedRecord, List<ProtocolValidationIssue> issues) {
    ProtocolValidationResult {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (parsedRecord != null && !issues.isEmpty()) {
            throw new IllegalArgumentException("valid record cannot contain issues");
        }
    }

    Optional<ProtocolRecord> record() {
        return Optional.ofNullable(parsedRecord);
    }

    boolean isValid() {
        return parsedRecord != null;
    }

    static ProtocolValidationResult valid(ProtocolRecord record) {
        return new ProtocolValidationResult(Objects.requireNonNull(record, "record"), List.of());
    }

    static ProtocolValidationResult invalid(ProtocolValidationIssue issue) {
        return new ProtocolValidationResult(null, List.of(issue));
    }
}
