package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorPartStructureSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.PartId;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

/** Exact, generation-bound Editor projection for Part collection structure writes (add / copy / delete). */
final class EditorPartStructureAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorPartStructureAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    PartId add(final String identity, final Object source, final Object model, final PartId id, final PartId parentId) {
        requireAuthorization();
        Objects.requireNonNull(id, "id");
        if (id.value().isBlank()) throw new IllegalArgumentException("id must not be blank");
        modelGuard.requireCurrent(identity, model);
        if (findSource(source, model, id) != null) {
            throw new IllegalArgumentException("Cubism part ID is already present: " + id.value());
        }
        final Object parent = parentId == null ? rootPart(source) : requireSource(source, model, parentId);
        final Object hostId = resolver.construct("cubism.editor-model.part-id.create", id.value());
        final Object hostGuid = resolver.construct("cubism.editor-model.part-guid.create");
        final Object hostSource = resolver.construct("cubism.editor-model.part-source.create", source);
        resolver.invoke("cubism.editor-model.part-source.set-local-name", hostSource, id.value());
        resolver.invoke("cubism.editor-model.part-source.set-id", hostSource, hostId);
        resolver.invoke("cubism.editor-model.part-source.set-guid", hostSource, hostGuid);
        resolver.invoke("cubism.editor-model.part-source.set-default-order", hostSource, Integer.valueOf(500));
        final int index = childCount(parent);
        write(identity, source, model, "Turboism: Create Part", () -> resolver.invoke(
            "cubism.editor-model.part-handler.add-part-child",
            parentHandler(parent), hostSource, Integer.valueOf(index)));
        modelGuard.requireCurrent(identity, model);
        return id;
    }

    PartId copy(final String identity, final Object source, final Object model, final PartId id) {
        requireAuthorization();
        Objects.requireNonNull(id, "id");
        modelGuard.requireCurrent(identity, model);
        final Object current = requireSource(source, model, id);
        final Object parent = resolver.invoke("cubism.editor-model.part-source.parent", current);
        if (parent == null) {
            throw new IllegalStateException("Editor part source has no parent.");
        }
        final Object copied = resolver.invokeStatic(
            "cubism.editor-model.copy-helper.copy", current, null, Integer.valueOf(1), null);
        if (!resolver.isInstance("cubism.editor-model.part-source.class", copied)) {
            throw new IllegalStateException("Editor part copy is invalid.");
        }
        final Object freshId = resolver.invokeStatic(
            "cubism.editor-model.model-handler.create-free-id-default",
            modelHandler(source),
            resolver.invoke("cubism.editor-model.part-source.id", copied),
            null,
            Integer.valueOf(2),
            null
        );
        resolver.invoke("cubism.editor-model.part-source.set-id", copied, freshId);
        resolver.invoke("cubism.editor-model.part-source.set-guid", copied, resolver.construct("cubism.editor-model.part-guid.create"));
        final int index = childCount(parent);
        write(identity, source, model, "Turboism: Duplicate Part", () -> resolver.invoke(
            "cubism.editor-model.part-handler.add-part-child",
            parentHandler(parent), copied, Integer.valueOf(index)));
        modelGuard.requireCurrent(identity, model);
        return new PartId(text(resolver.invoke("cubism.editor-model.id.value", freshId)));
    }

    void remove(final String identity, final Object source, final Object model, final PartId id) {
        requireAuthorization();
        Objects.requireNonNull(id, "id");
        modelGuard.requireCurrent(identity, model);
        final Object current = requireSource(source, model, id);
        final Object modelInstance = resolver.invoke("cubism.editor-model.model-source.current-instance", source);
        write(identity, source, model, "Turboism: Delete Part", () -> resolver.invoke(
            "cubism.editor-model.model-handler.remove-objects",
            modelHandler(source), List.of(current), modelInstance, Boolean.FALSE));
        modelGuard.requireCurrent(identity, model);
    }

    private void requireAuthorization() {
        if (!resolver.authorizesFeature(
            EditorPartStructureSelectorContract.ADAPTER_SLICE_ID,
            EditorPartStructureSelectorContract.CAPABILITY_ID,
            EditorPartStructureSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Part structure editing is unavailable without exact verified host evidence."
            );
        }
    }

    private Object modelHandler(final Object source) {
        final Object handler = resolver.invoke("cubism.editor-model.model-source.handler", source);
        if (!resolver.isInstance("cubism.editor-model.model-handler.class", handler)) {
            throw new IllegalStateException("Editor model handler is unavailable.");
        }
        return handler;
    }

    private Object rootPart(final Object source) {
        final Object root = resolver.invoke("cubism.editor-model.model-source.root-part", source);
        if (!resolver.isInstance("cubism.editor-model.part-source.class", root)) {
            throw new IllegalStateException("Editor root part is unavailable.");
        }
        return root;
    }

    private Object parentHandler(final Object partSource) {
        final Object handler = resolver.invoke("cubism.editor-model.part-source.handler", partSource);
        if (!resolver.isInstance("cubism.editor-model.part-handler.class", handler)) {
            throw new IllegalStateException("Editor part handler is unavailable.");
        }
        return handler;
    }

    private int childCount(final Object partSource) {
        final Object raw = resolver.invoke("cubism.editor-model.part-source.children", partSource);
        if (!(raw instanceof List<?> children)) {
            throw new IllegalStateException("Editor part children are unavailable.");
        }
        return children.size();
    }

    private Object findSource(final Object source, final Object model, final PartId id) {
        final Object rawSources = resolver.invoke("cubism.editor-model.model-source.parts", source);
        if (!(rawSources instanceof List<?> partSources)) {
            throw new IllegalStateException("Editor part collection is unavailable.");
        }
        for (Object partSource : partSources) {
            if (!resolver.isInstance("cubism.editor-model.part-source.class", partSource)) {
                throw new IllegalStateException("Editor part collection contains an invalid value.");
            }
            if (sourceId(partSource).equals(id)) return partSource;
        }
        return null;
    }

    private Object requireSource(final Object source, final Object model, final PartId id) {
        final Object partSource = findSource(source, model, id);
        if (partSource == null) {
            throw new NoSuchElementException("Cubism part is absent: " + id.value());
        }
        return partSource;
    }

    private PartId sourceId(final Object partSource) {
        final Object hostId = resolver.invoke("cubism.editor-model.part-source.id", partSource);
        return new PartId(text(resolver.invoke("cubism.editor-model.id.value", hostId)));
    }

    private void write(
        final String identity,
        final Object source,
        final Object model,
        final String actionName,
        final Supplier<Object> undoSupplier
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        final Object editMode = resolver.invoke("cubism.editor-model.modeling-document.edit-mode", document);
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, actionName);
        boolean completed = false;
        try {
            final Object undo = undoSupplier.get();
            final Object accepted = resolver.invoke("cubism.editor-model.undo.add", edit, undo, Boolean.TRUE);
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the " + actionName + " Undo entry.");
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
            resolver.invoke("cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null);
        }
    }

    private void refresh(final Object app) {
        final Object completePack = resolver.invoke("cubism.editor-model.app-controller.complete-pack", app);
        resolver.invoke("cubism.editor-model.complete-pack.update-part-palette", completePack, Boolean.TRUE);
        resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE);
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Editor part structure text is unavailable.");
        }
        return text;
    }
}
