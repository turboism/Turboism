package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;

/** Override-based hooks around the Turboism-visible Cubism editor lifetime. */
@CubismEditor
public interface EditorLifecycleHooks {

    /** Runs after the host exists but before Turboism publishes editor-started. */
    default void beforeEditorStartup(final EditorLifecycleSnapshot editor) {}

    /** Notification that Cubism and the verified Turboism host session are ready. */
    default void onEditorStarted(final EditorLifecycleSnapshot editor) {}

    /** Post-processing phase after editor-started notification. */
    default void afterEditorStartup(final EditorLifecycleSnapshot editor) {}

    /** Runs synchronously at the beginning of Cubism's exit command. */
    @CubismEditor({
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    })
    default void beforeEditorExit(final EditorLifecycleSnapshot editor) {}

    /** Runs when Cubism accepts the exit request, before process shutdown. */
    default void onEditorExiting(final EditorLifecycleSnapshot editor) {}

    /** Always receives the exit-command result before shutdown continues. */
    default void afterEditorExit(final EditorExitResult result) {}
}
