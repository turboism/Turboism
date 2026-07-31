package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorParameterBindingBatchWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;

import java.util.List;
import java.util.Objects;

/** Serial Editor transaction for atomic multi-target binding inversion and transfer. */
final class EditorParameterBindingBatchAccess implements ParameterBindingBatchOperations {

    @FunctionalInterface
    interface CurrentGuard {
        void requireCurrent(String identity, Object model);
    }

    @FunctionalInterface
    interface TargetLookup {
        Object find(String identity, Object source, Object model, ParameterBindingTarget target);
    }

    @FunctionalInterface
    interface ParameterSourceLookup {
        Object find(Object model, ParameterId parameterId);
    }

    private final VerifiedMemberResolver resolver;
    private final String identity;
    private final Object modelSource;
    private final Object model;
    private final CurrentGuard currentGuard;
    private final TargetLookup targetLookup;
    private final ParameterSourceLookup parameterSourceLookup;

    EditorParameterBindingBatchAccess(
        final VerifiedMemberResolver resolver,
        final String identity,
        final Object modelSource,
        final Object model,
        final CurrentGuard currentGuard,
        final TargetLookup targetLookup,
        final ParameterSourceLookup parameterSourceLookup
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.modelSource = Objects.requireNonNull(modelSource, "modelSource");
        this.model = Objects.requireNonNull(model, "model");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.targetLookup = Objects.requireNonNull(targetLookup, "targetLookup");
        this.parameterSourceLookup = Objects.requireNonNull(parameterSourceLookup, "parameterSourceLookup");
    }

    @Override
    public void invert(final List<ParameterBindingTarget> targets) {
        final List<ParameterBindingTarget> selected = targets(targets);
        requireAuthorized(EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID);
        final java.util.Map<ParameterBindingTarget, List<Object>> guids = new java.util.LinkedHashMap<>();
        for (ParameterBindingTarget target : selected) {
            final Object source = targetLookup.find(identity, modelSource, model, target);
            final Object grid = resolver.invoke("cubism.editor-model.parameter-controllable.keyform-grid", source);
            guids.put(target, boundParameterGuids(grid));
        }
        if (guids.values().stream().allMatch(List::isEmpty)) return;
        mutate("Invert Parameter Bindings", selected, (grid, target) -> {
            for (Object guid : guids.get(target)) {
                resolver.invoke("cubism.editor-model.keyform-grid.reverse-parameter", grid, guid);
            }
        });
    }

    @Override
    public void transfer(final ParameterBindingTransferPlan plan) {
        Objects.requireNonNull(plan, "plan");
        final List<ParameterBindingTarget> selected = targets(plan.targets());
        requireAuthorized(EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID);
        final Object sourceGuid = parameterGuid(plan.sourceParameterId());
        final Object targetGuid = parameterGuid(plan.targetParameterId());
        for (ParameterBindingTarget target : selected) {
            final Object source = targetLookup.find(identity, modelSource, model, target);
            final Object grid = resolver.invoke("cubism.editor-model.parameter-controllable.keyform-grid", source);
            requireTransferAllowed(grid, sourceGuid, targetGuid);
        }
        mutate("Transfer Parameter Bindings", selected, (grid, ignored) -> {
            resolver.invoke("cubism.editor-model.keyform-grid.change-parameter", grid, sourceGuid, targetGuid);
            if (plan.invertAfterTransfer()) {
                resolver.invoke("cubism.editor-model.keyform-grid.reverse-parameter", grid, targetGuid);
            }
        });
    }

    private void requireTransferAllowed(final Object grid, final Object sourceGuid, final Object targetGuid) {
        final Object sourceBinding = resolver.invoke(
            "cubism.editor-model.keyform-grid.find-binding", grid, sourceGuid
        );
        if (sourceBinding == null) {
            throw new IllegalStateException("The target is not bound to the source parameter.");
        }
        final Object targetBinding = resolver.invoke(
            "cubism.editor-model.keyform-grid.find-binding", grid, targetGuid
        );
        if (targetBinding != null) {
            throw new IllegalStateException("The target is already bound to the destination parameter.");
        }
    }

    private void mutate(
        final String action,
        final List<ParameterBindingTarget> targets,
        final Mutation mutation
    ) {
        currentGuard.requireCurrent(identity, model);
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        final Object editMode = resolver.invoke("cubism.editor-model.modeling-document.edit-mode", document);
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, "Turboism: " + action);
        boolean completed = false;
        try {
            for (ParameterBindingTarget target : targets) {
                final Object source = targetLookup.find(identity, modelSource, model, target);
                final Object grid = resolver.invoke(
                    "cubism.editor-model.parameter-controllable.keyform-grid", source
                );
                final Object handler = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.handler", source
                );
                final Object undo = resolver.invoke(
                    "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                    handler,
                    "Turboism: " + action
                );
                final Object accepted = resolver.invoke(
                    "cubism.editor-model.undo.add", edit, undo, Boolean.TRUE
                );
                if (!(accepted instanceof Boolean value) || !value) {
                    throw new IllegalStateException("Cubism rejected the parameter-binding Undo entry.");
                }
                final Object listener = resolver.createFunctionalProxy(
                    "cubism.editor-model.undo-listener.class",
                    ignored -> {
                        refresh(app, targets);
                        return null;
                    }
                );
                resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
                mutation.apply(grid, target);
            }
            refresh(app, targets);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null
            );
        }
        currentGuard.requireCurrent(identity, model);
    }

    private List<Object> boundParameterGuids(final Object grid) {
        final Object value = resolver.invoke("cubism.editor-model.keyform-grid.bindings", grid);
        if (!(value instanceof List<?> bindings)) {
            throw new IllegalStateException("Editor parameter bindings are not a list.");
        }
        return bindings.stream().map(binding -> resolver.invoke(
            "cubism.editor-model.keyform-binding.parameter-guid", binding
        )).toList();
    }

    private Object parameterGuid(final ParameterId parameterId) {
        return resolver.invoke(
            "cubism.editor-model.parameter-source.guid",
            parameterSourceLookup.find(model, parameterId)
        );
    }

    private void refresh(final Object app, final List<ParameterBindingTarget> targets) {
        resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
        final Object pack = resolver.invoke("cubism.editor-model.app-controller.complete-pack", app);
        resolver.invoke("cubism.editor-model.complete-pack.update-parameter", pack, Boolean.TRUE);
        if (targets.stream().anyMatch(target -> target.type() == ParameterBindingTargetType.ART_MESH)) {
            resolver.invoke("cubism.editor-model.complete-pack.update-part-palette", pack, Boolean.TRUE);
        }
        if (targets.stream().anyMatch(target -> target.type() != ParameterBindingTargetType.ART_MESH)) {
            resolver.invoke("cubism.editor-model.complete-pack.update-deformer-palette", pack, Boolean.TRUE);
        }
        resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", pack, Boolean.TRUE);
    }

    private void requireAuthorized(final String capabilityId) {
        if (!resolver.authorizesFeature(
            EditorParameterBindingBatchWriteSelectorContract.ADAPTER_SLICE_ID,
            capabilityId,
            EditorParameterBindingBatchWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor parameter-binding batch writes require exact verified host evidence."
            );
        }
    }

    private static List<ParameterBindingTarget> targets(final List<ParameterBindingTarget> targets) {
        final List<ParameterBindingTarget> copy = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (copy.isEmpty()) throw new IllegalArgumentException("targets must not be empty");
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException("targets must not contain duplicates");
        }
        return copy;
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Object grid, ParameterBindingTarget target);
    }
}
