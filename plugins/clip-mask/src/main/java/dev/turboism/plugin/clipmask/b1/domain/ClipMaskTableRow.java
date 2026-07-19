package dev.turboism.plugin.clipmask.b1.domain;

import java.util.List;

public record ClipMaskTableRow(String targetId, List<String> sourceIds, boolean inverted) {
    public ClipMaskTableRow {
        sourceIds = List.copyOf(sourceIds);
    }
}
