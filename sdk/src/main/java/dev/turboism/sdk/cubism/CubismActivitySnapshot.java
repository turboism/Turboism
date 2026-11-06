package dev.turboism.sdk.cubism;

import dev.turboism.sdk.CubismEditor;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One observation of the Cubism Editor's desktop activity and active project.
 *
 * <p>{@code editorForeground} is a best-effort process-level signal: it is true while one of
 * the Cubism process's top-level windows is the active desktop window. It does not prove that
 * the Editor is the operating system's foreground process when the host runs without AWT
 * windows.</p>
 *
 * @param editorForeground whether the Cubism process currently owns the active desktop window
 * @param activeProject the project whose tab is currently active, empty when none is open
 * @param observedAt the instant this observation was made
 */
@CubismEditor
public record CubismActivitySnapshot(
        boolean editorForeground, Optional<ProjectSnapshot> activeProject, Instant observedAt) {
    public CubismActivitySnapshot {
        activeProject = Objects.requireNonNull(activeProject, "activeProject");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    /** Returns the canonical inactive observation at {@code observedAt}. */
    public static CubismActivitySnapshot inactive(final Instant observedAt) {
        return new CubismActivitySnapshot(false, Optional.empty(), observedAt);
    }
}
