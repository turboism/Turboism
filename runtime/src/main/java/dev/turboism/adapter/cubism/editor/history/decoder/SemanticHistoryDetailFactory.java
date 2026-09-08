package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryTarget;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds domain-semantic details from facts projected by one exact operation descriptor. */
final class SemanticHistoryDetailFactory {

    private SemanticHistoryDetailFactory() { }

    static HistoryEntryDetail set(
        final String label,
        final HistoryOrigin origin,
        final HistoryTarget target,
        final String property,
        final Optional<String> before,
        final Optional<String> after,
        final HistoryEditContext context
    ) {
        final HistoryTarget trustedTarget = Objects.requireNonNull(target, "target");
        final String trustedProperty = Objects.requireNonNull(property, "property");
        final HistoryEditContext trustedContext = Objects.requireNonNull(context, "context");
        final Optional<SemanticHistoryOperationCatalog.Descriptor> descriptor =
            SemanticHistoryOperationCatalog.descriptor(trustedTarget.type(), trustedProperty);
        if (descriptor.isEmpty()) {
            return HistoryEntryDetail.labelOnly(
                label,
                origin,
                "history.semantic-operation-unmapped"
            );
        }
        final SemanticHistoryOperationCatalog.Descriptor trustedDescriptor = descriptor.orElseThrow();
        final Optional<String> normalizedBefore = normalizedValue(
            trustedDescriptor.valueKind(),
            Objects.requireNonNull(before, "before")
        );
        final Optional<String> normalizedAfter = normalizedValue(
            trustedDescriptor.valueKind(),
            Objects.requireNonNull(after, "after")
        );
        final Optional<String> degradation = degradation(
            trustedDescriptor,
            trustedContext,
            before,
            after,
            normalizedBefore,
            normalizedAfter
        );
        final HistoryChange change = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.of(trustedProperty),
            normalizedBefore,
            normalizedAfter,
            trustedContext
        );
        return new HistoryEntryDetail(
            label,
            degradation.isEmpty()
                ? HistoryAction.DetailLevel.FULL
                : HistoryAction.DetailLevel.PARTIAL,
            origin,
            List.of(trustedTarget),
            List.of(change),
            Optional.empty(),
            degradation
        );
    }

    private static Optional<String> normalizedValue(
        final SemanticHistoryOperationCatalog.ValueKind valueKind,
        final Optional<String> value
    ) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return switch (valueKind) {
            case COLOR -> SemanticHistoryValueCodec.color(value.orElseThrow());
            case TEXT, NUMBER, INTEGER, BOOLEAN, TARGET_ID, BOUNDED_COLLECTION -> value;
        };
    }

    private static Optional<String> degradation(
        final SemanticHistoryOperationCatalog.Descriptor descriptor,
        final HistoryEditContext context,
        final Optional<String> rawBefore,
        final Optional<String> rawAfter,
        final Optional<String> normalizedBefore,
        final Optional<String> normalizedAfter
    ) {
        if (!descriptor.supports(context.kind())) {
            return Optional.of("history.form-scope-unresolved");
        }
        if (context.kind() == HistoryEditContext.Kind.KEYFORM
            && context.coordinates().isEmpty()) {
            return Optional.of("history.keyform-coordinates-incomplete");
        }
        if ((rawBefore.isPresent() && normalizedBefore.isEmpty())
            || (rawAfter.isPresent() && normalizedAfter.isEmpty())) {
            return Optional.of("history.value-codec-unavailable");
        }
        if (normalizedAfter.isEmpty()) {
            return Optional.of("history.after-value-unavailable");
        }
        if (normalizedBefore.isEmpty()) {
            return Optional.of("history.before-value-unavailable");
        }
        return Optional.empty();
    }
}
