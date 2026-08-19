package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorParameterStructureSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Exact, generation-bound Editor projection for Parameter collection structure writes. */
final class EditorParameterStructureAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorParameterStructureAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    ParameterId create(
        final String identity,
        final Object source,
        final Object model,
        final ParameterDefinition definition,
        final Optional<ParameterGroupId> folderId
    ) {
        requireAuthorization();
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(folderId, "folderId");
        modelGuard.requireCurrent(identity, model);
        if (findSource(source, model, definition.id()) != null) {
            throw new IllegalArgumentException(
                "Cubism parameter ID is already present: " + definition.id().value());
        }
        final Object parentGroup = folderId
            .map(id -> requireGroup(source, model, id))
            .orElseGet(() -> rootGroup(source));
        final Object hostId = resolver.construct(
            "cubism.editor-model.parameter-id.create", definition.id().value());
        final Object type = definition.type() == ParameterType.BLEND_SHAPE
            ? resolver.readStaticField("cubism.editor-model.parameter-source.type-morph-target")
            : resolver.readStaticField("cubism.editor-model.parameter-source.type-normal");
        final Object hostSource = resolver.construct(
            "cubism.editor-model.parameter-source.create",
            hostId,
            definition.name(),
            Float.valueOf(definition.minimumValue()),
            Float.valueOf(definition.maximumValue()),
            Float.valueOf(definition.defaultValue()),
            "",
            null,
            type
        );
        resolver.invoke("cubism.editor-model.parameter-source.set-repeat", hostSource, Boolean.valueOf(definition.repeat()));
        final int index = groupChildCount(parentGroup);
        write(identity, source, model, "Turboism: Create Parameter", () -> resolver.invoke(
            "cubism.editor-model.parameter-group-handler.add-parameter-child",
            groupHandler(parentGroup), hostSource, Integer.valueOf(index)));
        modelGuard.requireCurrent(identity, model);
        return definition.id();
    }

    List<ParameterId> createMany(
        final String identity,
        final Object source,
        final Object model,
        final List<ParameterDefinition> definitions,
        final Optional<ParameterGroupId> folderId
    ) {
        requireAuthorization();
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(folderId, "folderId");
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("definitions must not be empty");
        }
        modelGuard.requireCurrent(identity, model);
        final HashSet<ParameterId> batchIds = new HashSet<>();
        final List<Object> hostSources = new ArrayList<>(definitions.size());
        for (ParameterDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (!batchIds.add(definition.id())) {
                throw new IllegalArgumentException(
                    "Cubism parameter ID is duplicated within the batch: " + definition.id().value());
            }
            if (findSource(source, model, definition.id()) != null) {
                throw new IllegalArgumentException(
                    "Cubism parameter ID is already present: " + definition.id().value());
            }
        }
        final Object parentGroup = folderId
            .map(id -> requireGroup(source, model, id))
            .orElseGet(() -> rootGroup(source));
        final List<Supplier<Object>> undoSuppliers = new ArrayList<>(definitions.size());
        for (ParameterDefinition definition : definitions) {
            final Object hostId = resolver.construct(
                "cubism.editor-model.parameter-id.create", definition.id().value());
            final Object type = definition.type() == ParameterType.BLEND_SHAPE
                ? resolver.readStaticField("cubism.editor-model.parameter-source.type-morph-target")
                : resolver.readStaticField("cubism.editor-model.parameter-source.type-normal");
            final Object hostSource = resolver.construct(
                "cubism.editor-model.parameter-source.create",
                hostId,
                definition.name(),
                Float.valueOf(definition.minimumValue()),
                Float.valueOf(definition.maximumValue()),
                Float.valueOf(definition.defaultValue()),
                "",
                null,
                type
            );
            resolver.invoke(
                "cubism.editor-model.parameter-source.set-repeat", hostSource, Boolean.valueOf(definition.repeat()));
            final int index = groupChildCount(parentGroup);
            hostSources.add(hostSource);
            undoSuppliers.add(() -> resolver.invoke(
                "cubism.editor-model.parameter-group-handler.add-parameter-child",
                groupHandler(parentGroup), hostSource, Integer.valueOf(index)));
        }
        writeBatch(identity, source, model, "Turboism: Create Parameters", undoSuppliers);
        modelGuard.requireCurrent(identity, model);
        final List<ParameterId> created = new ArrayList<>(definitions.size());
        for (ParameterDefinition definition : definitions) {
            created.add(definition.id());
        }
        return List.copyOf(created);
    }

    void removeMany(
        final String identity,
        final Object source,
        final Object model,
        final List<ParameterId> ids
    ) {
        requireAuthorization();
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("ids must not be empty");
        }
        modelGuard.requireCurrent(identity, model);
        final HashSet<ParameterId> batchIds = new HashSet<>();
        final List<Supplier<Object>> undoSuppliers = new ArrayList<>(ids.size());
        for (ParameterId id : ids) {
            Objects.requireNonNull(id, "id");
            if (!batchIds.add(id)) {
                throw new IllegalArgumentException(
                    "Cubism parameter ID is duplicated within the batch: " + id.value());
            }
            final Object current = requireSource(source, model, id);
            final Object guid = resolver.invoke("cubism.editor-model.parameter-source.guid", current);
            final Object parameterSet = requireParameterSet(model);
            undoSuppliers.add(() -> resolver.invoke(
                "cubism.editor-model.model-handler.remove-parameter",
                modelHandler(source), guid, parameterSet, Boolean.TRUE));
        }
        writeBatch(identity, source, model, "Turboism: Delete Parameters", undoSuppliers);
        modelGuard.requireCurrent(identity, model);
    }

    ParameterId copy(final String identity, final Object source, final Object model, final ParameterId id) {
        requireAuthorization();
        Objects.requireNonNull(id, "id");
        modelGuard.requireCurrent(identity, model);
        final Object current = requireSource(source, model, id);
        final Object parentGroup = resolver.invoke("cubism.editor-model.parameter-source.parent-group", current);
        if (parentGroup == null) {
            throw new IllegalStateException("Editor parameter source has no parent group.");
        }
        final Object hostId = resolver.invokeStatic(
            "cubism.editor-model.model-handler.create-free-id-default",
            modelHandler(source),
            resolver.invoke("cubism.editor-model.parameter-source.id", current),
            null,
            Integer.valueOf(2),
            null
        );
        final Object type = hostType(current);
        final Object hostSource = resolver.construct(
            "cubism.editor-model.parameter-source.create",
            hostId,
            text(resolver.invoke("cubism.editor-model.parameter-source.name", current)),
            number(resolver.invoke("cubism.editor-model.parameter-source.minimum", current)),
            number(resolver.invoke("cubism.editor-model.parameter-source.maximum", current)),
            number(resolver.invoke("cubism.editor-model.parameter-source.default", current)),
            "",
            null,
            type
        );
        resolver.invoke("cubism.editor-model.parameter-source.set-repeat", hostSource, Boolean.FALSE);
        final int index = groupChildCount(parentGroup);
        write(identity, source, model, "Turboism: Duplicate Parameter", () -> resolver.invoke(
            "cubism.editor-model.parameter-group-handler.add-parameter-child",
            groupHandler(parentGroup), hostSource, Integer.valueOf(index)));
        modelGuard.requireCurrent(identity, model);
        // Read the copy identity back from the host state the undo envelope actually registered
        // (the child the host appended to the parent group), not from the createFreeId guess: the
        // returned id must agree with the host state both after this write and after undo/redo.
        final Object registeredId = lastChildId(parentGroup);
        return new ParameterId(text(resolver.invoke(
            "cubism.editor-model.id.value", registeredId != null ? registeredId : hostId)));
    }

    void remove(final String identity, final Object source, final Object model, final ParameterId id) {
        requireAuthorization();
        Objects.requireNonNull(id, "id");
        modelGuard.requireCurrent(identity, model);
        final Object current = requireSource(source, model, id);
        final Object guid = resolver.invoke("cubism.editor-model.parameter-source.guid", current);
        final Object parameterSet = requireParameterSet(model);
        write(identity, source, model, "Turboism: Delete Parameter", () -> resolver.invoke(
            "cubism.editor-model.model-handler.remove-parameter",
            modelHandler(source), guid, parameterSet, Boolean.TRUE));
        modelGuard.requireCurrent(identity, model);
    }

    ParameterGroupId addGroup(final String identity, final Object source, final Object model, final String name) {
        requireAuthorization();
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        modelGuard.requireCurrent(identity, model);
        final Object root = rootGroup(source);
        final Object hostGroupId = resolver.invokeStatic(
            "cubism.editor-model.model-handler.create-free-id-default",
            modelHandler(source),
            resolver.construct("cubism.editor-model.parameter-group-id.create", name),
            null,
            Integer.valueOf(2),
            null
        );
        // CParameterGroup's constructor Intrinsics.checkNotNullParameter rejects a null guid
        // (verified host evidence: CParameterGroup(String, CParameterGroupGuid, CParameterGroupId)
        // with CParameterGroupGuid.<init>()V no-arg constructor).
        final Object guid = resolver.construct("cubism.editor-model.parameter-group-guid.create");
        final Object hostGroup = resolver.construct(
            "cubism.editor-model.parameter-group.create", name, guid, hostGroupId);
        resolver.invoke("cubism.editor-model.parameter-group.set-folder-opened", hostGroup, Boolean.FALSE);
        final int index = groupChildCount(root);
        write(identity, source, model, "Turboism: Create Parameter Folder", () -> resolver.invoke(
            "cubism.editor-model.parameter-group-handler.add-group-child",
            groupHandler(root), hostGroup, Integer.valueOf(index)));
        modelGuard.requireCurrent(identity, model);
        return new ParameterGroupId(text(resolver.invoke("cubism.editor-model.id.value", hostGroupId)));
    }

    void removeGroup(final String identity, final Object source, final Object model, final ParameterGroupId id) {
        requireAuthorization();
        Objects.requireNonNull(id, "id");
        modelGuard.requireCurrent(identity, model);
        final Object group = requireGroup(source, model, id);
        final Object parent = resolver.invoke("cubism.editor-model.parameter-group.parent", group);
        if (parent == null) {
            throw new IllegalArgumentException("The root parameter folder cannot be deleted.");
        }
        final Object parameterSet = requireParameterSet(model);
        write(identity, source, model, "Turboism: Delete Parameter Folder", () -> resolver.invoke(
            "cubism.editor-model.parameter-group-handler.remove-descendant",
            groupHandler(parent), group, parameterSet, Boolean.TRUE, Boolean.TRUE));
        modelGuard.requireCurrent(identity, model);
    }

    void renameGroup(
        final String identity, final Object source, final Object model, final ParameterGroupId id, final String name
    ) {
        requireAuthorization();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        modelGuard.requireCurrent(identity, model);
        final Object group = requireGroup(source, model, id);
        final Object current = resolver.invoke("cubism.editor-model.parameter-group.name", group);
        if (current instanceof String existing && existing.equals(name)) return;
        write(identity, source, model, "Turboism: Rename Parameter Folder", () -> {
            final Object undo = resolver.construct(
                "cubism.editor-model.simple-undo.create", "Turboism: Rename Parameter Folder", group, null);
            resolver.invoke("cubism.editor-model.parameter-group.set-name", group, name);
            return undo;
        });
        modelGuard.requireCurrent(identity, model);
    }

    void moveParameter(
        final String identity,
        final Object source,
        final Object model,
        final ParameterId parameterId,
        final ParameterGroupId targetGroupId
    ) {
        requireAuthorization();
        Objects.requireNonNull(parameterId, "parameterId");
        Objects.requireNonNull(targetGroupId, "targetGroupId");
        modelGuard.requireCurrent(identity, model);
        final Object current = requireSource(source, model, parameterId);
        final Object targetGroup = requireGroup(source, model, targetGroupId);
        final int index = groupChildCount(targetGroup);
        write(identity, source, model, "Turboism: Move Parameter", () -> resolver.invoke(
            "cubism.editor-model.model-handler.move-parameter",
            modelHandler(source), targetGroup, current, Integer.valueOf(index)));
        modelGuard.requireCurrent(identity, model);
    }

    private void requireAuthorization() {
        if (!resolver.authorizesFeature(
            EditorParameterStructureSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterStructureSelectorContract.CAPABILITY_ID,
            EditorParameterStructureSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Parameter structure editing is unavailable without exact verified host evidence."
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

    private Object rootGroup(final Object source) {
        final Object root = resolver.invoke("cubism.editor-model.model-source.root-parameter-group", source);
        if (!resolver.isInstance("cubism.editor-model.parameter-group.class", root)) {
            throw new IllegalStateException("Editor root parameter folder is unavailable.");
        }
        return root;
    }

    private Object groupHandler(final Object group) {
        final Object handler = resolver.invoke("cubism.editor-model.parameter-group.handler", group);
        if (!resolver.isInstance("cubism.editor-model.parameter-group-handler.class", handler)) {
            throw new IllegalStateException("Editor parameter folder handler is unavailable.");
        }
        return handler;
    }

    private int groupChildCount(final Object group) {
        final Object raw = resolver.invoke("cubism.editor-model.parameter-group.children", group);
        if (!(raw instanceof List<?> children)) {
            throw new IllegalStateException("Editor parameter folder children are unavailable.");
        }
        return children.size();
    }

    /**
     * Returns the host id of the child the host appended last to {@code group} (the parameter the
     * add-parameter-child undo envelope registered), or {@code null} when the tail is not a
     * parameter source or the children are unavailable.
     */
    private Object lastChildId(final Object group) {
        final Object raw = resolver.invoke("cubism.editor-model.parameter-group.children", group);
        if (!(raw instanceof List<?> children) || children.isEmpty()) return null;
        final Object last = children.get(children.size() - 1);
        if (last == null || !resolver.isInstance("cubism.editor-model.parameter-source.class", last)) {
            return null;
        }
        return resolver.invoke("cubism.editor-model.parameter-source.id", last);
    }

    private ParameterGroupId groupId(final Object group) {
        final Object hostId = resolver.invoke("cubism.editor-model.parameter-group.id", group);
        return new ParameterGroupId(text(resolver.invoke("cubism.editor-model.id.value", hostId)));
    }

    private Object requireGroup(final Object source, final Object model, final ParameterGroupId id) {
        final Object group = findGroup(rootGroup(source), id);
        if (group == null) {
            throw new NoSuchElementException("Cubism parameter group is absent: " + id.value());
        }
        return group;
    }

    private Object findGroup(final Object group, final ParameterGroupId id) {
        if (groupId(group).equals(id)) return group;
        final Object raw = resolver.invoke("cubism.editor-model.parameter-group.children", group);
        if (!(raw instanceof List<?> children)) {
            throw new IllegalStateException("Editor parameter folder children are unavailable.");
        }
        for (Object child : children) {
            if (!resolver.isInstance("cubism.editor-model.parameter-group.class", child)) {
                continue;
            }
            final Object found = findGroup(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private Object requireParameterSet(final Object model) {
        final Object parameterSet = resolver.invoke("cubism.editor-model.model.parameter-set", model);
        if (!resolver.isInstance("cubism.editor-model.parameter-set.class", parameterSet)) {
            throw new IllegalStateException("Editor parameter set is unavailable.");
        }
        return parameterSet;
    }

    private Object findSource(final Object source, final Object model, final ParameterId id) {
        final Object raw = resolver.invoke(
            "cubism.editor-model.parameter-set.parameters", requireParameterSet(model));
        if (!(raw instanceof List<?> parameters)) {
            throw new IllegalStateException("Editor parameter collection is unavailable.");
        }
        for (Object parameter : parameters) {
            if (!resolver.isInstance("cubism.editor-model.parameter.class", parameter)) {
                throw new IllegalStateException("Editor parameter collection contains an invalid value.");
            }
            final Object parameterSource = resolver.invoke("cubism.editor-model.parameter.source", parameter);
            if (parameterSource != null && sourceId(parameterSource).equals(id)) {
                return parameterSource;
            }
        }
        return null;
    }

    private Object requireSource(final Object source, final Object model, final ParameterId id) {
        final Object parameterSource = findSource(source, model, id);
        if (parameterSource == null) {
            throw new NoSuchElementException("Cubism parameter is absent: " + id.value());
        }
        return parameterSource;
    }

    private ParameterId sourceId(final Object parameterSource) {
        final Object hostId = resolver.invoke("cubism.editor-model.parameter-source.id", parameterSource);
        return new ParameterId(text(resolver.invoke("cubism.editor-model.id.value", hostId)));
    }

    private Object hostType(final Object parameterSource) {
        final Object type = resolver.invoke("cubism.editor-model.parameter-source.param-type", parameterSource);
        final Object normal = resolver.readStaticField("cubism.editor-model.parameter-source.type-normal");
        return type != null && type.equals(normal)
            ? normal
            : resolver.readStaticField("cubism.editor-model.parameter-source.type-morph-target");
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

    private void writeBatch(
        final String identity,
        final Object source,
        final Object model,
        final String actionName,
        final List<Supplier<Object>> undoSuppliers
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        final Object editMode = resolver.invoke("cubism.editor-model.modeling-document.edit-mode", document);
        // One edit-mode envelope around the whole batch: every child operation lands
        // in a single Undo entry, never one entry per parameter.
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, actionName);
        boolean completed = false;
        try {
            for (Supplier<Object> undoSupplier : undoSuppliers) {
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
            }
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
        resolver.invoke("cubism.editor-model.complete-pack.update-parameter", completePack, Boolean.TRUE);
        resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", completePack, Boolean.TRUE);
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Editor parameter structure text is unavailable.");
        }
        return text;
    }

    private static Float number(final Object value) {
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) {
            throw new IllegalStateException("Editor parameter structure number is unavailable.");
        }
        return number.floatValue();
    }
}
