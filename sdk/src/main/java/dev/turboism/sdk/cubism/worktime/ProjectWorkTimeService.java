package dev.turboism.sdk.cubism.worktime;

import dev.turboism.sdk.CubismEditor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the runtime-owned project work-time tracker.
 *
 * <p>The runtime observes the active project and desktop foreground state, so every consumer
 * shares one accounting source instead of re-implementing activation tracking. The service
 * keeps a cumulative total per project across Editor sessions and a separate per-process
 * session total.</p>
 *
 * <p>Access requires {@code turboism.cubism.project.read}. Values are eventually consistent:
 * {@link #snapshot()} returns the latest completed observation (normally at most one second
 * old) and never performs a host read on the caller's thread.</p>
 */
@CubismEditor
public interface ProjectWorkTimeService {

    /** Returns the latest completed work-time observation. */
    ProjectWorkTimeSnapshot snapshot();

    /** Returns the active project's work time, or empty when no project is active. */
    default Optional<ProjectWorkTime> activeProject() {
        return snapshot().activeProject();
    }

    /** Returns total Editor foreground time since this process started. */
    default Duration sessionForegroundTime() {
        return snapshot().sessionForegroundTime();
    }

    /** Returns every project with recorded work time in display order. */
    default List<ProjectWorkTime> projects() {
        return snapshot().projects();
    }

    /** Reports whether a live runtime tracker backs this instance. */
    default boolean isAvailable() {
        return true;
    }

    /** Returns the fail-closed sentinel used when no tracker is installed. */
    static ProjectWorkTimeService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}. */
    enum Unavailable implements ProjectWorkTimeService {
        INSTANCE;

        private final Instant epoch = Instant.EPOCH;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public ProjectWorkTimeSnapshot snapshot() {
            return ProjectWorkTimeSnapshot.empty(epoch);
        }

        @Override
        public Optional<ProjectWorkTime> activeProject() {
            return Optional.empty();
        }

        @Override
        public Duration sessionForegroundTime() {
            return Duration.ZERO;
        }

        @Override
        public List<ProjectWorkTime> projects() {
            return List.of();
        }

        @Override
        public String toString() {
            return "ProjectWorkTimeService.unavailable";
        }
    }
}
