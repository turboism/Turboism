package dev.turboism.ui.host;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/** Runtime-only lifecycle view for Cubism Editor UI integration providers. */
public interface EditorUiHostLifecycle {

    /**
     * @return the current immutable host state
     */
    EditorUiHostSnapshot snapshot();

    /**
     * Registers a listener for host state changes.
     *
     * @param listener receives each new snapshot
     * @return a registration that unsubscribes the listener when disposed
     */
    Registration subscribe(Consumer<EditorUiHostSnapshot> listener);
}
