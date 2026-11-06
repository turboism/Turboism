package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.CubismActivitySnapshot;
import dev.turboism.sdk.event.TurboismEvent;
import java.util.Objects;

/**
 * Runtime-owned transition of the Editor's foreground state or active project tab.
 *
 * <p>A transition is published when either {@link CubismActivitySnapshot#editorForeground()}
 * or the active project identity changes. The first observation establishes the runtime
 * baseline and is not published; a late subscriber should read
 * {@link dev.turboism.sdk.cubism.CubismFacade#activity()} for the current state.</p>
 *
 * @param previous the last observed activity
 * @param current the activity that replaced it
 */
@CubismEditor
public record CubismActivityChangedEvent(CubismActivitySnapshot previous, CubismActivitySnapshot current)
        implements TurboismEvent {
    public CubismActivityChangedEvent {
        previous = Objects.requireNonNull(previous, "previous");
        current = Objects.requireNonNull(current, "current");
    }
}
