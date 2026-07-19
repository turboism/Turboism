package dev.turboism.plugin.clipmask.b1.domain;

import java.util.Objects;

public record ClipMaskIssue(String targetId, ClipMaskIssueCode code, int ordinal) {
    public ClipMaskIssue {
        targetId = targetId == null ? "" : targetId;
        code = Objects.requireNonNull(code, "code");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
    }
}
