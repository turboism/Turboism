package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.PreviewApi;

/** Explicit destination collision policy for Editor file writes. */
@PreviewApi
public enum EditorOverwritePolicy {
    REJECT_EXISTING,
    REPLACE_EXISTING
}
