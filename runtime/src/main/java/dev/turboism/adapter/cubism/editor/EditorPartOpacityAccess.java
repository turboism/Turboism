package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.EditorPartOpacity52SelectorContract;
import dev.turboism.mapping.verification.EditorPartOpacitySelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Exact, generation-bound Editor authoring projection for Part opacity. */
final class EditorPartOpacityAccess {

    private static final String ACTION_NAME = "Turboism: Set Part Opacity";
    private static final String NAME_ACTION_NAME = "Turboism: Set Part Name";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorPartOpacityAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    Parts parts(final String identity, final Object source, final Object model) {
        requireProjectionAuthorization();
        modelGuard.requireCurrent(identity, model);
        return new EditorParts(identity, source, model);
    }

    private boolean opacityAuthorized() {
        if (isCubism52()) {
            return resolver.authorizesFeature(
                EditorPartOpacity52SelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacity52SelectorContract.CAPABILITY_ID,
                EditorPartOpacity52SelectorContract.REQUIRED_ALIASES
            );
        }
        return resolver.authorizesFeature(
            EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
            EditorPartOpacitySelectorContract.CAPABILITY_ID,
            EditorPartOpacitySelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean opacityWriteAuthorized() {
        return resolver.authorizesFeature(
            EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
            EditorPartOpacitySelectorContract.CAPABILITY_ID,
            EditorPartOpacitySelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean isCubism52() {
        return resolver.isExactCubismVersion(EditorPartOpacity52SelectorContract.CUBISM_VERSION);
    }

    private boolean nameAuthorized() {
        return resolver.authorizesFeature(
            EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
            EditorPartNameSelectorContract.CAPABILITY_ID,
            EditorPartNameSelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean nameWriteAuthorized() {
        return resolver.authorizesFeature(
            EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
            EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
            EditorPartNameSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }

    private List<PartBinding> bindings(final Object source, final Object model) {
        final Object rawSources = resolver.invoke("cubism.editor-model.model-source.parts", source);
        final Object rawParts = resolver.invoke("cubism.editor-model.model.parts", model);
        if (!(rawSources instanceof List<?> sources) || !(rawParts instanceof List<?> parts)) {
            throw unavailable("Editor Part collections are unavailable.");
        }
        final IdentityHashMap<Object, Object> instancesBySource = new IdentityHashMap<>();
        for (Object part : parts) {
            if (!resolver.isInstance("cubism.editor-model.part.class", part)) {
                throw unavailable("Editor Part collection contains an invalid value.");
            }
            final Object partSource = resolver.invoke("cubism.editor-model.part.source", part);
            if (instancesBySource.put(partSource, part) != null) {
                throw unavailable("Editor Part source is bound to multiple active instances.");
            }
        }
        return sources.stream().map(partSource -> {
            if (!resolver.isInstance("cubism.editor-model.part-source.class", partSource)) {
                throw unavailable("Editor Part source collection contains an invalid value.");
            }
            final Object part = instancesBySource.get(partSource);
            if (part == null) {
                throw unavailable("Editor Part source has no active model instance.");
            }
            return new PartBinding(partId(partSource), partSource, part);
        }).toList();
    }

    private PartBinding binding(final Object source, final Object model, final PartId id) {
        return bindings(source, model).stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("Cubism Part is absent: " + id.value()));
    }

    private PartId partId(final Object partSource) {
        final Object hostId = resolver.invoke("cubism.editor-model.part-source.id", partSource);
        final Object value = resolver.invoke("cubism.editor-model.part-id.value", hostId);
        if (!(value instanceof String id) || id.isBlank()) {
            throw unavailable("Editor Part identity is unavailable.");
        }
        return new PartId(id);
    }

    private float opacity(final Object part) {
        requireOpacityAuthorization();
        final Object value = isCubism52()
            ? resolver.invoke("cubism.editor-model.part.parts-opacity", part)
            : resolver.invoke("cubism.editor-model.part-form.opacity", currentForm(part));
        if (!(value instanceof Float opacity) || !Float.isFinite(opacity)) {
            throw unavailable("Editor Part authoring opacity is unavailable.");
        }
        return opacity;
    }

    private String name(final PartBinding binding) {
        if (!nameAuthorized()) {
            throw new UnsupportedOperationException(
                "Part display-name reading is unavailable without exact verified host evidence."
            );
        }
        final Object value = resolver.invoke(
            "cubism.editor-model.part-source.local-name",
            binding.source()
        );
        if (value == null) return binding.id().value();
        if (!(value instanceof String name)) {
            throw unavailable("Editor Part display name is invalid.");
        }
        return name.isBlank() ? binding.id().value() : name;
    }

    private void setName(
        final String identity,
        final Object source,
        final Object model,
        final PartId id,
        final Object expectedSource,
        final Object expectedPart,
        final String requestedName
    ) {
        final String name = Objects.requireNonNull(requestedName, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        requireNameWriteAuthorization();
        final PartBinding current = requireCurrentPart(
            identity, source, model, id, expectedSource, expectedPart
        );
        if (name.equals(name(current))) return;
        writePartSource(
            source,
            current,
            NAME_ACTION_NAME,
            () -> resolver.invoke("cubism.editor-model.part-source.set-local-name", current.source(), name)
        );
        requireCurrentPart(identity, source, model, id, expectedSource, expectedPart);
    }

    private Object currentForm(final Object part) {
        final Object form = resolver.invoke("cubism.editor-model.part.current-keyform", part);
        if (!resolver.isInstance("cubism.editor-model.part-form.class", form)) {
            throw unavailable("Editor Part has no current authoring keyform.");
        }
        return form;
    }

    private void setOpacity(
        final String identity,
        final Object source,
        final Object model,
        final PartId id,
        final Object expectedSource,
        final Object expectedPart,
        final float opacity
    ) {
        if (!Float.isFinite(opacity)) {
            throw new IllegalArgumentException("opacity must be finite");
        }
        requireOpacityWriteAuthorization();
        final PartBinding current = requireCurrentPart(
            identity, source, model, id, expectedSource, expectedPart
        );
        if (Float.compare(opacity(current.part()), opacity) == 0) {
            return;
        }
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, ACTION_NAME
        );
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.part-source.handler", current.source()
            );
            if (!resolver.isInstance("cubism.editor-model.part-handler.class", handler)) {
                throw unavailable("Editor Part Undo handler is unavailable.");
            }
            final Object partUndo = resolver.invoke(
                "cubism.editor-model.part-handler.create-undo-for-all-edit",
                handler,
                ACTION_NAME
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, partUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Part opacity Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", source);
                    refresh(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", partUndo, listener);
            resolver.invoke(
                "cubism.editor-model.part-form.set-opacity",
                currentForm(current.part()),
                Float.valueOf(opacity)
            );
            resolver.invoke("cubism.editor-model.model-source.update-instances", source);
            refresh(app);
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
        requireCurrentPart(identity, source, model, id, expectedSource, expectedPart);
    }

    private void refresh(final Object app) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-part-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
    }

    private void writePartSource(
        final Object source,
        final PartBinding current,
        final String actionName,
        final Runnable mutation
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, actionName);
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.part-source.handler", current.source()
            );
            if (!resolver.isInstance("cubism.editor-model.part-handler.class", handler)) {
                throw unavailable("Editor Part Undo handler is unavailable.");
            }
            final Object partUndo = resolver.invoke(
                "cubism.editor-model.part-handler.create-undo-for-all-edit", handler, actionName
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, partUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Part authoring Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", source);
                    refresh(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", partUndo, listener);
            mutation.run();
            resolver.invoke("cubism.editor-model.model-source.update-instances", source);
            refresh(app);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null
            );
        }
    }

    private PartBinding requireCurrentPart(
        final String identity,
        final Object source,
        final Object model,
        final PartId id,
        final Object expectedSource,
        final Object expectedPart
    ) {
        modelGuard.requireCurrent(identity, model);
        final PartBinding current = binding(source, model, id);
        if (current.source() != expectedSource || current.part() != expectedPart) {
            throw unavailable("Cubism Part reference is stale for the active Editor model generation.");
        }
        return current;
    }

    private int parentIndex(final Object source, final Object model, final Object partSource) {
        final Object parent = resolver.invoke("cubism.editor-model.part-source.parent", partSource);
        if (parent == null) return -1;
        final List<PartBinding> parts = bindings(source, model);
        for (int index = 0; index < parts.size(); index++) {
            if (parts.get(index).source() == parent) return index;
        }
        throw unavailable("Editor Part parent is outside the active Part collection.");
    }

    private void requireProjectionAuthorization() {
        if (!opacityAuthorized() && !nameAuthorized()) {
            throw new UnsupportedOperationException(
                "Part access is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireOpacityAuthorization() {
        if (!opacityAuthorized()) {
            throw new UnsupportedOperationException(
                "Part opacity reading is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireOpacityWriteAuthorization() {
        if (!opacityWriteAuthorized()) {
            throw new UnsupportedOperationException(
                "Part opacity writing is unavailable on this exact Cubism version."
            );
        }
    }

    private void requireNameWriteAuthorization() {
        if (!nameWriteAuthorized()) {
            throw new UnsupportedOperationException(
                "Part display-name writing is unavailable without exact verified host evidence."
            );
        }
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private final class EditorParts implements Parts {
        private final String identity;
        private final Object source;
        private final Object model;

        private EditorParts(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override public List<Part> all() {
            modelGuard.requireCurrent(identity, model);
            return bindings(source, model).stream()
                .map(value -> (Part) new EditorPart(
                    identity, source, model, value.id(), value.source(), value.part()
                ))
                .toList();
        }

        @Override public Part find(final PartId id) {
            final PartBinding value = binding(source, model, Objects.requireNonNull(id, "id"));
            return new EditorPart(identity, source, model, value.id(), value.source(), value.part());
        }
    }

    private final class EditorPart implements Part {
        private final String identity;
        private final Object source;
        private final Object model;
        private final PartId id;
        private final Object expectedSource;
        private final Object expectedPart;

        private EditorPart(
            final String identity,
            final Object source,
            final Object model,
            final PartId id,
            final Object expectedSource,
            final Object expectedPart
        ) {
            this.identity = identity;
            this.source = source;
            this.model = model;
            this.id = id;
            this.expectedSource = expectedSource;
            this.expectedPart = expectedPart;
        }

        private PartBinding current() {
            return requireCurrentPart(
                identity, source, model, id, expectedSource, expectedPart
            );
        }

        @Override public PartId id() { current(); return id; }
        @Override public String name() { return EditorPartOpacityAccess.this.name(current()); }
        @Override public void setName(final String name) {
            EditorPartOpacityAccess.this.setName(
                identity, source, model, id, expectedSource, expectedPart, name
            );
        }
        @Override public float getOpacity() { return opacity(current().part()); }
        @Override public int parentIndex() {
            return EditorPartOpacityAccess.this.parentIndex(source, model, current().source());
        }
        @Override public void setOpacity(final float opacity) {
            EditorPartOpacityAccess.this.setOpacity(
                identity, source, model, id, expectedSource, expectedPart, opacity
            );
        }
    }

    private record PartBinding(PartId id, Object source, Object part) { }
}
