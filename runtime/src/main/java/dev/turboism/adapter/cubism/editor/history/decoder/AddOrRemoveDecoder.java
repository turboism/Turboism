package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorHistorySemanticSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Direction and target decoder for approved exact Modeling add/remove classes. */
final class AddOrRemoveDecoder implements NativeHistoryDecoder {

    private static final List<TargetDescriptor> TARGETS = List.of(
        new TargetDescriptor(
            "cubism.editor-history.semantic.add-remove.parameter.class",
            "cubism.editor-history.semantic.add-remove.parameter.item",
            "cubism.editor-model.parameter-source.id",
            "cubism.editor-model.id.value",
            "PARAMETER",
            EditorHistorySemanticSelectorContract.ADD_REMOVE_PARAMETER_REQUIRED_ALIASES
        ),
        new TargetDescriptor(
            "cubism.editor-history.semantic.add-remove.part.class",
            "cubism.editor-history.semantic.add-remove.part.item",
            "cubism.editor-model.part-source.id",
            "cubism.editor-model.part-id.value",
            "PART",
            EditorHistorySemanticSelectorContract.ADD_REMOVE_PART_REQUIRED_ALIASES
        ),
        new TargetDescriptor(
            "cubism.editor-history.semantic.add-remove.drawable.class",
            "cubism.editor-history.semantic.add-remove.drawable.item",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.id.value",
            "DRAWABLE",
            EditorHistorySemanticSelectorContract.ADD_REMOVE_DRAWABLE_REQUIRED_ALIASES
        ),
        new TargetDescriptor(
            "cubism.editor-history.semantic.add-remove.deformer.class",
            "cubism.editor-history.semantic.add-remove.deformer.item",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.id.value",
            "DEFORMER",
            EditorHistorySemanticSelectorContract.ADD_REMOVE_DEFORMER_REQUIRED_ALIASES
        ),
        new TargetDescriptor(
            "cubism.editor-history.semantic.add-remove.parameter-group.class",
            "cubism.editor-history.semantic.add-remove.parameter-group.item",
            "cubism.editor-model.parameter-group.id",
            "cubism.editor-model.id.value",
            "PARAMETER_GROUP",
            EditorHistorySemanticSelectorContract.ADD_REMOVE_PARAMETER_GROUP_REQUIRED_ALIASES
        )
    );

    boolean supports(final VerifiedMemberResolver resolver, final Object entry) {
        if (!NativeHistoryDecoderRegistry.authorized(
            resolver,
            EditorHistorySemanticSelectorContract.ADD_REMOVE_REQUIRED_ALIASES
        )) {
            return false;
        }
        return targetDescriptor(resolver, entry).isPresent();
    }

    @Override
    public NativeHistoryDecodeResult decode(
        final Object entry,
        final String label,
        final NativeHistoryDecodeContext context,
        final int depth,
        final NativeHistoryDecoderRegistry registry
    ) {
        final VerifiedMemberResolver resolver = context.resolver();
        final TargetDescriptor descriptor = targetDescriptor(resolver, entry).orElseThrow();
        final Object rawAdd = resolver.invoke(
            "cubism.editor-history.semantic.add-remove.is-add",
            entry
        );
        final Object rawIndex = resolver.invoke(
            "cubism.editor-history.semantic.add-remove.index",
            entry
        );
        resolver.invoke("cubism.editor-history.semantic.add-remove.owner", entry);
        if (!(rawAdd instanceof Boolean isAdd) || !(rawIndex instanceof Integer index) || index < 0) {
            return NativeHistoryDecodeResult.failed("history.detail.add-remove-shape-invalid");
        }
        final HistoryChange.Operation operation = isAdd
            ? HistoryChange.Operation.ADD
            : HistoryChange.Operation.REMOVE;
        final Optional<HistoryTarget> target = target(resolver, entry, descriptor);
        final HistoryChange change = new HistoryChange(
            operation,
            target.isPresent() ? Optional.of(0) : Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT,
                Optional.empty(),
                List.of()
            )
        );
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            context.boundedLabel(label),
            target.isPresent()
                ? HistoryAction.DetailLevel.FULL
                : HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            target.map(List::of).orElse(List.of()),
            List.of(change),
            Optional.empty(),
            target.isPresent()
                ? Optional.empty()
                : Optional.of("history.detail.native-target-unavailable")
        );
        return NativeHistoryDecodeResult.decoded(detail);
    }

    private static Optional<TargetDescriptor> targetDescriptor(
        final VerifiedMemberResolver resolver,
        final Object entry
    ) {
        for (final TargetDescriptor descriptor : TARGETS) {
            final Set<String> aliases = new HashSet<>(descriptor.requiredAliases());
            if (NativeHistoryDecoderRegistry.authorized(resolver, Set.copyOf(aliases))
                && resolver.isExactInstance(descriptor.classAlias(), entry)) {
                return Optional.of(descriptor);
            }
        }
        return Optional.empty();
    }

    private static Optional<HistoryTarget> target(
        final VerifiedMemberResolver resolver,
        final Object entry,
        final TargetDescriptor descriptor
    ) {
        final Object item = resolver.invoke(descriptor.itemAlias(), entry);
        if (item == null) return Optional.empty();
        final Object hostId = resolver.invoke(descriptor.idAlias(), item);
        if (hostId == null) return Optional.empty();
        final Object value = resolver.invoke(descriptor.idValueAlias(), hostId);
        if (!(value instanceof String id) || id.isBlank()) return Optional.empty();
        try {
            return Optional.of(new HistoryTarget(descriptor.targetType(), Optional.of(id), Optional.empty()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private record TargetDescriptor(
        String classAlias,
        String itemAlias,
        String idAlias,
        String idValueAlias,
        String targetType,
        Set<String> requiredAliases
    ) {
    }
}
