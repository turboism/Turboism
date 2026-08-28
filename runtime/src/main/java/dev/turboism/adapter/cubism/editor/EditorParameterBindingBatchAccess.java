package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorParameterBindingBatchWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Serial Editor transaction for atomic multi-target binding inversion and GUID transfer. */
final class EditorParameterBindingBatchAccess implements ParameterBindingBatchOperations {

    private static final float EPSILON = 0.000001F;

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
        requireAuthorized(
            EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
            EditorParameterBindingBatchWriteSelectorContract.INVERT_MUTATION_REQUIRED_ALIASES
        );
        final Map<ParameterBindingTarget, List<Object>> guids = new LinkedHashMap<>();
        for (ParameterBindingTarget target : selected) {
            final Object source = targetLookup.find(identity, modelSource, model, target);
            final Object grid = resolver.invoke("cubism.editor-model.parameter-controllable.keyform-grid", source);
            guids.put(target, boundParameterGuids(grid));
        }
        if (guids.values().stream().allMatch(List::isEmpty)) return;
        final Map<ParameterBindingTarget, List<String>> beforeKeyformOrders =
            tracedInvertKeyformOrders(selected);
        mutate("Invert Parameter Bindings", selected, (grid, target) -> {
            for (Object guid : guids.get(target)) {
                resolver.invoke("cubism.editor-model.keyform-grid.reverse-parameter", grid, guid);
            }
        });
        final Map<ParameterBindingTarget, List<String>> afterKeyformOrders =
            tracedInvertKeyformOrders(selected);
        traceInvertOrders(beforeKeyformOrders, afterKeyformOrders);
    }

    Map<ParameterBindingTarget, List<String>> keyformOrders(
        final List<ParameterBindingTarget> targets
    ) {
        requireAuthorized(
            EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
            EditorParameterBindingBatchWriteSelectorContract
                .INVERT_VALIDATION_OBSERVATION_ALIASES
        );
        return keyformOrdersUnchecked(targets(targets));
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
        final Map<ParameterBindingTarget, List<String>> beforeBindings =
            tracedTransferBindings(selected);
        mutate("Transfer Parameter Bindings", selected, (grid, ignored) -> {
            resolver.invoke("cubism.editor-model.keyform-grid.change-parameter", grid, sourceGuid, targetGuid);
            if (plan.invertAfterTransfer()) {
                resolver.invoke("cubism.editor-model.keyform-grid.reverse-parameter", grid, targetGuid);
            }
        });
        traceTransferBindings(beforeBindings, tracedTransferBindings(selected));
    }

    Map<ParameterBindingTarget, List<String>> bindingParameterIds(
        final List<ParameterBindingTarget> targets
    ) {
        requireAuthorized(EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID);
        return bindingParameterIdsUnchecked(targets(targets));
    }

    Map<ParameterBindingTarget, List<String>> keyformOrdersForTransfer(
        final List<ParameterBindingTarget> targets
    ) {
        requireAuthorized(
            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID,
            EditorParameterBindingBatchWriteSelectorContract
                .CLAMPED_TRANSFER_VALIDATION_OBSERVATION_ALIASES
        );
        return keyformOrdersUnchecked(targets(targets));
    }

    Map<ParameterBindingTarget, List<String>> morphOrdersForTransfer(
        final List<ParameterBindingTarget> targets
    ) {
        requireAuthorized(
            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID,
            EditorParameterBindingBatchWriteSelectorContract
                .MORPH_TRANSFER_VALIDATION_OBSERVATION_ALIASES
        );
        return morphOrdersUnchecked(targets(targets));
    }

    @Override
    public void transferClamped(final ParameterBindingTransferPlan plan) {
        Objects.requireNonNull(plan, "plan");
        final List<ParameterBindingTarget> selected = targets(plan.targets());
        requireAuthorized(EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID, EditorParameterBindingBatchWriteSelectorContract.REQUIRED_ALIASES);
        currentGuard.requireCurrent(identity, model);
        final Object sourceGuid = parameterGuid(plan.sourceParameterId());
        final Object targetGuid = parameterGuid(plan.targetParameterId());
        requireOrdinaryParameter(plan.targetParameterId());
        final ParameterRange range = parameterRange(plan.targetParameterId());
        final ParameterRange sourceRange = parameterRange(plan.sourceParameterId());
        final float sourceSpan = sourceRange.maximum() - sourceRange.minimum();
        // Native reference semantics: a degenerate source range (|max-min| < 1e-9) skips remapping.
        final boolean remaps = Math.abs(sourceSpan) >= 0.000000001F;
        final Map<ParameterBindingTarget, ClampedTransfer> transfers = new LinkedHashMap<>();

        // Everything above this point is preflight. No edit is opened until every target passes.
        for (ParameterBindingTarget target : selected) {
            final Object source = targetLookup.find(identity, modelSource, model, target);
            final Object grid = resolver.invoke("cubism.editor-model.parameter-controllable.keyform-grid", source);
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
            final List<Float> before = keyValues(resolver.invoke(
                "cubism.editor-model.keyform-binding.keys", sourceBinding
            ));
            if (before.isEmpty()) {
                throw new IllegalStateException("The source binding has no keyform values.");
            }
            requireDistinct(before);
            final ArrayList<Float> mapped = new ArrayList<>(before.size());
            for (float value : before) {
                final float requested = plan.invertAfterTransfer() ? -value + 0.0F : value;
                final float mappedValue;
                if (remaps) {
                    // Linear remap from the source range into the destination range, then clamp.
                    final float ratio = (requested - sourceRange.minimum()) / sourceSpan;
                    final float targetSpan = range.maximum() - range.minimum();
                    mappedValue = clamp(
                        range.minimum() + ratio * targetSpan,
                        range.minimum(),
                        range.maximum()
                    );
                } else {
                    mappedValue = requested;
                }
                if (!Float.isFinite(mappedValue)) {
                    throw new IllegalStateException("Mapped binding values must be finite.");
                }
                mapped.add(mappedValue);
            }
            requireDistinct(mapped);
            // Cubism expects `before` and `mapped` in source order. It pairs each current
            // key with `mapped[before.indexOf(currentValue)]`, then sorts coordinates and
            // reorders keyform data by the original index.
            transfers.put(target, new ClampedTransfer(before, List.copyOf(mapped)));
        }

        final Map<ParameterBindingTarget, List<String>> beforeBindings =
            tracedTransferBindings(selected);
        final Map<ParameterBindingTarget, List<String>> beforeKeyformOrders =
            tracedClampedTransferKeyformOrders(selected);
        mutate("Transfer Parameter Bindings", selected, (grid, target) -> {
            final ClampedTransfer transfer = Objects.requireNonNull(transfers.get(target), "transfer");
            resolver.invoke("cubism.editor-model.keyform-grid.change-parameter", grid, sourceGuid, targetGuid);
            resolver.invoke(
                "cubism.editor-model.keyform-grid.rearrange-keys",
                grid,
                targetGuid,
                transfer.before(),
                transfer.mapped()
            );
        });
        traceTransferBindings(beforeBindings, tracedTransferBindings(selected));
        traceClampedTransferOrders(
            beforeKeyformOrders,
            tracedClampedTransferKeyformOrders(selected)
        );
    }

    @Override
    public void transferMorphClamped(final ParameterBindingTransferPlan plan) {
        Objects.requireNonNull(plan, "plan");
        final List<ParameterBindingTarget> selected = targets(plan.targets());
        requireAuthorized(
            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_CAPABILITY_ID,
            EditorParameterBindingBatchWriteSelectorContract.MORPH_TRANSFER_REQUIRED_ALIASES
        );
        currentGuard.requireCurrent(identity, model);
        requireMorphParameter(plan.sourceParameterId(), "source");
        requireMorphParameter(plan.targetParameterId(), "destination");
        final Object targetGuid = parameterGuid(plan.targetParameterId());
        if (targetGuid == null) {
            throw new IllegalStateException("The destination parameter GUID is unavailable.");
        }
        final Object parameterSources = parameterSourceSet();
        final Map<ParameterBindingTarget, List<MorphTransfer>> transfers = new LinkedHashMap<>();

        // Read and validate every Morph Target before opening the single Editor edit.
        for (ParameterBindingTarget target : selected) {
            final Object objectSource = targetLookup.find(identity, modelSource, model, target);
            final Object keyformGrid = resolver.invoke(
                "cubism.editor-model.parameter-controllable.keyform-grid", objectSource
            );
            if (resolver.invoke("cubism.editor-model.keyform-grid.find-binding", keyformGrid, targetGuid) != null) {
                throw new IllegalStateException("The target is already bound to the destination parameter.");
            }
            final Object morphSet = resolver.invoke(
                "cubism.editor-model.parameter-controllable.morph-target-set", objectSource
            );
            if (!resolver.isInstance("cubism.editor-model.morph-target-set.class", morphSet)) {
                throw new IllegalStateException("Editor Morph Target set is unavailable.");
            }
            final Object rawTargets = resolver.invoke(
                "cubism.editor-model.morph-target-set.morph-targets", morphSet
            );
            if (!(rawTargets instanceof List<?> morphTargets)) {
                throw new IllegalStateException("Editor Morph Target collection is unavailable.");
            }
            final ArrayList<MorphTransfer> mapped = new ArrayList<>();
            boolean targetBound = false;
            for (Object morphTarget : morphTargets) {
                if (!resolver.isInstance("cubism.editor-model.morph-target.class", morphTarget)) {
                    throw new IllegalStateException("Editor Morph Target collection contains an invalid value.");
                }
                final ParameterId boundParameter = morphParameterId(parameterSources, morphTarget);
                if (plan.targetParameterId().equals(boundParameter)) {
                    targetBound = true;
                }
                if (!plan.sourceParameterId().equals(boundParameter)) {
                    continue;
                }
                final float value = morphKeyValue(morphTarget);
                // Native reference semantics: the whole Morph Target setting moves unchanged
                // (no remap, no clamp); inversion only negates each key value.
                final float mappedValue = plan.invertAfterTransfer() ? -value + 0.0F : value;
                if (!Float.isFinite(mappedValue)) {
                    throw new IllegalStateException("Mapped Morph Target values must be finite.");
                }
                mapped.add(new MorphTransfer(morphTarget, targetGuid, mappedValue));
            }
            if (targetBound) {
                throw new IllegalStateException("The target is already bound to the destination parameter.");
            }
            if (mapped.isEmpty()) {
                throw new IllegalStateException("The target is not bound to the source parameter.");
            }
            requireDistinct(mapped.stream().map(MorphTransfer::mapped).toList());
            transfers.put(target, List.copyOf(mapped));
        }

        final Map<ParameterBindingTarget, List<String>> beforeMorphOrders =
            tracedMorphTransferOrders(selected);
        final int admittedUndoCount = mutateMorph(
            "Transfer Morph Target Bindings", selected, transfers
        );
        traceMorphTransferOrders(
            beforeMorphOrders,
            tracedMorphTransferOrders(selected),
            admittedUndoCount
        );
    }

    private int mutateMorph(
        final String action,
        final List<ParameterBindingTarget> targets,
        final Map<ParameterBindingTarget, List<MorphTransfer>> transfers
    ) {
        currentGuard.requireCurrent(identity, model);
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        final Object editMode = resolver.invoke("cubism.editor-model.modeling-document.edit-mode", document);
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, "Turboism: " + action);
        final boolean traceMorphTransfer = "Transfer Morph Target Bindings".equals(action)
            && Boolean.getBoolean("turboism.validation.editorParameterBindingMorphTrace");
        boolean completed = false;
        int admittedUndoCount = 0;
        try {
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
                    refresh(app, targets);
                    if (traceMorphTransfer) {
                        traceMorphTransferSnapshot(morphOrdersUnchecked(targets));
                    }
                    return null;
                }
            );
            final Object listenerAccepted = resolver.invoke(
                "cubism.editor-model.undo.add-listener", edit, listener
            );
            if (!(listenerAccepted instanceof Boolean listenerValue) || !listenerValue) {
                throw new IllegalStateException(
                    "Cubism rejected the Morph Target group Undo listener."
                );
            }
            final Object utils = resolver.readStaticField("cubism.editor-model.morph-target-utils.instance");
            for (ParameterBindingTarget target : targets) {
                for (MorphTransfer transfer : Objects.requireNonNull(transfers.get(target), "transfer")) {
                    final Object undo = resolver.invoke(
                        "cubism.editor-model.morph-target.change-parameter",
                        utils,
                        transfer.morphTarget(),
                        transfer.targetGuid(),
                        Float.valueOf(transfer.mapped())
                    );
                    final Object accepted = resolver.invoke("cubism.editor-model.undo.add", edit, undo, Boolean.TRUE);
                    if (!(accepted instanceof Boolean value) || !value) {
                        throw new IllegalStateException("Cubism rejected the Morph Target Undo entry.");
                    }
                    admittedUndoCount++;
                }
            }
            resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
            refresh(app, targets);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null
            );
        }
        currentGuard.requireCurrent(identity, model);
        return admittedUndoCount;
    }

    private Object parameterSourceSet() {
        final Object set = resolver.invoke(
            "cubism.editor-model.model-source.parameter-source-set", modelSource
        );
        if (!resolver.isInstance("cubism.editor-model.parameter-source-set.class", set)) {
            throw new IllegalStateException("Editor parameter source collection is unavailable.");
        }
        return set;
    }

    private ParameterId morphParameterId(final Object parameterSources, final Object morphTarget) {
        final Object parameterGuid = resolver.invoke(
            "cubism.editor-model.morph-target.parameter-guid", morphTarget
        );
        if (parameterGuid == null) {
            throw new IllegalStateException("Editor Morph Target parameter is unavailable.");
        }
        final Object parameterSource = resolver.invoke(
            "cubism.editor-model.parameter-source-set.get", parameterSources, parameterGuid
        );
        if (parameterSource == null) {
            throw new IllegalStateException("Editor Morph Target parameter is outside the active model.");
        }
        final Object hostId = resolver.invoke("cubism.editor-model.parameter-source.id", parameterSource);
        final Object value = resolver.invoke("cubism.editor-model.id.value", hostId);
        if (!(value instanceof String id) || id.isBlank()) {
            throw new IllegalStateException("Editor Morph Target parameter identity is unavailable.");
        }
        return new ParameterId(id);
    }

    private float morphKeyValue(final Object morphTarget) {
        final Object value = resolver.invoke("cubism.editor-model.morph-target.key-value", morphTarget);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Editor Morph Target key value is not numeric.");
        }
        final float keyValue = number.floatValue();
        if (!Float.isFinite(keyValue)) {
            throw new IllegalStateException("Editor Morph Target key value must be finite.");
        }
        return keyValue;
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
        final boolean traceInvert = "Invert Parameter Bindings".equals(action)
            && Boolean.getBoolean("turboism.editorObjectValidation.trace");
        final boolean traceTransfer = "Transfer Parameter Bindings".equals(action)
            && Boolean.getBoolean("turboism.editorObjectValidation.trace");
        final boolean traceClampedTransfer = traceTransfer
            && Boolean.getBoolean("turboism.validation.editorParameterBindingClampedTrace");
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
                        if (traceInvert) {
                            traceInvertSnapshot(keyformOrdersUnchecked(targets));
                        }
                        if (traceTransfer) {
                            traceTransferSnapshot(bindingParameterIdsUnchecked(targets));
                        }
                        if (traceClampedTransfer) {
                            traceClampedTransferSnapshot(keyformOrdersUnchecked(targets));
                        }
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

    private Map<ParameterBindingTarget, List<String>> tracedInvertKeyformOrders(
        final List<ParameterBindingTarget> targets
    ) {
        if (!Boolean.getBoolean("turboism.editorObjectValidation.trace")) {
            return Map.of();
        }
        requireAuthorized(
            EditorParameterBindingBatchWriteSelectorContract.INVERT_CAPABILITY_ID,
            EditorParameterBindingBatchWriteSelectorContract
                .INVERT_VALIDATION_OBSERVATION_ALIASES
        );
        return keyformOrdersUnchecked(targets);
    }

    private Map<ParameterBindingTarget, List<String>> tracedTransferBindings(
        final List<ParameterBindingTarget> targets
    ) {
        if (!Boolean.getBoolean("turboism.editorObjectValidation.trace")) {
            return Map.of();
        }
        return bindingParameterIdsUnchecked(targets);
    }

    private Map<ParameterBindingTarget, List<String>> tracedClampedTransferKeyformOrders(
        final List<ParameterBindingTarget> targets
    ) {
        if (!Boolean.getBoolean("turboism.validation.editorParameterBindingClampedTrace")) {
            return Map.of();
        }
        return keyformOrdersUnchecked(targets);
    }

    private Map<ParameterBindingTarget, List<String>> tracedMorphTransferOrders(
        final List<ParameterBindingTarget> targets
    ) {
        if (!Boolean.getBoolean("turboism.validation.editorParameterBindingMorphTrace")) {
            return Map.of();
        }
        return morphOrdersUnchecked(targets);
    }

    private Map<ParameterBindingTarget, List<String>> morphOrdersUnchecked(
        final List<ParameterBindingTarget> targets
    ) {
        final Object parameterSources = parameterSourceSet();
        final LinkedHashMap<ParameterBindingTarget, List<String>> orders = new LinkedHashMap<>();
        for (ParameterBindingTarget target : targets) {
            final Object objectSource = targetLookup.find(identity, modelSource, model, target);
            final Object morphSet = resolver.invoke(
                "cubism.editor-model.parameter-controllable.morph-target-set", objectSource
            );
            if (!resolver.isInstance("cubism.editor-model.morph-target-set.class", morphSet)) {
                throw new IllegalStateException("Editor Morph Target set is unavailable.");
            }
            final Object rawTargets = resolver.invoke(
                "cubism.editor-model.morph-target-set.morph-targets", morphSet
            );
            if (!(rawTargets instanceof List<?> morphTargets)) {
                throw new IllegalStateException("Editor Morph Target collection is unavailable.");
            }
            final ArrayList<String> order = new ArrayList<>(morphTargets.size());
            for (Object morphTarget : morphTargets) {
                if (!resolver.isInstance("cubism.editor-model.morph-target.class", morphTarget)) {
                    throw new IllegalStateException(
                        "Editor Morph Target collection contains an invalid value."
                    );
                }
                final Object keyformGuid = resolver.invoke(
                    "cubism.editor-model.morph-target.keyform-guid", morphTarget
                );
                final Object keyformText = resolver.invoke(
                    "cubism.editor-model.form-guid.value", keyformGuid
                );
                if (!(keyformText instanceof String keyform) || keyform.isBlank()) {
                    throw new IllegalStateException("Editor Morph Target keyform GUID is unavailable.");
                }
                final ParameterId parameterId = morphParameterId(parameterSources, morphTarget);
                order.add(
                    keyform + "|" + parameterId.value() + "|"
                        + Integer.toUnsignedString(Float.floatToRawIntBits(morphKeyValue(morphTarget)))
                );
            }
            orders.put(target, List.copyOf(order));
        }
        return Map.copyOf(orders);
    }

    private Map<ParameterBindingTarget, List<String>> bindingParameterIdsUnchecked(
        final List<ParameterBindingTarget> targets
    ) {
        final LinkedHashMap<ParameterBindingTarget, List<String>> bindings = new LinkedHashMap<>();
        for (ParameterBindingTarget target : targets) {
            final Object source = targetLookup.find(identity, modelSource, model, target);
            final Object grid = resolver.invoke(
                "cubism.editor-model.parameter-controllable.keyform-grid", source
            );
            final List<String> parameterIds = boundParameterGuids(grid).stream()
                .map(this::guidValue)
                .toList();
            bindings.put(target, parameterIds);
        }
        return Map.copyOf(bindings);
    }

    private String guidValue(final Object guid) {
        final Object text = resolver.invoke("cubism.editor-model.guid.value", guid);
        if (!(text instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("Editor parameter GUID is unavailable.");
        }
        return value;
    }

    private Map<ParameterBindingTarget, List<String>> keyformOrdersUnchecked(
        final List<ParameterBindingTarget> targets
    ) {
        final LinkedHashMap<ParameterBindingTarget, List<String>> orders = new LinkedHashMap<>();
        for (ParameterBindingTarget target : targets) {
            final Object source = targetLookup.find(identity, modelSource, model, target);
            final Object grid = resolver.invoke(
                "cubism.editor-model.parameter-controllable.keyform-grid", source
            );
            orders.put(target, keyformOrder(grid));
        }
        return Map.copyOf(orders);
    }

    private List<String> keyformOrder(final Object grid) {
        final Object value = resolver.invoke(
            "cubism.editor-model.keyform-grid.keyforms-on-grid", grid
        );
        if (!(value instanceof List<?> keyforms)) {
            throw new IllegalStateException("Editor native keyform order is not a list.");
        }
        final ArrayList<String> order = new ArrayList<>(keyforms.size());
        for (Object keyform : keyforms) {
            final Object guid = resolver.invoke(
                "cubism.editor-model.keyform-on-grid.form-guid", keyform
            );
            final Object text = resolver.invoke("cubism.editor-model.form-guid.value", guid);
            if (!(text instanceof String valueAsString) || valueAsString.isBlank()) {
                throw new IllegalStateException("Editor native keyform GUID is unavailable.");
            }
            order.add(valueAsString);
        }
        return List.copyOf(order);
    }

    private static void traceInvertOrders(
        final Map<ParameterBindingTarget, List<String>> before,
        final Map<ParameterBindingTarget, List<String>> after
    ) {
        writeInvertTrace(
            "before=" + before + System.lineSeparator()
                + "after=" + after + System.lineSeparator(),
            false
        );
    }

    private static void traceInvertSnapshot(
        final Map<ParameterBindingTarget, List<String>> snapshot
    ) {
        writeInvertTrace(
            "snapshot=" + snapshot + System.lineSeparator(),
            true
        );
    }

    private static void writeInvertTrace(final String evidence, final boolean append) {
        writeValidationTrace("parameter-binding-invert-native-order.txt", evidence, append);
    }

    private static void traceTransferBindings(
        final Map<ParameterBindingTarget, List<String>> before,
        final Map<ParameterBindingTarget, List<String>> after
    ) {
        if (before.isEmpty() && after.isEmpty()) return;
        writeTransferTrace(
            "before=" + before + System.lineSeparator()
                + "after=" + after + System.lineSeparator(),
            false
        );
    }

    private static void traceTransferSnapshot(
        final Map<ParameterBindingTarget, List<String>> snapshot
    ) {
        writeTransferTrace(
            "snapshot=" + snapshot + System.lineSeparator(),
            true
        );
    }

    private static void writeTransferTrace(final String evidence, final boolean append) {
        writeValidationTrace("parameter-binding-transfer-native-bindings.txt", evidence, append);
    }

    private static void traceClampedTransferOrders(
        final Map<ParameterBindingTarget, List<String>> before,
        final Map<ParameterBindingTarget, List<String>> after
    ) {
        if (before.isEmpty() && after.isEmpty()) return;
        writeClampedTransferTrace(
            "before=" + before + System.lineSeparator()
                + "after=" + after + System.lineSeparator(),
            false
        );
    }

    private static void traceClampedTransferSnapshot(
        final Map<ParameterBindingTarget, List<String>> snapshot
    ) {
        writeClampedTransferTrace(
            "snapshot=" + snapshot + System.lineSeparator(),
            true
        );
    }

    private static void writeClampedTransferTrace(
        final String evidence,
        final boolean append
    ) {
        writeValidationTrace(
            "parameter-binding-clamped-transfer-native-order.txt",
            evidence,
            append
        );
    }

    private static void traceMorphTransferOrders(
        final Map<ParameterBindingTarget, List<String>> before,
        final Map<ParameterBindingTarget, List<String>> after,
        final int admittedUndoCount
    ) {
        if (before.isEmpty() && after.isEmpty()) return;
        writeMorphTransferTrace(
            "before=" + before + System.lineSeparator()
                + "after=" + after + System.lineSeparator()
                + "admittedUndoCount=" + admittedUndoCount + System.lineSeparator(),
            false
        );
    }

    private static void traceMorphTransferSnapshot(
        final Map<ParameterBindingTarget, List<String>> snapshot
    ) {
        writeMorphTransferTrace(
            "snapshot=" + snapshot + System.lineSeparator(),
            true
        );
    }

    private static void writeMorphTransferTrace(final String evidence, final boolean append) {
        writeValidationTrace("parameter-binding-morph-transfer-native-order.txt", evidence, append);
    }

    private static void writeValidationTrace(
        final String fileName,
        final String evidence,
        final boolean append
    ) {
        EditorObjectValidationTrace.writeArtifact(fileName, evidence, append);
    }

    private Object parameterGuid(final ParameterId parameterId) {
        return resolver.invoke(
            "cubism.editor-model.parameter-source.guid",
            parameterSourceLookup.find(model, parameterId)
        );
    }

    private void requireOrdinaryParameter(final ParameterId parameterId) {
        final Object source = parameterSourceLookup.find(model, parameterId);
        if (source == null) throw new IllegalStateException("The parameter source is unavailable.");
        final Object morph = resolver.invoke(
            "cubism.editor-model.parameter-source.morph-target", source
        );
        if (!(morph instanceof Boolean isMorphTarget)) {
            throw new IllegalStateException("The parameter type is unavailable.");
        }
        if (isMorphTarget) {
            throw new IllegalStateException(
                "Clamped ordinary binding transfer cannot target a Morph Target parameter."
            );
        }
    }

    private void requireMorphParameter(final ParameterId parameterId, final String role) {
        final Object source = parameterSourceLookup.find(model, parameterId);
        if (source == null) {
            throw new IllegalStateException("The Morph Target " + role + " parameter is unavailable.");
        }
        final Object morph = resolver.invoke(
            "cubism.editor-model.parameter-source.morph-target", source
        );
        if (!(morph instanceof Boolean isMorphTarget)) {
            throw new IllegalStateException("The Morph Target " + role + " type is unavailable.");
        }
        if (!isMorphTarget) {
            throw new IllegalStateException(
                "Morph Target binding transfer requires a Morph Target " + role + " parameter."
            );
        }
    }

    private ParameterRange parameterRange(final ParameterId parameterId) {
        final Object source = parameterSourceLookup.find(model, parameterId);
        if (source == null) throw new IllegalStateException("The parameter source is unavailable.");
        final float minimum = number(resolver.invoke(
            "cubism.editor-model.parameter-source.minimum", source
        ));
        final float maximum = number(resolver.invoke(
            "cubism.editor-model.parameter-source.maximum", source
        ));
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum > maximum) {
            throw new IllegalStateException("The parameter range is invalid.");
        }
        return new ParameterRange(minimum, maximum);
    }

    private static List<Float> keyValues(final Object value) {
        if (!(value instanceof List<?> keys)) {
            throw new IllegalStateException("Editor keyform values are not a list.");
        }
        final ArrayList<Float> result = new ArrayList<>(keys.size());
        for (Object key : keys) {
            if (!(key instanceof Number number)) {
                throw new IllegalStateException("Editor keyform values are not numeric.");
            }
            final float valueAsFloat = number.floatValue();
            if (!Float.isFinite(valueAsFloat)) {
                throw new IllegalStateException("Editor keyform values must be finite.");
            }
            result.add(valueAsFloat == 0.0F ? 0.0F : valueAsFloat);
        }
        return List.copyOf(result);
    }

    private static void requireDistinct(final List<Float> values) {
        for (int index = 0; index < values.size(); index++) {
            for (int other = 0; other < index; other++) {
                if (near(values.get(index), values.get(other))) {
                    throw new IllegalStateException("Mapped binding points would collide.");
                }
            }
        }
    }

    private static float clamp(final float value, final float minimum, final float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float number(final Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Editor parameter range is not numeric.");
        }
        return number.floatValue();
    }

    private static boolean near(final float left, final float right) {
        return Math.abs(left - right) <= EPSILON;
    }

    private static List<ParameterBindingTarget> targets(final List<ParameterBindingTarget> targets) {
        final List<ParameterBindingTarget> copy = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (copy.isEmpty()) throw new IllegalArgumentException("targets must not be empty");
        if (copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException("targets must not contain duplicates");
        }
        return copy;
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
        requireAuthorized(
            capabilityId,
            EditorParameterBindingBatchWriteSelectorContract.TRANSFER_REQUIRED_ALIASES
        );
    }

    private void requireAuthorized(final String capabilityId, final Set<String> aliases) {
        if (!resolver.authorizesFeature(
            EditorParameterBindingBatchWriteSelectorContract.ADAPTER_SLICE_ID,
            capabilityId,
            aliases
        )) {
            throw new UnsupportedOperationException(
                "Editor parameter-binding batch writes require exact verified host evidence."
            );
        }
    }

    private record ParameterRange(float minimum, float maximum) { }

    private record ClampedTransfer(List<Float> before, List<Float> mapped) { }

    private record MorphTransfer(Object morphTarget, Object targetGuid, float mapped) { }

    @FunctionalInterface
    private interface Mutation {
        void apply(Object grid, ParameterBindingTarget target);
    }
}
