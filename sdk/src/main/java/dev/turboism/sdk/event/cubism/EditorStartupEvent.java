package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the Turboism-visible Cubism Editor startup lifecycle. */
public sealed interface EditorStartupEvent extends TurboismEvent
    permits EditorStartupEvent.Before, EditorStartupEvent.On, EditorStartupEvent.After {

    EditorLifecycleSnapshot editor();

    record Before(EditorLifecycleSnapshot editor) implements EditorStartupEvent {
        public Before { editor = Objects.requireNonNull(editor, "editor"); }
    }

    record On(EditorLifecycleSnapshot editor) implements EditorStartupEvent {
        public On { editor = Objects.requireNonNull(editor, "editor"); }
    }

    record After(EditorLifecycleSnapshot editor) implements EditorStartupEvent {
        public After { editor = Objects.requireNonNull(editor, "editor"); }
    }
}
