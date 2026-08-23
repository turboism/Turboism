package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorMorphTargetSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.MorphTarget;
import dev.turboism.sdk.cubism.model.MorphTargets;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Exact, generation-bound Editor projection for keyform Morph Target reads and binding writes. */
final class EditorMorphTargetAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorMorphTargetAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    MorphTargets morphTargets(final String identity, final Object source, final Object model, final Object objectSource) {
        requireReadAuthorization();
        modelGuard.requireCurrent(identity, model);
        if (objectSource == null) {
            throw new IllegalStateException("Editor Morph Target owner is unavailable.");
        }
        return new EditorMorphTargets(identity, source, model, objectSource);
    }

    private boolean readAuthorized() {
        return resolver.authorizesFeature(
            EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
            EditorMorphTargetSelectorContract.READ_CAPABILITY_ID,
            EditorMorphTargetSelectorContract.READ_REQUIRED_ALIASES
        );
    }

    private boolean writeAuthorized() {
        return resolver.authorizesFeature(
            EditorMorphTargetSelectorContract.ADAPTER_SLICE_ID,
            EditorMorphTargetSelectorContract.WRITE_CAPABILITY_ID,
            EditorMorphTargetSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }

    private void requireReadAuthorization() {
        if (!readAuthorized()) {
            throw new UnsupportedOperationException(
                "Morph Target reading is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireWriteAuthorization() {
        if (!writeAuthorized()) {
            throw new UnsupportedOperationException(
                "Morph Target writing is unavailable without exact verified host evidence."
            );
        }
    }

    private Object currentSet(final Object objectSource) {
        final Object set = resolver.invoke(
            "cubism.editor-model.parameter-controllable.morph-target-set", objectSource);
        if (!resolver.isInstance("cubism.editor-model.morph-target-set.class", set)) {
            throw new IllegalStateException("Editor Morph Target set is unavailable.");
        }
        return set;
    }

    private Object parameterSourceSet(final Object source) {
        final Object set = resolver.invoke(
            "cubism.editor-model.model-source.parameter-source-set", source);
        if (!resolver.isInstance("cubism.editor-model.parameter-source-set.class", set)) {
            throw new IllegalStateException("Editor parameter source collection is unavailable.");
        }
        return set;
    }

    private ParameterId parameterId(final Object source, final Object target) {
        final Object parameterGuid = resolver.invoke(
            "cubism.editor-model.morph-target.parameter-guid", target);
        if (parameterGuid == null) {
            throw new IllegalStateException("Editor Morph Target parameter is unavailable.");
        }
        final Object parameterSource = resolver.invoke(
            "cubism.editor-model.parameter-source-set.get", parameterSourceSet(source), parameterGuid);
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

    private Object parameterGuid(final Object source, final ParameterId id) {
        final Object hostId = resolver.construct(
            "cubism.editor-model.parameter-id.create", id.value());
        final Object parameterSource = resolver.invoke(
            "cubism.editor-model.parameter-source-set.get-by-id", parameterSourceSet(source), hostId);
        if (parameterSource == null) {
            throw new NoSuchElementException("Cubism parameter is absent: " + id.value());
        }
        return resolver.invoke("cubism.editor-model.parameter-source.guid", parameterSource);
    }

    private float keyValue(final Object target) {
        final Object value = resolver.invoke("cubism.editor-model.morph-target.key-value", target);
        if (!(value instanceof Float keyValue) || !Float.isFinite(keyValue)) {
            throw new IllegalStateException("Editor Morph Target key value is unavailable.");
        }
        return keyValue;
    }

    private Optional<String> keyformGuid(final Object target) {
        final Object value = resolver.invoke("cubism.editor-model.morph-target.keyform-guid", target);
        if (value == null) return Optional.empty();
        final Object guidValue = resolver.invoke("cubism.editor-model.form-guid.value", value);
        if (!(guidValue instanceof String guid) || guid.isBlank()) {
            throw new IllegalStateException("Editor Morph Target keyform GUID is invalid.");
        }
        return Optional.of(guid);
    }

    private List<Object> targets(final Object set) {
        final Object raw = resolver.invoke("cubism.editor-model.morph-target-set.morph-targets", set);
        if (!(raw instanceof List<?> targets)) {
            throw new IllegalStateException("Editor Morph Target collection is unavailable.");
        }
        for (Object target : targets) {
            if (!resolver.isInstance("cubism.editor-model.morph-target.class", target)) {
                throw new IllegalStateException("Editor Morph Target collection contains an invalid value.");
            }
        }
        return targets.stream().map(value -> (Object) value).toList();
    }

    private void refresh(final Object app) {
        final Object completePack = resolver.invoke("cubism.editor-model.app-controller.complete-pack", app);
        resolver.invoke("cubism.editor-model.complete-pack.update-part-palette", completePack, Boolean.TRUE);
        resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE);
    }

    private final class EditorMorphTargets implements MorphTargets {
        private final String identity;
        private final Object source;
        private final Object model;
        private final Object objectSource;

        private EditorMorphTargets(
            final String identity, final Object source, final Object model, final Object objectSource
        ) {
            this.identity = identity;
            this.source = source;
            this.model = model;
            this.objectSource = objectSource;
        }

        @Override public List<MorphTarget> all() {
            modelGuard.requireCurrent(identity, model);
            final Object set = currentSet(objectSource);
            return targets(set).stream()
                .map(target -> (MorphTarget) new EditorMorphTarget(identity, source, model, objectSource, target))
                .toList();
        }

        @Override public MorphTarget find(final ParameterId id) {
            modelGuard.requireCurrent(identity, model);
            final Object set = currentSet(objectSource);
            final Object target = targets(set).stream()
                .filter(candidate -> parameterId(source, candidate).equals(Objects.requireNonNull(id, "id")))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                    "Cubism Morph Target is absent for parameter: " + id.value()));
            return new EditorMorphTarget(identity, source, model, objectSource, target);
        }
    }

    private final class EditorMorphTarget implements MorphTarget {
        private final String identity;
        private final Object source;
        private final Object model;
        private final Object objectSource;
        private final Object expected;

        private EditorMorphTarget(
            final String identity, final Object source, final Object model,
            final Object objectSource, final Object expected
        ) {
            this.identity = identity;
            this.source = source;
            this.model = model;
            this.objectSource = objectSource;
            this.expected = expected;
        }

        private Object currentSetOrThrow() {
            modelGuard.requireCurrent(identity, model);
            final Object freshSet = currentSet(objectSource);
            for (Object target : targets(freshSet)) {
                if (target == expected) return freshSet;
            }
            throw new IllegalStateException(
                "Cubism Morph Target reference is stale for the active Editor model generation.");
        }

        @Override public ParameterId parameterId() {
            currentSetOrThrow();
            return EditorMorphTargetAccess.this.parameterId(source, expected);
        }

        @Override public float keyValue() {
            currentSetOrThrow();
            return EditorMorphTargetAccess.this.keyValue(expected);
        }

        @Override public Optional<String> keyformGuid() {
            currentSetOrThrow();
            return EditorMorphTargetAccess.this.keyformGuid(expected);
        }

        @Override public int index() {
            final Object set = currentSetOrThrow();
            final List<Object> targets = targets(set);
            for (int index = 0; index < targets.size(); index++) {
                if (targets.get(index) == expected) return index;
            }
            throw new IllegalStateException(
                "Cubism Morph Target reference is stale for the active Editor model generation.");
        }

        @Override public void setParameter(final ParameterId id) {
            setParameterAndKeyValue(id, keyValue());
        }

        @Override public void setKeyValue(final float value) {
            setParameterAndKeyValue(parameterId(), value);
        }

        @Override public void setParameterAndKeyValue(final ParameterId id, final float value) {
            Objects.requireNonNull(id, "id");
            if (!Float.isFinite(value)) throw new IllegalArgumentException("key value must be finite");
            requireWriteAuthorization();
            currentSetOrThrow();
            if (parameterId().equals(id) && Float.compare(keyValue(), value) == 0) return;
            final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
            final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
            final Object editMode = resolver.invoke("cubism.editor-model.modeling-document.edit-mode", document);
            final Object edit = resolver.invoke(
                "cubism.editor-model.edit-mode.begin", editMode, "Turboism: Set Morph Target");
            boolean completed = false;
            try {
                // Host mechanism: MorphTargetParameterUtils.changeMorphTargetParameter_exe wraps the
                // binding change in ChangeMorphTargetParameterUndoRedo whose constructor captures the
                // old parameter/key value and applies the new binding immediately (construct-and-redo);
                // undo() restores oldParameterGuid/oldKeyValue, redo() re-applies the new binding.
                final Object utils = resolver.readStaticField("cubism.editor-model.morph-target-utils.instance");
                final Object undo = resolver.invoke(
                    "cubism.editor-model.morph-target.change-parameter",
                    utils, expected, parameterGuid(source, id), Float.valueOf(value));
                final Object accepted = resolver.invoke("cubism.editor-model.undo.add", edit, undo, Boolean.TRUE);
                if (!(accepted instanceof Boolean acceptedValue) || !acceptedValue) {
                    throw new IllegalStateException("Cubism rejected the Morph Target Undo entry.");
                }
                final Object listener = resolver.createFunctionalProxy(
                    "cubism.editor-model.undo-listener.class",
                    ignored -> {
                        resolver.invoke("cubism.editor-model.model-source.update-instances", source);
                        refresh(app);
                        return null;
                    }
                );
                resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
                resolver.invoke("cubism.editor-model.model-source.update-instances", source);
                refresh(app);
                resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
                completed = true;
            } finally {
                resolver.invoke(
                    "cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null);
            }
            currentSetOrThrow();
        }
    }
}
