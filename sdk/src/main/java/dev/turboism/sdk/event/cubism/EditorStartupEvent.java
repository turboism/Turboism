package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the Turboism-visible Cubism Editor startup lifecycle. */
public sealed interface EditorStartupEvent extends TurboismEvent
    permits EditorStartupEvent.Before, EditorStartupEvent.On, EditorStartupEvent.After {

    /** Returns the snapshot of the Editor instance this event concerns. */
    EditorLifecycleSnapshot editor();

    /** State published synchronously before Editor startup proceeds. */
    record Before(EditorLifecycleSnapshot editor) implements EditorStartupEvent {
        public Before { editor = Objects.requireNonNull(editor, "editor"); }
    }

    /** State published when Editor startup completes. */
    record On(EditorLifecycleSnapshot editor) implements EditorStartupEvent {
        public On { editor = Objects.requireNonNull(editor, "editor"); }
    }

    /** State published after the startup lifecycle finished. */
    record After(EditorLifecycleSnapshot editor) implements EditorStartupEvent {
        public After { editor = Objects.requireNonNull(editor, "editor"); }
    }
}
