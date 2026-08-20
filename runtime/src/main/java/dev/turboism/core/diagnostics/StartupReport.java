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

    /**
     * Appends a problem to the report.
     *
     * <p>Not synchronized: callers must confine a report to one thread or guard it
     * themselves.</p>
     *
     * @param code     stable diagnostic code
     * @param message  human-readable detail
     * @param path     the file or plugin location the problem refers to
     * @param severity how serious the problem is
     */
    public void addProblem(String code, String message, String path, Severity severity) {
        problems.add(new DiagnosticProblem(code, message, path, severity));
    }

    /**
     * @return an unmodifiable <em>view</em> of the problems in insertion order —
     *     not a snapshot, so later {@link #addProblem} calls become visible through it
     */
    public List<DiagnosticProblem> problems() {
        return Collections.unmodifiableList(problems);
    }

    /**
     * @return when this report object was constructed, which marks the start of the
     *     collection window rather than the time of any particular problem
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * @return {@code true} when at least one collected problem has
     *     {@link Severity#ERROR}; warnings and info alone do not count
     */
    public boolean hasErrors() {
        return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
    }

    /**
     * One problem observed while the runtime started.
     *
     * @param code     stable diagnostic code
     * @param message  human-readable detail
     * @param path     the file or plugin location the problem refers to
     * @param severity how serious the problem is
     */
    public record DiagnosticProblem(String code, String message, String path, Severity severity) {
    }

    public enum Severity {
        ERROR, WARNING, INFO
    }
}
