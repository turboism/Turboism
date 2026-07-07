package dev.turboism.core.diagnostics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Skeleton diagnostic report collector.
 */
public final class StartupReport {

    private final Instant createdAt = Instant.now();
    private final List<DiagnosticProblem> problems = new ArrayList<>();

    public void addProblem(String code, String message, String path, Severity severity) {
        problems.add(new DiagnosticProblem(code, message, path, severity));
    }

    public List<DiagnosticProblem> problems() {
        return Collections.unmodifiableList(problems);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean hasErrors() {
        return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
    }

    public record DiagnosticProblem(String code, String message, String path, Severity severity) {
    }

    public enum Severity {
        ERROR, WARNING, INFO
    }
}
