package dev.turboism.plugin.clipmask.b1.domain;

import java.util.Objects;

public record ClipMaskIssue(String targetId, ClipMaskIssueCode code) {
    public ClipMaskIssue {
        targetId = targetId == null ? "" : targetId;
        code = Objects.requireNonNull(code, "code");
    }
}
