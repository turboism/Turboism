package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared value construction for native direct-hierarchy relations.
 *
 * <p>Only already-admitted selectors are read, every value is bounded by the SDK DTO constructors,
 * and nothing is inferred: a source whose type or ID cannot be read exactly yields no relation at
 * all rather than a guessed one.</p>
 */
final class PartMembershipRelations {

    private static final List<String> SOURCE_TYPE_ALIASES = List.of(
        "cubism.editor-model.art-mesh-source.class",
        "cubism.editor-model.warp-source.class",
        "cubism.editor-model.rotation-source.class"
    );

    private PartMembershipRelations() {
    }

    /** Reads the child endpoint; the child is any ArtMesh or Deformer, never a Part. */
    static Optional<HistoryTarget> target(
        final VerifiedMemberResolver resolver,
        final Object source
    ) {
        for (final String alias : SOURCE_TYPE_ALIASES) {
            if (resolver.isInstance(alias, source)) {
                return targetFor(resolver, source, typeOf(alias));
            }
        }
        return Optional.empty();
    }

    /** Reads the parent Part endpoint. */
    static Optional<HistoryTarget> partTarget(
        final VerifiedMemberResolver resolver,
        final Object part
    ) {
        if (!resolver.isInstance("cubism.editor-model.part-source.class", part)) return Optional.empty();
        final Object id = resolver.invoke("cubism.editor-model.part-source.id", part);
        final Object value = id == null ? null : resolver.invoke("cubism.editor-model.part-id.value", id);
        return build(resolver, "PART", value, part);
    }

    static HistoryRelationChange.Endpoint unknownEndpoint() {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.UNKNOWN,
            Optional.empty()
        );
    }

    static boolean sameIdentity(final HistoryTarget left, final HistoryTarget right) {
        if (!left.type().equals(right.type())) return false;
        return left.id().isPresent() && left.id().equals(right.id());
    }

    static HistoryEntryDetail detail(
        final String label,
        final HistoryTarget child,
        final HistoryRelationChange relation,
        final HistoryAction.DetailLevel level,
        final Optional<String> degradation
    ) {
        final HistoryChange change = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
            Optional.of(relation)
        );
        return new HistoryEntryDetail(
            label,
            level,
            HistoryOrigin.hostUnattributed(),
            List.of(child),
            List.of(change),
            Optional.empty(),
            degradation
        );
    }

    /**
     * Coalesces the sibling entries of one host edit into the complete previous-and-next relation.
     *
     * <p>The host builds a Part move as a group that leaves the old Part and joins the new one, so a
     * single entry only ever establishes one side. When the whole native group is that pair, the
     * group is fully described by the net relation and this returns one complete localised detail
     * instead of two one-sided halves. A group that contains anything else is left untouched: the
     * SDK group contract requires every observed child to remain projected.</p>
     */
    static Optional<HistoryEntryDetail> coalesce(
        final List<HistoryEntryDetail> children,
        final String groupLabel
    ) {
        if (children.size() != 2) return Optional.empty();
        final Optional<HistoryEntryDetail> combined = combine(children.get(0), children.get(1));
        if (combined.isEmpty()) return Optional.empty();
        final HistoryEntryDetail relation = combined.orElseThrow();
        return Optional.of(new HistoryEntryDetail(
            groupLabel,
            relation.detailLevel(),
            relation.origin(),
            relation.targets(),
            relation.changes(),
            Optional.empty(),
            relation.degradationCode()
        ));
    }

    private static Optional<HistoryEntryDetail> combine(
        final HistoryEntryDetail left,
        final HistoryEntryDetail right
    ) {
        final Optional<RelationView> leftView = RelationView.of(left);
        final Optional<RelationView> rightView = RelationView.of(right);
        if (leftView.isEmpty() || rightView.isEmpty()) return Optional.empty();
        final RelationView join = leftView.orElseThrow().join()
            ? leftView.orElseThrow()
            : rightView.orElseThrow();
        final RelationView leave = leftView.orElseThrow().join()
            ? rightView.orElseThrow()
            : leftView.orElseThrow();
        if (!join.join() || leave.join()) return Optional.empty();
        if (!sameIdentity(join.child(), leave.child())) return Optional.empty();
        final HistoryTarget previous = leave.parent();
        final HistoryTarget next = join.parent();
        if (sameIdentity(previous, next)) return Optional.empty();
        final HistoryRelationChange relation = new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            new HistoryRelationChange.Endpoint(
                HistoryRelationChange.State.TARGET,
                Optional.of(previous)
            ),
            new HistoryRelationChange.Endpoint(
                HistoryRelationChange.State.TARGET,
                Optional.of(next)
            )
        );
        return Optional.of(detail(
            join.detail().summary(),
            join.child(),
            relation,
            HistoryAction.DetailLevel.FULL,
            Optional.empty()
        ));
    }

    private static Optional<HistoryTarget> targetFor(
        final VerifiedMemberResolver resolver,
        final Object source,
        final String type
    ) {
        final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", source);
        final Object value = id == null ? null : resolver.invoke("cubism.editor-model.id.value", id);
        return build(resolver, type, value, source);
    }

    private static Optional<HistoryTarget> build(
        final VerifiedMemberResolver resolver,
        final String type,
        final Object rawValue,
        final Object source
    ) {
        if (!(rawValue instanceof String value) || value.isBlank()) return Optional.empty();
        final Object rawName = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.local-name",
            source
        );
        final Optional<String> displayName = rawName instanceof String name && !name.isBlank()
            ? Optional.of(name)
            : Optional.empty();
        try {
            return Optional.of(new HistoryTarget(type, Optional.of(value), displayName));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static String typeOf(final String sourceTypeAlias) {
        return switch (sourceTypeAlias) {
            case "cubism.editor-model.art-mesh-source.class" -> "ART_MESH";
            case "cubism.editor-model.warp-source.class" -> "WARP_DEFORMER";
            case "cubism.editor-model.rotation-source.class" -> "ROTATION_DEFORMER";
            default -> throw new IllegalArgumentException("unsupported native source type");
        };
    }

    /** One decoded child of a group, read back from the immutable SDK detail. */
    private record RelationView(
        HistoryEntryDetail detail,
        HistoryTarget child,
        HistoryTarget parent,
        boolean join
    ) {

        /**
         * Resolves the relation one native group child establishes, looking through a wrapper.
         *
         * <p>The host wraps each membership leaf in its own single-child group, so the two sides of
         * one Part move arrive as {@code GroupUndo[GroupUndo[leave], GroupUndo[join]]} rather than
         * as two sibling leaves. Such a wrapper carries no edit of its own, so it is transparent
         * here. Only a provably empty-handed wrapper is looked through: a truncated group, or one
         * holding more than one child, describes more than the relation and stays opaque, which is
         * what keeps every observed child projected.</p>
         */
        static Optional<RelationView> of(final HistoryEntryDetail detail) {
            final Optional<RelationView> direct = direct(detail);
            if (direct.isPresent()) return direct;
            return detail.group()
                .filter(group -> !group.truncated())
                .filter(group -> group.observedChildCount() == 1)
                .filter(group -> group.children().size() == 1)
                .flatMap(group -> direct(group.children().get(0)));
        }

        private static Optional<RelationView> direct(final HistoryEntryDetail detail) {
            if (detail.detailLevel() == HistoryAction.DetailLevel.LABEL_ONLY
                || detail.targets().size() != 1
                || detail.changes().size() != 1) {
                return Optional.empty();
            }
            final Optional<HistoryRelationChange> relation = detail.changes().get(0).relation();
            if (relation.isEmpty()
                || relation.orElseThrow().kind() != HistoryRelationChange.Kind.PART_MEMBERSHIP) {
                return Optional.empty();
            }
            final HistoryRelationChange.Endpoint before = relation.orElseThrow().before();
            final HistoryRelationChange.Endpoint after = relation.orElseThrow().after();
            final boolean join = before.state() == HistoryRelationChange.State.UNKNOWN
                && after.state() == HistoryRelationChange.State.TARGET;
            final boolean leave = before.state() == HistoryRelationChange.State.TARGET
                && after.state() == HistoryRelationChange.State.UNKNOWN;
            if (!join && !leave) return Optional.empty();
            final HistoryTarget parent = (join ? after : before).target().orElseThrow();
            return Optional.of(new RelationView(
                Objects.requireNonNull(detail, "detail"),
                detail.targets().get(0),
                parent,
                join
            ));
        }
    }
}
