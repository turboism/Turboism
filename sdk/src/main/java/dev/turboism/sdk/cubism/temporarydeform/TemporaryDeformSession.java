package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;

/**
 * Active per-invocation session for a custom temporary deform tool.
 *
 * <p>Callbacks run on the EDT while the tool owns the native temporary deform slot.</p>
 */
@CubismEditor(from = "5.0.00")
public interface TemporaryDeformSession {

    /** Evaluates pointer hover position and returns the cursor shape to display. */
    default TemporaryDeformCursor onPointerHover(final TemporaryDeformPointerEvent event) {
        return TemporaryDeformCursor.DEFAULT;
    }

    /** Begins one drag stroke at the initial pointer press location. */
    void onPointerPress(TemporaryDeformPointerEvent event);

    /** Applies incremental drag updates inside the active native undo stroke. */
    void onPointerDrag(TemporaryDeformPointerEvent event);

    /** Finalizes one drag stroke on pointer release. */
    default void onPointerRelease(final TemporaryDeformPointerEvent event) {}

    /** Notifies the active session that a Tool Details option changed. */
    default void onOptionChanged(final String optionId, final String value) {}

    /** Returns the canvas overlay geometry to draw for the current session state. */
    TemporaryDeformRenderScene render(float cameraScale, boolean valid);

    /** Releases session resources when the temporary deform tool deactivates or unbinds. */
    default void deactivate() {}
}
