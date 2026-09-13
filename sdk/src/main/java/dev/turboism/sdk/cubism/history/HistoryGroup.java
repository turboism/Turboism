package dev.turboism.sdk.cubism.history;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded recursive projection of one grouped native or Turboism history operation. */
public record HistoryGroup(
    Optional<String> groupId,
    int observedChildCount,
    List<HistoryEntryDetail> children,
    boolean truncated
) {

    private static final int MAX_CHILDREN = 64;
    private static final int MAX_GROUP_ID_LENGTH = 128;

    public HistoryGroup {
        groupId = Objects.requireNonNull(groupId, "groupId").map(HistoryGroup::normalizedGroupId);
        children = List.copyOf(Objects.requireNonNull(children, "children"));
        if (children.size() > MAX_CHILDREN) {
            throw new IllegalArgumentException(
                "children must not exceed " + MAX_CHILDREN + " entries"
            );
        }
        if (children.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("children must not contain null entries");
        }
        if (observedChildCount < children.size()) {
            throw new IllegalArgumentException(
                "observedChildCount must be at least the projected child count"
            );
        }
        final boolean nestedTruncation = children.stream()
            .map(HistoryEntryDetail::group)
            .flatMap(java.util.Optional::stream)
            .anyMatch(HistoryGroup::truncated);
        if (!truncated && (observedChildCount > children.size() || nestedTruncation)) {
            throw new IllegalArgumentException(
                "truncated must be true when observed or nested children were omitted"
            );
        }
    }
    private static String normalizedGroupId(final String value) {
        final String normalized = Objects.requireNonNull(value, "groupId").strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException("groupId must not be blank");
        if (normalized.length() > MAX_GROUP_ID_LENGTH) {
            throw new IllegalArgumentException("groupId must not exceed " + MAX_GROUP_ID_LENGTH + " characters");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("groupId must not contain control characters");
        }
        return normalized;
    }
}
