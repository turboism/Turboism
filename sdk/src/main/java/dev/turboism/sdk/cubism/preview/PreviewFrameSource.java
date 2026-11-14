package dev.turboism.sdk.cubism.preview;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * One live preview surface a plugin may read frames from and place a control strip on.
 *
 * <p>A source is bound to exactly one host preview and to the plugin generation that resolved it.
 * Every member fails closed once the source, the preview or that generation is gone, so a plugin
 * never has to track the host's window lifecycle.</p>
 *
 * <p>Threading: all members are safe to call from any thread. {@link #capture()} never renders or
 * reads back on the calling thread — an implementation reads the framebuffer on its own thread and
 * completes the returned stage there.</p>
 */
@CubismEditor
public interface PreviewFrameSource extends AutoCloseable {

    /**
     * Returns the stable identity of the host preview this source reads.
     *
     * @return the surface id, never blank
     */
    String surfaceId();

    /**
     * Returns the width the preview currently renders at.
     *
     * @return the width in pixels, at least 1
     */
    int width();

    /**
     * Returns the height the preview currently renders at.
     *
     * @return the height in pixels, at least 1
     */
    int height();

    /**
     * Reads one frame of the preview.
     *
     * <p>Calls on one source are serialised: the stage returned by a later call completes after the
     * earlier one settled. A call after {@link #close()} — or after the host preview went away —
     * fails the stage with {@link PreviewFrameSourceClosedException} instead of throwing into the
     * host.</p>
     *
     * @return a stage completing with the frame, or failing with
     *     {@link PreviewFrameSourceClosedException} or an implementation-defined capture failure
     */
    CompletionStage<PreviewFrame> capture();

    /**
     * Places a plugin-owned control strip on this preview, replacing whatever strip is there.
     *
     * <p>The runtime renders {@code content} and reports the action id of a pressed or edited
     * control — plus the value a value-carrying control emitted — to {@code actionIds}, on the host
     * UI thread. Closing the returned {@link Registration} removes the strip and restores the
     * host's own preview chrome. A strip attached after the source was closed is refused with
     * {@link PreviewFrameSourceClosedException}.</p>
     *
     * @param content the toolkit-neutral strip the runtime renders, never {@code null}
     * @param actionIds sink receiving the action id of a control the plugin attached and, for a
     *     value-carrying control, the value it emitted
     * @return the registration that removes the strip
     */
    Registration attachControls(PanelView content, BiConsumer<String, Optional<UiActionEvent>> actionIds);

    /**
     * Replaces the content of the strip this source is currently showing.
     *
     * <p>Intended for the state a strip reports and enables — labels, progress text, which control
     * is active — where re-attaching would rebuild the whole strip on every tick. A call while no
     * strip is attached does nothing; a call after the source was closed is dropped silently.</p>
     *
     * @param content the new strip content, never {@code null}
     */
    void updateControls(PanelView content);

    /**
     * Releases this source. Idempotent, and terminal: capture and attachment stay refused
     * afterwards, and the runtime keeps the plugin's generation fence authoritative.
     */
    @Override
    void close();
}
