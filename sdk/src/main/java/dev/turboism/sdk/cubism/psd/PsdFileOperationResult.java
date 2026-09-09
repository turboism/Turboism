package dev.turboism.sdk.cubism.psd;

import java.util.Objects;

/**
 * Result of opening or stopping a PSD handle, with a runtime-sanitized diagnostic.
 * OPENED means the default application launch was accepted, not that the editor finished loading.
 * STOPPED means owned work has settled; it does not imply deletion of any file.
 */
public record PsdFileOperationResult(Status status, String diagnostic) {
    public PsdFileOperationResult {
        status = Objects.requireNonNull(status, "status");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Outcome of one authorized file-handle operation. */
    public enum Status {
        OPENED,
        STOPPED,
        UNAVAILABLE,
        REJECTED,
        FAILED
    }
}
