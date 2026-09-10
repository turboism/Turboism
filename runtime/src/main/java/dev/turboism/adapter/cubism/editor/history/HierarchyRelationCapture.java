package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Captures one direct native hierarchy relation around a verified object-hierarchy mutation.
 *
 * <p>The helper deliberately knows only exact resolver aliases. It freezes the child and old
 * direct parent before mutation, then obtains the after endpoint from the native direct getter
 * after mutation. The requested parent is an admission operand and is never used as the after
 * fact.</p>
 */
public final class HierarchyRelationCapture {
    private static final String OPERATION_ID = "history.relation.set-parent";
    private static final String PART_CHILDREN_ALIAS = "cubism.editor-model.part-source.children";

    private HierarchyRelationCapture() {
    }

    /** The non-mutating outcome selected by the preflight. */
    public enum Decision {
        CAPTURE,
        NO_CHANGE,
        REORDER
    }

    /**
     * Prepares one relation contribution. An empty result means that this exact host does not
     * prove the initial target-to-target relation (for example an unnormalized root), so the
     * caller must retain its existing legacy writer.
     */
    public static Optional<Plan> prepare(
        final VerifiedMemberResolver resolver,
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier,
        final Object modelSource,
        final Object childSource,
        final Object requestedParentSource,
        final boolean parentIsDeformer,
        final int requestedIndex,
        final String kindLabel
    ) {
        final VerifiedMemberResolver checkedResolver = Objects.requireNonNull(resolver, "resolver");
        final EditorAuthoringTransactionCoordinator.Binding checkedBinding =
            Objects.requireNonNull(binding, "binding");
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> checkedSupplier =
            Objects.requireNonNull(bindingSupplier, "bindingSupplier");
        Objects.requireNonNull(modelSource, "modelSource");
        Objects.requireNonNull(childSource, "childSource");
        Objects.requireNonNull(requestedParentSource, "requestedParentSource");
        final String checkedKindLabel = requireText(kindLabel, "kindLabel");
        requireSameBinding(checkedBinding, checkedSupplier);

        if (childSource == requestedParentSource) {
            throw new IllegalArgumentException("an object cannot be its own parent");
        }

        final ObjectType childType = objectType(checkedResolver, childSource);
        final ObjectType requestedParentType = objectType(checkedResolver, requestedParentSource);
        validateParenting(childType, requestedParentType, parentIsDeformer);
        requireActive(checkedResolver, modelSource, childSource, childType, "child");
        requireActive(checkedResolver, modelSource, requestedParentSource, requestedParentType, "parent");

        final HistoryTarget childTarget = target(checkedResolver, childSource, childType);
        final HistoryTarget requestedParentTarget =
            target(checkedResolver, requestedParentSource, requestedParentType);
        rejectSelfTarget(childTarget, requestedParentTarget, "requested parent");

        final Family requestedFamily = parentIsDeformer
            ? Family.DEFORMER_PARENT
            : Family.PART_MEMBERSHIP;
        final DirectRelation before = directRelation(
            checkedResolver,
            modelSource,
            childSource,
            requestedFamily,
            true
        );
        if (before.family() == Family.UNKNOWN) {
            // Root/internal-root normalization is intentionally not guessed in the first writer
            // slice. The existing native writer remains the safe fallback for this case.
            return Optional.empty();
        }
        if (before.family() == Family.PART_MEMBERSHIP && before.index() < 0) {
            // A Part compensation must preserve its exact old child position. Do not use a
            // guessed index when the exact children getter is not admitted by this resolver.
            return Optional.empty();
        }

        final HistoryTarget beforeTarget = target(
            checkedResolver,
            before.parent(),
            objectType(checkedResolver, before.parent())
        );
        rejectSelfTarget(childTarget, beforeTarget, "old parent");

        if (before.parent() == requestedParentSource) {
            if (before.family() != requestedFamily) {
                throw new IllegalStateException("native direct parent families disagree");
            }
            if (requestedFamily == Family.DEFORMER_PARENT) {
                return Optional.of(new Plan(
                    Decision.NO_CHANGE,
                    checkedResolver,
                    checkedBinding,
                    checkedSupplier,
                    modelSource,
                    childSource,
                    requestedParentSource,
                    requestedIndex,
                    checkedKindLabel,
                    childTarget,
                    beforeTarget,
                    before,
                    relationKind(requestedFamily)
                ));
            }
            final List<?> children = children(checkedResolver, requestedParentSource);
            if (children == null) return Optional.empty();
            final int effectiveIndex = effectiveIndex(children, childSource, requestedIndex);
            if (before.index() == effectiveIndex) {
                return Optional.of(new Plan(
                    Decision.NO_CHANGE,
                    checkedResolver,
                    checkedBinding,
                    checkedSupplier,
                    modelSource,
                    childSource,
                    requestedParentSource,
                    requestedIndex,
                    checkedKindLabel,
                    childTarget,
                    beforeTarget,
                    before,
                    relationKind(requestedFamily)
                ));
            }
            return Optional.of(new Plan(
                Decision.REORDER,
                checkedResolver,
                checkedBinding,
                checkedSupplier,
                modelSource,
                childSource,
                requestedParentSource,
                requestedIndex,
                checkedKindLabel,
                childTarget,
                beforeTarget,
                before,
                relationKind(requestedFamily)
            ));
        }

        final HistoryRelationChange.Kind relationKind = relationKind(requestedFamily);
        return Optional.of(new Plan(
            Decision.CAPTURE,
            checkedResolver,
            checkedBinding,
            checkedSupplier,
            modelSource,
            childSource,
            requestedParentSource,
            requestedIndex,
            checkedKindLabel,
            childTarget,
            beforeTarget,
            before,
            relationKind
        ));
    }

    private static void requireSameBinding(
        final EditorAuthoringTransactionCoordinator.Binding expected,
        final Supplier<EditorAuthoringTransactionCoordinator.Binding> supplier
    ) {
        final EditorAuthoringTransactionCoordinator.Binding actual =
            Objects.requireNonNull(supplier.get(), "authoring binding");
        if (!expected.equals(actual)) {
            throw new IllegalStateException("authoring binding changed before hierarchy capture");
        }
    }

    private static void validateParenting(
        final ObjectType childType,
        final ObjectType parentType,
        final boolean parentIsDeformer
    ) {
        if (parentIsDeformer) {
            if (!isDeformerChild(childType)
                || (parentType != ObjectType.WARP_DEFORMER
                    && parentType != ObjectType.ROTATION_DEFORMER)) {
                throw new IllegalArgumentException(
                    "invalid Deformer parent for " + childType.historyType()
                );
            }
            return;
        }
        if (parentType != ObjectType.PART || !isPartChild(childType)) {
            throw new IllegalArgumentException(
                "invalid Part parent for " + childType.historyType()
            );
        }
    }

    private static boolean isPartChild(final ObjectType type) {
        return type == ObjectType.PART
            || type == ObjectType.ART_MESH
            || type == ObjectType.WARP_DEFORMER
            || type == ObjectType.ROTATION_DEFORMER;
    }

    private static boolean isDeformerChild(final ObjectType type) {
        return type == ObjectType.ART_MESH
            || type == ObjectType.WARP_DEFORMER
            || type == ObjectType.ROTATION_DEFORMER;
    }

    private static void requireActive(
        final VerifiedMemberResolver resolver,
        final Object modelSource,
        final Object source,
        final ObjectType type,
        final String role
    ) {
        final String alias = switch (type) {
            case PART -> "cubism.editor-model.model-source.parts";
            case ART_MESH -> "cubism.editor-model.model-source.all-art-meshes";
            case WARP_DEFORMER, ROTATION_DEFORMER ->
                "cubism.editor-model.model-source.all-deformers";
        };
        final Object raw = resolver.invoke(alias, modelSource);
        if (!(raw instanceof List<?> sources)) {
            throw unavailable("Editor " + role + " source collection is unavailable.");
        }
        if (sources.stream().noneMatch(candidate -> candidate == source)) {
            throw new IllegalStateException(
                "Editor " + role + " is absent from the active model source collection."
            );
        }
    }

    private static DirectRelation directRelation(
        final VerifiedMemberResolver resolver,
        final Object modelSource,
        final Object childSource,
        final Family family,
        final boolean requirePartIndex
    ) {
        if (family == Family.PART_MEMBERSHIP) {
            final Object partParent = resolver.invoke(
                "cubism.editor-model.part-source.parent",
                childSource
            );
            if (partParent != null
                && !resolver.isInstance("cubism.editor-model.part-source.class", partParent)) {
                throw unavailable("Editor direct Part parent has an invalid source type.");
            }
            if (partParent == null) return new DirectRelation(Family.UNKNOWN, null, -1);
            final int index = requirePartIndex ? childIndex(resolver, partParent, childSource) : -1;
            requireActive(resolver, modelSource, partParent, ObjectType.PART, "direct Part parent");
            return new DirectRelation(Family.PART_MEMBERSHIP, partParent, index);
        }
        if (family == Family.DEFORMER_PARENT) {
            final Object deformerParent = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                childSource
            );
            if (deformerParent != null && !isDeformerSource(resolver, deformerParent)) {
                throw unavailable("Editor direct Deformer parent has an invalid source type.");
            }
            if (deformerParent == null) return new DirectRelation(Family.UNKNOWN, null, -1);
            final ObjectType parentType = objectType(resolver, deformerParent);
            requireActive(
                resolver,
                modelSource,
                deformerParent,
                parentType,
                "direct Deformer parent"
            );
            return new DirectRelation(Family.DEFORMER_PARENT, deformerParent, -1);
        }
        throw new IllegalArgumentException("unknown direct relation family");
    }

    private static boolean isDeformerSource(
        final VerifiedMemberResolver resolver,
        final Object source
    ) {
        return resolver.isInstance("cubism.editor-model.warp-source.class", source)
            || resolver.isInstance("cubism.editor-model.rotation-source.class", source);
    }

    private static int childIndex(
        final VerifiedMemberResolver resolver,
        final Object partSource,
        final Object childSource
    ) {
        final List<?> children = children(resolver, partSource);
        if (children == null) return -1;
        int found = -1;
        for (int index = 0; index < children.size(); index++) {
            if (children.get(index) == childSource) {
                if (found >= 0) {
                    throw unavailable("Editor direct Part parent contains a duplicate child.");
                }
                found = index;
            }
        }
        if (found < 0) {
            throw unavailable("Editor object is absent from its declared Part parent.");
        }
        return found;
    }

    private static List<?> children(
        final VerifiedMemberResolver resolver,
        final Object partSource
    ) {
        if (!childrenAuthorized(resolver)) return null;
        final Object raw = resolver.invoke(PART_CHILDREN_ALIAS, partSource);
        if (!(raw instanceof List<?> values)) {
            throw unavailable("Editor Part children are unavailable.");
        }
        return List.copyOf(values);
    }

    private static boolean childrenAuthorized(final VerifiedMemberResolver resolver) {
        final Set<String> aliases = Set.of(PART_CHILDREN_ALIAS);
        return resolver.authorizesFeature(
            EditorObjectHierarchyEditSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID,
            aliases
        ) || resolver.authorizesFeature(
            EditorPartStructureSelectorContract.ADAPTER_SLICE_ID,
            EditorPartStructureSelectorContract.CAPABILITY_ID,
            aliases
        );
    }

    private static int effectiveIndex(
        final List<?> children,
        final Object childSource,
        final int requestedIndex
    ) {
        int oldIndex = -1;
        int occurrences = 0;
        for (int index = 0; index < children.size(); index++) {
            if (children.get(index) == childSource) {
                oldIndex = index;
                occurrences++;
            }
        }
        if (occurrences != 1) {
            throw unavailable("Editor same-parent ordering is not a unique child relation.");
        }
        final int remaining = children.size() - 1;
        if (requestedIndex < 0) return remaining;
        return Math.min(requestedIndex, remaining);
    }

    private static ObjectType objectType(
        final VerifiedMemberResolver resolver,
        final Object source
    ) {
        if (resolver.isInstance("cubism.editor-model.part-source.class", source)) {
            return ObjectType.PART;
        }
        if (resolver.isInstance("cubism.editor-model.art-mesh-source.class", source)) {
            return ObjectType.ART_MESH;
        }
        if (resolver.isInstance("cubism.editor-model.warp-source.class", source)) {
            return ObjectType.WARP_DEFORMER;
        }
        if (resolver.isInstance("cubism.editor-model.rotation-source.class", source)) {
            return ObjectType.ROTATION_DEFORMER;
        }
        throw unavailable("Editor hierarchy source has an unsupported exact type.");
    }

    private static HistoryTarget target(
        final VerifiedMemberResolver resolver,
        final Object source,
        final ObjectType type
    ) {
        final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", source);
        final Object rawValue = resolver.invoke("cubism.editor-model.id.value", id);
        if (!(rawValue instanceof String value) || value.isBlank()) {
            throw unavailable("Editor hierarchy source ID is invalid.");
        }
        final Object rawName = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.local-name",
            source
        );
        final Optional<String> name = rawName instanceof String nameValue && !nameValue.isBlank()
            ? Optional.of(nameValue)
            : Optional.empty();
        return new HistoryTarget(type.historyType(), Optional.of(value), name);
    }

    private static void rejectSelfTarget(
        final HistoryTarget child,
        final HistoryTarget parent,
        final String role
    ) {
        if (child.type().equals(parent.type()) && child.id().equals(parent.id())) {
            throw new IllegalArgumentException(
                "a hierarchy relation cannot target the child as its " + role
            );
        }
    }

    private static HistoryRelationChange.Kind relationKind(final Family family) {
        return switch (family) {
            case PART_MEMBERSHIP -> HistoryRelationChange.Kind.PART_MEMBERSHIP;
            case DEFORMER_PARENT -> HistoryRelationChange.Kind.DEFORMER_PARENT;
            case UNKNOWN -> throw new IllegalArgumentException("unknown relation family");
        };
    }

    private static String requireText(final String value, final String name) {
        final String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return checked;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private enum Family {
        PART_MEMBERSHIP,
        DEFORMER_PARENT,
        UNKNOWN
    }

    private enum ObjectType {
        PART("PART"),
        ART_MESH("ART_MESH"),
        WARP_DEFORMER("WARP_DEFORMER"),
        ROTATION_DEFORMER("ROTATION_DEFORMER");

        private final String historyType;

        ObjectType(final String historyType) {
            this.historyType = historyType;
        }

        String historyType() {
            return historyType;
        }
    }

    private record DirectRelation(Family family, Object parent, int index) {
    }

    /** Immutable plan retained until the native contribution is admitted. */
    public static final class Plan {
        private final Decision decision;
        private final VerifiedMemberResolver resolver;
        private final EditorAuthoringTransactionCoordinator.Binding binding;
        private final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier;
        private final Object modelSource;
        private final Object childSource;
        private final Object requestedParentSource;
        private final int requestedIndex;
        private final String kindLabel;
        private final HistoryTarget childTarget;
        private final HistoryTarget beforeTarget;
        private final DirectRelation before;
        private final HistoryRelationChange.Kind relationKind;
        private final String label;
        private final HistoryRelationChange.Endpoint beforeEndpoint;
        private final HistoryEntryDetail pending;

        private Plan(
            final Decision decision,
            final VerifiedMemberResolver resolver,
            final EditorAuthoringTransactionCoordinator.Binding binding,
            final Supplier<EditorAuthoringTransactionCoordinator.Binding> bindingSupplier,
            final Object modelSource,
            final Object childSource,
            final Object requestedParentSource,
            final int requestedIndex,
            final String kindLabel,
            final HistoryTarget childTarget,
            final HistoryTarget beforeTarget,
            final DirectRelation before,
            final HistoryRelationChange.Kind relationKind
        ) {
            this.decision = Objects.requireNonNull(decision, "decision");
            this.resolver = resolver;
            this.binding = binding;
            this.bindingSupplier = bindingSupplier;
            this.modelSource = modelSource;
            this.childSource = childSource;
            this.requestedParentSource = requestedParentSource;
            this.requestedIndex = requestedIndex;
            this.kindLabel = kindLabel;
            this.childTarget = childTarget;
            this.beforeTarget = beforeTarget;
            this.before = before;
            this.relationKind = Objects.requireNonNull(relationKind, "relationKind");
            this.label = "Turboism: Set Parent " + kindLabel;
            this.beforeEndpoint = new HistoryRelationChange.Endpoint(
                HistoryRelationChange.State.TARGET,
                Optional.of(beforeTarget)
            );
            final HistoryOrigin origin = HistoryOrigin.turboism(binding.pluginId(), OPERATION_ID);
            final HistoryRelationChange relation = new HistoryRelationChange(
                relationKind,
                beforeEndpoint,
                unknownEndpoint()
            );
            this.pending = detail(
                label,
                origin,
                childTarget,
                relation,
                HistoryAction.DetailLevel.PARTIAL,
                Optional.of("history.relation.after-pending")
            );
        }

        /** Returns whether the caller should capture, no-op, or retain legacy reorder behavior. */
        public Decision decision() {
            return decision;
        }

        /** Builds the coordinator contribution for a captured relation. */
        public EditorUndoContribution contribution(
            final EditorUndoContribution.UndoAdmission undoAdmission
        ) {
            if (decision != Decision.CAPTURE) {
                throw new IllegalStateException("a non-capture hierarchy plan has no contribution");
            }
            final String targetIdentity = binding.modelIdentity()
                + ":hierarchy:" + childTarget.type() + ":" + childTarget.id().orElseThrow();
            if (targetIdentity.length() > 256) {
                throw new IllegalArgumentException("hierarchy target identity is too long");
            }
            return new EditorUndoContribution(
                OPERATION_ID,
                targetIdentity,
                label,
                undoAdmission,
                this::mutateNative,
                this::applied,
                this::compensate,
                this::restored,
                EnumSet.of(
                    EditorRefreshRequirement.MODEL_INSTANCES,
                    EditorRefreshRequirement.DEFORMER_PALETTE,
                    EditorRefreshRequirement.CANVAS,
                    EditorRefreshRequirement.MARK_DIRTY
                ),
                pending
            ).withCaptureAfter(this::captureAfter);
        }

        private void mutateNative() {
            requireCurrentBinding();
            if (relationKind == HistoryRelationChange.Kind.DEFORMER_PARENT) {
                final Object parentGuid = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.guid",
                    requestedParentSource
                );
                resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
                    childSource,
                    parentGuid
                );
            } else {
                resolver.invoke(
                    "cubism.editor-model.part-source.add-child",
                    requestedParentSource,
                    childSource,
                    Integer.valueOf(requestedIndex)
                );
            }
        }

        private boolean applied() {
            requireCurrentBinding();
            final DirectRelation actual = directRelation(
                resolver,
                modelSource,
                childSource,
                before.family(),
                false
            );
            return actual.family() == before.family() && actual.parent() == requestedParentSource;
        }

        private void compensate() {
            requireCurrentBinding();
            switch (before.family()) {
                case PART_MEMBERSHIP -> resolver.invoke(
                    "cubism.editor-model.part-source.add-child",
                    before.parent(),
                    childSource,
                    Integer.valueOf(before.index())
                );
                case DEFORMER_PARENT -> {
                    final Object oldGuid = resolver.invoke(
                        "cubism.editor-model.parameter-controllable-source.guid",
                        before.parent()
                    );
                    resolver.invoke(
                        "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
                        childSource,
                        oldGuid
                    );
                }
                case UNKNOWN -> throw new IllegalStateException(
                    "cannot compensate an unnormalized root relation"
                );
            }
        }

        private boolean restored() {
            requireCurrentBinding();
            final DirectRelation actual = directRelation(
                resolver,
                modelSource,
                childSource,
                before.family(),
                before.family() == Family.PART_MEMBERSHIP
            );
            if (actual.family() != before.family() || actual.parent() != before.parent()) return false;
            return before.family() != Family.PART_MEMBERSHIP || actual.index() == before.index();
        }

        private HistoryEntryDetail captureAfter() {
            requireCurrentBinding();
            final DirectRelation actual = directRelation(
                resolver,
                modelSource,
                childSource,
                before.family(),
                false
            );
            final HistoryRelationChange.Endpoint afterEndpoint;
            if (actual.family() == Family.UNKNOWN) {
                afterEndpoint = unknownEndpoint();
            } else {
                final ObjectType actualType = objectType(resolver, actual.parent());
                final HistoryTarget actualTarget = target(resolver, actual.parent(), actualType);
                afterEndpoint = new HistoryRelationChange.Endpoint(
                    HistoryRelationChange.State.TARGET,
                    Optional.of(actualTarget)
                );
            }
            final HistoryRelationChange relation = new HistoryRelationChange(
                relationKind,
                beforeEndpoint,
                afterEndpoint
            );
            final HistoryOrigin origin = HistoryOrigin.turboism(binding.pluginId(), OPERATION_ID);
            final boolean full = fullRelation(childTarget, beforeEndpoint, afterEndpoint, relationKind);
            return detail(
                label,
                origin,
                childTarget,
                relation,
                full ? HistoryAction.DetailLevel.FULL : HistoryAction.DetailLevel.PARTIAL,
                full ? Optional.empty() : Optional.of(degradation(beforeEndpoint, afterEndpoint, relationKind))
            );
        }

        private void requireCurrentBinding() {
            final EditorAuthoringTransactionCoordinator.Binding actual =
                Objects.requireNonNull(bindingSupplier.get(), "authoring binding");
            if (!binding.equals(actual)) {
                throw new IllegalStateException("hierarchy relation binding changed during authoring");
            }
        }
    }

    private static HistoryRelationChange.Endpoint unknownEndpoint() {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.UNKNOWN,
            Optional.empty()
        );
    }

    private static boolean fullRelation(
        final HistoryTarget child,
        final HistoryRelationChange.Endpoint before,
        final HistoryRelationChange.Endpoint after,
        final HistoryRelationChange.Kind kind
    ) {
        if (child.id().isEmpty() || child.displayName().isEmpty()) return false;
        if (before.state() != HistoryRelationChange.State.TARGET
            || after.state() != HistoryRelationChange.State.TARGET) return false;
        final HistoryTarget oldTarget = before.target().orElseThrow();
        final HistoryTarget newTarget = after.target().orElseThrow();
        if (oldTarget.id().isEmpty() || oldTarget.displayName().isEmpty()
            || newTarget.id().isEmpty() || newTarget.displayName().isEmpty()) return false;
        if (!legalParent(kind, oldTarget.type()) || !legalParent(kind, newTarget.type())) return false;
        if (child.type().equals(oldTarget.type()) && child.id().equals(oldTarget.id())) return false;
        if (child.type().equals(newTarget.type()) && child.id().equals(newTarget.id())) return false;
        return !sameEndpointIdentity(before, after);
    }

    private static boolean legalParent(
        final HistoryRelationChange.Kind kind,
        final String type
    ) {
        return switch (kind) {
            case PART_MEMBERSHIP -> "PART".equals(type);
            case DEFORMER_PARENT -> "WARP_DEFORMER".equals(type)
                || "ROTATION_DEFORMER".equals(type);
        };
    }

    private static boolean sameEndpointIdentity(
        final HistoryRelationChange.Endpoint left,
        final HistoryRelationChange.Endpoint right
    ) {
        if (left.state() != right.state()) return false;
        if (left.state() != HistoryRelationChange.State.TARGET) return true;
        final HistoryTarget leftTarget = left.target().orElseThrow();
        final HistoryTarget rightTarget = right.target().orElseThrow();
        return leftTarget.type().equals(rightTarget.type())
            && leftTarget.id().equals(rightTarget.id());
    }

    private static String degradation(
        final HistoryRelationChange.Endpoint before,
        final HistoryRelationChange.Endpoint after,
        final HistoryRelationChange.Kind kind
    ) {
        if (after.state() == HistoryRelationChange.State.UNKNOWN) {
            return "history.relation.after-unknown";
        }
        if (before.state() == HistoryRelationChange.State.UNKNOWN) {
            return "history.relation.before-unknown";
        }
        final HistoryTarget oldTarget = before.target().orElseThrow();
        final HistoryTarget newTarget = after.target().orElseThrow();
        if (oldTarget.displayName().isEmpty() || newTarget.displayName().isEmpty()) {
            return "history.relation.name-unavailable";
        }
        if (!legalParent(kind, oldTarget.type()) || !legalParent(kind, newTarget.type())) {
            return "history.relation.endpoint-unverified";
        }
        if (sameEndpointIdentity(before, after)) return "history.relation.identity-unchanged";
        return "history.relation.partial";
    }

    private static HistoryEntryDetail detail(
        final String label,
        final HistoryOrigin origin,
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
            new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT,
                Optional.empty(),
                List.of()
            ),
            Optional.of(relation)
        );
        return new HistoryEntryDetail(
            label,
            level,
            origin,
            List.of(child),
            List.of(change),
            Optional.empty(),
            degradation
        );
    }
}
