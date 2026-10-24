package dev.turboism.plugin.parametersync.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A parameter synchronization group.
 *
 * <p>All parameters in the group are peer members. When any member parameter's value
 * changes via user interaction on the UI, its normalized progress is calculated and
 * instantly synchronized to all other members in the group, without needing a designated "source".</p>
 */
public final class SyncRule {

    private final String id;
    private final String name;
    private final List<SyncGroupMember> members;
    private final boolean enabled;

    public SyncRule(final String id, final String name, final List<SyncGroupMember> members, final boolean enabled) {
        this.id = id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
        final List<SyncGroupMember> cleanMembers = new ArrayList<>();
        if (members != null) {
            for (final SyncGroupMember m : members) {
                if (m != null
                        && cleanMembers.stream()
                                .noneMatch(existing -> existing.parameterId().equals(m.parameterId()))) {
                    cleanMembers.add(m);
                }
            }
        }
        if (cleanMembers.size() < 2) {
            throw new IllegalArgumentException("A synchronization group must have at least 2 distinct parameters.");
        }
        this.members = Collections.unmodifiableList(cleanMembers);
        this.name = name != null && !name.isBlank() ? name.strip() : defaultGroupName(this.members);
        this.enabled = enabled;
    }

    public SyncRule(final String id, final List<SyncGroupMember> members, final boolean enabled) {
        this(id, null, members, enabled);
    }

    public SyncRule(
            final String id,
            final String sourceId,
            final List<SyncTarget> targets,
            final boolean bidirectional,
            final boolean enabled) {
        this(id, null, adaptLegacyTargets(sourceId, targets), enabled);
    }

    public SyncRule(
            final String id,
            final String paramA,
            final String paramB,
            final SyncMode mode,
            final float ratio,
            final float offset,
            final boolean bidirectional,
            final boolean enabled) {
        this(
                id,
                null,
                List.of(
                        SyncGroupMember.of(paramA, SyncMode.NORMALIZED_RANGE),
                        new SyncGroupMember(paramB, mode, ratio, offset)),
                enabled);
    }

    public static SyncRule createDefault(final String paramA, final String paramB) {
        return new SyncRule(
                UUID.randomUUID().toString(),
                null,
                List.of(SyncGroupMember.of(paramA), SyncGroupMember.of(paramB)),
                true);
    }

    public static SyncRule of(final List<String> parameterIds) {
        final List<SyncGroupMember> list =
                parameterIds.stream().map(SyncGroupMember::of).toList();
        return new SyncRule(UUID.randomUUID().toString(), list, true);
    }

    private static String defaultGroupName(final List<SyncGroupMember> members) {
        return members.stream().map(SyncGroupMember::parameterId).collect(Collectors.joining(" ↔ "));
    }

    private static List<SyncGroupMember> adaptLegacyTargets(final String sourceId, final List<SyncTarget> targets) {
        final List<SyncGroupMember> list = new ArrayList<>();
        if (sourceId != null && !sourceId.isBlank()) {
            list.add(SyncGroupMember.of(sourceId));
        }
        if (targets != null) {
            for (final SyncTarget t : targets) {
                list.add(new SyncGroupMember(t.targetId(), t.mode(), t.ratio(), t.offset()));
            }
        }
        return list;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<SyncGroupMember> members() {
        return members;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean contains(final String parameterId) {
        if (parameterId == null) {
            return false;
        }
        return members.stream().anyMatch(m -> m.parameterId().equals(parameterId));
    }

    public Optional<SyncGroupMember> member(final String parameterId) {
        if (parameterId == null) {
            return Optional.empty();
        }
        return members.stream().filter(m -> m.parameterId().equals(parameterId)).findFirst();
    }

    public List<String> memberIds() {
        return members.stream().map(SyncGroupMember::parameterId).toList();
    }

    // ── Legacy Compatibility Accessors ──

    public String sourceId() {
        return members.isEmpty() ? "" : members.get(0).parameterId();
    }

    public String targetId() {
        return members.size() > 1 ? members.get(1).parameterId() : "";
    }

    public List<SyncTarget> targets() {
        if (members.size() <= 1) {
            return List.of();
        }
        return members.subList(1, members.size()).stream()
                .map(m -> new SyncTarget(m.parameterId(), m.mode(), m.ratio(), m.offset()))
                .toList();
    }

    public SyncMode mode() {
        return members.size() > 1 ? members.get(1).mode() : SyncMode.NORMALIZED_RANGE;
    }

    public float ratio() {
        return members.size() > 1 ? members.get(1).ratio() : 1.0f;
    }

    public float offset() {
        return members.size() > 1 ? members.get(1).offset() : 0.0f;
    }

    public boolean bidirectional() {
        return true;
    }

    public boolean hasTarget(final String parameterId) {
        return contains(parameterId);
    }

    public SyncRule withEnabled(final boolean enabled) {
        return new SyncRule(id, name, members, enabled);
    }

    public SyncRule withName(final String name) {
        return new SyncRule(id, name, members, enabled);
    }

    public SyncRule withMembers(final List<SyncGroupMember> newMembers) {
        return new SyncRule(id, name, newMembers, enabled);
    }

    public SyncRule withBidirectional(final boolean ignored) {
        return this;
    }

    public SyncRule withTargets(final List<SyncTarget> newTargets) {
        return new SyncRule(id, sourceId(), newTargets, true, enabled);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final SyncRule syncRule = (SyncRule) o;
        return enabled == syncRule.enabled
                && Objects.equals(id, syncRule.id)
                && Objects.equals(name, syncRule.name)
                && Objects.equals(members, syncRule.members);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, members, enabled);
    }

    @Override
    public String toString() {
        return "SyncGroup{"
                + "id='" + id + '\''
                + ", name='" + name + '\''
                + ", members=" + memberIds()
                + ", enabled=" + enabled
                + '}';
    }
}
