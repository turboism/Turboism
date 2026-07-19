package dev.turboism.plugin.clipmask.b1.domain;

public record ClipMaskCounts(
    int candidateCount,
    int acceptedCount,
    int rejectedCount,
    int exactDuplicateGroupCount,
    int inversionConflictGroupCount,
    int orderConflictGroupCount,
    int uniqueSourceCount,
    int totalSourceReferenceCount
) {
}
