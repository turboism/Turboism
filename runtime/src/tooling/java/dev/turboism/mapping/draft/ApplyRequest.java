package dev.turboism.mapping.draft;

import java.nio.file.Path;
import java.util.Objects;

/** Candidate/review/artifact tuple. Applying is a dry-run unless write is true. */
public record ApplyRequest(Path candidate, Path review, Path artifact, boolean write) {
    public ApplyRequest {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(review, "review");
        Objects.requireNonNull(artifact, "artifact");
    }
}
