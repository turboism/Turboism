package dev.turboism.sdk.cubism.preview;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;
import java.util.Optional;

/**
 * Framework capability: reads frames from the editor's focused preview surface.
 *
 * <p>The runtime owns preview discovery, the OpenGL context handoff and the readback; the plugin
 * owns what it does with the frames. A source is generation-bound: every call fails with
 * {@link PreviewFrameSourceClosedException} after the document closes, the preview is rebuilt or
 * the plugin generation is retired. A capture never reads the desktop, only the preview's own
 * framebuffer.</p>
 */
@Incubating
@CubismEditor
public interface PreviewFrameService {

    /**
     * Resolves the preview surface that currently has focus.
     *
     * <p>Empty is a normal answer: no preview is open or none is focused. Reading a preview demands
     * {@code turboism.ui.viewport.read}, so an implementation may refuse the call synchronously
     * instead of reporting empty. The returned source belongs to the caller and stays valid until
     * it is closed or its generation retires; resolving again after the host rebuilt the preview
     * yields a different source.</p>
     *
     * @return the focused source, or empty when there is none
     */
    Optional<PreviewFrameSource> focused();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} when the backend backing this instance is unavailable, including the
     *     {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static PreviewFrameService unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Sentinel returned by {@link #unavailable()}: no preview is ever reported focused, matching the
     * other unavailable query surfaces; probe with {@link #isAvailable()} first.
     */
    enum Unavailable implements PreviewFrameService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<PreviewFrameSource> focused() {
            return Optional.empty();
        }
    }
}
