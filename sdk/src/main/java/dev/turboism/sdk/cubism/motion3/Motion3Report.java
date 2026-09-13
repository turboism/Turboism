package dev.turboism.sdk.cubism.motion3;

import java.util.List;

/** Outcome of {@link Motion3Validator#validate(byte[])}. */
public record Motion3Report(List<Motion3Issue> issues) {

    public Motion3Report {
        issues = List.copyOf(issues);
    }

    /** True when no {@link Motion3Issue.Severity#ERROR} issue was reported. */
    public boolean valid() {
        return issues.stream().noneMatch(
            issue -> issue.severity() == Motion3Issue.Severity.ERROR
        );
    }

    /** Only the blocking issues, in report order. */
    public List<Motion3Issue> errors() {
        return issues.stream()
            .filter(issue -> issue.severity() == Motion3Issue.Severity.ERROR)
            .toList();
    }

    /** Only the consistency warnings, in report order. */
    public List<Motion3Issue> warnings() {
        return issues.stream()
            .filter(issue -> issue.severity() == Motion3Issue.Severity.WARNING)
            .toList();
    }
}
