package dev.turboism.plugin.uitheme.b1.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which discovered theme packages may be offered, and why the rest may not.
 *
 * <p>Pure and deterministic: given the same metadata list it always produces the same
 * accepted, rejected and issue lists in the same order. Rejection is reported, never thrown -
 * one bad package never prevents the others being catalogued. Not instantiable.
 */
public final class ThemePackageCatalog {

    private static final int MAX_CANDIDATES = 256;
    private static final int MAX_DEPTH = 16;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*");
    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
        .comparing(Candidate::id, ThemePackageCatalog::asciiCompare)
        .thenComparingInt(Candidate::ordinal);
    private static final Comparator<Issue> ISSUE_ORDER = Comparator
        .comparing(Issue::id, ThemePackageCatalog::asciiCompare)
        .thenComparing(Issue::code)
        .thenComparingInt(Issue::ordinal);

    private ThemePackageCatalog() {
    }

    /**
     * Classifies every candidate as accepted or rejected, recording one issue per rejection.
     *
     * <p>Candidates are validated in order: a catalog over 256 entries rejects everything at once;
     * then id syntax, name presence and field-length limits; then duplicate ids, which reject
     * every colliding entry rather than picking a winner; then parent references - self-parent,
     * missing parent, inheritance cycles, and chains deeper than 16. Finally rejection propagates
     * transitively, repeatedly, so a theme inheriting from a rejected one is rejected too. Each
     * candidate keeps only its first issue code. Results are sorted by id then by input position,
     * so ordering does not depend on the input order or on hash iteration.
     *
     * @param metadata the discovered package headers, in discovery order; must not be null and
     *                 must contain no nulls
     * @return the accepted and rejected candidates plus the issues explaining every rejection
     * @throws NullPointerException if the list or any element is null
     */
    public static Result build(final List<ThemePackageMetadata> metadata) {
        Objects.requireNonNull(metadata, "metadata");
        final List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < metadata.size(); index++) {
            candidates.add(new Candidate(index, Objects.requireNonNull(metadata.get(index), "metadata")));
        }
        final Map<Integer, IssueCode> rejected = new HashMap<>();
        final List<Issue> issues = new ArrayList<>();
        if (candidates.size() > MAX_CANDIDATES) {
            for (Candidate candidate : candidates) {
                reject(candidate, IssueCode.CATALOG_LIMIT, rejected, issues);
            }
            return result(candidates, rejected, issues);
        }

        final Map<String, List<Candidate>> byId = new HashMap<>();
        for (Candidate candidate : candidates) {
            byId.computeIfAbsent(candidate.id(), ignored -> new ArrayList<>()).add(candidate);
            final ThemePackageMetadata value = candidate.metadata();
            if (!isValidId(value.id())) {
                reject(candidate, IssueCode.INVALID_ID, rejected, issues);
            } else if (value.name() == null || value.name().isEmpty() || value.name().length() > 128) {
                reject(candidate, IssueCode.INVALID_NAME, rejected, issues);
            } else if (value.description().length() > 1024
                || value.author().length() > 128
                || value.url().length() > 2048
                || value.version().length() > 64) {
                reject(candidate, IssueCode.FIELD_LIMIT, rejected, issues);
            }
        }
        for (List<Candidate> duplicates : byId.values()) {
            if (duplicates.size() > 1) {
                for (Candidate duplicate : duplicates) {
                    reject(duplicate, IssueCode.DUPLICATE_ID, rejected, issues);
                }
            }
        }

        final Map<String, Candidate> unique = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (byId.get(candidate.id()).size() == 1) {
                unique.put(candidate.id(), candidate);
            }
        }
        for (Candidate candidate : candidates) {
            if (rejected.containsKey(candidate.ordinal())) {
                continue;
            }
            final String parent = candidate.metadata().parentId();
            if (parent == null) {
                continue;
            }
            if (parent.equals(candidate.id())) {
                reject(candidate, IssueCode.SELF_PARENT, rejected, issues);
            } else if (!unique.containsKey(parent)) {
                reject(candidate, IssueCode.MISSING_PARENT, rejected, issues);
            }
        }

        for (Candidate candidate : candidates) {
            if (!rejected.containsKey(candidate.ordinal())) {
                classifyInheritance(candidate, unique, rejected, issues);
            }
        }
        boolean changed;
        do {
            changed = false;
            for (Candidate candidate : candidates) {
                if (rejected.containsKey(candidate.ordinal())) {
                    continue;
                }
                final String parent = candidate.metadata().parentId();
                if (parent != null) {
                    final Candidate parentCandidate = unique.get(parent);
                    if (parentCandidate != null && rejected.containsKey(parentCandidate.ordinal())) {
                        reject(candidate, IssueCode.INVALID_PARENT, rejected, issues);
                        changed = true;
                    }
                }
            }
        } while (changed);
        return result(candidates, rejected, issues);
    }

    /**
     * Tests whether a string is a syntactically usable theme id: two dot-separated lowercase
     * segments, each starting with a letter and containing only letters, digits and hyphens,
     * with no hyphen immediately before the dot or at the end, and at most 64 characters overall.
     *
     * @param value the candidate id; null is simply invalid
     * @return whether the id is well-formed - says nothing about whether such a theme exists
     */
    public static boolean isValidId(final String value) {
        if (value == null || value.length() > 64 || !ID.matcher(value).matches()) {
            return false;
        }
        final int dot = value.indexOf('.');
        return value.charAt(dot - 1) != '-' && value.charAt(value.length() - 1) != '-';
    }

    private static void classifyInheritance(
        final Candidate start,
        final Map<String, Candidate> unique,
        final Map<Integer, IssueCode> rejected,
        final List<Issue> issues
    ) {
        final Set<String> seen = new HashSet<>();
        Candidate current = start;
        int depth = 1;
        while (current.metadata().parentId() != null) {
            if (!seen.add(current.id())) {
                markCycle(start, unique, rejected, issues);
                return;
            }
            final Candidate parent = unique.get(current.metadata().parentId());
            if (parent == null || rejected.containsKey(parent.ordinal())) {
                return;
            }
            current = parent;
            depth++;
            if (depth > MAX_DEPTH) {
                reject(start, IssueCode.INHERITANCE_DEPTH, rejected, issues);
                return;
            }
        }
    }

    private static void markCycle(
        final Candidate start,
        final Map<String, Candidate> unique,
        final Map<Integer, IssueCode> rejected,
        final List<Issue> issues
    ) {
        final Set<String> cycle = new HashSet<>();
        Candidate current = start;
        while (cycle.add(current.id())) {
            current = unique.get(current.metadata().parentId());
            if (current == null) {
                return;
            }
        }
        final String cycleStart = current.id();
        do {
            reject(current, IssueCode.INHERITANCE_CYCLE, rejected, issues);
            current = unique.get(current.metadata().parentId());
        } while (current != null && !current.id().equals(cycleStart));
    }

    private static void reject(
        final Candidate candidate,
        final IssueCode code,
        final Map<Integer, IssueCode> rejected,
        final List<Issue> issues
    ) {
        if (rejected.putIfAbsent(candidate.ordinal(), code) == null) {
            issues.add(new Issue(candidate.id(), code, candidate.ordinal()));
        }
    }

    private static Result result(
        final List<Candidate> candidates,
        final Map<Integer, IssueCode> rejected,
        final List<Issue> issues
    ) {
        final List<Candidate> acceptedValues = candidates.stream()
            .filter(candidate -> !rejected.containsKey(candidate.ordinal()))
            .sorted(CANDIDATE_ORDER)
            .toList();
        final List<Candidate> rejectedValues = candidates.stream()
            .filter(candidate -> rejected.containsKey(candidate.ordinal()))
            .sorted(CANDIDATE_ORDER)
            .toList();
        final List<Issue> orderedIssues = issues.stream().sorted(ISSUE_ORDER).toList();
        return new Result(acceptedValues, rejectedValues, orderedIssues);
    }

    private static int asciiCompare(final String left, final String right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    /**
     * A package under consideration, paired with where it appeared in the input.
     *
     * @param ordinal the candidate's position in the input list, used as the tie-break that keeps
     *                ordering deterministic when ids match
     * @param metadata the package header; must not be null
     */
    public record Candidate(int ordinal, ThemePackageMetadata metadata) {
        public Candidate {
            metadata = Objects.requireNonNull(metadata, "metadata");
        }

        /**
         * @return the package's id, or the empty string when it declares none, so candidates can be
         *         compared and grouped without null handling
         */
        public String id() {
            return metadata.id() == null ? "" : metadata.id();
        }
    }

    /**
     * One reason a candidate was rejected.
     *
     * @param id the rejected candidate's id, empty when it declared none
     * @param code why it was rejected - the first failure found, not necessarily the only one
     * @param ordinal the rejected candidate's position in the input list
     */
    public record Issue(String id, IssueCode code, int ordinal) {
        public Issue {
            id = id == null ? "" : id;
            code = Objects.requireNonNull(code, "code");
        }
    }

    /**
     * The complete classification of one catalog build.
     *
     * <p>All three lists are defensively copied, so the result is immutable. Together the
     * accepted and rejected lists account for every input candidate, and every rejected candidate
     * has exactly one issue.
     *
     * @param accepted candidates that may be offered, sorted by id then input position
     * @param rejected candidates that may not, in the same ordering
     * @param issues one issue per rejected candidate, sorted by id, code then input position
     */
    public record Result(List<Candidate> accepted, List<Candidate> rejected, List<Issue> issues) {
        public Result {
            accepted = List.copyOf(accepted);
            rejected = List.copyOf(rejected);
            issues = List.copyOf(issues);
        }
    }

    public enum IssueCode {
        INVALID_ID,
        INVALID_NAME,
        FIELD_LIMIT,
        CATALOG_LIMIT,
        DUPLICATE_ID,
        MISSING_PARENT,
        SELF_PARENT,
        INHERITANCE_CYCLE,
        INHERITANCE_DEPTH,
        INVALID_PARENT
    }
}
