package dev.turboism.plugin.clipmask.b1.domain;

import java.util.List;

public record ClipMaskTarget(String targetId, List<String> sourceIds, boolean inverted) {
    public ClipMaskTarget {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
