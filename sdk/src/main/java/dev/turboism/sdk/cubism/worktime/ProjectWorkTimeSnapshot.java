package dev.turboism.sdk.cubism.worktime;

import dev.turboism.sdk.CubismEditor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view of the runtime work-time tracker at one observation.
 *
 * <p>{@code projects} is ordered by descending {@link ProjectWorkTime#totalTime()}, with the
 * project name as a deterministic tie-breaker. The active project, when present, is also one
 * of the listed projects.</p>
 *
 * @param editorForeground whether the Cubism process was foreground at the observation
 * @param sessionForegroundTime total Editor foreground time since this process started
 * @param activeProject project whose tab was active, empty when none was open
 * @param projects all projects with recorded work time, ordered for display
 * @param observedAt the instant this snapshot was observed
 */
@CubismEditor
public record ProjectWorkTimeSnapshot(
        boolean editorForeground,
        Duration sessionForegroundTime,
        Optional<ProjectWorkTime> activeProject,
        List<ProjectWorkTime> projects,
        Instant observedAt) {
    public ProjectWorkTimeSnapshot {
        sessionForegroundTime = Objects.requireNonNull(sessionForegroundTime, "sessionForegroundTime");
        if (sessionForegroundTime.isNegative()) {
            throw new IllegalArgumentException("sessionForegroundTime must not be negative");
        }
        activeProject = Objects.requireNonNull(activeProject, "activeProject");
        projects = List.copyOf(Objects.requireNonNull(projects, "projects"));
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    /** Returns the canonical empty snapshot at {@code observedAt}. */
    public static ProjectWorkTimeSnapshot empty(final Instant observedAt) {
        return new ProjectWorkTimeSnapshot(
                false, Duration.ZERO, Optional.empty(), List.of(), Objects.requireNonNull(observedAt, "observedAt"));
    }

    /** Looks up one recorded project by its opaque key. */
    public Optional<ProjectWorkTime> project(final String projectKey) {
        Objects.requireNonNull(projectKey, "projectKey");
        return projects.stream()
                .filter(project -> project.projectKey().equals(projectKey))
                .findFirst();
    }
}
