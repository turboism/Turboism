package dev.turboism.ui.host;

import dev.turboism.sdk.plugin.Registration;

import java.util.function.Consumer;

/** Runtime-only lifecycle view for Cubism Editor UI integration providers. */
public interface EditorUiHostLifecycle {

    EditorUiHostSnapshot snapshot();

    Registration subscribe(Consumer<EditorUiHostSnapshot> listener);
}
