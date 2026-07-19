package dev.turboism.plugin.clipmask.b1.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClipMaskAnalyzer {
    private static final int MAX_TARGETS = 10_000;
    private static final int MAX_SOURCES = 64;
    private static final int MAX_TOTAL_SOURCES = 100_000;

    private ClipMaskAnalyzer() {
    }

    public static ClipMaskAnalysis analyze(final List<ClipMaskTarget> input) {
        final List<ClipMaskTarget> candidates = input == null ? List.of() : List.copyOf(input);
        final List<ClipMaskIssue> issues = new ArrayList<>();
        final Set<Integer> rejectedOrdinals = new LinkedHashSet<>();
        final Map<String, List<Integer>> targetOrdinals = new HashMap<>();
        int totalSources = 0;
        for (int index = 0; index < candidates.size(); index++) {
            final ClipMaskTarget target = candidates.get(index);
            final String id = target == null ? "" : target.targetId();
            targetOrdinals.computeIfAbsent(id, ignored -> new ArrayList<>()).add(index);
            if (candidates.size() > MAX_TARGETS) reject(index, id, ClipMaskIssueCode.TARGET_LIMIT, rejectedOrdinals, issues);
            if (!validId(id)) reject(index, id, ClipMaskIssueCode.INVALID_TARGET_ID, rejectedOrdinals, issues);
            if (target == null || target.sourceIds().isEmpty()) {
                reject(index, id, ClipMaskIssueCode.EMPTY_MASK_SET, rejectedOrdinals, issues);
                continue;
            }
            if (target.sourceIds().size() > MAX_SOURCES) reject(index, id, ClipMaskIssueCode.SOURCE_PER_TARGET_LIMIT, rejectedOrdinals, issues);
            totalSources += target.sourceIds().size();
            for (String source : target.sourceIds()) {
                if (!validId(source)) reject(index, id, ClipMaskIssueCode.INVALID_SOURCE_ID, rejectedOrdinals, issues);
            }
        }
        if (totalSources > MAX_TOTAL_SOURCES) {
            for (int index = 0; index < candidates.size(); index++) {
                reject(index, candidates.get(index).targetId(), ClipMaskIssueCode.TOTAL_SOURCE_LIMIT, rejectedOrdinals, issues);
            }
        }
        for (Map.Entry<String, List<Integer>> entry : targetOrdinals.entrySet()) {
            if (entry.getValue().size() > 1) {
                for (Integer index : entry.getValue()) reject(index, entry.getKey(), ClipMaskIssueCode.DUPLICATE_TARGET_ID, rejectedOrdinals, issues);
            }
        }
        final List<ClipMaskTarget> accepted = indexed(candidates, rejectedOrdinals, false);
        final List<ClipMaskTarget> rejected = indexed(candidates, rejectedOrdinals, true);
        final List<ClipMaskConflictGroup> exact = groups(accepted, SignatureKind.EXACT);
        final List<ClipMaskConflictGroup> inversion = groups(accepted, SignatureKind.INVERSION);
        final List<ClipMaskConflictGroup> order = groups(accepted, SignatureKind.ORDER);
        final List<ClipMaskTableRow> rows = accepted.stream()
            .map(target -> new ClipMaskTableRow(target.targetId(), target.sourceIds(), target.inverted()))
            .sorted(Comparator.comparing(ClipMaskTableRow::targetId)).toList();
        final Set<String> uniqueSources = new java.util.TreeSet<>();
        final List<ClipMaskGraphNode> nodes = new ArrayList<>();
        final List<ClipMaskGraphEdge> edges = new ArrayList<>();
        for (ClipMaskTarget target : accepted.stream().sorted(Comparator.comparing(ClipMaskTarget::targetId)).toList()) {
            nodes.add(new ClipMaskGraphNode(target.targetId(), ClipMaskGraphNode.Kind.TARGET));
            for (int index = 0; index < target.sourceIds().size(); index++) {
                final String source = target.sourceIds().get(index);
                uniqueSources.add(source);
                edges.add(new ClipMaskGraphEdge(target.targetId(), source, index));
            }
        }
        for (String source : uniqueSources) nodes.add(new ClipMaskGraphNode(source, ClipMaskGraphNode.Kind.SOURCE));
        nodes.sort(Comparator.comparing(ClipMaskGraphNode::kind).thenComparing(ClipMaskGraphNode::id));
        issues.sort(Comparator.comparing(ClipMaskIssue::targetId)
            .thenComparing(ClipMaskIssue::code)
            .thenComparingInt(ClipMaskIssue::ordinal));
        return new ClipMaskAnalysis(
            accepted, rejected, exact, inversion, order, rows, nodes, edges,
            new ClipMaskCounts(candidates.size(), accepted.size(), rejected.size(), exact.size(), inversion.size(), order.size(), uniqueSources.size(), totalSources),
            issues
        );
    }

    private static List<ClipMaskTarget> indexed(List<ClipMaskTarget> values, Set<Integer> rejected, boolean selectRejected) {
        final List<ClipMaskTarget> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (rejected.contains(index) == selectRejected && values.get(index) != null) result.add(values.get(index));
        }
        result.sort(Comparator.comparing(ClipMaskTarget::targetId));
        return List.copyOf(result);
    }

    private static List<ClipMaskConflictGroup> groups(List<ClipMaskTarget> values, SignatureKind kind) {
        final Map<String, List<ClipMaskTarget>> grouped = new LinkedHashMap<>();
        for (ClipMaskTarget value : values) grouped.computeIfAbsent(signature(value, kind), ignored -> new ArrayList<>()).add(value);
        final List<ClipMaskConflictGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<ClipMaskTarget>> entry : grouped.entrySet()) {
            final List<ClipMaskTarget> group = entry.getValue();
            boolean qualifies = group.size() >= 2;
            if (kind == SignatureKind.INVERSION) qualifies &= group.stream().map(ClipMaskTarget::inverted).distinct().count() > 1;
            if (kind == SignatureKind.ORDER) qualifies &= group.stream().map(value -> String.join(";", value.sourceIds())).distinct().count() > 1;
            if (qualifies) result.add(new ClipMaskConflictGroup(entry.getKey(), group.stream().map(ClipMaskTarget::targetId).sorted().toList()));
        }
        result.sort(Comparator.comparing(ClipMaskConflictGroup::signature).thenComparing(group -> String.join(";", group.targetIds())));
        return List.copyOf(result);
    }

    private static String signature(ClipMaskTarget value, SignatureKind kind) {
        if (kind == SignatureKind.EXACT) return (value.inverted() ? "INV|" : "NOR|") + String.join(";", value.sourceIds());
        final List<String> sorted = new ArrayList<>(value.sourceIds());
        sorted.sort(String::compareTo);
        return (kind == SignatureKind.ORDER ? (value.inverted() ? "INV|" : "NOR|") : "") + String.join(";", sorted);
    }

    private static void reject(int index, String id, ClipMaskIssueCode code, Set<Integer> rejected, List<ClipMaskIssue> issues) {
        rejected.add(index);
        if (issues.stream().noneMatch(issue -> issue.ordinal() == index && issue.code() == code)) {
            issues.add(new ClipMaskIssue(id, code, index));
        }
    }

    private static boolean validId(String value) { return value != null && !value.isEmpty() && value.length() <= 128; }
    private enum SignatureKind { EXACT, INVERSION, ORDER }
}
