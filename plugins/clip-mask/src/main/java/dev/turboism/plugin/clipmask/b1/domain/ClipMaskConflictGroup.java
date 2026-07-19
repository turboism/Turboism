package dev.turboism.plugin.clipmask.b1.domain;

import java.util.List;

public record ClipMaskConflictGroup(String signature, List<String> targetIds) {
    public ClipMaskConflictGroup {
        targetIds = List.copyOf(targetIds);
    }
}
