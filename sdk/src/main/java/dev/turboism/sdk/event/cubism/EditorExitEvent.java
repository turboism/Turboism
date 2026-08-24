package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of one Cubism Editor exit request. */
public sealed interface EditorExitEvent extends TurboismEvent
    permits EditorExitEvent.Before, EditorExitEvent.On, EditorExitEvent.After {

    EditorLifecycleSnapshot editor();

    record Before(EditorLifecycleSnapshot editor) implements EditorExitEvent {
        public Before { editor = Objects.requireNonNull(editor, "editor"); }
    }

    record On(EditorLifecycleSnapshot editor) implements EditorExitEvent {
        public On { editor = Objects.requireNonNull(editor, "editor"); }
    }

    record After(EditorExitResult result) implements EditorExitEvent {
        public After { result = Objects.requireNonNull(result, "result"); }
        @Override public EditorLifecycleSnapshot editor() { return result.editor(); }
    }
}
