package dev.turboism.sdk.cubism.preview;

import dev.turboism.sdk.CubismEditor;

/**
 * Reports that a {@link PreviewFrameSource} is no longer live.
 *
 * <p>Closing a source, closing the preview, closing the document and retiring the plugin
 * generation all fence the source, so a late capture observes this failure instead of reading a
 * host surface that no longer belongs to it.</p>
 */
@CubismEditor
public final class PreviewFrameSourceClosedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with its stable message. */
    public PreviewFrameSourceClosedException() {
        super("preview frame source is closed");
    }
}
