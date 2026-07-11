package dev.turboism.mapping.draft;

import java.nio.file.Path;

/** Exact candidate bytes plus derived review, diff, and diagnostic files. */
public record GeneratedBundle(
    Path candidatePath,
    byte[] candidateBytes,
    Path reviewPath,
    byte[] reviewBytes,
    Path diffPath,
    byte[] diffBytes,
    Path diagnosticPath,
    byte[] diagnosticBytes
) {
    public GeneratedBundle {
        candidateBytes = candidateBytes.clone();
        reviewBytes = reviewBytes.clone();
        diffBytes = diffBytes.clone();
        diagnosticBytes = diagnosticBytes.clone();
    }

    @Override public byte[] candidateBytes() { return candidateBytes.clone(); }
    @Override public byte[] reviewBytes() { return reviewBytes.clone(); }
    @Override public byte[] diffBytes() { return diffBytes.clone(); }
    @Override public byte[] diagnosticBytes() { return diagnosticBytes.clone(); }
}
