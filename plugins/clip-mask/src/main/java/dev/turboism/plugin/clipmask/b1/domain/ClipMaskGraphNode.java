package dev.turboism.plugin.clipmask.b1.domain;

public record ClipMaskGraphNode(String id, Kind kind) {
    public enum Kind {
        TARGET,
        SOURCE
    }
}
