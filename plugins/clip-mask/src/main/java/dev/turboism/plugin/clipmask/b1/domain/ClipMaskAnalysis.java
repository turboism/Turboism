package dev.turboism.plugin.clipmask.b1.domain;

import java.util.List;

public record ClipMaskAnalysis(
    List<ClipMaskTarget> acceptedTargets,
    List<ClipMaskTarget> rejectedTargets,
    List<ClipMaskConflictGroup> exactDuplicateGroups,
    List<ClipMaskConflictGroup> inversionConflictGroups,
    List<ClipMaskConflictGroup> orderConflictGroups,
    List<ClipMaskTableRow> tableRows,
    List<ClipMaskGraphNode> graphNodes,
    List<ClipMaskGraphEdge> graphEdges,
    ClipMaskCounts counts,
    List<ClipMaskIssue> issues
) {
    public ClipMaskAnalysis {
        acceptedTargets = List.copyOf(acceptedTargets);
        rejectedTargets = List.copyOf(rejectedTargets);
        exactDuplicateGroups = List.copyOf(exactDuplicateGroups);
        inversionConflictGroups = List.copyOf(inversionConflictGroups);
        orderConflictGroups = List.copyOf(orderConflictGroups);
        tableRows = List.copyOf(tableRows);
        graphNodes = List.copyOf(graphNodes);
        graphEdges = List.copyOf(graphEdges);
        issues = List.copyOf(issues);
    }
}
