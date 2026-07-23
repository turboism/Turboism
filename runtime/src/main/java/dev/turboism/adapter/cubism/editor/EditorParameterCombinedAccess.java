package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorParameterCombinedWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Verified Editor-native four-corner parameter pairing operations. */
final class EditorParameterCombinedAccess {

    private final VerifiedMemberResolver resolver;
    private final ModelGuard modelGuard;
    private final ParameterSourceLookup sourceLookup;

    EditorParameterCombinedAccess(
        final VerifiedMemberResolver resolver,
        final ModelGuard modelGuard,
        final ParameterSourceLookup sourceLookup
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.sourceLookup = Objects.requireNonNull(sourceLookup, "sourceLookup");
    }

    Optional<ParameterId> partner(
        final String expectedIdentity,
        final Object expectedModel,
        final ParameterId id
    ) {
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        final Object source = sourceLookup.source(expectedModel, id);
        final PairBinding pair = pairBinding(source);
        if (pair == null) {
            return Optional.empty();
        }
        final Object partner = pair.first() == source ? pair.second() : pair.first();
        return Optional.of(sourceId(partner));
    }

    void combine(
        final String expectedIdentity,
        final Object expectedModel,
        final ParameterId id,
        final ParameterId partnerId
    ) {
        Objects.requireNonNull(partnerId, "partnerId");
        if (id.equals(partnerId)) {
            throw new IllegalArgumentException("A parameter cannot be Combined with itself.");
        }
        requireAuthorization();
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        final Object source = sourceLookup.source(expectedModel, id);
        final Object partner = sourceLookup.source(expectedModel, partnerId);
        final Object group = parentGroup(source);
        if (parentGroup(partner) != group) {
            throw new IllegalStateException(
                "Combined parameters must belong to the same Editor parameter group."
            );
        }
        final PairBinding current = pairBinding(source);
        if (current != null && current.contains(source, partner)) {
            return;
        }
        if (current != null || pairBinding(partner) != null) {
            throw new IllegalStateException(
                "One of the parameters is already Combined with another parameter."
            );
        }
        edit(expectedIdentity, expectedModel, group, source, partner, () -> {
            final List<Object> children = groupChildren(group);
            final int sourceIndex = identityIndex(children, source);
            if (sourceIndex < 0 || identityIndex(children, partner) < 0) {
                throw unavailable("Editor Combined pair membership is unavailable.");
            }
            resolver.invoke(
                "cubism.editor-model.parameter-group.remove",
                group,
                resolver.invoke("cubism.editor-model.parameter-source.guid", partner)
            );
            resolver.invoke(
                "cubism.editor-model.parameter-group.add",
                group,
                partner,
                Integer.valueOf(sourceIndex + 1)
            );
            setCombined(source, true);
            setCombined(partner, false);
        });
    }

    void uncombine(
        final String expectedIdentity,
        final Object expectedModel,
        final ParameterId id
    ) {
        requireAuthorization();
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
        final Object source = sourceLookup.source(expectedModel, id);
        final PairBinding pair = pairBinding(source);
        if (pair == null) {
            return;
        }
        edit(
            expectedIdentity,
            expectedModel,
            parentGroup(source),
            pair.first(),
            pair.second(),
            () -> {
                setCombined(pair.first(), false);
                setCombined(pair.second(), false);
            }
        );
    }

    private void edit(
        final String expectedIdentity,
        final Object expectedModel,
        final Object group,
        final Object firstSource,
        final Object secondSource,
        final Runnable mutation
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object undo = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, "Turboism: Edit Combined Parameters"
        );
        boolean completed = false;
        try {
            addUndo(undo, group);
            addUndo(undo, firstSource);
            addUndo(undo, secondSource);
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    refreshUi(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            mutation.run();
            refreshUi(app);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
        modelGuard.requireCurrent(expectedIdentity, expectedModel);
    }

    private void addUndo(final Object edit, final Object target) {
        final Object targetUndo = resolver.construct(
            "cubism.editor-model.simple-undo.create",
            "Turboism: Edit Combined Parameters",
            target,
            null
        );
        final Object accepted = resolver.invoke(
            "cubism.editor-model.undo.add",
            edit,
            targetUndo,
            Boolean.TRUE
        );
        if (!(accepted instanceof Boolean value) || !value) {
            throw new IllegalStateException("Cubism rejected the Combined parameter Undo entry.");
        }
    }

    private void refreshUi(final Object app) {
        final Object mainFrame = resolver.invoke(
            "cubism.editor-model.app-controller.main-frame", app
        );
        final Object palette = resolver.invoke(
            "cubism.editor-model.main-frame.parameter-palette", mainFrame
        );
        final Object paletteView = resolver.invoke(
            "cubism.editor-model.parameter-palette.view", palette
        );
        final Object operation = resolver.invoke(
            "cubism.editor-model.parameter-palette-view.operation", paletteView
        );
        resolver.invoke(
            "cubism.editor-model.parameter-operation.refresh",
            operation,
            Boolean.TRUE
        );
    }

    private void requireAuthorization() {
        if (!resolver.authorizesFeature(
            EditorParameterCombinedWriteSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterCombinedWriteSelectorContract.CAPABILITY_ID,
            EditorParameterCombinedWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Parameter Combined editing is unavailable without exact verified host evidence."
            );
        }
    }

    private Object parentGroup(final Object source) {
        final Object group = resolver.invoke(
            "cubism.editor-model.parameter-source.parent-group", source
        );
        if (!resolver.isInstance("cubism.editor-model.parameter-group.class", group)) {
            throw unavailable("Editor parameter group is unavailable.");
        }
        return group;
    }

    private List<Object> groupChildren(final Object group) {
        final Object raw = resolver.invoke("cubism.editor-model.parameter-group.children", group);
        if (!(raw instanceof List<?> children)) {
            throw unavailable("Editor parameter group children are unavailable.");
        }
        return List.copyOf(children);
    }

    private PairBinding pairBinding(final Object source) {
        final List<Object> children = groupChildren(parentGroup(source));
        final int index = identityIndex(children, source);
        if (index < 0) {
            throw unavailable("Editor parameter is absent from its parent group.");
        }
        if (combinedFlag(source)) {
            if (index + 1 >= children.size()
                || !resolver.isInstance(
                    "cubism.editor-model.parameter-source.class",
                    children.get(index + 1)
                )) {
                throw unavailable("Editor Combined pair is structurally invalid.");
            }
            return new PairBinding(source, children.get(index + 1));
        }
        if (index > 0) {
            final Object previous = children.get(index - 1);
            if (resolver.isInstance("cubism.editor-model.parameter-source.class", previous)
                && combinedFlag(previous)) {
                return new PairBinding(previous, source);
            }
        }
        return null;
    }

    private ParameterId sourceId(final Object source) {
        final Object rawId = resolver.invoke("cubism.editor-model.parameter-source.id", source);
        return new ParameterId(text(resolver.invoke("cubism.editor-model.id.value", rawId)));
    }

    private boolean combinedFlag(final Object source) {
        final Object value = resolver.invoke("cubism.editor-model.parameter-source.combined", source);
        if (!(value instanceof Boolean combined)) {
            throw unavailable("Editor parameter combined flag is invalid.");
        }
        return combined;
    }

    private void setCombined(final Object source, final boolean combined) {
        resolver.invoke(
            "cubism.editor-model.parameter-source.set-combined",
            source,
            Boolean.valueOf(combined)
        );
    }

    private static int identityIndex(final List<Object> values, final Object expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) == expected) {
                return index;
            }
        }
        return -1;
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw unavailable("Editor parameter identity is invalid.");
        }
        return text;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    @FunctionalInterface
    interface ModelGuard {
        void requireCurrent(String expectedIdentity, Object expectedModel);
    }

    @FunctionalInterface
    interface ParameterSourceLookup {
        Object source(Object model, ParameterId id);
    }

    private record PairBinding(Object first, Object second) {
        private boolean contains(final Object left, final Object right) {
            return (first == left && second == right) || (first == right && second == left);
        }
    }
}
