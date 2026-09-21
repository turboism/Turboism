package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of one Cubism Editor exit request. */
public sealed interface EditorExitEvent extends TurboismEvent
    permits EditorExitEvent.Before, EditorExitEvent.On, EditorExitEvent.After {

    /** Returns the snapshot of the Editor instance this event concerns. */
    EditorLifecycleSnapshot editor();

    /** State published synchronously before the exit request proceeds. */
    record Before(EditorLifecycleSnapshot editor) implements EditorExitEvent {
        public Before { editor = Objects.requireNonNull(editor, "editor"); }
    }

    /** State published when the exit request is accepted. */
    record On(EditorLifecycleSnapshot editor) implements EditorExitEvent {
        public On { editor = Objects.requireNonNull(editor, "editor"); }
    }

    /** State published after the exit request resolved with {@code result}. */
    record After(EditorExitResult result) implements EditorExitEvent {
        public After { result = Objects.requireNonNull(result, "result"); }
        @Override public EditorLifecycleSnapshot editor() { return result.editor(); }
    }
}
