package dev.turboism.sdk.cubism.worktime;

import dev.turboism.sdk.CubismEditor;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Accumulated foreground time for one Cubism project.
 *
 * <p>{@code projectKey} is an opaque, persistence-stable identity chosen by the runtime. It is
 * derived from the host's project identity information and remains stable across Editor
 * sessions whenever the host reports enough information to distinguish the project.</p>
 *
 * @param projectKey opaque stable project identity used for persistence
 * @param projectName display name of the project at the last observation
 * @param sessionTime foreground time accumulated for this project since this Editor process started
 * @param totalTime foreground time accumulated for this project across Editor sessions
 * @param lastForegroundAt last instant this project was observed in the foreground
 */
@CubismEditor
public record ProjectWorkTime(
        String projectKey, String projectName, Duration sessionTime, Duration totalTime, Instant lastForegroundAt) {
    public ProjectWorkTime {
        projectKey = requireText(projectKey, "projectKey");
        projectName = requireText(projectName, "projectName");
        sessionTime = requireNonNegative(sessionTime, "sessionTime");
        totalTime = requireNonNegative(totalTime, "totalTime");
        lastForegroundAt = Objects.requireNonNull(lastForegroundAt, "lastForegroundAt");
        if (totalTime.compareTo(sessionTime) < 0) {
            throw new IllegalArgumentException("totalTime must not be smaller than sessionTime");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requireNonNegative(final Duration value, final String name) {
        final Duration duration = Objects.requireNonNull(value, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
